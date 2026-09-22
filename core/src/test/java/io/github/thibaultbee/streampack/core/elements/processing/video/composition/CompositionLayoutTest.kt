/*
 * Copyright 2026 Thibault B.
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
package io.github.thibaultbee.streampack.core.elements.processing.video.composition

import android.util.Size
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The placement maths. A wrong sign or a swapped axis here shows up on a phone as a layer that is
 * upside down or off-screen, which is expensive to debug, so it is pinned down in unit tests.
 *
 * `android.opengl.Matrix` is column-major: `m[0]` is the x scale, `m[5]` the y scale, `m[12]` the
 * x translation and `m[13]` the y translation.
 */
@RunWith(AndroidJUnit4::class)
class CompositionLayoutTest {
    private val landscapeCanvas = Size(1920, 1080)
    private val portraitCanvas = Size(1080, 1920)

    private val source16x9 = Size(1920, 1080)
    private val source4x3 = Size(640, 480)
    private val unknownSource = Size(0, 0)

    private fun transformOf(layer: VideoLayer, canvas: Size, source: Size): FloatArray {
        val matrix = FloatArray(16)
        layer.buildTransformMatrix(matrix, canvas, source)
        return matrix
    }

    private fun cropOf(layer: VideoLayer, canvas: Size, source: Size): FloatArray {
        val matrix = FloatArray(16)
        layer.buildCropMatrix(matrix, canvas, source)
        return matrix
    }

    private fun assertPlacement(
        matrix: FloatArray,
        scaleX: Float,
        scaleY: Float,
        translateX: Float,
        translateY: Float
    ) {
        assertEquals("scaleX", scaleX, matrix[0], TOLERANCE)
        assertEquals("scaleY", scaleY, matrix[5], TOLERANCE)
        assertEquals("translateX", translateX, matrix[12], TOLERANCE)
        assertEquals("translateY", translateY, matrix[13], TOLERANCE)
    }

    // region placement

    @Test
    fun `full frame layer with matching aspect is the identity`() {
        val layer = VideoLayer(id = "main", rect = LayerRect.FULL)

        val matrix = transformOf(layer, landscapeCanvas, source16x9)

        // This is the regression that matters most: a single full-frame layer must be
        // pixel-identical to the pre-compositing single-source path.
        assertPlacement(matrix, scaleX = 1f, scaleY = 1f, translateX = 0f, translateY = 0f)
    }

    @Test
    fun `unknown source size falls back to stretching`() {
        val layer = VideoLayer(id = "main", rect = LayerRect.FULL, scaleMode = LayerScaleMode.FIT)

        val matrix = transformOf(layer, landscapeCanvas, unknownSource)

        assertPlacement(matrix, scaleX = 1f, scaleY = 1f, translateX = 0f, translateY = 0f)
    }

    @Test
    fun `bottom right picture in picture lands bottom right`() {
        val layer = VideoLayer(
            id = "pip",
            rect = LayerRect.PIP_BOTTOM_RIGHT,
            scaleMode = LayerScaleMode.STRETCH
        )

        val matrix = transformOf(layer, landscapeCanvas, source16x9)

        // rect = (0.66, 0.62) .. (0.98, 0.96)
        assertPlacement(
            matrix,
            scaleX = 0.32f,
            scaleY = 0.34f,
            translateX = 0.64f,   // left + right - 1
            translateY = -0.58f   // 1 - (top + bottom): negative is down in NDC
        )
    }

    @Test
    fun `top left picture in picture lands top left`() {
        val layer = VideoLayer(
            id = "pip",
            rect = LayerRect.PIP_TOP_LEFT,
            scaleMode = LayerScaleMode.STRETCH
        )

        val matrix = transformOf(layer, landscapeCanvas, source16x9)

        assertTrue("should sit left of centre", matrix[12] < 0f)
        assertTrue("should sit above centre", matrix[13] > 0f)
    }

    @Test
    fun `side by side halves tile the canvas`() {
        val left = VideoLayer(id = "l", rect = LayerRect.LEFT_HALF, scaleMode = LayerScaleMode.STRETCH)
        val right = VideoLayer(id = "r", rect = LayerRect.RIGHT_HALF, scaleMode = LayerScaleMode.STRETCH)

        assertPlacement(
            transformOf(left, landscapeCanvas, source16x9),
            scaleX = 0.5f, scaleY = 1f, translateX = -0.5f, translateY = 0f
        )
        assertPlacement(
            transformOf(right, landscapeCanvas, source16x9),
            scaleX = 0.5f, scaleY = 1f, translateX = 0.5f, translateY = 0f
        )
    }

    // endregion

    // region FIT

    @Test
    fun `fit pillarboxes a 4x3 source in a 16x9 canvas`() {
        val layer = VideoLayer(id = "main", rect = LayerRect.FULL, scaleMode = LayerScaleMode.FIT)

        val matrix = transformOf(layer, landscapeCanvas, source4x3)

        // source 4:3 is narrower than the 16:9 slot, so the quad shrinks horizontally.
        assertEquals("scaleX", (4f / 3f) / (16f / 9f), matrix[0], TOLERANCE)
        assertEquals("scaleY", 1f, matrix[5], TOLERANCE)
    }

    @Test
    fun `fit letterboxes a 16x9 source in a portrait canvas`() {
        val layer = VideoLayer(id = "main", rect = LayerRect.FULL, scaleMode = LayerScaleMode.FIT)

        val matrix = transformOf(layer, portraitCanvas, source16x9)

        assertEquals("scaleX", 1f, matrix[0], TOLERANCE)
        assertEquals("scaleY", (9f / 16f) / (16f / 9f), matrix[5], TOLERANCE)
    }

    @Test
    fun `fit keeps the layer inside its own rectangle`() {
        val layer = VideoLayer(
            id = "pip",
            rect = LayerRect.PIP_BOTTOM_RIGHT,
            scaleMode = LayerScaleMode.FIT
        )

        val matrix = transformOf(layer, landscapeCanvas, source4x3)

        // Never larger than the slot itself, in either axis.
        assertTrue("scaleX within slot", kotlin.math.abs(matrix[0]) <= 0.32f + TOLERANCE)
        assertTrue("scaleY within slot", kotlin.math.abs(matrix[5]) <= 0.34f + TOLERANCE)
    }

    @Test
    fun `rotating a layer by 90 degrees swaps the source aspect`() {
        val upright = VideoLayer(id = "a", scaleMode = LayerScaleMode.FIT, rotationDegrees = 0)
        val rotated = VideoLayer(id = "a", scaleMode = LayerScaleMode.FIT, rotationDegrees = 90)

        val uprightMatrix = transformOf(upright, landscapeCanvas, source4x3)
        val rotatedMatrix = transformOf(rotated, landscapeCanvas, source4x3)

        // 4:3 upright is pillarboxed; rotated to 3:4 it is pillarboxed harder.
        assertTrue(rotatedMatrix[0] < uprightMatrix[0])
    }

    // endregion

    // region mirror

    @Test
    fun `mirror flips the layer about its own centre`() {
        val layer = VideoLayer(
            id = "pip",
            rect = LayerRect.PIP_BOTTOM_RIGHT,
            scaleMode = LayerScaleMode.STRETCH,
            mirror = true
        )

        val matrix = transformOf(layer, landscapeCanvas, source16x9)

        assertEquals("scaleX is negated", -0.32f, matrix[0], TOLERANCE)
        // The position must not move: mirroring is about the layer's centre, not the canvas.
        assertEquals("translateX unchanged", 0.64f, matrix[12], TOLERANCE)
    }

    // endregion

    // region FILL crop

    @Test
    fun `crop is identity unless the scale mode is fill`() {
        listOf(LayerScaleMode.FIT, LayerScaleMode.STRETCH).forEach { mode ->
            val layer = VideoLayer(id = "main", scaleMode = mode)
            val matrix = cropOf(layer, landscapeCanvas, source4x3)
            assertPlacement(matrix, scaleX = 1f, scaleY = 1f, translateX = 0f, translateY = 0f)
        }
    }

    @Test
    fun `fill crops the top and bottom of a 4x3 source in a 16x9 slot`() {
        val layer = VideoLayer(id = "main", rect = LayerRect.FULL, scaleMode = LayerScaleMode.FILL)

        val matrix = cropOf(layer, landscapeCanvas, source4x3)

        val expectedCropHeight = (4f / 3f) / (16f / 9f)
        assertEquals("full width kept", 1f, matrix[0], TOLERANCE)
        assertEquals("height cropped", expectedCropHeight, matrix[5], TOLERANCE)
        assertEquals("crop is centred", (1f - expectedCropHeight) / 2f, matrix[13], TOLERANCE)
    }

    @Test
    fun `fill crops the sides of a 16x9 source in a portrait slot`() {
        val layer = VideoLayer(id = "main", rect = LayerRect.FULL, scaleMode = LayerScaleMode.FILL)

        val matrix = cropOf(layer, portraitCanvas, source16x9)

        val expectedCropWidth = (9f / 16f) / (16f / 9f)
        assertEquals("width cropped", expectedCropWidth, matrix[0], TOLERANCE)
        assertEquals("full height kept", 1f, matrix[5], TOLERANCE)
        assertEquals("crop is centred", (1f - expectedCropWidth) / 2f, matrix[12], TOLERANCE)
    }

    // endregion

    // region layout invariants

    @Test
    fun `duplicate layer ids are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            CompositionLayout(
                canvasSize = landscapeCanvas,
                layers = listOf(VideoLayer(id = "same"), VideoLayer(id = "same", z = 1))
            )
        }
    }

    @Test
    fun `draw order is bottom first and skips what cannot be seen`() {
        val layout = CompositionLayout(
            canvasSize = landscapeCanvas,
            layers = listOf(
                VideoLayer(id = "top", z = 2),
                VideoLayer(id = "hidden", z = 1, visible = false),
                VideoLayer(id = "transparent", z = 3, alpha = 0f),
                VideoLayer(id = "bottom", z = 0)
            )
        )

        assertEquals(listOf("bottom", "top"), layout.drawOrder.map { it.id })
    }

    @Test
    fun `the bottom layer drives the clock by default`() {
        val layout = CompositionLayout(
            canvasSize = landscapeCanvas,
            layers = listOf(VideoLayer(id = "pip", z = 5), VideoLayer(id = "main", z = 0))
        )

        assertEquals("main", layout.primaryLayer?.id)
    }

    @Test
    fun `removing the primary layer promotes the next one`() {
        val layout = CompositionLayout(
            canvasSize = landscapeCanvas,
            layers = listOf(VideoLayer(id = "main", z = 0), VideoLayer(id = "pip", z = 1))
        ).withoutLayer("main")

        assertEquals("pip", layout.primaryLayerId)
        assertNull(layout["main"])
    }

    @Test
    fun `mapLayer only touches the named layer`() {
        val layout = CompositionLayout(
            canvasSize = landscapeCanvas,
            layers = listOf(VideoLayer(id = "main", z = 0), VideoLayer(id = "pip", z = 1))
        ).mapLayer("pip") { it.copy(rect = LayerRect.PIP_TOP_LEFT) }

        assertEquals(LayerRect.PIP_TOP_LEFT, layout["pip"]?.rect)
        assertEquals(LayerRect.FULL, layout["main"]?.rect)
    }

    @Test
    fun `dragging a layer stops at the canvas edge`() {
        val moved = LayerRect.PIP_BOTTOM_RIGHT.offsetInsideCanvas(dx = 0.5f, dy = 0.5f)

        assertEquals(1f, moved.right, TOLERANCE)
        assertEquals(1f, moved.bottom, TOLERANCE)
        assertEquals(
            "width is preserved",
            LayerRect.PIP_BOTTOM_RIGHT.width,
            moved.width,
            TOLERANCE
        )
    }

    // endregion

    companion object {
        private const val TOLERANCE = 1e-4f
    }
}
