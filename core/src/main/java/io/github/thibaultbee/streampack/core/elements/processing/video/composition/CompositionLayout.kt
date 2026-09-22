/*
 * Copyright (C) 2026 Thibault B.
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
package io.github.thibaultbee.streampack.core.elements.processing.video.composition

import android.graphics.Color
import android.opengl.Matrix
import android.util.Size

/**
 * How a layer's source fills its destination rectangle.
 */
enum class LayerScaleMode {
    /**
     * Scales down to fit the whole source inside the rectangle, preserving aspect ratio.
     * The unused part of the rectangle shows whatever is beneath it.
     */
    FIT,

    /**
     * Fills the whole rectangle, preserving aspect ratio, cropping the overflow.
     */
    FILL,

    /**
     * Fills the whole rectangle, ignoring aspect ratio.
     */
    STRETCH
}

/**
 * A rectangle in normalized canvas coordinates: `0..1`, origin at the top-left.
 *
 * Immutable on purpose. The layout is handed to the GL thread without a lock, which is only safe
 * if nothing in it can be mutated behind the renderer's back — that rules out
 * [android.graphics.RectF].
 */
data class LayerRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width = right - left
    val height = bottom - top

    val centerX = (left + right) / 2f
    val centerY = (top + bottom) / 2f

    init {
        require(right >= left) { "right ($right) must be >= left ($left)" }
        require(bottom >= top) { "bottom ($bottom) must be >= top ($top)" }
    }

    /**
     * Moves the rectangle by [dx], [dy], keeping it fully inside the canvas.
     */
    fun offsetInsideCanvas(dx: Float, dy: Float): LayerRect {
        val clampedDx = dx.coerceIn(-left, 1f - right)
        val clampedDy = dy.coerceIn(-top, 1f - bottom)
        return LayerRect(
            left + clampedDx,
            top + clampedDy,
            right + clampedDx,
            bottom + clampedDy
        )
    }

    companion object {
        val FULL = LayerRect(0f, 0f, 1f, 1f)

        val PIP_BOTTOM_RIGHT = LayerRect(0.66f, 0.62f, 0.98f, 0.96f)
        val PIP_BOTTOM_LEFT = LayerRect(0.02f, 0.62f, 0.34f, 0.96f)
        val PIP_TOP_RIGHT = LayerRect(0.66f, 0.04f, 0.98f, 0.38f)
        val PIP_TOP_LEFT = LayerRect(0.02f, 0.04f, 0.34f, 0.38f)

        val LEFT_HALF = LayerRect(0f, 0f, 0.5f, 1f)
        val RIGHT_HALF = LayerRect(0.5f, 0f, 1f, 1f)
        val TOP_HALF = LayerRect(0f, 0f, 1f, 0.5f)
        val BOTTOM_HALF = LayerRect(0f, 0.5f, 1f, 1f)
    }
}

/**
 * One composited layer: which region of the canvas a source is drawn into, and how.
 *
 * @param id identifies the input that feeds this layer. It is the key used by
 * [io.github.thibaultbee.streampack.core.elements.processing.video.ICompositingSurfaceProcessor].
 * @param z draw order. Lower is drawn first, so the highest [z] ends up on top.
 * @param rect destination rectangle, in normalized canvas coordinates.
 * @param scaleMode how the source fills [rect].
 * @param alpha layer opacity, `0..1`.
 * @param mirror whether to mirror the layer horizontally about its own centre.
 * @param rotationDegrees extra rotation of the source inside its rectangle. 0, 90, 180 or 270.
 * @param visible whether the layer is drawn at all.
 */
data class VideoLayer(
    val id: String,
    val z: Int = 0,
    val rect: LayerRect = LayerRect.FULL,
    val scaleMode: LayerScaleMode = LayerScaleMode.FIT,
    val alpha: Float = 1f,
    val mirror: Boolean = false,
    val rotationDegrees: Int = 0,
    val visible: Boolean = true
) {
    init {
        require(alpha in 0f..1f) { "alpha must be in 0..1, was $alpha" }
        require(rotationDegrees % 90 == 0) {
            "rotationDegrees must be a multiple of 90, was $rotationDegrees"
        }
    }

    /**
     * Whether this layer covers the whole canvas opaquely, so nothing below it can show through.
     */
    val isFullFrameOpaque =
        alpha >= 1f && scaleMode != LayerScaleMode.FIT && rect == LayerRect.FULL

    /**
     * Builds the `uTransMatrix` that places this layer's quad on the canvas.
     *
     * The vertex shader computes `gl_Position = uTransMatrix * aPosition` over a fixed full-NDC
     * quad, so placing a layer is purely a matter of this matrix — no vertex buffer changes.
     *
     * Normalized canvas coordinates map to NDC as `x = 2u - 1` and `y = 1 - 2v`, which gives a
     * scale of ([LayerRect.width], [LayerRect.height]) and a translation of
     * (`left + right - 1`, `1 - (top + bottom)`).
     *
     * [android.opengl.Matrix.translateM] and [android.opengl.Matrix.scaleM] post-concatenate, so
     * identity → translate → scale yields `T * S`: the quad is scaled, then moved into place.
     *
     * @param out receives the 4x4 matrix.
     * @param canvasSize the composition canvas, needed to know the destination aspect ratio.
     * @param sourceSize the layer's source resolution, for [LayerScaleMode.FIT]. Pass a zero size
     * when it is unknown; the layer then stretches.
     */
    fun buildTransformMatrix(out: FloatArray, canvasSize: Size, sourceSize: Size) {
        var scaleX = rect.width
        var scaleY = rect.height

        if (scaleMode == LayerScaleMode.FIT) {
            val sourceAspect = effectiveSourceAspect(sourceSize)
            val destAspect = destinationAspect(canvasSize)
            if (sourceAspect != null && destAspect != null) {
                if (sourceAspect > destAspect) {
                    // Source is wider than the slot: letterbox inside it.
                    scaleY *= destAspect / sourceAspect
                } else {
                    // Source is taller than the slot: pillarbox inside it.
                    scaleX *= sourceAspect / destAspect
                }
            }
        }

        // Mirroring is a reflection about the layer's own centre, which is what a user expects
        // from "mirror this layer". Keeping it out of the texture matrix avoids interfering with
        // the source's own mirroring compensation.
        if (mirror) {
            scaleX = -scaleX
        }

        Matrix.setIdentityM(out, 0)
        Matrix.translateM(
            out,
            0,
            rect.left + rect.right - 1f,
            1f - (rect.top + rect.bottom),
            0f
        )
        Matrix.scaleM(out, 0, scaleX, scaleY, 1f)
    }

    /**
     * Builds the texture-space crop applied for [LayerScaleMode.FILL], identity otherwise.
     *
     * FILL deliberately crops in texture space rather than growing the quad: there is no scissor
     * test, so a quad larger than its rectangle would spill over the neighbouring layers.
     */
    fun buildCropMatrix(out: FloatArray, canvasSize: Size, sourceSize: Size) {
        Matrix.setIdentityM(out, 0)

        if (scaleMode != LayerScaleMode.FILL) {
            return
        }

        val sourceAspect = effectiveSourceAspect(sourceSize) ?: return
        val destAspect = destinationAspect(canvasSize) ?: return

        var cropWidth = 1f
        var cropHeight = 1f
        if (sourceAspect > destAspect) {
            // Source is wider: keep the full height, crop the sides.
            cropWidth = destAspect / sourceAspect
        } else {
            // Source is taller: keep the full width, crop top and bottom.
            cropHeight = sourceAspect / destAspect
        }

        Matrix.translateM(out, 0, (1f - cropWidth) / 2f, (1f - cropHeight) / 2f, 0f)
        Matrix.scaleM(out, 0, cropWidth, cropHeight, 1f)
    }

    /**
     * The source aspect ratio once [rotationDegrees] is taken into account, or `null` when the
     * source size is not known yet.
     */
    private fun effectiveSourceAspect(sourceSize: Size): Float? {
        if (sourceSize.width <= 0 || sourceSize.height <= 0) {
            return null
        }
        return if (rotationDegrees % 180 == 0) {
            sourceSize.width.toFloat() / sourceSize.height
        } else {
            sourceSize.height.toFloat() / sourceSize.width
        }
    }

    /**
     * The aspect ratio of this layer's rectangle in real pixels.
     */
    private fun destinationAspect(canvasSize: Size): Float? {
        if (canvasSize.width <= 0 || canvasSize.height <= 0) {
            return null
        }
        val widthPx = rect.width * canvasSize.width
        val heightPx = rect.height * canvasSize.height
        if (widthPx <= 0f || heightPx <= 0f) {
            return null
        }
        return widthPx / heightPx
    }
}

/**
 * An immutable description of what the compositor draws.
 *
 * Immutability is the contract that makes the lock-free handoff to the GL thread safe: the
 * renderer reads the current layout once per frame, so a frame is always rendered entirely with
 * one layout or entirely with the next, never a mix.
 *
 * @param canvasSize the output resolution. Fixed for the lifetime of a composition, because
 * changing it means changing the video source configuration, which is forbidden while streaming.
 * @param layers the layers, in any order.
 * @param backgroundColor what the canvas is cleared to before drawing.
 * @param primaryLayerId the layer that drives the render clock and, for the app, the audio
 * source. Defaults to the bottom-most layer.
 */
data class CompositionLayout(
    val canvasSize: Size,
    val layers: List<VideoLayer> = emptyList(),
    val backgroundColor: Int = Color.BLACK,
    val primaryLayerId: String? = layers.minByOrNull { it.z }?.id
) {
    init {
        val duplicates = layers.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Duplicate layer ids: $duplicates" }
    }

    /**
     * Visible layers, bottom first. Computed once here so the GL thread never sorts or filters.
     */
    val drawOrder: List<VideoLayer> =
        layers.filter { it.visible && it.alpha > 0f }.sortedBy { it.z }

    /**
     * The layer driving the render clock, or `null` when the composition is empty.
     */
    val primaryLayer: VideoLayer? =
        layers.firstOrNull { it.id == primaryLayerId } ?: layers.minByOrNull { it.z }

    operator fun get(layerId: String): VideoLayer? = layers.firstOrNull { it.id == layerId }

    /**
     * Returns a copy with [layerId] transformed by [transform], or this layout unchanged when
     * there is no such layer.
     */
    fun mapLayer(layerId: String, transform: (VideoLayer) -> VideoLayer): CompositionLayout {
        if (layers.none { it.id == layerId }) {
            return this
        }
        return copy(layers = layers.map { if (it.id == layerId) transform(it) else it })
    }

    fun withLayer(layer: VideoLayer): CompositionLayout {
        val existing = layers.indexOfFirst { it.id == layer.id }
        val newLayers = if (existing >= 0) {
            layers.toMutableList().apply { set(existing, layer) }
        } else {
            layers + layer
        }
        return copy(layers = newLayers)
    }

    fun withoutLayer(layerId: String): CompositionLayout {
        val newLayers = layers.filterNot { it.id == layerId }
        return copy(
            layers = newLayers,
            primaryLayerId = if (primaryLayerId == layerId) {
                newLayers.minByOrNull { it.z }?.id
            } else {
                primaryLayerId
            }
        )
    }
}
