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
package io.github.thibaultbee.streampack.core.elements.encoders

/**
 * Frames out of an encoder and their rate over about a second.
 *
 * The rate used to be computed only when a frame came out, so an encoder that stopped getting
 * frames kept reporting its last rate: on 2026-10-10 the live showed 30.9 fps for minutes while
 * no picture left the phone. Here no frame for [staleAfterMs] reads as nothing coming out.
 *
 * Written from the encoder's thread, read from any.
 */
internal class OutputFpsMeter(
    private val windowMs: Long = 1_000L,
    private val staleAfterMs: Long = 2 * windowMs,
) {
    private var frameCount = 0L
    private var framesInWindow = 0
    private var windowStartMs = 0L
    private var lastFrameMs = 0L
    private var lastFps = 0f

    @Synchronized
    fun onFrame(nowMs: Long) {
        frameCount++
        framesInWindow++
        lastFrameMs = nowMs
        if (windowStartMs == 0L) {
            windowStartMs = nowMs
        }
        val elapsed = nowMs - windowStartMs
        if (elapsed >= windowMs) {
            lastFps = framesInWindow * 1000f / elapsed
            framesInWindow = 0
            windowStartMs = nowMs
        }
    }

    /** Frames since the last [reset]. */
    @get:Synchronized
    val count: Long
        get() = frameCount

    /** The rate of the last full window, or nothing once frames have stopped. */
    @Synchronized
    fun fps(nowMs: Long): Float =
        if (lastFrameMs != 0L && nowMs - lastFrameMs >= staleAfterMs) 0f else lastFps

    @Synchronized
    fun reset() {
        frameCount = 0
        framesInWindow = 0
        windowStartMs = 0
        lastFrameMs = 0
        lastFps = 0f
    }
}
