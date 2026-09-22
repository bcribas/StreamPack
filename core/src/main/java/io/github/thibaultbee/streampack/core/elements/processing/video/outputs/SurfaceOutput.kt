/*
 * Copyright (C) 2024 Thibault B.
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
package io.github.thibaultbee.streampack.core.elements.processing.video.outputs

import android.graphics.Rect
import android.util.Size
import android.view.Surface
import androidx.annotation.IntRange
import io.github.thibaultbee.streampack.core.elements.processing.video.source.ISourceInfoProvider
import io.github.thibaultbee.streampack.core.elements.utils.RotationValue
import io.github.thibaultbee.streampack.core.elements.utils.extensions.rotate
import io.github.thibaultbee.streampack.core.logger.Logger
import io.github.thibaultbee.streampack.core.pipelines.outputs.SurfaceDescriptor
import kotlin.math.roundToInt

fun SurfaceOutput(
    descriptor: SurfaceDescriptor,
    isStreaming: () -> Boolean,
    sourceResolution: Size,
    needMirroring: Boolean,
    sourceInfoProvider: ISourceInfoProvider
) =
    SurfaceOutput(
        descriptor.surface,
        descriptor.resolution,
        descriptor.targetRotation,
        isStreaming,
        sourceResolution,
        needMirroring,
        sourceInfoProvider
    )

class SurfaceOutput(
    override val targetSurface: Surface,
    override val targetResolution: Size,
    @RotationValue val targetRotation: Int,
    val isStreaming: () -> Boolean,
    sourceResolution: Size,
    val needMirroring: Boolean,
    sourceInfoProvider: ISourceInfoProvider,
    override val maxFps: Int? = null
) :
    ISurfaceOutput {
    override val type = ISurfaceOutput.OutputType.INTERNAL

    /**
     * The orientation/mirroring transform for this output's source. Declared before
     * [viewportRect] because that initializer reads [rotationDegrees].
     */
    private val sourceTransform = SourceTransform(
        targetRotation,
        needMirroring,
        sourceInfoProvider
    )

    @get:IntRange(from = 0, to = 359)
    val rotationDegrees: Int
        get() = sourceTransform.rotationDegrees

    @get:IntRange(from = 0, to = 359)
    val sourceRotationDegrees: Int
        get() = sourceTransform.sourceRotationDegrees

    /**
     * Calculate viewport rect for letterboxing/pillarboxing.
     * This ensures the source content fits within the target while preserving aspect ratio.
     * We use getSurfaceSize(targetResolution) to get the source size - for cameras this returns
     * targetResolution (same aspect ratio = no letterboxing), for external sources it returns
     * the actual source resolution (may differ = letterboxing applied).
     */
    override val viewportRect: Rect = calculateViewportRect(
        sourceInfoProvider.getSurfaceSize(targetResolution).rotate(rotationDegrees),
        targetResolution
    )

    private fun calculateViewportRect(sourceSize: Size, targetSize: Size): Rect {
        val sourceRatio = sourceSize.width.toFloat() / sourceSize.height
        val targetRatio = targetSize.width.toFloat() / targetSize.height

        return if (sourceRatio > targetRatio) {
            // Source is wider than target - letterbox (black bars on top and bottom)
            val newHeight = (targetSize.width / sourceRatio).roundToInt()
            val yOffset = (targetSize.height - newHeight) / 2
            Rect(0, yOffset, targetSize.width, yOffset + newHeight)
        } else if (sourceRatio < targetRatio) {
            // Source is taller than target - pillarbox (black bars on sides)
            val newWidth = (targetSize.height * sourceRatio).roundToInt()
            val xOffset = (targetSize.width - newWidth) / 2
            Rect(xOffset, 0, xOffset + newWidth, targetSize.height)
        } else {
            // Same aspect ratio - use full target
            Rect(0, 0, targetSize.width, targetSize.height)
        }
    }

    override fun updateTransformMatrix(output: FloatArray, input: FloatArray) =
        sourceTransform.updateTransformMatrix(output, input)
}
