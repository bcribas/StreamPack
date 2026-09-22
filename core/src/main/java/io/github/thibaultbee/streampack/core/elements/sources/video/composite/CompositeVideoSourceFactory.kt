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

import android.content.Context
import io.github.thibaultbee.streampack.core.elements.processing.video.CompositeSurfaceProcessorFactory
import io.github.thibaultbee.streampack.core.elements.processing.video.ICompositingSurfaceProcessor
import io.github.thibaultbee.streampack.core.elements.sources.video.IVideoSourceInternal
import io.github.thibaultbee.streampack.core.pipelines.IVideoDispatcherProvider

/**
 * Builds a [CompositeVideoSource] from a list of layers.
 *
 * ```kotlin
 * streamer.setVideoSource(
 *     CompositeVideoSourceFactory(
 *         listOf(
 *             LayerSpec(VideoLayer("main", z = 0), CameraSourceFactory(backCameraId)),
 *             LayerSpec(
 *                 VideoLayer("pip", z = 1, rect = LayerRect.PIP_BOTTOM_RIGHT),
 *                 UvcVideoSource.Factory(cameraHelper)
 *             )
 *         )
 *     )
 * )
 * ```
 */
class CompositeVideoSourceFactory(
    private val specs: List<LayerSpec>,
    private val processorFactory: ICompositingSurfaceProcessor.Factory =
        CompositeSurfaceProcessorFactory()
) : IVideoSourceInternal.Factory {

    init {
        require(specs.isNotEmpty()) { "A composition needs at least one layer" }
        val duplicates = specs.groupBy { it.layer.id }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Duplicate layer ids: $duplicates" }
    }

    override suspend fun create(
        context: Context,
        dispatcherProvider: IVideoDispatcherProvider
    ): IVideoSourceInternal =
        CompositeVideoSource(context, specs, dispatcherProvider, processorFactory)

    /**
     * Compares the *children*, never the geometry.
     *
     * If geometry took part in this comparison, every nudge of a picture-in-picture would make
     * `VideoInput.setSource` tear the whole composition down and rebuild it — releasing and
     * reopening every camera — in the middle of a live stream. Geometry only ever travels through
     * [ICompositeVideoSource.updateLayout].
     */
    override fun isSourceEquals(source: IVideoSourceInternal?): Boolean {
        if (source !is CompositeVideoSource) {
            return false
        }
        // After a device rotation the canvas has the wrong shape and the pipeline does not
        // reallocate the surface it renders into, so the source has to be rebuilt.
        if (!source.isConfiguredForCurrentRotation()) {
            return false
        }
        return source.matchesSpecs(specs)
    }

    override fun toString() =
        "CompositeVideoSourceFactory(layers=${specs.map { it.layer.id }})"
}
