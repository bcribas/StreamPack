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
package io.github.thibaultbee.streampack.ext.srt.elements.endpoints.resilient

import android.media.MediaFormat
import android.net.Uri
import io.github.thibaultbee.streampack.core.configuration.mediadescriptor.MediaDescriptor
import io.github.thibaultbee.streampack.core.elements.data.Extra
import io.github.thibaultbee.streampack.core.elements.data.Frame
import io.github.thibaultbee.streampack.core.elements.encoders.CodecConfig
import io.github.thibaultbee.streampack.core.elements.endpoints.MediaContainerType
import io.github.thibaultbee.streampack.core.elements.endpoints.MediaSinkType
import io.github.thibaultbee.streampack.core.elements.endpoints.composites.data.Packet
import io.github.thibaultbee.streampack.core.elements.endpoints.composites.muxers.IMuxer
import io.github.thibaultbee.streampack.core.elements.endpoints.composites.muxers.IMuxerInternal
import io.github.thibaultbee.streampack.core.elements.endpoints.composites.sinks.ISinkInternal
import io.github.thibaultbee.streampack.core.elements.endpoints.composites.sinks.ITsPacketTap
import io.github.thibaultbee.streampack.core.elements.endpoints.composites.sinks.SinkConfiguration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Collections

class ResilientSrtEndpointTest {
    private companion object {
        const val VIDEO = 1
        const val AUDIO = 2
    }

    /** Emits one packet per frame: [pid, key flag, pts]; signals video key frames like TsMuxer. */
    private class FakeMuxer : IMuxerInternal {
        override var listener: IMuxerInternal.IMuxerListener? = null
        override val streamConfigs: List<CodecConfig> = emptyList()
        override val info: IMuxer.IMuxerInfo get() = error("unused")

        override fun write(frame: Frame, streamPid: Int) {
            if (frame.isKeyFrame && streamPid == VIDEO) listener?.onRandomAccessPoint(frame.ptsInUs)
            val bytes = byteArrayOf(streamPid.toByte(), if (frame.isKeyFrame) 1 else 0, frame.ptsInUs.toByte())
            listener?.onOutputFrame(Packet(ByteBuffer.wrap(bytes), frame.ptsInUs))
        }

        override fun addStreams(streamsConfig: List<CodecConfig>) = emptyMap<CodecConfig, Int>()
        override fun addStream(streamConfig: CodecConfig) = 0
        override fun startStream() = Unit
        override fun stopStream() = Unit
        override fun release() = Unit
    }

    /** A sink whose connection the test controls. */
    private class FakeSink(
        private val onOpen: suspend () -> Unit = {},
        var failWrites: Boolean = false,
    ) : ISinkInternal {
        val written: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override val isOpenFlow = MutableStateFlow(false)

        override suspend fun open(mediaDescriptor: MediaDescriptor) {
            onOpen()
            isOpenFlow.value = true
        }

        override suspend fun write(packet: Packet): Int {
            if (failWrites) throw IOException("Connection was broken")
            val b = packet.buffer
            written += "${b.get(b.position())}:${b.get(b.position() + 1)}:${b.get(b.position() + 2)}"
            return b.remaining()
        }

        override fun configure(config: SinkConfiguration) = Unit
        override suspend fun startStream() = Unit
        override suspend fun stopStream() = Unit
        override suspend fun close() {
            isOpenFlow.value = false
        }
    }

    private class RecordingTap : ITsPacketTap {
        val packets: MutableList<Int> = Collections.synchronizedList(mutableListOf())
        var accessPoints = 0
        override fun onTsPackets(buffer: ByteBuffer) {
            packets += buffer.get(buffer.position() + 2).toInt()
        }

        override fun onRandomAccessPoint(ptsInUs: Long) {
            accessPoints++
        }
    }

    private val descriptor = object : MediaDescriptor(
        Type(MediaContainerType.TS, MediaSinkType.SRT)
    ) {
        override val uri: Uri get() = error("unused")
    }

    private fun frame(pts: Long, key: Boolean = false) = object : Frame {
        override val rawBuffer: ByteBuffer = ByteBuffer.allocate(0)
        override val ptsInUs = pts
        override val dtsInUs: Long? = null
        override val isKeyFrame = key
        override val extra: Extra? = null
        override val format: MediaFormat get() = error("unused")
        override fun close() = Unit
    }

    private fun endpoint(sinks: () -> ISinkInternal, backoff: List<Long> = listOf(20, 20)) =
        ResilientSrtEndpoint(
            ioDispatcher = Dispatchers.IO,
            sinkFactory = sinks,
            muxer = FakeMuxer(),
            validator = {},
            firstAttemptWaitMs = 500,
            backoffMs = backoff,
            sinkCloseTimeoutMs = 500,
        )

    private suspend fun ResilientSrtEndpoint.awaitState(predicate: (SrtLinkState) -> Boolean) =
        withTimeout(3_000) { linkStateFlow.first(predicate) }

    @Test
    fun `open does not fail without network, and the endpoint stays open`() = runBlocking {
        val endpoint = endpoint({ FakeSink(onOpen = { throw IOException("SRT connection timeout") }) })

        endpoint.open(descriptor)

        assertTrue(endpoint.isOpenFlow.value)
        val state = endpoint.awaitState { it is SrtLinkState.Connecting && it.attempt >= 2 }
        assertEquals("SRT connection timeout", (state as SrtLinkState.Connecting).lastError)
        assertNull(endpoint.throwableFlow.value)
        endpoint.close()
        assertFalse(endpoint.isOpenFlow.value)
        assertEquals(SrtLinkState.Idle, endpoint.linkStateFlow.value)
    }

    @Test
    fun `a broken link reconnects by itself and the endpoint never closes`() = runBlocking {
        val sinks = mutableListOf<FakeSink>()
        val endpoint = endpoint({ FakeSink().also { sinks += it } })
        endpoint.open(descriptor)
        endpoint.startStream()
        endpoint.awaitState { it == SrtLinkState.Connected(1) }

        endpoint.write(frame(1, key = true), VIDEO)
        sinks[0].failWrites = true
        endpoint.write(frame(2), VIDEO) // the send fails: link down

        endpoint.awaitState { it == SrtLinkState.Connected(2) }
        assertTrue(endpoint.isOpenFlow.value)
        assertNull(endpoint.throwableFlow.value)
        assertEquals(2, sinks.size)
        endpoint.close()
    }

    @Test
    fun `the tap gets every packet, with and without a link`() = runBlocking {
        val connected = CompletableDeferred<Unit>()
        val endpoint = endpoint({
            FakeSink(onOpen = { if (!connected.isCompleted) throw IOException("down") })
        })
        val tap = RecordingTap()
        endpoint.tsTap = tap
        endpoint.open(descriptor)
        endpoint.startStream()

        endpoint.write(frame(1, key = true), VIDEO) // no link yet
        endpoint.write(frame(2), AUDIO)
        connected.complete(Unit)
        endpoint.awaitState { it is SrtLinkState.Connected }
        endpoint.write(frame(3, key = true), VIDEO)
        endpoint.write(frame(4), AUDIO)

        assertEquals(listOf(1, 2, 3, 4), tap.packets.toList())
        assertEquals(2, tap.accessPoints)
        endpoint.close()
    }

    @Test
    fun `after connecting, nothing is sent before a video key frame`() = runBlocking {
        val sinks = mutableListOf<FakeSink>()
        var keyFramesRequested = 0
        val endpoint = endpoint({ FakeSink().also { sinks += it } })
        endpoint.keyFrameRequester = { keyFramesRequested++ }
        endpoint.open(descriptor)
        endpoint.startStream()
        endpoint.awaitState { it is SrtLinkState.Connected }
        assertEquals(1, keyFramesRequested)

        endpoint.write(frame(1), AUDIO)
        endpoint.write(frame(2), VIDEO)
        endpoint.write(frame(3, key = true), AUDIO) // an audio "key frame" opens nothing
        endpoint.write(frame(4, key = true), VIDEO)
        endpoint.write(frame(5), AUDIO)

        assertEquals(listOf("1:1:4", "2:0:5"), sinks[0].written.toList())
        endpoint.close()
    }

    @Test
    fun `close returns promptly even while an attempt hangs`() = runBlocking {
        val endpoint = endpoint({ FakeSink(onOpen = { kotlinx.coroutines.delay(60_000) }) })
        endpoint.open(descriptor) // gives up waiting after firstAttemptWaitMs

        val start = System.nanoTime()
        endpoint.close()
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("close took $elapsedMs ms", elapsedMs < 2_500)
        assertEquals(SrtLinkState.Idle, endpoint.linkStateFlow.value)
    }

    @Test
    fun `it can be opened again after a close`() = runBlocking {
        val endpoint = endpoint({ FakeSink() })
        repeat(3) {
            endpoint.open(descriptor)
            endpoint.awaitState { it is SrtLinkState.Connected }
            endpoint.close()
            assertEquals(SrtLinkState.Idle, endpoint.linkStateFlow.value)
        }
    }
}
