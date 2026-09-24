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

import android.Manifest
import android.content.Context
import android.view.Surface
import androidx.annotation.RequiresPermission
import io.github.thibaultbee.streampack.core.configuration.mediadescriptor.MediaDescriptor
import io.github.thibaultbee.streampack.core.elements.encoders.IEncoder
import io.github.thibaultbee.streampack.core.elements.endpoints.DynamicEndpoint
import io.github.thibaultbee.streampack.core.elements.endpoints.DynamicEndpointFactory
import io.github.thibaultbee.streampack.core.elements.endpoints.IEndpoint
import io.github.thibaultbee.streampack.core.elements.endpoints.IEndpointInternal
import io.github.thibaultbee.streampack.core.elements.processing.video.DefaultSurfaceProcessorFactory
import io.github.thibaultbee.streampack.core.elements.processing.video.ISurfaceProcessorInternal
import io.github.thibaultbee.streampack.core.elements.sources.audio.IAudioSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.CameraSource
import io.github.thibaultbee.streampack.core.elements.utils.RotationValue
import io.github.thibaultbee.streampack.core.elements.utils.extensions.displayRotation
import io.github.thibaultbee.streampack.core.pipelines.DispatcherProvider
import io.github.thibaultbee.streampack.core.pipelines.IDispatcherProvider
import io.github.thibaultbee.streampack.core.pipelines.StreamerPipeline
import io.github.thibaultbee.streampack.core.pipelines.inputs.IAudioInput
import io.github.thibaultbee.streampack.core.pipelines.inputs.IVideoInput
import io.github.thibaultbee.streampack.core.pipelines.outputs.IVideoPipelineOutputInternal
import io.github.thibaultbee.streampack.core.pipelines.outputs.encoding.IEncodingPipelineOutputInternal
import io.github.thibaultbee.streampack.core.pipelines.outputs.encoding.EncodingPipelineOutput
import io.github.thibaultbee.streampack.core.regulator.controllers.IBitrateRegulatorController
import io.github.thibaultbee.streampack.core.streamers.infos.CameraStreamerConfigurationInfo
import io.github.thibaultbee.streampack.core.streamers.infos.IConfigurationInfo
import io.github.thibaultbee.streampack.core.streamers.infos.StreamerConfigurationInfo
import io.github.thibaultbee.streampack.core.logger.Logger
import io.github.thibaultbee.streampack.core.pipelines.outputs.encoding.IConfigurableAudioVideoEncodingPipelineOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * A [ISingleStreamer] implementation for audio and video.
 *
 * @param context the application context
 * @param withAudio `true` to capture audio. It can't be changed after instantiation.
 * @param withVideo `true` to capture video. It can't be changed after instantiation.
 * @param audioInputMode the audio output mode. By default, it is [StreamerPipeline.AudioInputMode.CALLBACK]. Use [StreamerPipeline.AudioInputMode.PUSH] only to get processor (incl. vumeter) running outside a stream
 * @param endpointFactory the [IEndpointInternal.Factory] implementation. By default, it is a [DynamicEndpointFactory].
 * @param defaultRotation the default rotation in [Surface] rotation ([Surface.ROTATION_0], ...). By default, it is the current device orientation.
 * @param surfaceProcessorFactory the [ISurfaceProcessorInternal.Factory] implementation. By default, it is a [DefaultSurfaceProcessorFactory].
 */
internal class SingleStreamerImpl(
    private val context: Context,
    withAudio: Boolean,
    withVideo: Boolean,
    audioInputMode: StreamerPipeline.AudioInputMode = StreamerPipeline.AudioInputMode.CALLBACK,
    endpointFactory: IEndpointInternal.Factory = DynamicEndpointFactory(),
    @RotationValue defaultRotation: Int = context.displayRotation,
    surfaceProcessorFactory: ISurfaceProcessorInternal.Factory = DefaultSurfaceProcessorFactory(),
    dispatcherProvider: IDispatcherProvider = DispatcherProvider(),
) : ISingleStreamer, IAudioSingleStreamer, IVideoSingleStreamer, ISecondaryOutputStreamer {
    private val coroutineScope: CoroutineScope = CoroutineScope(dispatcherProvider.default)

    private val pipeline = StreamerPipeline(
        context,
        withAudio,
        withVideo,
        audioInputMode = audioInputMode,
        surfaceProcessorFactory,
        dispatcherProvider
    )
    private val pipelineOutput: IEncodingPipelineOutputInternal =
        EncodingPipelineOutput(
            context,
            withAudio,
            withVideo,
            endpointFactory,
            defaultRotation,
            dispatcherProvider
        )

    private val initJob: Job = coroutineScope.launch {
        pipeline.addOutput(pipelineOutput)
    }

    override val throwableFlow: StateFlow<Throwable?> =
        merge(pipeline.throwableFlow, pipelineOutput.throwableFlow).stateIn(
            coroutineScope,
            SharingStarted.Eagerly,
            null
        )

    override val isOpenFlow: StateFlow<Boolean>
        get() = pipelineOutput.isOpenFlow

    /**
     * The secondary outputs added through [addSecondaryOutput]. Guarded by [secondaryMutex].
     */
    private val secondaryOutputs = mutableListOf<IConfigurableAudioVideoEncodingPipelineOutput>()
    private val secondaryMutex = Mutex()

    @Volatile
    private var hasSecondaryOutputs = false

    private val _isStreamingFlow = MutableStateFlow(false)

    /**
     * Whether the live, the main output, is streaming.
     *
     * With no secondary output it is exactly the pipeline's own state, as it always was. With
     * one, the sources keep running for it while the main output is stopped, so it also requires
     * the main output to be streaming.
     */
    override val isStreamingFlow: StateFlow<Boolean> = _isStreamingFlow.asStateFlow()

    override val isPipelineStreamingFlow: StateFlow<Boolean> = pipeline.isStreamingFlow

    private fun refreshIsStreaming() {
        _isStreamingFlow.value = if (hasSecondaryOutputs) {
            pipeline.isStreamingFlow.value && pipelineOutput.isStreamingFlow.value
        } else {
            pipeline.isStreamingFlow.value
        }
    }

    init {
        // Unconfined, so the state follows its sources on their own thread, as it did when it
        // was the pipeline's flow itself; startStream and stopStream also refresh it on return.
        coroutineScope.launch(Dispatchers.Unconfined) {
            combine(pipeline.isStreamingFlow, pipelineOutput.isStreamingFlow) { _, _ -> }
                .collect { refreshIsStreaming() }
        }
    }

    // AUDIO
    /**
     * The audio input.
     * It allows advanced audio source settings.
     */
    override val audioInput: IAudioInput
        get() = pipeline.audioInput

    override val audioEncoder: IEncoder?
        get() = pipelineOutput.audioEncoder

    override suspend fun setAudioSource(audioSourceFactory: IAudioSourceInternal.Factory) {
        initJob.join()
        pipeline.setAudioSource(audioSourceFactory)
    }

    // VIDEO
    /**
     * The video input.
     * It allows advanced video source settings.
     */
    override val videoInput: IVideoInput
        get() = pipeline.videoInput

    override val videoEncoder: IEncoder?
        get() = pipelineOutput.videoEncoder

    // ENDPOINT
    override val endpoint: IEndpoint
        get() = pipelineOutput.endpoint

    /**
     * Sets the target rotation.
     *
     * @param rotation the target rotation in [Surface] rotation ([Surface.ROTATION_0], ...)
     */
    override suspend fun setTargetRotation(@RotationValue rotation: Int) {
        initJob.join()
        pipeline.setTargetRotation(rotation)
    }

    /**
     * Gets configuration information.
     *
     * Could throw an exception if the endpoint needs to infer the configuration from the
     * [MediaDescriptor].
     * In this case, prefer using [getInfo] with the [MediaDescriptor] used in [open].
     */
    override val info: IConfigurationInfo
        get() = if (videoInput.sourceFlow.value is CameraSource) {
            CameraStreamerConfigurationInfo(endpoint.info)
        } else {
            StreamerConfigurationInfo(endpoint.info)
        }

    /**
     * Gets configuration information from [MediaDescriptor].
     *
     * If the endpoint is not [DynamicEndpoint], [descriptor] is unused as the endpoint type is
     * already known.
     *
     * @param descriptor the media descriptor
     */
    override fun getInfo(descriptor: MediaDescriptor): IConfigurationInfo {
        val endpointInfo = try {
            endpoint.info
        } catch (_: Throwable) {
            endpoint.getInfo(descriptor)
        }
        return if (videoInput.sourceFlow.value is CameraSource) {
            CameraStreamerConfigurationInfo(endpointInfo)
        } else {
            StreamerConfigurationInfo(endpointInfo)
        }
    }

    // CONFIGURATION
    /**
     * The audio configuration flow.
     */
    override val audioConfigFlow: StateFlow<AudioConfig?> = pipelineOutput.audioCodecConfigFlow

    /**
     * Configures audio settings.
     * It is the first method to call after a [SingleStreamerImpl] instantiation.
     * It must be call when both stream and audio capture are not running.
     *
     * Use [IConfigurationInfo] to get value limits.
     *
     * @param audioConfig Audio configuration to set
     *
     * @throws [Throwable] if configuration can not be applied.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override suspend fun setAudioConfig(audioConfig: AudioConfig) {
        initJob.join()
        pipelineOutput.setAudioCodecConfig(audioConfig)
    }

    /**
     * The video configuration flow.
     */
    override val videoConfigFlow: StateFlow<VideoConfig?> = pipelineOutput.videoCodecConfigFlow

    /**
     * Configures video settings.
     * It is the first method to call after a [SingleStreamerImpl] instantiation.
     * It must be call when both stream and video capture are not running.
     *
     * Use [IConfigurationInfo] to get value limits.
     *
     * If video encoder does not support [VideoConfig.level] or [VideoConfig.profile], it fallbacks
     * to video encoder default level and default profile.
     *
     * @param videoConfig Video configuration to set
     *
     * @throws [Throwable] if configuration can not be applied.
     */
    override suspend fun setVideoConfig(videoConfig: VideoConfig) {
        initJob.join()
        pipelineOutput.setVideoCodecConfig(videoConfig)
    }

    /**
     * Opens the streamer endpoint.
     *
     * @param descriptor Media descriptor to open
     */
    override suspend fun open(descriptor: MediaDescriptor) {
        initJob.join()
        pipelineOutput.open(descriptor)
    }

    /**
     * Closes the streamer endpoint.
     */
    override suspend fun close() {
        initJob.join()
        pipelineOutput.close()
    }

    /**
     * Starts audio/video stream.
     * Stream depends of the endpoint: Audio/video could be write to a file or send to a remote
     * device.
     * To avoid creating an unresponsive UI, do not call on main thread.
     *
     * @see [stopStream]
     */
    override suspend fun startStream() {
        initJob.join()
        try {
            pipelineOutput.startStream()
        } finally {
            refreshIsStreaming()
        }
    }

    /**
     * Stops audio/video stream.
     *
     * Internally, it resets audio and video recorders and encoders to get them ready for another
     * [startStream] session. It explains why preview could be restarted.
     *
     * While a secondary output is streaming, only the main output is stopped: the sources keep
     * running for the secondary one.
     *
     * @see [startStream]
     */
    override suspend fun stopStream() {
        initJob.join()
        try {
            val secondaryStreaming = secondaryMutex.withLock {
                secondaryOutputs.any { it.isStreamingFlow.value }
            }
            if (secondaryStreaming) {
                pipelineOutput.stopStream()
            } else {
                pipeline.stopStream()
            }
        } finally {
            refreshIsStreaming()
        }
    }

    override suspend fun addSecondaryOutput(
        endpointFactory: IEndpointInternal.Factory,
        withAudio: Boolean,
        withVideo: Boolean,
        @RotationValue targetRotation: Int?
    ): IConfigurableAudioVideoEncodingPipelineOutput {
        initJob.join()
        return secondaryMutex.withLock {
            val output = pipeline.createEncodingOutput(
                withAudio = withAudio,
                withVideo = withVideo,
                endpointFactory = endpointFactory,
                targetRotation = targetRotation
                    ?: (pipelineOutput as IVideoPipelineOutputInternal).targetRotation
            )
            secondaryOutputs += output
            hasSecondaryOutputs = true
            output
        }.also { refreshIsStreaming() }
    }

    override suspend fun removeSecondaryOutput(output: IConfigurableAudioVideoEncodingPipelineOutput) {
        initJob.join()
        require(output !== pipelineOutput) { "The main output is not a secondary output" }
        secondaryMutex.withLock {
            if (!secondaryOutputs.remove(output)) {
                Logger.w(TAG, "removeSecondaryOutput: $output is not a secondary output")
                return
            }
            runCatching { output.stopStream() }
                .onFailure { Logger.w(TAG, "removeSecondaryOutput: stop failed: ${it.message}") }
            runCatching { output.close() }
                .onFailure { Logger.w(TAG, "removeSecondaryOutput: close failed: ${it.message}") }
            runCatching { pipeline.removeOutput(output) }
                .onFailure { Logger.w(TAG, "removeSecondaryOutput: detach failed: ${it.message}") }
            runCatching { output.release() }
                .onFailure { Logger.w(TAG, "removeSecondaryOutput: release failed: ${it.message}") }
            hasSecondaryOutputs = secondaryOutputs.isNotEmpty()
            // Back to the main output's resolution, when the sources are idle enough to allow it.
            pipeline.refreshSourceConfigs()
        }
        refreshIsStreaming()
    }

    /**
     * Releases the streamer.
     */
    override suspend fun release() {
        pipeline.release()
        coroutineScope.cancel()
    }

    /**
     * Adds a bitrate regulator controller.
     */
    override var bitrateRegulatorControllerFactory: IBitrateRegulatorController.Factory?
        get() = pipelineOutput.bitrateRegulatorControllerFactory
        set(value) {
            pipelineOutput.bitrateRegulatorControllerFactory = value
        }

    companion object Companion {
        const val TAG = "SingleStreamer"
    }
}