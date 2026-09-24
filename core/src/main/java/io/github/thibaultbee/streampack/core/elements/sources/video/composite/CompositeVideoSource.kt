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
import android.util.Size
import android.view.Surface
import io.github.thibaultbee.streampack.core.elements.encoders.VideoCodecConfig.Companion.DEFAULT_RESOLUTION
import io.github.thibaultbee.streampack.core.elements.processing.video.CompositeSurfaceProcessorFactory
import io.github.thibaultbee.streampack.core.elements.processing.video.ICompositingSurfaceProcessor
import io.github.thibaultbee.streampack.core.elements.processing.video.composition.CompositionLayout
import io.github.thibaultbee.streampack.core.elements.processing.video.composition.VideoLayer
import io.github.thibaultbee.streampack.core.elements.processing.video.outputs.SurfaceOutput
import io.github.thibaultbee.streampack.core.elements.processing.video.source.DefaultSourceInfoProvider
import io.github.thibaultbee.streampack.core.elements.processing.video.source.ISourceInfoProvider
import io.github.thibaultbee.streampack.core.elements.sources.video.AbstractPreviewableSource
import io.github.thibaultbee.streampack.core.elements.sources.video.ISurfaceSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.video.IVideoSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.video.VideoSourceConfig
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.ICameraHoldingSource
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.ICameraSource
import io.github.thibaultbee.streampack.core.elements.utils.RotationValue
import io.github.thibaultbee.streampack.core.elements.utils.extensions.displayRotation
import io.github.thibaultbee.streampack.core.elements.utils.extensions.isRotationPortrait
import io.github.thibaultbee.streampack.core.elements.utils.extensions.landscapize
import io.github.thibaultbee.streampack.core.elements.utils.extensions.portraitize
import io.github.thibaultbee.streampack.core.elements.utils.time.Timebase
import io.github.thibaultbee.streampack.core.logger.Logger
import io.github.thibaultbee.streampack.core.pipelines.IVideoDispatcherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToInt

/**
 * One layer of a composition: where it goes, and what feeds it.
 *
 * @param layer the geometry. Its [VideoLayer.id] is the key used everywhere else.
 * @param childFactory builds the source behind this layer.
 * @param captureResolution what to ask the child to capture at. Defaults to the canvas, but a
 * small picture-in-picture should ask for much less — capturing 1080p to draw it at a quarter of
 * the frame is a pure waste of power.
 */
data class LayerSpec(
    val layer: VideoLayer,
    val childFactory: IVideoSourceInternal.Factory,
    val captureResolution: Size? = null
)

/**
 * A video source that owns several child sources and composites them into one frame.
 *
 * It follows the pattern already used by
 * [io.github.thibaultbee.streampack.core.elements.sources.video.mediaprojection.MediaProjectionVideoSource]:
 * the surface handed in by [setOutput] becomes an *output* of an inner processor, whose *inputs*
 * are given to the children. The difference is that the inner processor here composites instead
 * of just forwarding.
 *
 * Implementing preview means the preview surface is another output of that same processor, so
 * what the operator sees is exactly what is encoded — including the composition.
 */
class CompositeVideoSource(
    private val context: Context,
    private val specs: List<LayerSpec>,
    private val dispatcherProvider: IVideoDispatcherProvider,
    private val processorFactory: ICompositingSurfaceProcessor.Factory =
        CompositeSurfaceProcessorFactory()
) : AbstractPreviewableSource(), ICompositeVideoSource, ICameraHoldingSource {

    override val timebase = Timebase.UPTIME

    override var canvasSize: Size = DEFAULT_RESOLUTION
        private set

    /**
     * The provider handed to the pipeline. It is the one that learns the target rotation.
     */
    private val canvasInfoProvider = CanvasInfoProvider(capturesTargetRotation = true)

    /**
     * The provider used for the composite's own outputs, which are always built at rotation 0.
     * It must not capture, or it would immediately overwrite the pipeline's rotation with 0.
     */
    private val internalInfoProvider = CanvasInfoProvider(capturesTargetRotation = false)

    override val infoProviderFlow =
        MutableStateFlow(canvasInfoProvider as ISourceInfoProvider).asStateFlow()

    private val _isStreamingFlow = MutableStateFlow(false)
    override val isStreamingFlow = _isStreamingFlow.asStateFlow()

    private val _isPreviewingFlow = MutableStateFlow(false)
    override val isPreviewingFlow = _isPreviewingFlow.asStateFlow()

    private val _layoutFlow = MutableStateFlow(CompositionLayout(canvasSize))
    override val layoutFlow = _layoutFlow.asStateFlow()

    private val _layerFailureFlow = MutableSharedFlow<LayerFailure>(extraBufferCapacity = 8)
    override val layerFailureFlow = _layerFailureFlow.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val childMutex = Mutex()

    /** Guarded by [childMutex]. */
    private val children = LinkedHashMap<String, Child>()

    /** Guarded by [childMutex]. */
    private var processor: ICompositingSurfaceProcessor? = null

    private var videoSourceConfig: VideoSourceConfig? = null

    /**
     * Whether streaming was actually asked for.
     *
     * Distinct from "a child is producing frames": children keep running to feed the preview
     * after the stream stops, so child activity alone cannot mean the composite is streaming —
     * nor can a child stopping mean something went wrong.
     */
    private var isStreamRequested = false

    private var outputSurface: Surface? = null
    private var outputSurfaceOutput: SurfaceOutput? = null

    private var previewSurface: Surface? = null
    private var previewSurfaceOutput: SurfaceOutput? = null

    /**
     * Seeded from the display because [configure] needs the canvas orientation before the
     * pipeline ever reports a target rotation.
     */
    /**
     * Outputs are immutable, so a change only takes effect when the preview is re-attached.
     */
    override var previewMaxFps: Int? = null
        set(value) {
            if (field == value) {
                return
            }
            field = value
            Logger.i(TAG, "Preview frame rate cap is now ${value ?: "off"}")
            scope.launch {
                childMutex.withLock {
                    previewSurface?.let { attachPreviewUnsafe(it) }
                }
            }
        }

    @RotationValue
    private var targetRotation: Int = context.displayRotation

    override val primaryChildSource: IVideoSourceInternal?
        get() = _layoutFlow.value.primaryLayer?.id?.let { children[it]?.source }

    override fun childSource(layerId: String): IVideoSourceInternal? = children[layerId]?.source

    // region layout

    override fun updateLayout(layout: CompositionLayout) {
        require(layout.canvasSize == canvasSize) {
            "Canvas size is fixed: expected $canvasSize, got ${layout.canvasSize}"
        }
        _layoutFlow.value = layout

        // A processor built for a different canvas is already stale and about to be replaced
        // along with the whole source; pushing to it would only throw.
        val currentProcessor = processor
        if (currentProcessor != null && currentProcessor.canvasSize == layout.canvasSize) {
            currentProcessor.layout = layout
        }
    }

    override fun updateLayer(layerId: String, transform: (VideoLayer) -> VideoLayer) {
        updateLayout(_layoutFlow.value.mapLayer(layerId, transform))
    }

    override fun setPrimaryLayer(layerId: String) {
        updateLayout(_layoutFlow.value.copy(primaryLayerId = layerId))
    }

    override fun setTargetRotation(@RotationValue targetRotation: Int) {
        this.targetRotation = targetRotation
        processor?.setTargetRotation(targetRotation)
    }

    // endregion

    // region IVideoSourceInternal

    override suspend fun configure(config: VideoSourceConfig) {
        require(!config.dynamicRangeProfile.isHdr) {
            "Composite video source does not support HDR yet"
        }

        videoSourceConfig = config
        canvasSize = orientedCanvasSize(config.resolution, targetRotation)

        childMutex.withLock {
            if (children.isEmpty()) {
                createChildrenUnsafe(config)
            } else {
                children.values.forEach { child ->
                    runCatching { child.source.configure(childConfig(config, child)) }
                        .onFailure {
                            Logger.w(TAG, "Failed to reconfigure layer ${child.layerId}", it)
                        }
                }
            }
        }

        // The layout carries the canvas size, so it has to be rebuilt when the canvas changes.
        val layers = specs.map { it.layer }
        _layoutFlow.value = CompositionLayout(
            canvasSize = canvasSize,
            layers = layers,
            primaryLayerId = _layoutFlow.value.primaryLayerId
                ?: layers.minByOrNull { it.z }?.id
        )
        processor?.layout = _layoutFlow.value
    }

    /**
     * Requires [childMutex].
     */
    private suspend fun createChildrenUnsafe(config: VideoSourceConfig) {
        specs.forEach { spec ->
            val layerId = spec.layer.id
            try {
                val child = createChildUnsafe(spec, config)
                children[layerId] = child
                observeChild(child)
            } catch (t: Throwable) {
                // A layer that cannot even be created simply never appears. The rest of the
                // composition must still stream.
                Logger.e(TAG, "Failed to create layer $layerId", t)
                _layerFailureFlow.tryEmit(
                    LayerFailure(layerId, t.message ?: "Failed to create source")
                )
            }
        }
    }

    private fun childConfig(config: VideoSourceConfig, child: Child) =
        config.copy(resolution = child.captureResolution)

    private fun observeChild(child: Child) {
        child.watchJob?.cancel()
        child.watchJob = scope.launch {
            child.source.isStreamingFlow.drop(1).collect { isStreaming ->
                // Only unexpected stops are failures. Stopping the stream stops every child by
                // design, and reporting that as a failure made a normal Stop replace a layer
                // with the placeholder.
                if (!isStreaming && isStreamRequested) {
                    Logger.w(TAG, "Layer ${child.layerId} stopped streaming")
                    _layerFailureFlow.tryEmit(
                        LayerFailure(child.layerId, "Source stopped delivering frames")
                    )
                }
                refreshIsStreaming()
            }
        }
    }

    /**
     * The composite streams while *any* child streams.
     *
     * This must not be `all`: [io.github.thibaultbee.streampack.core.pipelines.inputs.VideoInput]
     * stops the whole video input when the source's streaming flow goes false, so reporting false
     * on a single child hiccup would drop the live stream.
     */
    private fun refreshIsStreaming() {
        _isStreamingFlow.value =
            isStreamRequested && children.values.any { it.source.isStreamingFlow.value }
    }

    override suspend fun startStream() {
        childMutex.withLock {
            ensureProcessorUnsafe()

            isStreamRequested = true

            // Re-attach the encoder output that stopStream detached.
            if (outputSurfaceOutput == null) {
                outputSurface?.let { attachOutputUnsafe(it) }
            }

            coroutineScope {
                children.values.filterNot { it.source.isStreamingFlow.value }.map { child ->
                    async {
                        runCatching { child.source.startStream() }
                            .onFailure {
                                Logger.e(TAG, "Failed to start layer ${child.layerId}", it)
                                _layerFailureFlow.tryEmit(
                                    LayerFailure(
                                        child.layerId,
                                        it.message ?: "Failed to start source"
                                    )
                                )
                            }
                    }
                }.awaitAll()
            }

            refreshIsStreaming()
        }
    }

    override suspend fun stopStream() {
        childMutex.withLock {
            isStreamRequested = false

            /**
             * Detach the encoder-facing output before anything else.
             *
             * MediaCodec.stop() invalidates every output buffer immediately, including ones
             * already handed to the muxer, so a frame still in flight when the pipeline tears the
             * encoder down reads freed memory and takes the process with it. Relying on a flag
             * that the GL thread reads leaves that window open; removing the output closes it,
             * because the compositor then has nowhere to send a frame at all.
             */
            outputSurfaceOutput?.let { output ->
                processor?.removeOutputSurface(output)
            }
            outputSurfaceOutput = null

            _isStreamingFlow.value = false

            if (_isPreviewingFlow.value) {
                /**
                 * A plain camera keeps running when the stream stops, because its preview is a
                 * separate capture target. The composite's preview is fed by its children through
                 * the compositor, so stopping them here would freeze the picture on the last
                 * frame instead of ending the stream.
                 */
                Logger.i(TAG, "Stream stopped; children keep running to feed the preview")
                return@withLock
            }

            stopChildrenUnsafe()
        }
    }

    /**
     * Requires [childMutex]. Stops every child and rearms its timebase.
     *
     * The timebase reset mirrors VideoInput.stopStreamUnsafe: it is what stops presentation
     * timestamps jumping across the gap, for instance after the device has been locked.
     */
    private suspend fun stopChildrenUnsafe() {
        children.values.forEach { child ->
            runCatching { child.source.stopStream() }
                .onFailure { Logger.w(TAG, "Failed to stop layer ${child.layerId}", it) }
        }

        val currentProcessor = processor ?: return
        children.values.forEach { child ->
            child.inputSurface?.let { surface ->
                runCatching {
                    currentProcessor.setTimebase(surface, child.source.timebaseOrUptime())
                }
            }
        }
    }

    override suspend fun release() {
        childMutex.withLock {
            isStreamRequested = false
            children.values.forEach { child ->
                child.watchJob?.cancel()
                runCatching { child.source.release() }
                    .onFailure { Logger.w(TAG, "Failed to release layer ${child.layerId}", it) }
            }
            children.clear()

            processor?.let { currentProcessor ->
                runCatching { currentProcessor.removeAllOutputSurfaces() }
                runCatching { currentProcessor.release() }
            }
            processor = null
            outputSurfaceOutput = null
            previewSurfaceOutput = null
            outputSurface = null
            previewSurface = null
        }
        scope.cancel()
    }

    // endregion

    // region ISurfaceSourceInternal

    override suspend fun getOutput(): Surface? = outputSurface

    override suspend fun setOutput(surface: Surface) {
        childMutex.withLock {
            outputSurface = surface
            ensureProcessorUnsafe()
            attachOutputUnsafe(surface)
        }
    }

    override suspend fun resetOutputImpl() {
        childMutex.withLock {
            val currentProcessor = processor
            outputSurfaceOutput?.let { output ->
                currentProcessor?.removeOutputSurface(output)
            }
            outputSurfaceOutput = null
            outputSurface = null
        }
    }

    /**
     * Requires [childMutex].
     */
    private fun attachOutputUnsafe(surface: Surface) {
        val currentProcessor = processor ?: return
        outputSurfaceOutput?.let { currentProcessor.removeOutputSurface(it) }

        val output = SurfaceOutput(
            targetSurface = surface,
            targetResolution = canvasSize,
            targetRotation = 0,
            isStreaming = { _isStreamingFlow.value },
            sourceResolution = canvasSize,
            needMirroring = false,
            sourceInfoProvider = internalInfoProvider
        )
        currentProcessor.addOutputSurface(output)
        outputSurfaceOutput = output
    }

    // endregion

    // region IPreviewableSource

    override suspend fun hasPreview(): Boolean = previewSurface != null

    override suspend fun setPreview(surface: Surface) {
        childMutex.withLock {
            previewSurface = surface
            ensureProcessorUnsafe()
            attachPreviewUnsafe(surface)
        }
    }

    override suspend fun startPreview() {
        childMutex.withLock {
            ensureProcessorUnsafe()
            previewSurface?.let { attachPreviewUnsafe(it) }
            _isPreviewingFlow.value = true

            // Children must run for the preview to show anything, even when not streaming.
            children.values.forEach { child ->
                if (!child.source.isStreamingFlow.value) {
                    runCatching { child.source.startStream() }
                        .onFailure {
                            Logger.e(TAG, "Failed to start layer ${child.layerId} for preview", it)
                        }
                }
            }
            refreshIsStreaming()
        }
    }

    override suspend fun stopPreview() {
        childMutex.withLock {
            _isPreviewingFlow.value = false

            // Children were possibly only alive for the preview; stop them if nothing wants them.
            if (!isStreamRequested) {
                stopChildrenUnsafe()
                refreshIsStreaming()
            }
            previewSurfaceOutput?.let { processor?.removeOutputSurface(it) }
            previewSurfaceOutput = null
        }
    }

    override suspend fun resetPreviewImpl() {
        childMutex.withLock {
            _isPreviewingFlow.value = false
            previewSurfaceOutput?.let { processor?.removeOutputSurface(it) }
            previewSurfaceOutput = null
            previewSurface = null
        }
    }

    /**
     * The canvas, shrunk to fit [targetSize] but never enlarged, keeping the canvas shape exactly.
     *
     * Same shape matters: [attachPreviewUnsafe] uses this as the output's target resolution, and
     * because the canvas info provider reports the canvas size with zero relative rotation,
     * `calculateViewportRect` then lands on its equal-aspect branch and gives the full surface —
     * no letterbox, and layer geometry untouched.
     *
     * The answer is remembered because [io.github.thibaultbee.streampack.ui.views.PreviewView]
     * asks for it immediately before requesting a surface of exactly this size and handing it to
     * [setPreview].
     */
    override fun <T> getPreviewSize(targetSize: Size, targetClass: Class<T>): Size {
        val canvas = canvasSize
        if (targetSize.width <= 0 || targetSize.height <= 0) {
            return canvas.also { previewResolution = it }
        }

        val scale = minOf(
            targetSize.width.toFloat() / canvas.width,
            targetSize.height.toFloat() / canvas.height
        )
        if (scale >= 1f) {
            return canvas.also { previewResolution = it }
        }

        // Even dimensions: an odd one leaves a one-pixel seam at a layer edge.
        fun even(value: Float) = value.roundToInt().coerceAtLeast(2) and 1.inv()
        val scaled = Size(even(canvas.width * scale), even(canvas.height * scale))
        Logger.i(TAG, "Preview canvas $canvas scaled to $scaled for target $targetSize")
        return scaled.also { previewResolution = it }
    }

    /**
     * What [getPreviewSize] last answered. Written from the caller thread, read when the preview
     * output is built.
     */
    @Volatile
    private var previewResolution: Size = DEFAULT_RESOLUTION

    /**
     * Requires [childMutex].
     */
    private fun attachPreviewUnsafe(surface: Surface) {
        val currentProcessor = processor ?: return
        previewSurfaceOutput?.let { currentProcessor.removeOutputSurface(it) }

        val output = SurfaceOutput(
            targetSurface = surface,
            // The preview surface may be smaller than the canvas; the content is still
            // canvas-sized, which is why sourceResolution below stays the canvas.
            targetResolution = previewResolution,
            targetRotation = 0,
            isStreaming = { _isPreviewingFlow.value },
            sourceResolution = canvasSize,
            needMirroring = false,
            sourceInfoProvider = internalInfoProvider,
            maxFps = previewMaxFps
        )
        currentProcessor.addOutputSurface(output)
        previewSurfaceOutput = output
    }

    // endregion

    /**
     * Requires [childMutex]. Builds the inner processor and wires every child into it, once.
     */
    private suspend fun ensureProcessorUnsafe() {
        if (processor != null) {
            return
        }

        val config = videoSourceConfig
            ?: throw IllegalStateException("Composite video source is not configured")

        val newProcessor = processorFactory.create(
            canvasSize,
            config.dynamicRangeProfile,
            dispatcherProvider
        )
        newProcessor.setTargetRotation(targetRotation)
        newProcessor.layout = _layoutFlow.value
        processor = newProcessor

        children.values.forEach { child -> attachChildUnsafe(child, newProcessor) }
    }

    /**
     * Requires [childMutex]. Gives [child] its own input on the compositor.
     */
    private suspend fun attachChildUnsafe(child: Child, processor: ICompositingSurfaceProcessor) {
        try {
            val childProvider = child.source.infoProviderFlow.value
            val inputSurface = processor.createInputSurface(
                layerId = child.layerId,
                surfaceSize = childProvider.getSurfaceSize(child.captureResolution),
                timebase = child.source.timebaseOrUptime(),
                sourceInfoProvider = childProvider
            )
            child.inputSurface = inputSurface
            child.inputSurfaceSize = childProvider.getSurfaceSize(child.captureResolution)
            (child.source as ISurfaceSourceInternal).setOutput(inputSurface)
            observeChildInfoProvider(child, processor)
        } catch (t: Throwable) {
            Logger.e(TAG, "Failed to attach layer ${child.layerId}", t)
            _layerFailureFlow.tryEmit(
                LayerFailure(child.layerId, t.message ?: "Failed to attach source")
            )
        }
    }

    /**
     * Requires [childMutex]. Stops [child], frees its input and releases it.
     *
     * The caller must already have taken the layer out of the layout, so the renderer has stopped
     * referencing the texture before it is deleted.
     */
    private suspend fun detachChildUnsafe(child: Child, release: Boolean) {
        child.watchJob?.cancel()
        child.infoJob?.cancel()

        runCatching { child.source.stopStream() }
            .onFailure { Logger.w(TAG, "Failed to stop layer ${child.layerId}", it) }
        runCatching { (child.source as ISurfaceSourceInternal).resetOutput() }
            .onFailure { Logger.w(TAG, "Failed to reset layer ${child.layerId} output", it) }

        processor?.removeInputSurface(child.layerId)
        child.inputSurface = null
        child.inputSurfaceSize = null

        if (release) {
            runCatching { child.source.release() }
                .onFailure { Logger.w(TAG, "Failed to release layer ${child.layerId}", it) }
        }
    }

    /**
     * Requires [childMutex].
     */
    private suspend fun createChildUnsafe(spec: LayerSpec, config: VideoSourceConfig): Child {
        val source = spec.childFactory.create(context, dispatcherProvider)
        require(source is ISurfaceSourceInternal) {
            "Layer ${spec.layer.id} source ${source::class.java.simpleName} does not provide a surface"
        }

        val child = Child(
            layerId = spec.layer.id,
            source = source,
            captureResolution = spec.captureResolution ?: config.resolution
        )
        source.configure(childConfig(config, child))
        return child
    }

    // region camera exclusivity

    /**
     * The camera2 devices held by the layers, so [VideoInput] can apply the one-client-per-camera
     * rule to a composition the same way it does to a plain camera source.
     */
    override val heldCameraIds: Set<String>
        get() = children.values.mapNotNull { (it.source as? ICameraSource)?.cameraId }.toSet()

    override suspend fun evictCameras() {
        val cameraLayerIds = children.values
            .filter { it.source is ICameraSource }
            .map { it.layerId }

        if (cameraLayerIds.isEmpty()) {
            return
        }

        // Out of the layout before the teardown, as in removeLayer.
        var layout = _layoutFlow.value
        cameraLayerIds.forEach { layout = layout.withoutLayer(it) }
        updateLayout(layout)

        childMutex.withLock {
            cameraLayerIds.forEach { layerId ->
                val child = children.remove(layerId) ?: return@forEach
                Logger.i(TAG, "Evicting camera layer $layerId")
                detachChildUnsafe(child, release = true)
            }
            refreshIsStreaming()
        }
    }

    // endregion

    // region live structural changes

    override suspend fun addLayer(spec: LayerSpec) {
        val config = videoSourceConfig
            ?: throw IllegalStateException("Composite video source is not configured")

        childMutex.withLock {
            require(!children.containsKey(spec.layer.id)) {
                "Layer ${spec.layer.id} already exists"
            }

            val child = createChildUnsafe(spec, config)
            children[spec.layer.id] = child
            observeChild(child)

            processor?.let { attachChildUnsafe(child, it) }

            // A layer added mid-stream has to be started explicitly; the others are already running.
            if (_isStreamingFlow.value || _isPreviewingFlow.value) {
                runCatching { child.source.startStream() }
                    .onFailure {
                        Logger.e(TAG, "Failed to start layer ${spec.layer.id}", it)
                        _layerFailureFlow.tryEmit(
                            LayerFailure(spec.layer.id, it.message ?: "Failed to start source")
                        )
                    }
            }
            refreshIsStreaming()
        }

        // Added last: until the input delivers its first frame the layer is skipped anyway.
        updateLayout(_layoutFlow.value.withLayer(spec.layer))
    }

    override suspend fun removeLayer(layerId: String) {
        /**
         * Out of the layout first, torn down second. The renderer reads the layout once per
         * frame, so dropping the layer before posting the GL teardown guarantees no frame is
         * still sampling the texture when it is deleted.
         */
        updateLayout(_layoutFlow.value.withoutLayer(layerId))

        childMutex.withLock {
            val child = children.remove(layerId)
            if (child == null) {
                Logger.w(TAG, "No layer $layerId to remove")
                return@withLock
            }
            detachChildUnsafe(child, release = true)
            refreshIsStreaming()
        }
    }

    override suspend fun replaceLayerSource(layerId: String, spec: LayerSpec) {
        require(spec.layer.id == layerId) {
            "Spec layer id ${spec.layer.id} does not match $layerId"
        }
        val config = videoSourceConfig
            ?: throw IllegalStateException("Composite video source is not configured")

        childMutex.withLock {
            val previous = children.remove(layerId)
                ?: throw IllegalArgumentException("No layer $layerId to replace")

            /**
             * The layer stays in the layout throughout. While the new input has not produced a
             * frame the compositor simply skips it, so the rest of the composition keeps
             * streaming without a gap — this is what turns a dead source into one blank rectangle
             * instead of a dead stream.
             */
            detachChildUnsafe(previous, release = true)

            val child = createChildUnsafe(spec, config)
            children[layerId] = child
            observeChild(child)
            processor?.let { attachChildUnsafe(child, it) }

            if (_isStreamingFlow.value || _isPreviewingFlow.value) {
                runCatching { child.source.startStream() }
                    .onFailure {
                        Logger.e(TAG, "Failed to start replacement for layer $layerId", it)
                        // Reported like a source that fails to be created, so the app can put
                        // something else in the layer instead of leaving it blank
                        _layerFailureFlow.tryEmit(
                            LayerFailure(layerId, it.message ?: "Failed to start the new source")
                        )
                    }
            }
            refreshIsStreaming()
        }

        updateLayout(_layoutFlow.value.withLayer(spec.layer))
    }

    // endregion

    /**
     * Sources such as USB and network inputs change their reported size at runtime. Only that
     * child's input is rebuilt; the canvas, the encoder and the other layers are untouched.
     */
    private fun observeChildInfoProvider(child: Child, processor: ICompositingSurfaceProcessor) {
        child.infoJob?.cancel()
        child.infoJob = scope.launch {
            child.source.infoProviderFlow.drop(1).collect { provider ->
                childMutex.withLock {
                    val newSize = provider.getSurfaceSize(child.captureResolution)
                    if (newSize == child.inputSurfaceSize) {
                        // Orientation or mirroring changed but the buffer is still the right
                        // size: the transform is enough, no reallocation needed.
                        processor.setSourceInfoProvider(child.layerId, provider)
                        return@withLock
                    }

                    Logger.i(
                        TAG,
                        "Layer ${child.layerId} resolution changed " +
                                "(${child.inputSurfaceSize} -> $newSize), rebuilding its input"
                    )
                    rebuildChildInputUnsafe(child, processor, provider, newSize)
                }
            }
        }
    }

    /**
     * Requires [childMutex]. Reallocates one child's input at a new size.
     *
     * Only that layer is touched: the canvas, the encoder and the other layers keep running, so a
     * USB camera changing format or a network source switching resolution costs one layer a few
     * frames rather than interrupting the stream.
     */
    private suspend fun rebuildChildInputUnsafe(
        child: Child,
        processor: ICompositingSurfaceProcessor,
        provider: ISourceInfoProvider,
        newSize: Size
    ) {
        try {
            (child.source as ISurfaceSourceInternal).resetOutput()
            processor.removeInputSurface(child.layerId)

            val inputSurface = processor.createInputSurface(
                layerId = child.layerId,
                surfaceSize = newSize,
                timebase = child.source.timebaseOrUptime(),
                sourceInfoProvider = provider
            )
            child.inputSurface = inputSurface
            child.inputSurfaceSize = newSize
            child.source.setOutput(inputSurface)
        } catch (t: Throwable) {
            Logger.e(TAG, "Failed to rebuild input for layer ${child.layerId}", t)
            _layerFailureFlow.tryEmit(
                LayerFailure(child.layerId, t.message ?: "Failed to apply new resolution")
            )
        }
    }

    private fun IVideoSourceInternal.timebaseOrUptime(): Timebase =
        (this as? ISurfaceSourceInternal)?.timebase ?: Timebase.UPTIME

    /**
     * Presents the composition to the pipeline as an already-oriented frame at exactly the
     * encoder resolution.
     *
     * Reporting zero relative rotation means the outer [SurfaceOutput] applies no rotation and no
     * letterboxing, which is correct: the composite has already placed and oriented every layer.
     * Device rotation is therefore baked into the canvas by [setTargetRotation].
     */
    private inner class CanvasInfoProvider(
        private val capturesTargetRotation: Boolean
    ) : DefaultSourceInfoProvider(
        isMirror = false,
        rotationDegrees = 0
    ) {
        override fun getSurfaceSize(targetResolution: Size): Size = canvasSize

        override fun getRelativeRotationDegrees(
            targetRotation: Int,
            requiredMirroring: Boolean
        ): Int {
            /**
             * This is how the composite learns which way is up.
             *
             * The pipeline never tells a source its target rotation: it only passes it when
             * building a [SurfaceOutput], which asks the source's info provider for the relative
             * rotation. Since the composite has to bake rotation into the canvas itself, it reads
             * the value here — and still answers zero, because by the time the frame leaves the
             * canvas it is already upright.
             */
            if (capturesTargetRotation) {
                onTargetRotationObserved(targetRotation)
            }
            return 0
        }
    }

    /**
     * Called whenever the pipeline builds an output for this source, which is also whenever the
     * device rotation changes.
     */
    private fun onTargetRotationObserved(@RotationValue rotation: Int) {
        if (targetRotation == rotation) {
            return
        }
        Logger.i(TAG, "Target rotation is now $rotation")

        /**
         * [VideoSourceConfig.resolution] is always in natural orientation, while the encoder
         * surface is rotated to the target. The canvas is the final frame, so it has to follow
         * the encoder: a portrait stream needs a portrait canvas, otherwise the pipeline
         * letterboxes a landscape canvas into a portrait surface.
         */
        val previousCanvasSize = canvasSize
        videoSourceConfig?.let { canvasSize = orientedCanvasSize(it.resolution, rotation) }

        setTargetRotation(rotation)

        if (previousCanvasSize != canvasSize) {
            // Keep the layout consistent with the new canvas so later updates do not throw.
            _layoutFlow.value = _layoutFlow.value.copy(canvasSize = canvasSize)

            /**
             * The canvas changed shape, but the surface this source renders into was allocated by
             * the pipeline at the old size and is not reallocated on rotation alone. The source
             * has to be recreated, which is what [CompositeVideoSourceFactory.isSourceEquals]
             * reports through [isConfiguredForCurrentRotation] — the same approach
             * MediaProjectionVideoSource uses for screen rotation.
             */
            Logger.w(
                TAG,
                "Canvas orientation changed ($previousCanvasSize -> $canvasSize); " +
                        "the composition must be re-created to match"
            )
        }
    }

    /**
     * Whether this source's canvas still matches the orientation the display is in.
     */
    internal fun isConfiguredForCurrentRotation(): Boolean {
        val config = videoSourceConfig ?: return true
        return canvasSize == orientedCanvasSize(config.resolution, context.displayRotation)
    }

    private fun orientedCanvasSize(resolution: Size, @RotationValue rotation: Int): Size =
        if (context.isRotationPortrait(rotation)) resolution.portraitize else resolution.landscapize

    /**
     * Whether this composition is already made of the sources [other] would create.
     *
     * Used by [CompositeVideoSourceFactory.isSourceEquals] so a reconfiguration that changes
     * nothing but geometry does not rebuild every child.
     */
    internal fun matchesSpecs(other: List<LayerSpec>): Boolean {
        // Compared against the live children, not the construction-time specs: layers can be
        // added and removed at runtime.
        if (children.isEmpty() || other.size != children.size) {
            return false
        }
        return other.all { spec ->
            val child = children[spec.layer.id]
            child != null && spec.childFactory.isSourceEquals(child.source)
        }
    }

    private class Child(
        val layerId: String,
        val source: IVideoSourceInternal,
        val captureResolution: Size
    ) {
        var inputSurface: Surface? = null

        /** Size the input was allocated at, to detect a source changing format at runtime. */
        var inputSurfaceSize: Size? = null
        var watchJob: Job? = null
        var infoJob: Job? = null
    }

    companion object {
        private const val TAG = "CompositeVideoSource"
    }
}
