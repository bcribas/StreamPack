package io.github.thibaultbee.streampack.core.elements.processing.video.outputs

import android.graphics.Rect
import android.util.Size
import android.view.Surface

interface ISurfaceOutput {
    val targetSurface: Surface
    val targetResolution: Size
    val viewportRect: Rect
    val type: OutputType

    /**
     * The highest rate this output wants frames at, or `null` for every frame the processor
     * produces.
     *
     * Only a processor that draws each output separately can honour it. It exists so a preview
     * can be throttled without touching the encoder, which shares the same processor.
     */
    val maxFps: Int? get() = null

    /**
     * [maxFps] as a minimum interval in nanoseconds; 0 when unlimited.
     */
    val minFrameIntervalNs: Long
        get() = maxFps?.takeIf { it > 0 }?.let { 1_000_000_000L / it } ?: 0L

    fun updateTransformMatrix(output: FloatArray, input: FloatArray)

    enum class OutputType {
        INTERNAL,
        BITMAP
    }
}