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

import android.graphics.Color
import android.graphics.SurfaceTexture
import android.opengl.Matrix
import android.util.Size
import android.view.Surface
import androidx.concurrent.futures.CallbackToFutureAdapter
import com.google.common.util.concurrent.ListenableFuture
import io.github.thibaultbee.streampack.core.elements.processing.video.composition.CompositionLayout
import io.github.thibaultbee.streampack.core.elements.processing.video.outputs.ISurfaceOutput
import io.github.thibaultbee.streampack.core.elements.processing.video.outputs.SourceTransform
import io.github.thibaultbee.streampack.core.elements.processing.video.outputs.SurfaceOutput
import io.github.thibaultbee.streampack.core.elements.processing.video.source.DefaultSourceInfoProvider
import io.github.thibaultbee.streampack.core.elements.processing.video.source.ISourceInfoProvider
import io.github.thibaultbee.streampack.core.elements.utils.RotationValue
import io.github.thibaultbee.streampack.core.elements.processing.video.utils.extensions.preRotate
import io.github.thibaultbee.streampack.core.elements.utils.av.video.DynamicRangeProfile
import io.github.thibaultbee.streampack.core.elements.utils.extensions.rotate
import io.github.thibaultbee.streampack.core.elements.utils.time.TimeUtils
import io.github.thibaultbee.streampack.core.elements.utils.time.Timebase
import io.github.thibaultbee.streampack.core.elements.utils.time.VideoTimebaseConverter
import io.github.thibaultbee.streampack.core.logger.Logger
import io.github.thibaultbee.streampack.core.pipelines.DispatcherProvider.Companion.THREAD_NAME_GL
import io.github.thibaultbee.streampack.core.pipelines.IVideoDispatcherProvider
import io.github.thibaultbee.streampack.core.pipelines.utils.HandlerThreadExecutor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Draws several identified inputs into one frame, each in its own region of a fixed canvas.
 *
 * This is a sibling of [DefaultSurfaceProcessor], not a replacement. That one is a one-in /
 * many-out tee, relied on by the camera, screen, USB and network sources; its inputs share a
 * single GL texture and a single texture matrix. Here every input owns a texture, a texture
 * matrix, a timestamp and a [SourceTransform], which is what makes several live inputs possible.
 *
 * All GL state lives on [glThread]. The only thing crossing threads freely is [layout], which is
 * immutable and published through an [AtomicReference] so gesture-rate updates never block on the
 * renderer.
 */
internal class CompositeSurfaceProcessor(
    override val canvasSize: Size,
    private val dynamicRangeProfile: DynamicRangeProfile,
    private val glThread: HandlerThreadExecutor,
    private val renderClock: RenderClock = RenderClock.DrivenByPrimary,
    private val nominalFps: Int = DEFAULT_NOMINAL_FPS
) : ICompositingSurfaceProcessor, SurfaceTexture.OnFrameAvailableListener {
    override var isMuted: Boolean = false

    private val renderer = OpenGlRenderer()
    private val glHandler = glThread.handler

    private val isReleaseRequested = AtomicBoolean(false)
    private var isReleased = false

    /**
     * Published lock-free; read once per frame by the GL thread.
     */
    private val layoutRef = AtomicReference(CompositionLayout(canvasSize))

    override var layout: CompositionLayout
        get() = layoutRef.get()
        set(value) {
            require(value.canvasSize == canvasSize) {
                "Canvas size is fixed: expected $canvasSize, got ${value.canvasSize}"
            }
            layoutRef.set(value)
        }

    private val targetRotation = AtomicInteger(0)

    /** GL thread only. */
    private val inputs = LinkedHashMap<String, GlInput>()

    /** GL thread only. Identity lookup for [onFrameAvailable]. */
    private val inputsByTexture = HashMap<SurfaceTexture, GlInput>()

    /** GL thread only. */
    private val surfaceOutputs = mutableListOf<ISurfaceOutput>()

    private var lastRenderedTimestampNs = 0L
    private var lastRenderWallClockNs = 0L

    // CFR render loop state, ported from DefaultSurfaceProcessor.
    private var isRenderLoopRunning = false
    private val cfrFrameIntervalNs = when (renderClock) {
        is RenderClock.Cfr -> 1_000_000_000L / renderClock.fps
        RenderClock.DrivenByPrimary -> 0L
    }
    private var nextCfrFrameTimeNs = 0L

    private val renderRunnable = object : Runnable {
        override fun run() {
            if (isReleaseRequested.get() || isReleased) {
                return
            }

            val nowNs = System.nanoTime()
            if (nextCfrFrameTimeNs == 0L) {
                nextCfrFrameTimeNs = nowNs
            }

            if (nowNs >= nextCfrFrameTimeNs) {
                renderFrame()
                // Skip missed slots so a slow render doesn't burst later.
                do {
                    nextCfrFrameTimeNs += cfrFrameIntervalNs
                } while (nextCfrFrameTimeNs <= nowNs)
            }

            val delayMs = ((nextCfrFrameTimeNs - System.nanoTime() + 999_999L) / 1_000_000L)
                .coerceAtLeast(0L)
            glHandler.postDelayed(this, delayMs)
        }
    }

    /**
     * Renders from a secondary input when the primary has gone quiet.
     *
     * Without it, unplugging the primary source freezes the whole composition, including layers
     * that are perfectly healthy.
     */
    private val watchdogIntervalNs = STALL_FRAMES * 1_000_000_000L / nominalFps.coerceAtLeast(1)
    private var isWatchdogRunning = false
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (isReleaseRequested.get() || isReleased) {
                return
            }

            val sinceLastRenderNs = System.nanoTime() - lastRenderWallClockNs
            if (sinceLastRenderNs >= watchdogIntervalNs && inputs.values.any { it.hasFrame }) {
                Logger.d(TAG, "Primary input stalled, rendering from secondary inputs")
                renderFrame()
            }

            glHandler.postDelayed(this, watchdogIntervalNs / 1_000_000L)
        }
    }

    init {
        val future = submitSafely {
            renderer.init(dynamicRangeProfile)
        }
        try {
            future.get()
        } catch (e: Exception) {
            release()
            Logger.e(TAG, "Error while initializing renderer", e)
            throw e
        }
    }

    // region inputs

    override fun createInputSurface(
        layerId: String,
        surfaceSize: Size,
        timebase: Timebase,
        sourceInfoProvider: ISourceInfoProvider
    ): Surface {
        if (isReleaseRequested.get()) {
            throw IllegalStateException("CompositeSurfaceProcessor is released")
        }

        val future = submitSafely {
            if (isReleaseRequested.get()) {
                throw IllegalStateException("CompositeSurfaceProcessor is released")
            }
            require(!inputs.containsKey(layerId)) { "Layer $layerId already has an input" }

            val textureId = renderer.createInputTexture()
            val surfaceTexture = SurfaceTexture(textureId).apply {
                setDefaultBufferSize(surfaceSize.width, surfaceSize.height)
                setOnFrameAvailableListener(this@CompositeSurfaceProcessor, glHandler)
            }

            val input = GlInput(
                layerId = layerId,
                surface = Surface(surfaceTexture),
                surfaceTexture = surfaceTexture,
                textureId = textureId,
                sourceSize = surfaceSize,
                timeConverter = VideoTimebaseConverter(timebase, TimeUtils.systemTimeProvider)
            )
            input.setSourceInfoProvider(sourceInfoProvider, targetRotation.get())

            inputs[layerId] = input
            inputsByTexture[surfaceTexture] = input

            startClocksIfNeeded()

            input
        }

        return future.get().surface
    }

    override fun createInputSurface(surfaceSize: Size, timebase: Timebase): Surface {
        throw UnsupportedOperationException(
            "A compositing processor needs to know which layer an input feeds: " +
                    "use createInputSurface(layerId, surfaceSize, timebase, sourceInfoProvider)"
        )
    }

    override fun removeInputSurface(layerId: String) {
        if (isReleaseRequested.get()) {
            Logger.w(TAG, "CompositeSurfaceProcessor is released")
            return
        }

        executeSafely {
            val input = inputs.remove(layerId)
            if (input == null) {
                Logger.w(TAG, "No input for layer $layerId")
                return@executeSafely
            }
            releaseInputUnsafe(input)
            checkReadyToRelease()
        }
    }

    override fun removeInputSurface(surface: Surface) {
        if (isReleaseRequested.get()) {
            Logger.w(TAG, "CompositeSurfaceProcessor is released")
            return
        }

        executeSafely {
            val input = inputs.values.firstOrNull { it.surface == surface }
            if (input == null) {
                Logger.w(TAG, "Surface not found")
                return@executeSafely
            }
            inputs.remove(input.layerId)
            releaseInputUnsafe(input)
            checkReadyToRelease()
        }
    }

    /**
     * GL thread only. The caller must already have removed [input] from [inputs].
     */
    private fun releaseInputUnsafe(input: GlInput) {
        inputsByTexture.remove(input.surfaceTexture)
        input.surfaceTexture.setOnFrameAvailableListener(null, glHandler)
        input.surfaceTexture.release()
        input.surface.release()
        renderer.deleteInputTexture(input.textureId)

        if (inputs.isEmpty()) {
            stopClocksUnsafe()
        }
    }

    override fun setTimebase(surface: Surface, timebase: Timebase) {
        executeSafely {
            val input = inputs.values.firstOrNull { it.surface == surface }
            if (input != null) {
                input.timeConverter = VideoTimebaseConverter(timebase, TimeUtils.systemTimeProvider)
            } else {
                Logger.w(TAG, "Surface not found")
            }
        }
    }

    override fun setSourceInfoProvider(layerId: String, sourceInfoProvider: ISourceInfoProvider) {
        executeSafely {
            val input = inputs[layerId]
            if (input != null) {
                input.setSourceInfoProvider(sourceInfoProvider, targetRotation.get())
            } else {
                Logger.w(TAG, "No input for layer $layerId")
            }
        }
    }

    override fun setTargetRotation(@RotationValue targetRotation: Int) {
        if (this.targetRotation.getAndSet(targetRotation) == targetRotation) {
            return
        }
        executeSafely {
            inputs.values.forEach { it.refreshSourceTransform(targetRotation) }
        }
    }

    // endregion

    // region outputs

    override fun addOutputSurface(surfaceOutput: ISurfaceOutput) {
        if (isReleaseRequested.get()) {
            throw IllegalStateException("CompositeSurfaceProcessor is released")
        }

        executeSafely {
            if (isReleaseRequested.get()) {
                throw IllegalStateException("CompositeSurfaceProcessor is released")
            }
            if (surfaceOutputs.none { it.targetSurface == surfaceOutput.targetSurface }) {
                renderer.registerOutputSurface(
                    surfaceOutput.targetSurface,
                    surfaceOutput.viewportRect
                )
                surfaceOutputs.add(surfaceOutput)
            } else {
                Logger.w(TAG, "Surface already added")
            }
        }
    }

    private fun removeOutputSurfaceUnsafe(surfaceOutput: ISurfaceOutput) {
        if (surfaceOutputs.remove(surfaceOutput)) {
            renderer.unregisterOutputSurface(surfaceOutput.targetSurface)
        } else {
            Logger.w(TAG, "Surface not found")
        }
    }

    override fun removeOutputSurface(surfaceOutput: ISurfaceOutput) {
        if (isReleaseRequested.get()) {
            Logger.w(TAG, "CompositeSurfaceProcessor is released")
            return
        }
        executeSafely {
            if (isReleased) {
                return@executeSafely
            }
            removeOutputSurfaceUnsafe(surfaceOutput)
        }
    }

    override fun removeOutputSurface(surface: Surface) {
        if (isReleaseRequested.get()) {
            Logger.w(TAG, "CompositeSurfaceProcessor is released")
            return
        }
        executeSafely {
            if (isReleased) {
                return@executeSafely
            }
            val surfaceOutput = surfaceOutputs.firstOrNull { it.targetSurface == surface }
            if (surfaceOutput != null) {
                removeOutputSurfaceUnsafe(surfaceOutput)
            } else {
                Logger.w(TAG, "Surface not found")
            }
        }
    }

    private fun removeAllOutputSurfacesUnsafe() {
        surfaceOutputs.forEach { renderer.unregisterOutputSurface(it.targetSurface) }
        surfaceOutputs.clear()
    }

    override fun removeAllOutputSurfaces() {
        if (isReleaseRequested.get()) {
            Logger.w(TAG, "CompositeSurfaceProcessor is released")
            return
        }
        executeSafely {
            if (isReleased) {
                return@executeSafely
            }
            removeAllOutputSurfacesUnsafe()
        }
    }

    // endregion

    // region rendering

    /**
     * Executed on the GL thread.
     */
    override fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
        if (isReleaseRequested.get() || isReleased) {
            return
        }

        val input = inputsByTexture[surfaceTexture] ?: return

        // Guard against the race where a frame callback fires after the surface is released.
        try {
            surfaceTexture.updateTexImage()
        } catch (e: RuntimeException) {
            Logger.w(TAG, "updateTexImage failed (surface likely released): ${e.message}")
            return
        }
        surfaceTexture.getTransformMatrix(input.textureMatrix)
        input.latestTimestampNs = input.timeConverter.convertToUptimeNs(surfaceTexture.timestamp)
        input.hasFrame = true

        if (renderClock is RenderClock.DrivenByPrimary && isPrimary(input)) {
            renderFrame()
        }
    }

    private fun isPrimary(input: GlInput): Boolean {
        val primaryId = layoutRef.get().primaryLayer?.id
        // With no primary designated yet, any input may drive the clock rather than none.
        return primaryId == null || primaryId == input.layerId
    }

    /**
     * Executed on the GL thread.
     */
    private fun renderFrame() {
        if (isReleased || surfaceOutputs.isEmpty()) {
            return
        }

        val layout = layoutRef.get()
        val timestampNs = nextTimestampNs(layout)

        surfaceOutputs.forEach { output ->
            if (output is SurfaceOutput && !output.isStreaming()) {
                return@forEach
            }
            try {
                renderToOutput(output, layout, timestampNs)
            } catch (t: Throwable) {
                Logger.e(TAG, "Error while rendering frame", t)
            }
        }

        lastRenderedTimestampNs = timestampNs
        lastRenderWallClockNs = System.nanoTime()
    }

    private fun renderToOutput(
        output: ISurfaceOutput,
        layout: CompositionLayout,
        timestampNs: Long
    ) {
        val clearColor = if (isMuted) Color.BLACK else layout.backgroundColor
        if (!renderer.beginFrame(output.targetSurface, clearColor = clearColor)) {
            return
        }

        if (!isMuted) {
            var drawnLayers = 0
            for (layer in layout.drawOrder) {
                val input = inputs[layer.id] ?: continue
                // An external texture that never received a frame renders undefined content on
                // some drivers, so a layer that failed to start is skipped, not drawn black.
                if (!input.hasFrame) {
                    continue
                }

                // Texture matrix chain: surfaceTexture * sourceOrientation * crop
                input.sourceTransform.updateTransformMatrix(
                    input.orientedTexMatrix,
                    input.textureMatrix
                )
                // The source transform may rotate the frame by 90 or 270 degrees, which swaps
                // its aspect ratio. Fit and fill have to reason about the rotated frame, not the
                // raw buffer, or a portrait-mounted sensor is letterboxed the wrong way round.
                val sourceSize = input.effectiveSourceSize

                layer.buildCropMatrix(input.cropMatrix, canvasSize, sourceSize)
                Matrix.multiplyMM(
                    input.composedTexMatrix, 0,
                    input.orientedTexMatrix, 0,
                    input.cropMatrix, 0
                )
                if (layer.rotationDegrees != 0) {
                    // Same convention as SourceTransform: a rotation applied in texture space.
                    input.composedTexMatrix.preRotate(layer.rotationDegrees.toFloat(), 0.5f, 0.5f)
                }

                layer.buildTransformMatrix(input.transMatrix, canvasSize, sourceSize)

                renderer.drawLayer(
                    externalTextureId = input.textureId,
                    textureTransform = input.composedTexMatrix,
                    transformMatrix = input.transMatrix,
                    alpha = layer.alpha,
                    blend = drawnLayers > 0 || layer.alpha < 1f
                )
                drawnLayers++
            }
        }

        renderer.endFrame(output.targetSurface, timestampNs)
    }

    /**
     * Picks the presentation timestamp, keeping it strictly increasing.
     *
     * Inputs have independent clocks, so their converted timestamps can arrive out of order. A
     * non-increasing PTS breaks the muxer and SRT, so it is clamped here.
     */
    private fun nextTimestampNs(layout: CompositionLayout): Long {
        val candidate = when (renderClock) {
            is RenderClock.Cfr -> System.nanoTime()
            RenderClock.DrivenByPrimary -> {
                val primaryId = layout.primaryLayer?.id
                val primary = primaryId?.let { inputs[it] }
                if (primary != null && primary.hasFrame) {
                    primary.latestTimestampNs
                } else {
                    inputs.values.filter { it.hasFrame }
                        .maxOfOrNull { it.latestTimestampNs }
                        ?: System.nanoTime()
                }
            }
        }
        return maxOf(candidate, lastRenderedTimestampNs + 1)
    }

    // endregion

    // region lifecycle

    /**
     * GL thread only.
     */
    private fun startClocksIfNeeded() {
        if (renderClock is RenderClock.Cfr && !isRenderLoopRunning) {
            isRenderLoopRunning = true
            nextCfrFrameTimeNs = 0L
            glHandler.post(renderRunnable)
            Logger.i(TAG, "Started CFR render loop at ${renderClock.fps} fps")
        }
        if (renderClock is RenderClock.DrivenByPrimary && !isWatchdogRunning) {
            isWatchdogRunning = true
            lastRenderWallClockNs = System.nanoTime()
            glHandler.postDelayed(watchdogRunnable, watchdogIntervalNs / 1_000_000L)
        }
    }

    /**
     * GL thread only.
     */
    private fun stopClocksUnsafe() {
        if (isRenderLoopRunning) {
            isRenderLoopRunning = false
            glHandler.removeCallbacks(renderRunnable)
        }
        if (isWatchdogRunning) {
            isWatchdogRunning = false
            glHandler.removeCallbacks(watchdogRunnable)
        }
    }

    override fun release() {
        if (isReleaseRequested.getAndSet(true)) {
            return
        }
        executeSafely {
            if (!isReleased) {
                isReleased = true
                stopClocksUnsafe()
                checkReadyToRelease()
            }
        }
    }

    /**
     * GL thread only.
     */
    private fun checkReadyToRelease() {
        if (isReleased && inputs.isEmpty()) {
            removeAllOutputSurfacesUnsafe()
            renderer.release()
            glThread.quit()
        }
    }

    // endregion

    private fun executeSafely(block: () -> Unit) {
        executeSafely(block, {}, {})
    }

    private fun <T> executeSafely(
        block: () -> T,
        onSuccess: (T) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        try {
            glHandler.post {
                if (isReleased) {
                    Logger.w(TAG, "CompositeSurfaceProcessor is released, block will not run")
                    onError(IllegalStateException("CompositeSurfaceProcessor is released"))
                } else {
                    try {
                        onSuccess(block())
                    } catch (t: Throwable) {
                        onError(t)
                    }
                }
            }
        } catch (t: Throwable) {
            Logger.e(TAG, "Error while executing block", t)
            onError(t)
        }
    }

    private fun <T : Any> submitSafely(block: () -> T): ListenableFuture<T> {
        return CallbackToFutureAdapter.getFuture {
            executeSafely(block, { result -> it.set(result) }, { t -> it.setException(t) })
        }
    }

    /**
     * Everything the renderer needs for one input. Owned by the GL thread.
     */
    private class GlInput(
        val layerId: String,
        val surface: Surface,
        val surfaceTexture: SurfaceTexture,
        val textureId: Int,
        var sourceSize: Size,
        var timeConverter: VideoTimebaseConverter
    ) {
        /** Written by `SurfaceTexture.getTransformMatrix`, read while rendering. */
        val textureMatrix = FloatArray(16)

        // Scratch matrices, reused every frame so rendering allocates nothing.
        val orientedTexMatrix = FloatArray(16)
        val cropMatrix = FloatArray(16)
        val composedTexMatrix = FloatArray(16)
        val transMatrix = FloatArray(16)

        var latestTimestampNs = 0L
        var hasFrame = false

        /**
         * [sourceSize] after [sourceTransform]'s rotation. Recomputed with the transform, never
         * per frame.
         */
        var effectiveSourceSize: Size = sourceSize
            private set

        private var sourceInfoProvider: ISourceInfoProvider = DefaultSourceInfoProvider()

        /**
         * Orientation/mirroring for this input. Rebuilt only when the source info or the target
         * rotation changes, never per frame.
         */
        var sourceTransform: SourceTransform = SourceTransform(0, false, sourceInfoProvider)
            private set

        init {
            Matrix.setIdentityM(textureMatrix, 0)
        }

        fun setSourceInfoProvider(
            sourceInfoProvider: ISourceInfoProvider,
            @RotationValue targetRotation: Int
        ) {
            this.sourceInfoProvider = sourceInfoProvider
            sourceSize = sourceInfoProvider.getSurfaceSize(sourceSize)
            refreshSourceTransform(targetRotation)
        }

        fun refreshSourceTransform(@RotationValue targetRotation: Int) {
            sourceTransform = SourceTransform(targetRotation, false, sourceInfoProvider)
            effectiveSourceSize = sourceSize.rotate(sourceTransform.rotationDegrees)
        }
    }

    companion object {
        private const val TAG = "CompositeSurfaceProcessor"

        private const val DEFAULT_NOMINAL_FPS = 30

        /**
         * How many nominal frame intervals the primary may miss before a secondary input is
         * allowed to drive a frame.
         */
        private const val STALL_FRAMES = 3L
    }
}

/**
 * Factory for a [CompositeSurfaceProcessor].
 */
class CompositeSurfaceProcessorFactory(
    private val renderClock: RenderClock = RenderClock.DrivenByPrimary,
    private val nominalFps: Int = 30
) : ICompositingSurfaceProcessor.Factory {
    override fun create(
        canvasSize: Size,
        dynamicRangeProfile: DynamicRangeProfile,
        dispatcherProvider: IVideoDispatcherProvider
    ): ICompositingSurfaceProcessor {
        return CompositeSurfaceProcessor(
            canvasSize,
            dynamicRangeProfile,
            dispatcherProvider.createVideoHandlerExecutor(THREAD_NAME_GL),
            renderClock,
            nominalFps
        )
    }
}
