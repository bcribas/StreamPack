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
package io.github.thibaultbee.streampack.core.elements.sources.video.camera.controllers

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Gets a camera back after the system took it.
 *
 * Android gives a camera to whoever matters more: the lock screen's face unlock, the camera app
 * in front. The one that loses it is told once (`onDisconnected`) and nothing else happens: on
 * 2026-10-10 a live in the car lost both its cameras when unplugging the charger woke the lock
 * screen, and sent sound alone for 25 minutes. The other camera goes too when the device cannot
 * afford the three at once.
 *
 * [onLost] starts trying: once things settle, then every time a camera of the phone is freed
 * (the taker closing it) and at least every [retryMs]. It stops when an attempt finds the camera
 * open again with no loss reported since that attempt began, or nobody needs its frames any
 * more. [interruptionFlow] says why it is away meanwhile.
 *
 * Camera2 is kept out of it, so it is tested on the JVM.
 */
internal class CameraRecovery(
    private val scope: CoroutineScope,
    private val openWaiter: () -> Waiter,
    private val attempt: suspend () -> Outcome,
    private val settleMs: Long = SETTLE_MS,
    private val retryMs: Long = RETRY_MS,
    private val log: (String) -> Unit = {},
) {
    enum class Outcome {
        /** The camera is open and delivering again. */
        RECOVERED,

        /** Nothing to get back: nobody needs frames, or the camera is being released. */
        NOT_NEEDED,

        /** Still taken: try again later. */
        RETRY
    }

    /** Wakes the loop when a camera of the phone is freed. */
    interface Waiter {
        /** Forgets what happened before now. */
        fun clear()

        /** Returns at the next change, or after [timeoutMs]. */
        suspend fun awaitChange(timeoutMs: Long)

        fun close()
    }

    private val lock = Any()
    private var running = false
    private var losses = 0L

    /** Which loop is the current one: a loop that ended must not touch the next one's state. */
    private var generation = 0L

    private val _interruptionFlow = MutableStateFlow<String?>(null)

    /** Why the camera is away, while it is; null when it delivers. */
    val interruptionFlow: StateFlow<String?> = _interruptionFlow.asStateFlow()

    /** The camera went away for [reason] while it was not being released. */
    fun onLost(reason: String) {
        val mine = synchronized(lock) {
            losses++
            _interruptionFlow.value = reason
            if (running) return
            running = true
            ++generation
        }
        scope.launch {
            try {
                loop(mine)
            } finally {
                synchronized(lock) {
                    // Cancelled with the camera: nothing left to say about it
                    if (running && generation == mine) {
                        running = false
                        _interruptionFlow.value = null
                    }
                }
            }
        }
    }

    private suspend fun loop(mine: Long) {
        val waiter = openWaiter()
        try {
            // Whoever took it is still settling in; an attempt now only fails
            delay(settleMs)
            var attempts = 0
            while (true) {
                val seen = synchronized(lock) { losses }
                waiter.clear()
                attempts++
                val outcome = try {
                    attempt()
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    log("Attempt $attempts failed: ${t.message}")
                    Outcome.RETRY
                }
                if (outcome != Outcome.RETRY) {
                    val done = synchronized(lock) {
                        // A loss reported during the attempt was of the device it just opened
                        if (losses == seen && generation == mine) {
                            running = false
                            _interruptionFlow.value = null
                            true
                        } else false
                    }
                    if (done) {
                        if (outcome == Outcome.RECOVERED) log("Back after $attempts attempt(s)")
                        return
                    }
                }
                waiter.awaitChange(retryMs)
                // Freed is not closed yet everywhere: give the taker a moment to finish
                delay(GAP_MS)
            }
        } finally {
            waiter.close()
        }
    }

    companion object {
        const val SETTLE_MS = 1_000L
        /**
         * The lock screen's face unlock holds a camera of its own that apps are never told is
         * free again: on the S20 FE it lets go about 2 s after the eviction, found by retrying.
         */
        const val RETRY_MS = 2_500L
        const val GAP_MS = 300L
    }
}
