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
package io.github.thibaultbee.streampack.core.elements.sources.video.composite

import android.util.Size
import io.github.thibaultbee.streampack.core.elements.processing.video.composition.CompositionLayout
import io.github.thibaultbee.streampack.core.elements.processing.video.composition.VideoLayer
import io.github.thibaultbee.streampack.core.elements.sources.video.IVideoSource
import io.github.thibaultbee.streampack.core.elements.sources.video.IVideoSourceInternal
import io.github.thibaultbee.streampack.core.elements.utils.RotationValue
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Why a layer stopped producing frames.
 */
data class LayerFailure(
    val layerId: String,
    val reason: String
)

/**
 * A video source that draws several child sources into one frame.
 *
 * To the pipeline it is an ordinary single source producing an already-oriented frame at the
 * encoder resolution, so nothing downstream — [io.github.thibaultbee.streampack.core.pipelines.inputs.VideoInput],
 * the streamer, the encoders — needs to know that compositing is happening.
 */
interface ICompositeVideoSource : IVideoSource {
    /**
     * The composition canvas. Fixed while the source exists, because changing it means changing
     * the video source configuration, which the pipeline forbids while streaming.
     */
    val canvasSize: Size

    /**
     * The current layout.
     */
    val layoutFlow: StateFlow<CompositionLayout>

    /**
     * Emits when a child source stops delivering frames, for example a USB camera unplugged or a
     * network source dropping.
     *
     * The composite deliberately keeps streaming: only that one layer goes away.
     */
    val layerFailureFlow: SharedFlow<LayerFailure>

    /**
     * The child source behind the primary layer.
     *
     * The app keys its audio source off this, which is what makes "audio follows the main camera"
     * work when several sources are on screen at once.
     */
    val primaryChildSource: IVideoSourceInternal?

    /**
     * Replaces the whole layout.
     *
     * Geometry-only: safe to call while streaming, at gesture rate. It never starts or stops a
     * child, never touches the encoder and never reallocates a surface.
     */
    fun updateLayout(layout: CompositionLayout)

    /**
     * Transforms one layer, leaving the rest of the layout untouched. Geometry-only, see
     * [updateLayout].
     */
    fun updateLayer(layerId: String, transform: (VideoLayer) -> VideoLayer)

    /**
     * Designates the layer that drives the render clock and the audio source.
     */
    fun setPrimaryLayer(layerId: String)

    /**
     * Sets the rotation the canvas is produced for.
     */
    fun setTargetRotation(@RotationValue targetRotation: Int)

    /**
     * The source behind [layerId], or `null` when there is no such layer.
     *
     * Lets the app talk to one layer's source directly — a USB camera that needs to be told its
     * device reopened, for instance — without knowing how the composition is put together.
     */
    fun childSource(layerId: String): IVideoSourceInternal?

    /**
     * Adds a layer, starting its source if the composition is already live.
     *
     * Structural, so unlike the geometry calls it creates GL resources and may open a device. The
     * rest of the composition keeps streaming throughout.
     */
    suspend fun addLayer(spec: LayerSpec)

    /**
     * Removes a layer and releases its source.
     */
    suspend fun removeLayer(layerId: String)

    /**
     * Swaps the source behind a layer, keeping its place in the layout.
     *
     * This is how a dead source degrades to a placeholder without disturbing anything else: the
     * layer stays where it is and simply shows nothing until the new source delivers a frame.
     */
    suspend fun replaceLayerSource(layerId: String, spec: LayerSpec)
}
