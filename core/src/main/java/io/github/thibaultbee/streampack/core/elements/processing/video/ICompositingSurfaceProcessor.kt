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
package io.github.thibaultbee.streampack.core.elements.processing.video

import android.util.Size
import android.view.Surface
import io.github.thibaultbee.streampack.core.elements.processing.video.composition.CompositionLayout
import io.github.thibaultbee.streampack.core.elements.processing.video.source.ISourceInfoProvider
import io.github.thibaultbee.streampack.core.elements.utils.RotationValue
import io.github.thibaultbee.streampack.core.elements.utils.av.video.DynamicRangeProfile
import io.github.thibaultbee.streampack.core.elements.utils.time.Timebase
import io.github.thibaultbee.streampack.core.pipelines.IVideoDispatcherProvider

/**
 * A surface processor that draws several identified inputs into one frame.
 *
 * [io.github.thibaultbee.streampack.core.elements.processing.video.DefaultSurfaceProcessor] treats
 * its inputs as interchangeable: they share one GL texture and one texture matrix, so the last
 * frame wins. Here each input gets its own texture, its own texture matrix and its own place on
 * the canvas, described by a [CompositionLayout].
 */
interface ICompositingSurfaceProcessor : ISurfaceProcessorInternal {
    /**
     * The canvas size. Fixed for the lifetime of the processor.
     */
    val canvasSize: Size

    /**
     * The current layout.
     *
     * Setting it is lock-free, non-blocking and safe from any thread, including at gesture rate
     * while streaming: the renderer picks the new layout up on its next frame. Layout changes
     * never create or destroy GL resources — use [createInputSurface] and [removeInputSurface]
     * for that.
     */
    var layout: CompositionLayout

    /**
     * Creates an input surface bound to [layerId].
     *
     * @param layerId the id of the [io.github.thibaultbee.streampack.core.elements.processing.video.composition.VideoLayer]
     * this input feeds.
     * @param surfaceSize the size to allocate for the input.
     * @param timebase the input's timebase.
     * @param sourceInfoProvider the input's orientation and mirroring, used to make its frames
     * upright on the canvas.
     * @return the surface the source must render into.
     */
    fun createInputSurface(
        layerId: String,
        surfaceSize: Size,
        timebase: Timebase,
        sourceInfoProvider: ISourceInfoProvider
    ): Surface

    /**
     * Removes the input bound to [layerId] and frees its GL resources.
     *
     * Drop the layer from [layout] first, so the renderer stops referencing the texture before it
     * is deleted.
     */
    fun removeInputSurface(layerId: String)

    /**
     * Updates an input's orientation/mirroring, for sources that can change format at runtime.
     */
    fun setSourceInfoProvider(layerId: String, sourceInfoProvider: ISourceInfoProvider)

    /**
     * Sets the rotation the canvas is produced for.
     *
     * The composite presents an already-oriented frame to the pipeline, so device rotation has to
     * be baked in here rather than applied downstream.
     */
    fun setTargetRotation(@RotationValue targetRotation: Int)

    /**
     * Factory for an [ICompositingSurfaceProcessor].
     */
    interface Factory {
        fun create(
            canvasSize: Size,
            dynamicRangeProfile: DynamicRangeProfile,
            dispatcherProvider: IVideoDispatcherProvider
        ): ICompositingSurfaceProcessor
    }
}

/**
 * How the compositor decides when to produce a frame.
 */
sealed interface RenderClock {
    /**
     * Renders once per frame delivered by the primary layer. Output cadence then matches the main
     * camera and the encoder sees exactly what it sees today; a slow secondary layer (a still
     * image, a stalled network source) costs nothing.
     */
    data object DrivenByPrimary : RenderClock

    /**
     * Renders at a fixed rate regardless of input cadence. Use it when no input is a reliable
     * clock, such as a screen capture that only produces frames when the screen changes.
     */
    data class Cfr(val fps: Int) : RenderClock {
        init {
            require(fps > 0) { "fps must be > 0, was $fps" }
        }
    }
}
