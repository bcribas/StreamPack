/*
 * Copyright (C) 2025 Thibault B.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.thibaultbee.streampack.core.elements.sources.video.camera.utils

import android.Manifest
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.os.Build
import android.util.Range
import android.view.Surface
import androidx.annotation.RequiresPermission
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.CameraException
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.controllers.CameraDeviceController
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.targetFps
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.sessioncompat.ICameraCaptureSessionCompat
import io.github.thibaultbee.streampack.core.logger.Logger
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object CameraUtils {
    private const val TAG = "CameraUtils"

    @RequiresPermission(Manifest.permission.CAMERA)
    internal suspend fun openCamera(
        sessionCompat: ICameraCaptureSessionCompat,
        manager: CameraManager,
        cameraId: String,
        isClosedFlow: MutableStateFlow<Boolean>,
        throwableFlow: MutableStateFlow<Throwable?>
    ): CameraDevice = suspendCancellableCoroutine { continuation ->
        val callbacks = object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                if (continuation.isActive) {
                    continuation.resume(device)
                } else {
                    device.close()
                }
            }

            override fun onDisconnected(camera: CameraDevice) {
                // Once open, the continuation is long done: only an open still waiting fails
                resumeOnce(continuation, RuntimeException("Camera has been disconnected"))
                isClosedFlow.tryEmit(true)
                Logger.w(TAG, "Camera ${camera.id} has been disconnected")
                camera.close()
            }

            override fun onError(camera: CameraDevice, error: Int) {
                Logger.e(TAG, "Camera ${camera.id} is in error $error")

                val exception = when (error) {
                    ERROR_CAMERA_IN_USE -> CameraException("Camera already in use")
                    ERROR_MAX_CAMERAS_IN_USE -> CameraException("Max cameras in use")
                    ERROR_CAMERA_DISABLED -> CameraException("Camera has been disabled")
                    ERROR_CAMERA_DEVICE -> CameraException("Camera device has crashed")
                    ERROR_CAMERA_SERVICE -> CameraException("Camera service has crashed")
                    else -> CameraException("Unknown error")
                }
                resumeOnce(continuation, exception)
                // Before the closed flag: whoever watches it reads why
                throwableFlow.tryEmit(exception)
                isClosedFlow.tryEmit(true)
                camera.close()
            }

            override fun onClosed(camera: CameraDevice) {
                isClosedFlow.tryEmit(true)
                Logger.i(TAG, "Camera ${camera.id} has been closed")
            }
        }
        try {
            sessionCompat.openCamera(manager, cameraId, callbacks)
        } catch (t: Throwable) {
            resumeOnce(continuation, CameraException("Failed to open camera $cameraId: $t"))
        }
    }

    /**
     * Fails an open that is still waiting. A failed open is reported twice, by the call and by
     * the device's callback on another thread, so checking first is not enough: the second one
     * must not throw (it killed the app on 2026-10-10 while the camera app held the camera).
     */
    private fun resumeOnce(continuation: CancellableContinuation<CameraDevice>, t: Throwable) {
        if (!continuation.isActive) return
        try {
            continuation.resumeWithException(t)
        } catch (e: IllegalStateException) {
            Logger.d(TAG, "Open already settled: ${t.message}")
        }
    }

    internal suspend fun createCaptureSession(
        cameraDeviceController: CameraDeviceController,
        outputs: List<Surface>,
        dynamicRange: Long,
        isClosedFlow: MutableStateFlow<Boolean>
    ): CameraCaptureSession = suspendCancellableCoroutine { continuation ->
        val cameraId = cameraDeviceController.id
        val callbacks = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                Logger.i(
                    TAG,
                    "Camera session configured for camera $cameraId and outputs $outputs"
                )
                if (continuation.isActive) {
                    continuation.resume(session)
                } else {
                    session.close()
                }
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                isClosedFlow.tryEmit(true)
                Logger.e(
                    TAG,
                    "Camera session configuration failed for camera $cameraId and outputs $outputs"
                )
                try {
                    continuation.resumeWithException(CameraException("Camera: failed to configure the capture session for camera $cameraId and outputs $outputs"))
                } catch (t: Throwable) {
                    Logger.e(TAG, "Error while resuming with exception: $t")
                }
            }

            override fun onClosed(session: CameraCaptureSession) {
                isClosedFlow.tryEmit(true)
                Logger.e(
                    TAG,
                    "Camera capture session closed for camera $cameraId and outputs $outputs"
                )
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val outputConfigurations = outputs.map {
                    OutputConfiguration(it).apply {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            dynamicRangeProfile = dynamicRange
                        }
                    }
                }

                cameraDeviceController.createCaptureSessionByOutputConfiguration(
                    outputConfigurations, callbacks
                )
            } else {
                cameraDeviceController.createCaptureSession(
                    outputs, callbacks
                )
            }
        } catch (t: Throwable) {
            try {
                continuation.resumeWithException(CameraException("Failed to create capture session for camera $cameraId and outputs $outputs: $t"))
            } catch (t: Throwable) {
                Logger.e(TAG, "Error while resuming with exception: $t")
            }
        }
    }

    internal fun getClosestFpsRange(
        characteristics: CameraCharacteristics,
        fps: Int
    ): Range<Int> {
        var fpsRangeList = characteristics.targetFps
        Logger.i(TAG, "Supported FPS range list: $fpsRangeList")

        // Get range that contains FPS
        fpsRangeList =
            fpsRangeList.filter { it.contains(fps) or it.contains(fps * 1000) } // On Samsung S4 fps range is [4000-30000] instead of [4-30]
        if (fpsRangeList.isEmpty()) {
            Logger.w(
                TAG,
                "Failed to find a single FPS range that contains $fps. Trying with forced $fps."
            )
            return Range(fps, fps)
        }

        // Get smaller range
        var selectedFpsRange = fpsRangeList[0]
        fpsRangeList = fpsRangeList.drop(0)
        fpsRangeList.forEach {
            if ((it.upper - it.lower) < (selectedFpsRange.upper - selectedFpsRange.lower)) {
                selectedFpsRange = it
            }
        }

        Logger.d(TAG, "Selected Fps range $selectedFpsRange")
        return selectedFpsRange
    }
}