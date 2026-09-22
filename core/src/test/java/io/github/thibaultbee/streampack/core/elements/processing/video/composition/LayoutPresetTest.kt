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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Presets are the one-tap path the operator uses while live, so what matters here is that
 * applying one only ever moves geometry: same layers, same ids, same sources.
 */
@RunWith(AndroidJUnit4::class)
class LayoutPresetTest {
    private val canvas = Size(1920, 1080)

    private fun layoutOf(vararg ids: String) = CompositionLayout(
        canvasSize = canvas,
        layers = ids.mapIndexed { index, id -> VideoLayer(id = id, z = index) }
    )

    @Test
    fun `applying a preset keeps the same layers`() {
        val layout = layoutOf("main", "pip")

        val result = layout.applyPreset(CompositionPresets.SIDE_BY_SIDE)

        assertEquals(
            "no layer is added or dropped",
            setOf("main", "pip"),
            result.layers.map { it.id }.toSet()
        )
    }

    @Test
    fun `side by side puts the bottom layer on the left`() {
        val result = layoutOf("main", "pip").applyPreset(CompositionPresets.SIDE_BY_SIDE)

        assertEquals(LayerRect.LEFT_HALF, result["main"]?.rect)
        assertEquals(LayerRect.RIGHT_HALF, result["pip"]?.rect)
    }

    @Test
    fun `a picture in picture preset fills with the bottom layer and insets the top one`() {
        val result = layoutOf("main", "pip").applyPreset(CompositionPresets.PIP_TOP_LEFT)

        assertEquals(LayerRect.FULL, result["main"]?.rect)
        assertEquals(LayerScaleMode.FILL, result["main"]?.scaleMode)
        assertEquals(LayerRect.PIP_TOP_LEFT, result["pip"]?.rect)
        assertEquals(LayerScaleMode.FIT, result["pip"]?.scaleMode)
    }

    @Test
    fun `layers beyond the preset are hidden, not removed`() {
        val result = layoutOf("main", "pip").applyPreset(CompositionPresets.SINGLE)

        assertEquals("still two layers", 2, result.layers.size)
        assertTrue("main stays visible", result["main"]?.visible == true)
        assertFalse("pip is only hidden", result["pip"]?.visible == true)
    }

    @Test
    fun `a hidden layer comes back when a larger preset is applied`() {
        val hidden = layoutOf("main", "pip").applyPreset(CompositionPresets.SINGLE)

        val restored = hidden.applyPreset(CompositionPresets.PIP_BOTTOM_RIGHT)

        assertTrue(restored["pip"]?.visible == true)
        assertEquals(LayerRect.PIP_BOTTOM_RIGHT, restored["pip"]?.rect)
    }

    @Test
    fun `every built-in preset is applicable to a two layer composition`() {
        val layout = layoutOf("main", "pip")

        CompositionPresets.ALL.forEach { preset ->
            val result = layout.applyPreset(preset)
            assertEquals(
                "preset ${preset.id} kept the layers",
                2,
                result.layers.size
            )
            assertTrue(
                "preset ${preset.id} draws at least one layer",
                result.drawOrder.isNotEmpty()
            )
        }
    }

    @Test
    fun `built-in preset ids are unique`() {
        val ids = CompositionPresets.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `swapping order exchanges both the depth and the rectangle`() {
        val layout = layoutOf("main", "pip").applyPreset(CompositionPresets.PIP_BOTTOM_RIGHT)

        val swapped = layout.swapLayerOrder("main", "pip")

        // The guest is now full frame at the back, and the host is the corner inset on top.
        assertEquals(LayerRect.PIP_BOTTOM_RIGHT, swapped["main"]?.rect)
        assertEquals(LayerScaleMode.FIT, swapped["main"]?.scaleMode)
        assertEquals(LayerRect.FULL, swapped["pip"]?.rect)
        assertEquals(LayerScaleMode.FILL, swapped["pip"]?.scaleMode)
        assertEquals("guest is drawn first", "pip", swapped.drawOrder.first().id)
        assertEquals("host is drawn on top", "main", swapped.drawOrder.last().id)
    }

    @Test
    fun `swapping an unknown layer changes nothing`() {
        val layout = layoutOf("main", "pip")

        assertEquals(layout, layout.swapLayerOrder("main", "nope"))
    }

    @Test
    fun `applying a preset preserves the canvas`() {
        val result = layoutOf("main", "pip").applyPreset(CompositionPresets.TOP_BOTTOM)

        assertEquals(canvas, result.canvasSize)
    }
}
