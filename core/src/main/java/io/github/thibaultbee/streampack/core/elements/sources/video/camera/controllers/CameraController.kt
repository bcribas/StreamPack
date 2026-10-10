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
package io.github.thibaultbee.streampack.core.elements.sources.video.camera.controllers

import android.Manifest
import android.annotation.SuppressLint
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.Looper
import android.util.Range
import androidx.annotation.RequiresPermission
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.sessioncompat.CameraCaptureSessionCompatBuilder
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.utils.CameraDispatcherProvider
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.utils.CameraSessionCallback
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.utils.CameraSurface
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.utils.CameraUtils
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.utils.CaptureRequestWithTargetsBuilder
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.utils.CaptureResultListener
import io.github.thibaultbee.streampack.core.elements.utils.av.video.DynamicRangeProfile
import io.github.thibaultbee.streampack.core.logger.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Encapsulates device controller and session controller.
 */
internal class CameraController(
    private val manager: CameraManager,
    private val characteristics: CameraCharacteristics,
    dispatcherProvider: CameraDispatcherProvider,
    val cameraId: String,
    val onCaptureRequestBuilder: CaptureRequestWithTargetsBuilder.() -> Unit = {}
) {
    private val sessionCompat = CameraCaptureSessionCompatBuilder.build(dispatcherProvider)

    private val defaultDispatcher = dispatcherProvider.default
    private val coroutineScope = CoroutineScope(defaultDispatcher)
    private var isActiveJob: Job? = null

    @Volatile
    private var deviceController: CameraDeviceController? = null
    private var sessionController: CameraSessionController? = null

    /** Set once [release] starts: a device closing after that was closed on purpose. */
    @Volatile
    private var isReleased = false

    @SuppressLint("MissingPermission")
    private val recovery = CameraRecovery(
        scope = coroutineScope,
        openWaiter = ::openAvailabilityWaiter,
        attempt = ::reopen,
        log = { Logger.i(TAG, "Camera $cameraId: $it") }
    )

    /** Why the camera is away while it is being got back (see [CameraRecovery]); null otherwise. */
    val interruptionFlow: StateFlow<String?> = recovery.interruptionFlow

    /**
     * Whether the device went away without being released: the system took it. Read when the
     * session ends, to tell that from a session closed on purpose.
     */
    val isLost: Boolean
        get() = !isReleased && deviceController?.isClosed == true

    private var captureRequestBuilder: CaptureRequestWithTargetsBuilder? = null
    private val sessionCallback = CameraSessionCallback(coroutineScope)

    private val controllerMutex = Mutex()

    private val outputs = mutableMapOf<String, CameraSurface>()

    private val _isActiveFlow = MutableStateFlow(false)
    val isActiveFlow = _isActiveFlow.asStateFlow()

    private val fpsRange: Range<Int>
        get() = CameraUtils.getClosestFpsRange(characteristics, fps)
    private var fps: Int = 30
    private var dynamicRangeProfile: DynamicRangeProfile = DynamicRangeProfile.sdr

    /**
     * Whether the current capture session has the given output.
     */
    suspend fun hasOutput(output: CameraSurface) = withContext(defaultDispatcher) {
        controllerMutex.withLock { outputs.values.contains(output) }
    }

    /**
     * Whether the current capture session has the given output.
     *
     * @param name The name of the output to check
     */
    suspend fun hasOutput(name: String) = withContext(defaultDispatcher) {
        controllerMutex.withLock { outputs.keys.contains(name) }
    }

    /**
     * Gets an output from the current capture session.
     *
     * @param name The name of the output to get
     */
    suspend fun getOutput(name: String) = withContext(defaultDispatcher) {
        controllerMutex.withLock { outputs[name] }
    }

    /**
     * Adds an output to the current capture session.
     *
     * If the output is not in the current capture session, the capture session is recreated with
     * the new output.
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    suspend fun addOutput(output: CameraSurface) {
        require(output.surface.isValid) { "Output is invalid: $output" }
        withContext(defaultDispatcher) {
            controllerMutex.withLock {
                if (outputs.values.contains(output)) {
                    Logger.w(TAG, "Output is already added: $output")
                    return@withContext
                }
                outputs[output.name] = output
                if (isActiveFlow.value) {
                    restartSessionUnsafe()
                }
            }
        }
    }

    /**
     * Removes an output from the current capture session.
     *
     * If the output is in the current capture session, the capture session is recreated without.
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    suspend fun removeOutput(name: String) {
        withContext(defaultDispatcher) {
            controllerMutex.withLock {
                val needRestart = outputs.remove(name) != null && isActiveFlow.value
                if (outputs.isEmpty()) {
                    sessionController?.close()
                } else if (needRestart) {
                    restartSessionUnsafe()
                }
            }
        }
    }

    /**
     * To be used under [controllerMutex]
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    private suspend fun getDeviceController(): CameraDeviceController {
        return if (deviceController != null && !deviceController!!.isClosed) {
            deviceController!!
        } else {
            CameraDeviceController.create(manager, sessionCompat, cameraId).apply {
                deviceController = this
                watchForLoss(this)
                Logger.d(TAG, "Device controller created")
            }
        }
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    private suspend fun getCaptureRequestBuilder(): CaptureRequestWithTargetsBuilder {
        return if (captureRequestBuilder != null) {
            captureRequestBuilder!!
        } else {
            getDeviceController().createCaptureRequestBuilder().apply {
                captureRequestBuilder = this

                val minFrameDuration = 1_000_000_000 / fpsRange.upper.toLong()
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
                set(CaptureRequest.SENSOR_FRAME_DURATION, minFrameDuration)
                onCaptureRequestBuilder()
            }
        }
    }

    /**
     * To be used under [controllerMutex]
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    private suspend fun getSessionController(): CameraSessionController {
        return if (sessionController == null) {
            CameraSessionController.create(
                getDeviceController(),
                sessionCallback,
                sessionCompat,
                outputs.values.toList(),
                dynamicRange = dynamicRangeProfile.dynamicRange,
                defaultDispatcher
            ).apply {
                applySessionController(this)
                Logger.d(TAG, "Session controller created")
            }
        } else {
            if (!sessionController!!.isClosed) {
                sessionController!!
            } else {
                try {
                    sessionController!!.recreate(
                        getDeviceController(),
                        outputs.values.toList(),
                        dynamicRange = dynamicRangeProfile.dynamicRange
                    ).apply {
                        applySessionController(this)
                        Logger.d(TAG, "Session controller recreated")
                    }
                } catch (t: Throwable) {
                    _isActiveFlow.tryEmit(false)
                    throw t
                }
            }
        }
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    private suspend fun applySessionController(
        sessionController: CameraSessionController
    ) {
        this.sessionController = sessionController

        // The previous session's watcher would otherwise outlive it
        isActiveJob?.cancel()
        isActiveJob = coroutineScope.launch {
            sessionController.isClosedFlow.collect {
                _isActiveFlow.emit(!it)
            }
        }

        // Re-add targets that are in the new outputs (identified by their name)
        captureRequestBuilder?.let {
            val targets = outputs.values.filter { output ->
                it.hasTarget(output)
            }
            it.clearTargets()
            if (targets.isNotEmpty()) {
                it.addTargets(targets)
            }
        }

        applyRepeatingSessionUnsafe()
    }

    /**
     * Restarts the current capture session.
     *
     * The current capture session is closed and a new one is created with the same outputs.
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    private suspend fun restartSessionUnsafe() {
        val sessionController = sessionController
        if (sessionController == null) {
            Logger.i(TAG, "SessionController is null, nothing to restart")
            return
        }

        isActiveJob?.cancel()
        isActiveJob = null

        sessionController.recreate(
            getDeviceController(),
            outputs.values.toList(),
            dynamicRange = dynamicRangeProfile.dynamicRange
        ).apply {
            applySessionController(this)
            Logger.d(TAG, "Session controller restarted")
        }
    }

    /**
     * Adds a target to the current capture session.
     *
     * @param name The name of target to add
     * @return true if the target has been added, false otherwise
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    suspend fun addTarget(name: String) = withContext(defaultDispatcher) {
        controllerMutex.withLock {
            val target = outputs[name] ?: throw IllegalArgumentException(
                "Output $name not found in outputs ${outputs.keys.joinToString(", ")}"
            )

            val wasAdded = getCaptureRequestBuilder().addTarget(target)
            if (wasAdded) {
                try {
                    applyRepeatingSessionUnsafe()
                } catch (t: Throwable) {
                    Logger.e(TAG, "Error to add target $name", t)
                }
                Logger.d(TAG, "Target $name added")
            }
        }
    }

    /**
     * Removes a target from the current capture session.
     *
     * @param name The name of target to remove
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    suspend fun removeTarget(name: String) {
        withContext(defaultDispatcher) {
            controllerMutex.withLock {
                val target = outputs[name] ?: return@withContext

                val wasRemoved = getCaptureRequestBuilder().removeTarget(target)
                if (!wasRemoved) {
                    return@withLock
                }
                try {
                    applyRepeatingSessionUnsafe()
                } catch (t: Throwable) {
                    Logger.e(TAG, "Error to remove target $name", t)
                }
                Logger.d(TAG, "Target $name removed")
            }
        }
    }

    /**
     * Gets a setting from the current capture request.
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    fun <T> getSetting(key: CaptureRequest.Key<T?>): T? {
        val captureRequestBuilder =
            requireNotNull(captureRequestBuilder) { "CaptureRequestBuilder is null" }
        return captureRequestBuilder.get(key)
    }

    /**
     * Resets the current capture request to its default settings.
     *
     * Don't forget to call [applyRepeatingSession] to apply the setting.
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    suspend fun resetSettings() {
        withContext(defaultDispatcher) {
            controllerMutex.withLock {
                captureRequestBuilder ?: return@withLock
                val currentCaptureRequestBuilder = getCaptureRequestBuilder()
                // Get targets of previous builder
                val targets = currentCaptureRequestBuilder.getTargets()
                captureRequestBuilder = null
                getCaptureRequestBuilder().addTargets(targets)
            }
        }
    }

    /**
     * Sets a setting to the current capture request.
     *
     * Don't forget to call [applyRepeatingSession] to apply the setting.
     *
     * @param key The setting key
     * @param value The setting value
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    suspend fun <T> setSetting(key: CaptureRequest.Key<T>, value: T) {
        val captureRequestBuilder = getCaptureRequestBuilder()
        captureRequestBuilder.set(key, value)
    }

    /**
     * Sets the fps to the current capture request.
     *
     * @param fpsRange The fps range
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    suspend fun setFps(fps: Int) {
        withContext(defaultDispatcher) {
            controllerMutex.withLock {
                if (this@CameraController.fps == fps) {
                    return@withContext
                }

                this@CameraController.fps = fps

                // A closed camera only keeps the rate for its next session: building a request
                // here would open the device, and the camera service then takes it from whoever
                // holds it now, a live camera of the same app included
                if (captureRequestBuilder == null && !isActiveFlow.value) {
                    return@withContext
                }

                val captureRequestBuilder = getCaptureRequestBuilder()

                val range = fpsRange
                val minFrameDuration = 1_000_000_000 / range.upper.toLong()
                captureRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
                captureRequestBuilder.set(CaptureRequest.SENSOR_FRAME_DURATION, minFrameDuration)

                if (isActiveFlow.value) {
                    applyRepeatingSessionUnsafe()
                }
            }
        }
    }

    /**
     * Sets the dynamic range profile to the current capture request.
     *
     * @param dynamicRangeProfile The dynamic range profile
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    suspend fun setDynamicRangeProfile(dynamicRangeProfile: DynamicRangeProfile) {
        withContext(defaultDispatcher) {
            controllerMutex.withLock {
                if (this@CameraController.dynamicRangeProfile == dynamicRangeProfile) {
                    return@withContext
                }
                Logger.d(TAG, "Setting dynamic range profile to $dynamicRangeProfile")

                this@CameraController.dynamicRangeProfile = dynamicRangeProfile

                if (isActiveFlow.value) {
                    restartSessionUnsafe()
                }
            }
        }
    }

    /**
     * Adds a capture callback listener to the current capture session.
     *
     * The listener is removed when it returns true or [removeCaptureCallbackListener] is called.
     */
    suspend fun addCaptureCallbackListener(listener: CaptureResultListener) {
        sessionCallback.addListener(listener)
    }

    /**
     * Removes a capture callback listener from the current capture session.
     *
     * @param listener The listener to remove
     */
    suspend fun removeCaptureCallbackListener(listener: CaptureResultListener) {
        sessionCallback.removeListener(listener)
    }

    /**
     * Sets a repeating session with the current capture request.
     *
     * @param tag A tag to associate with the session.
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    private suspend fun applyRepeatingSessionUnsafe(tag: Any? = null) {
        val sessionController = getSessionController()
        val captureRequestBuilder = getCaptureRequestBuilder()
        tag?.let {
            captureRequestBuilder.setTag(it)
        }
        sessionController.applyRepeatingSession(captureRequestBuilder)
    }


    /**
     * Sets a repeating session with the current capture request.
     *
     * @param tag A tag to associate with the session.
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    suspend fun applyRepeatingSession(tag: Any? = null) {
        withContext(defaultDispatcher) {
            controllerMutex.withLock {
                applyRepeatingSessionUnsafe(tag)
            }
        }
    }

    private suspend fun closeControllers() {
        sessionController?.close()
        sessionController = null
        deviceController?.close()
        deviceController = null
        captureRequestBuilder = null
    }

    /**
     * Reports [device] lost when it closes without [release]: the only other way a device closes
     * is the system taking it.
     */
    private fun watchForLoss(device: CameraDeviceController) {
        coroutineScope.launch {
            device.isClosedFlow.first { it }
            if (isReleased) return@launch
            val reason = device.throwableFlow.value?.message ?: "taken by another app or the system"
            Logger.w(TAG, "Camera $cameraId lost: $reason")
            recovery.onLost(reason)
        }
    }

    /** One attempt of [CameraRecovery]: opens the device and its session again, as they were. */
    @RequiresPermission(Manifest.permission.CAMERA)
    private suspend fun reopen(): CameraRecovery.Outcome = withContext(defaultDispatcher) {
        controllerMutex.withLock {
            if (isReleased) return@withLock CameraRecovery.Outcome.NOT_NEEDED
            val builder = captureRequestBuilder ?: return@withLock CameraRecovery.Outcome.NOT_NEEDED
            // A surface that went away meanwhile (the screen's preview) cannot be configured
            outputs.values.filter { !it.surface.isValid }.forEach { gone ->
                Logger.i(TAG, "Camera $cameraId: dropping output ${gone.name}, its surface is gone")
                outputs.remove(gone.name)
                builder.removeTarget(gone)
            }
            if (outputs.isEmpty() || builder.isEmpty()) {
                return@withLock CameraRecovery.Outcome.NOT_NEEDED
            }
            if (deviceController?.isClosed == false && sessionController?.isClosed == false) {
                return@withLock CameraRecovery.Outcome.RECOVERED
            }
            try {
                // A device taken again while its session is configured may never call back:
                // waiting for it would hold this controller's lock for good
                val done = withTimeoutOrNull(REOPEN_TIMEOUT_MS) { applyRepeatingSessionUnsafe() }
                if (done == null) {
                    Logger.w(TAG, "Camera $cameraId: reopening took too long")
                    return@withLock CameraRecovery.Outcome.RETRY
                }
                Logger.i(TAG, "Camera $cameraId reopened")
                CameraRecovery.Outcome.RECOVERED
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Logger.w(TAG, "Camera $cameraId still unavailable: ${t.message}")
                CameraRecovery.Outcome.RETRY
            }
        }
    }

    /** Wakes [CameraRecovery] whenever a camera of the phone is freed: freeing one frees cost. */
    private fun openAvailabilityWaiter(): CameraRecovery.Waiter {
        val events = Channel<Unit>(Channel.CONFLATED)
        val callback = object : CameraManager.AvailabilityCallback() {
            override fun onCameraAvailable(cameraId: String) {
                events.trySend(Unit)
            }
        }
        manager.registerAvailabilityCallback(callback, Handler(Looper.getMainLooper()))
        return object : CameraRecovery.Waiter {
            override fun clear() {
                while (events.tryReceive().isSuccess) Unit
            }

            override suspend fun awaitChange(timeoutMs: Long) {
                withTimeoutOrNull(timeoutMs) { events.receive() }
            }

            override fun close() {
                manager.unregisterAvailabilityCallback(callback)
            }
        }
    }

    suspend fun release() {
        isReleased = true
        withContext(defaultDispatcher) {
            controllerMutex.withLock {
                closeControllers()
            }
        }
        outputs.clear()
        sessionCompat.release()
        coroutineScope.cancel()
    }

    fun muteVibrationAndSound() {
        deviceController?.muteVibrationAndSound()
    }

    fun unmuteVibrationAndSound() {
        deviceController?.unmuteVibrationAndSound()
    }

    companion object {
        private const val TAG = "CameraController"

        /** Opening a camera and its session takes well under a second when it is free. */
        private const val REOPEN_TIMEOUT_MS = 10_000L
    }
}