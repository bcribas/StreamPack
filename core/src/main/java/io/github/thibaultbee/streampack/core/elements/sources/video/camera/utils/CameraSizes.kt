/*
 * Copyright 2020 The Android Open Source Project
 * Copyright 2021 Thibault B.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.thibaultbee.streampack.core.elements.sources.video.camera.utils

import android.hardware.camera2.CameraCharacteristics
import android.util.Size
import io.github.thibaultbee.streampack.core.elements.utils.extensions.closestTo
import kotlin.math.abs

object CameraSizes {
    /**
     * Returns the largest available PREVIEW size. For more information, see:
     * https://d.android.com/reference/android/hardware/camera2/CameraDevice and
     * https://developer.android.com/reference/android/hardware/camera2/params/StreamConfigurationMap
     */
    fun <T> getPreviewOutputSize(
        characteristics: CameraCharacteristics,
        targetSize: Size,
        targetClass: Class<T>,
    ): Size {
        val allSizes =
            characteristics[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]!!.getOutputSizes(
                targetClass
            ).toList()

        // Get available sizes and sort them by area from largest to smallest
        val validSizes = allSizes
            .sortedWith(compareBy { it.height * it.width })
            .map { Size(it.width, it.height) }.reversed()

        // Then, get the largest output size that is smaller or equal than our max size
        return validSizes.closestTo(targetSize)
    }

    /**
     * The supported output closest to [targetSize] **among those with the same shape**.
     *
     * [getPreviewOutputSize] compares by area alone, which is only safe while [targetSize] is
     * itself a supported size — then the exact match wins by equality. Ask for a reduced preview
     * and it stops being safe: on a Galaxy S20 FE the 16:9 outputs are 3840x2160, 1920x1080,
     * 1280x720, 640x360 and 256x144, with no 960x540 at all. Against a 960x540 target the 4:3
     * 960x720 is closer in area than 640x360, so an area-only choice hands back a preview of the
     * wrong shape.
     *
     * Falls back to the full list when nothing matches the requested shape, so a camera with an
     * unusual sensor still gets a preview.
     *
     * @param aspectTolerance how far from [targetSize]'s ratio still counts as the same shape.
     */
    fun <T> getPreviewOutputSizeForAspect(
        characteristics: CameraCharacteristics,
        targetSize: Size,
        targetClass: Class<T>,
        aspectTolerance: Float = 0.05f
    ): Size {
        val allSizes =
            characteristics[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]!!.getOutputSizes(
                targetClass
            ).map { Size(it.width, it.height) }

        return closestSameShape(allSizes, targetSize, aspectTolerance)
    }

    /**
     * The pure part of [getPreviewOutputSizeForAspect], separated so it can be tested against a
     * real device's size list without mocking camera2.
     */
    internal fun closestSameShape(
        available: List<Size>,
        targetSize: Size,
        aspectTolerance: Float = 0.05f
    ): Size {
        if (available.isEmpty() || targetSize.width <= 0 || targetSize.height <= 0) {
            return targetSize
        }

        val targetRatio = targetSize.width.toFloat() / targetSize.height
        val sameShape = available.filter {
            it.width > 0 && it.height > 0 &&
                    abs(it.width.toFloat() / it.height - targetRatio) <= aspectTolerance
        }

        return (sameShape.ifEmpty { available }).closestTo(targetSize)
    }
}
