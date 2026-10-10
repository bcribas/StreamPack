package io.github.thibaultbee.streampack.core.elements.encoders

import org.junit.Assert.assertEquals
import org.junit.Test

class OutputFpsMeterTest {

    private fun runAt30(meter: OutputFpsMeter, fromMs: Long, seconds: Int): Long {
        var now = fromMs
        repeat(seconds * 30) {
            meter.onFrame(now)
            now += 33
        }
        return now
    }

    @Test
    fun `measures the rate frames come out at`() {
        val meter = OutputFpsMeter()
        val end = runAt30(meter, 1_000, 3)
        assertEquals(30f, meter.fps(end), 1.5f)
        assertEquals(90L, meter.count)
    }

    @Test
    fun `an encoder that stops getting frames falls to nothing`() {
        val meter = OutputFpsMeter()
        val end = runAt30(meter, 1_000, 3)
        // Within the window the last rate still stands
        assertEquals(30f, meter.fps(end + 500), 1.5f)
        // The 30.9 fps that stayed on screen for minutes on 2026-10-10
        assertEquals(0f, meter.fps(end + 3_000), 0f)
    }

    @Test
    fun `frames coming back are counted again`() {
        val meter = OutputFpsMeter()
        val stopped = runAt30(meter, 1_000, 2)
        val back = runAt30(meter, stopped + 10_000, 3)
        assertEquals(30f, meter.fps(back), 1.5f)
    }

    @Test
    fun `reset starts over`() {
        val meter = OutputFpsMeter()
        runAt30(meter, 1_000, 2)
        meter.reset()
        assertEquals(0L, meter.count)
        assertEquals(0f, meter.fps(10_000), 0f)
    }
}
