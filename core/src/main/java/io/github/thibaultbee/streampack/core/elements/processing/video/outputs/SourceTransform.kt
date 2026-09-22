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

import android.opengl.Matrix
import androidx.annotation.IntRange
import io.github.thibaultbee.streampack.core.elements.processing.video.source.ISourceInfoProvider
import io.github.thibaultbee.streampack.core.elements.processing.video.utils.extensions.preRotate
import io.github.thibaultbee.streampack.core.elements.processing.video.utils.extensions.preVerticalFlip
import io.github.thibaultbee.streampack.core.elements.utils.RotationValue

/**
 * The GL transform that turns one source's frames into upright, correctly mirrored frames.
 *
 * It is derived from a single [ISourceInfoProvider], so there is one of these per source. A
 * [SurfaceOutput] owns one for its own source; a compositing processor owns one per layer, since
 * each layer has its own source orientation and mirroring.
 *
 * The transform is computed once, at construction, and only depends on [targetRotation],
 * [needMirroring] and the source's info provider.
 *
 * @param targetRotation the rotation of the destination surface.
 * @param needMirroring whether the output must be mirrored.
 * @param sourceInfoProvider the source's orientation and mirroring information.
 */
class SourceTransform(
    @RotationValue val targetRotation: Int,
    val needMirroring: Boolean,
    sourceInfoProvider: ISourceInfoProvider
) {
    /**
     * The source rotation relative to the target, in degrees.
     */
    @IntRange(from = 0, to = 359)
    val rotationDegrees = sourceInfoProvider.getRelativeRotationDegrees(
        targetRotation,
        needMirroring
    )

    /**
     * The source rotation relative to the device's natural orientation, in degrees.
     */
    @IntRange(from = 0, to = 359)
    val sourceRotationDegrees = sourceInfoProvider.rotationDegrees

    private val additionalTransform = FloatArray(16)
    private val invertedTextureTransform = FloatArray(16)

    init {
        calculateAdditionalTransform(
            additionalTransform,
            invertedTextureTransform,
            sourceInfoProvider
        )
    }

    /**
     * Concatenates this source transform onto a texture transform.
     *
     * @param output receives `input * additionalTransform`.
     * @param input the texture transform from [android.graphics.SurfaceTexture.getTransformMatrix].
     */
    fun updateTransformMatrix(output: FloatArray, input: FloatArray) {
        Matrix.multiplyMM(
            output, 0, input, 0, additionalTransform, 0
        )
    }

    /**
     * Calculates the additional GL transform and saves it to additionalTransform.
     *
     *
     * The effect implementation needs to apply this value on top of texture transform obtained
     * from [android.graphics.SurfaceTexture.getTransformMatrix].
     *
     *
     * The overall transformation (A * B) is a concatenation of 2 values: A) the texture
     * transform (value of SurfaceTexture#getTransformMatrix), and B) CameraX's additional
     * transform based on user config such as the ViewPort API and UseCase#targetRotation. To
     * calculate B, we do it in 3 steps:
     *
     *  1. 1. Calculate A * B by using CameraX transformation value such as crop rect, relative
     * rotation, and mirroring. It already contains the texture transform(A).
     *  1. 2. Calculate A^-1 by predicating the texture transform(A) based on camera
     * characteristics then inverting it.
     *  1. 3. Calculate B by multiplying A^-1 * A * B.
     *
     */
    private fun calculateAdditionalTransform(
        additionalTransform: FloatArray,
        invertedTransform: FloatArray,
        sourceInfoProvider: ISourceInfoProvider
    ) {
        Matrix.setIdentityM(additionalTransform, 0)

        // Step 1, calculate the overall transformation(A * B) with the following steps:
        // - Flip compensate the GL coordinates v.s. image coordinates
        // - Rotate the image based on the relative rotation
        // - Mirror the image if needed
        // - Apply the crop rect

        // Flipping for GL.
        additionalTransform.preVerticalFlip(0.5f)

        // Rotation
        additionalTransform.preRotate(rotationDegrees.toFloat(), 0.5f, 0.5f)

        // Mirroring
        if (needMirroring) {
            Matrix.translateM(additionalTransform, 0, 1f, 0f, 0f)
            Matrix.scaleM(additionalTransform, 0, -1f, 1f, 1f)
        }

        // Note: We do NOT apply crop/scale transformation here because we use viewport-based
        // letterboxing. The viewportRect already defines where on the output surface the content
        // should be rendered while preserving aspect ratio. The texture should render at 1:1
        // into that viewport area.

        // Step 2: calculate the inverted texture transform: A^-1
        calculateInvertedTextureTransform(invertedTransform, sourceInfoProvider)

        // Step 3: calculate the additional transform: B = A^-1 * A * B
        Matrix.multiplyMM(
            additionalTransform, 0, invertedTransform, 0,
            additionalTransform, 0
        )
    }

    /**
     * Calculates the inverted texture transform and saves it to invertedTextureTransform.
     *
     * This method predicts the value of [android.graphics.SurfaceTexture.getTransformMatrix]
     * based on camera characteristics then invert it. The result is used to remove the texture
     * transform from overall transformation.
     */
    private fun calculateInvertedTextureTransform(
        invertedTextureTransform: FloatArray,
        sourceInfoProvider: ISourceInfoProvider
    ) {
        Matrix.setIdentityM(invertedTextureTransform, 0)

        // Flip for GL. SurfaceTexture#getTransformMatrix always contains this flipping regardless
        // of whether it has the camera transform.
        invertedTextureTransform.preVerticalFlip(0.5f)

        // Applies the camera sensor orientation if the input surface contains camera transform.
        // Rotation
        invertedTextureTransform.preRotate(
            sourceRotationDegrees.toFloat(),
            0.5f,
            0.5f
        )

        // Mirroring
        if (sourceInfoProvider.isMirror) {
            Matrix.translateM(invertedTextureTransform, 0, 1f, 0f, 0f)
            Matrix.scaleM(invertedTextureTransform, 0, -1f, 1f, 1f)
        }

        // Invert the matrix so it can be used to "undo" the SurfaceTexture#getTransformMatrix.
        Matrix.invertM(invertedTextureTransform, 0, invertedTextureTransform, 0)
    }
}
