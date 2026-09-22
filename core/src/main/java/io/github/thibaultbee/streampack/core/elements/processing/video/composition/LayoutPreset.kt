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

/**
 * One position in a [LayoutPreset].
 */
data class LayoutSlot(
    val rect: LayerRect,
    val scaleMode: LayerScaleMode = LayerScaleMode.FIT
)

/**
 * A named arrangement of positions.
 *
 * A preset describes **geometry only, never sources**. Applying one maps the layers that happen
 * to be loaded onto its slots, so "side by side" works whatever is currently on screen and never
 * restarts a source — which is what makes presets safe to tap while live.
 */
data class LayoutPreset(
    val id: String,
    val name: String,
    val slots: List<LayoutSlot>
) {
    init {
        require(slots.isNotEmpty()) { "A preset needs at least one slot" }
    }
}

/**
 * Applies [preset] to this layout, bottom layer into the first slot and upwards.
 *
 * Layers with no slot are hidden rather than removed, so re-applying a larger preset brings them
 * straight back with no source churn.
 */
fun CompositionLayout.applyPreset(preset: LayoutPreset): CompositionLayout {
    val ordered = layers.sortedBy { it.z }
    val newLayers = ordered.mapIndexed { index, layer ->
        val slot = preset.slots.getOrNull(index)
        if (slot == null) {
            // Also park it full-frame. Keeping the old rectangle meant a later swap moved the
            // visible layer into a stale inset rectangle, which looked like the swap was broken.
            layer.copy(visible = false, rect = LayerRect.FULL)
        } else {
            layer.copy(
                rect = slot.rect,
                scaleMode = slot.scaleMode,
                z = index,
                visible = true
            )
        }
    }
    return copy(layers = newLayers)
}

/**
 * Swaps the draw order of two layers, keeping every rectangle where it is.
 *
 * This is the one-tap "make the guest full screen": the sources do not move, only which one is
 * behind the other.
 */
fun CompositionLayout.swapLayerOrder(firstId: String, secondId: String): CompositionLayout {
    val first = get(firstId) ?: return this
    val second = get(secondId) ?: return this
    if (!first.visible || !second.visible) {
        // Swapping with something that is not on screen just makes the visible layer vanish.
        return this
    }
    return copy(
        layers = layers.map {
            when (it.id) {
                firstId -> it.copy(z = second.z, rect = second.rect, scaleMode = second.scaleMode)
                secondId -> it.copy(z = first.z, rect = first.rect, scaleMode = first.scaleMode)
                else -> it
            }
        }
    )
}

/**
 * Which of [presets] this layout currently matches, or null after a manual drag.
 *
 * Derived rather than stored: the layout is the immutable contract the GL thread consumes, and a
 * preset id kept inside it would have to be invalidated by every drag, snap and resize -- easy to
 * get wrong, and wrong in the direction of lying to the operator. The slot rectangles are exact
 * constants, so an exact-ish comparison is reliable; the drag snap only lands on edges and centre.
 */
fun CompositionLayout.matchingPreset(presets: List<LayoutPreset>): LayoutPreset? {
    val visible = layers.filter { it.visible }.sortedBy { it.z }
    return presets.firstOrNull { preset ->
        preset.slots.size == visible.size &&
                visible.zip(preset.slots).all { (layer, slot) ->
                    layer.scaleMode == slot.scaleMode && layer.rect.matches(slot.rect)
                }
    }
}

private fun LayerRect.matches(other: LayerRect): Boolean {
    fun near(a: Float, b: Float) = kotlin.math.abs(a - b) <= PRESET_MATCH_TOLERANCE
    return near(left, other.left) && near(top, other.top) &&
            near(right, other.right) && near(bottom, other.bottom)
}

private const val PRESET_MATCH_TOLERANCE = 1e-3f

/**
 * The arrangements shipped with the library.
 */
object CompositionPresets {
    val SINGLE = LayoutPreset(
        "single", "Full frame",
        listOf(LayoutSlot(LayerRect.FULL, LayerScaleMode.FILL))
    )

    val PIP_BOTTOM_RIGHT = pip("pip_br", "PiP bottom right", LayerRect.PIP_BOTTOM_RIGHT)
    val PIP_BOTTOM_LEFT = pip("pip_bl", "PiP bottom left", LayerRect.PIP_BOTTOM_LEFT)
    val PIP_TOP_RIGHT = pip("pip_tr", "PiP top right", LayerRect.PIP_TOP_RIGHT)
    val PIP_TOP_LEFT = pip("pip_tl", "PiP top left", LayerRect.PIP_TOP_LEFT)

    val SIDE_BY_SIDE = LayoutPreset(
        "side_by_side", "Side by side",
        listOf(
            LayoutSlot(LayerRect.LEFT_HALF, LayerScaleMode.FIT),
            LayoutSlot(LayerRect.RIGHT_HALF, LayerScaleMode.FIT)
        )
    )

    val TOP_BOTTOM = LayoutPreset(
        "top_bottom", "Stacked",
        listOf(
            LayoutSlot(LayerRect.TOP_HALF, LayerScaleMode.FIT),
            LayoutSlot(LayerRect.BOTTOM_HALF, LayerScaleMode.FIT)
        )
    )

    /**
     * Every built-in preset, in the order a picker should show them.
     */
    val ALL = listOf(
        SINGLE,
        PIP_BOTTOM_RIGHT,
        PIP_BOTTOM_LEFT,
        PIP_TOP_LEFT,
        PIP_TOP_RIGHT,
        SIDE_BY_SIDE,
        TOP_BOTTOM
    )

    private fun pip(id: String, name: String, rect: LayerRect) = LayoutPreset(
        id, name,
        listOf(
            LayoutSlot(LayerRect.FULL, LayerScaleMode.FILL),
            LayoutSlot(rect, LayerScaleMode.FIT)
        )
    )
}
