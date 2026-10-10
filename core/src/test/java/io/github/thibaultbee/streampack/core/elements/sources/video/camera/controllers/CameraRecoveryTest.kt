package io.github.thibaultbee.streampack.core.elements.sources.video.camera.controllers

import io.github.thibaultbee.streampack.core.elements.sources.video.camera.controllers.CameraRecovery.Outcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CameraRecoveryTest {

    /** A phone whose cameras are freed when the test says so. */
    private class FakeWaiter : CameraRecovery.Waiter {
        val freed = Channel<Unit>(Channel.CONFLATED)
        var opened = 0
        var closed = 0

        override fun clear() {
            while (freed.tryReceive().isSuccess) Unit
        }

        override suspend fun awaitChange(timeoutMs: Long) {
            withTimeoutOrNull(timeoutMs) { freed.receive() }
        }

        override fun close() {
            closed++
        }
    }

    private class Harness(scope: TestScope, outcomes: List<Outcome>) {
        val waiter = FakeWaiter()
        val left = ArrayDeque(outcomes)
        var attempts = 0
        var duringAttempt: (() -> Unit)? = null
        val recovery = CameraRecovery(
            scope = scope,
            openWaiter = { waiter.also { it.opened++ } },
            attempt = {
                attempts++
                duringAttempt?.invoke()
                left.removeFirstOrNull() ?: Outcome.RECOVERED
            },
        )
    }

    @Test
    fun `says why the camera is away until it is back`() = runTest {
        val h = Harness(this, listOf(Outcome.RETRY))
        h.recovery.onLost("taken by another app or the system")
        runCurrent()
        assertEquals("taken by another app or the system", h.recovery.interruptionFlow.value)

        // Settles first, then the camera is still taken
        advanceTimeBy(CameraRecovery.SETTLE_MS + 1)
        assertEquals(1, h.attempts)
        assertEquals("taken by another app or the system", h.recovery.interruptionFlow.value)

        // The face unlock lets go of the camera: tried at once, not at the next retry
        h.waiter.freed.trySend(Unit)
        advanceTimeBy(CameraRecovery.GAP_MS + 1)
        assertEquals(2, h.attempts)
        assertNull(h.recovery.interruptionFlow.value)
        assertEquals(1, h.waiter.closed)
    }

    @Test
    fun `keeps trying while nothing frees the camera`() = runTest {
        val h = Harness(this, List(3) { Outcome.RETRY })
        h.recovery.onLost("taken")
        advanceTimeBy(CameraRecovery.SETTLE_MS + 3 * (CameraRecovery.RETRY_MS + CameraRecovery.GAP_MS) + 1)
        assertEquals(4, h.attempts)
        assertNull(h.recovery.interruptionFlow.value)
    }

    @Test
    fun `a loss during the attempt is tried again`() = runTest {
        val h = Harness(this, emptyList())
        // The device it just opened is taken again before the attempt reports back
        h.duringAttempt = {
            h.duringAttempt = null
            h.recovery.onLost("taken again")
        }
        h.recovery.onLost("taken")
        advanceTimeBy(CameraRecovery.SETTLE_MS + 1)
        assertEquals(1, h.attempts)
        assertEquals("taken again", h.recovery.interruptionFlow.value)

        advanceUntilIdle()
        assertEquals(2, h.attempts)
        assertNull(h.recovery.interruptionFlow.value)
        // One loop the whole time
        assertEquals(1, h.waiter.opened)
    }

    @Test
    fun `a camera nobody needs is not fought for`() = runTest {
        val h = Harness(this, listOf(Outcome.NOT_NEEDED))
        h.recovery.onLost("taken")
        advanceUntilIdle()
        assertEquals(1, h.attempts)
        assertNull(h.recovery.interruptionFlow.value)
    }

    @Test
    fun `a later loss starts over`() = runTest {
        val h = Harness(this, emptyList())
        h.recovery.onLost("first")
        advanceUntilIdle()
        assertNull(h.recovery.interruptionFlow.value)

        h.recovery.onLost("second")
        runCurrent()
        assertEquals("second", h.recovery.interruptionFlow.value)
        advanceUntilIdle()
        assertEquals(2, h.attempts)
        assertNull(h.recovery.interruptionFlow.value)
        assertEquals(2, h.waiter.opened)
    }

    @Test
    fun `a failing attempt counts as still taken`() = runTest {
        val thrown = CompletableDeferred<Unit>()
        val waiter = FakeWaiter()
        var attempts = 0
        val recovery = CameraRecovery(
            scope = this,
            openWaiter = { waiter },
            attempt = {
                attempts++
                if (attempts == 1) {
                    thrown.complete(Unit)
                    throw IllegalStateException("Camera already in use")
                }
                Outcome.RECOVERED
            },
        )
        recovery.onLost("taken")
        advanceUntilIdle()
        assertTrue(thrown.isCompleted)
        assertEquals(2, attempts)
        assertNull(recovery.interruptionFlow.value)
    }
}
