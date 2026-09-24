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
package io.github.thibaultbee.streampack.core.streamers.single

import io.github.thibaultbee.streampack.core.elements.endpoints.IEndpointInternal
import io.github.thibaultbee.streampack.core.elements.utils.RotationValue
import io.github.thibaultbee.streampack.core.pipelines.outputs.encoding.IConfigurableAudioVideoEncodingPipelineOutput
import kotlinx.coroutines.flow.StateFlow

/**
 * A streamer that can feed extra outputs from its own sources, each with its own encoders and
 * endpoint: for example a local recording next to the live.
 *
 * A secondary output shares the sources, so it must use the same frame rate and the same audio
 * format (sample rate, channels, byte format) as the main one; resolution, bitrate and codec may
 * differ. It is started and stopped on its own, and it keeps the sources running when the main
 * output stops: while one is streaming, [ISingleStreamer.stopStream] stops only the main output,
 * and [ISingleStreamer.isStreamingFlow] follows the main output alone.
 */
interface ISecondaryOutputStreamer {
    /**
     * Whether the sources are streaming, for any output. Unlike
     * [ISingleStreamer.isStreamingFlow], it stays true while only a secondary output streams, so
     * it is what tells whether the source configuration can be changed.
     */
    val isPipelineStreamingFlow: StateFlow<Boolean>

    /**
     * Adds an output fed by the same sources. Configure it, open it and start it through the
     * returned object.
     *
     * @param targetRotation the rotation of the output, or null to use the main output's
     */
    suspend fun addSecondaryOutput(
        endpointFactory: IEndpointInternal.Factory,
        withAudio: Boolean = true,
        withVideo: Boolean = true,
        @RotationValue targetRotation: Int? = null
    ): IConfigurableAudioVideoEncodingPipelineOutput

    /**
     * Stops, closes, detaches and releases an output added by [addSecondaryOutput]. Every step
     * is attempted even if an earlier one fails.
     */
    suspend fun removeSecondaryOutput(output: IConfigurableAudioVideoEncodingPipelineOutput)
}
