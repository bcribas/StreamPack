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

import io.github.thibaultbee.streampack.core.configuration.mediadescriptor.MediaDescriptor
import io.github.thibaultbee.streampack.core.configuration.mediadescriptor.createDefaultTsServiceInfo
import io.github.thibaultbee.streampack.core.elements.data.Frame
import io.github.thibaultbee.streampack.core.elements.encoders.CodecConfig
import io.github.thibaultbee.streampack.core.elements.endpoints.IEndpoint
import io.github.thibaultbee.streampack.core.elements.endpoints.IEndpointInternal
import io.github.thibaultbee.streampack.core.elements.endpoints.composites.CompositeEndpoint
import io.github.thibaultbee.streampack.core.elements.endpoints.composites.data.Packet
import io.github.thibaultbee.streampack.core.elements.endpoints.composites.data.SrtPacket
import io.github.thibaultbee.streampack.core.elements.endpoints.composites.muxers.IMuxerInternal
import io.github.thibaultbee.streampack.core.elements.endpoints.composites.muxers.ts.TsMuxer
import io.github.thibaultbee.streampack.core.elements.endpoints.composites.muxers.ts.data.TSServiceInfo
import io.github.thibaultbee.streampack.core.elements.endpoints.composites.muxers.ts.data.TsPacketizationInfo
import io.github.thibaultbee.streampack.core.elements.endpoints.composites.sinks.ISinkInternal
import io.github.thibaultbee.streampack.core.elements.endpoints.composites.sinks.ITsPacketTap
import io.github.thibaultbee.streampack.core.elements.endpoints.composites.sinks.SinkConfiguration
import io.github.thibaultbee.streampack.core.elements.metrics.EmptyEndpointMetrics
import io.github.thibaultbee.streampack.core.elements.metrics.EndpointMetrics
import io.github.thibaultbee.streampack.core.elements.metrics.WithEndpointMetrics
import io.github.thibaultbee.streampack.core.logger.Logger
import io.github.thibaultbee.streampack.ext.srt.configuration.mediadescriptor.SrtMediaDescriptor
import io.github.thibaultbee.streampack.ext.srt.elements.endpoints.composites.sinks.SrtSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * The state of the network leg of a [ResilientSrtEndpoint].
 */
sealed interface SrtLinkState {
    /** Not opened. */
    data object Idle : SrtLinkState

    /**
     * Trying to connect, first or again.
     *
     * @param attempt consecutive failed attempts so far, starting at 1
     * @param everConnected whether this session was connected before, i.e. this is a reconnection
     * @param lastError why the previous attempt or connection failed
     */
    data class Connecting(
        val attempt: Int,
        val everConnected: Boolean,
        val lastError: String?
    ) : SrtLinkState

    /**
     * Connected. [epoch] counts the connections of this session, so an observer can tell a
     * reconnection from the same connection.
     */
    data class Connected(val epoch: Int) : SrtLinkState
}

/**
 * An SRT endpoint whose network leg reconnects by itself, underneath the encoders.
 *
 * The built-in SRT endpoint closes when the connection breaks, and the output then stops its
 * encoders; the app has to stop, close, reopen and restart everything. Here the endpoint stays
 * open from [open] to [close]: a broken connection only closes the SRT socket, a loop opens a new
 * one with backoff, and the encoders never notice. [linkStateFlow] says where the link stands.
 * [open] never fails because of the network, only because of a configuration that can never
 * connect.
 *
 * It owns one TS muxer. Every datagram goes first to [tsTap], always, then to the SRT socket
 * while there is one: a recording fed by the tap has no gap when the network drops. After a
 * reconnection nothing goes to the socket until the next video key frame, so the new session
 * starts where a player can; [keyFrameRequester] is asked for one right away.
 *
 * The encoders never wait for the network. The muxer's output is queued and one sender per
 * connection writes it to the socket: a socket that cannot keep up used to hold the muxer's lock
 * for seconds at a time, and with it both encoders, so the picture went down to a few frames a
 * second and nothing went out. When the queue is full the rest waits for the next key frame,
 * where the receiver picks up cleanly.
 *
 * A link that is connected but not flowing is replaced: no datagram accepted by the socket, no
 * answer from the server, or a round trip above the SRT latency (nothing arrives in time), for
 * [stallTimeoutMs] while there was something to send. That is what
 * a router switching provider looks like: the old path keeps the socket "connected" and only a
 * new socket, on a new port, goes out the new way. [networkChanged] does the same at once.
 *
 * @param sinkFactory a new SRT sink for every connection attempt
 * @param muxer the muxer; a [TsMuxer] is set up with the descriptor's service on every open
 * @param validator throws for a descriptor that can never connect
 */
class ResilientSrtEndpoint(
    ioDispatcher: CoroutineDispatcher,
    private val sinkFactory: () -> ISinkInternal = { SrtSink(ioDispatcher, closeOnWriteError = false) },
    private val muxer: IMuxerInternal = TsMuxer(),
    private val validator: (MediaDescriptor) -> Unit = { SrtSink.validate(SrtMediaDescriptor(it)) },
    private val firstAttemptWaitMs: Long = 3_500,
    private val backoffMs: List<Long> = listOf(500, 1_000, 2_000, 3_000, 5_000),
    private val sinkCloseTimeoutMs: Long = 2_000,
    /** A connected link with nothing accepted or confirmed for this long, while sending, is replaced. */
    private val stallTimeoutMs: Long = 8_000,
    private val stallCheckMs: Long = 1_000,
    /** Bytes waiting for the socket beyond which the rest waits for the next key frame. */
    private val maxQueuedBytes: Long = 4L shl 20,
    /** How many answers the server has sent on [ISinkInternal] so far (SRT ACKs), or null if unknown. */
    private val peerAnswers: (ISinkInternal) -> Long? = { sink ->
        (sink as? SrtSink)?.let { runCatching { it.metrics.packetsReadACK.toLong() }.getOrNull() }
    },
    /** The round trip to the server now, in ms, or null if unknown. */
    private val roundTripMs: (ISinkInternal) -> Long? = { sink ->
        (sink as? SrtSink)?.let { runCatching { it.metrics.rawMetrics.bstatsOrNull(false)?.msRTT?.toLong() }.getOrNull() }
    },
    /**
     * The round trip above which nothing arrives in time: the SRT latency of the descriptor. A
     * link above it for [stallTimeoutMs] is replaced; null leaves the round trip out.
     */
    private val roundTripLimitMs: (MediaDescriptor) -> Long? = { descriptor ->
        runCatching { SrtMediaDescriptor(descriptor).srtUrl.latencyInMs?.toLong() }.getOrNull()
    },
) : IEndpointInternal, WithEndpointMetrics<Any> {

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val defaultMaxOutputPacketNumber = (muxer as? TsMuxer)?.maxOutputPacketNumber

    /** Guards the muxer and everything the muxing thread reads below. */
    private val muxLock = Any()

    /** Serializes publishing a new sink against starting the stream on the current one. */
    private val sinkMutex = Mutex()

    @Volatile
    private var liveSink: ISinkInternal? = null

    /** The queue of the connection in [liveSink]; set and cleared with it, under [muxLock]. */
    private var outbox: Outbox? = null
    private var awaitingKeyFrame = true
    private var writingKeyFrame = false
    private var keyFrameRejected = false

    /** The queue overflowed: a key frame is asked for once the muxer's lock is released. */
    private var keyFrameWanted = false
    private var streamStarted = false

    private val failures = Channel<String>(Channel.CONFLATED)
    private val retrySignal = Channel<Unit>(Channel.CONFLATED)
    private var loopJob: Job? = null

    /** Receives a copy of every TS datagram, connected or not. Must not block or throw. */
    @Volatile
    var tsTap: ITsPacketTap? = null

    /** Asked for a key frame when a new connection starts, so the receiver can begin at once. */
    @Volatile
    var keyFrameRequester: (() -> Unit)? = null

    private val _linkStateFlow = MutableStateFlow<SrtLinkState>(SrtLinkState.Idle)
    val linkStateFlow: StateFlow<SrtLinkState> = _linkStateFlow.asStateFlow()

    private val _isOpenFlow = MutableStateFlow(false)
    override val isOpenFlow: StateFlow<Boolean> = _isOpenFlow.asStateFlow()

    /** Link errors never land here: they are the loop's business, not the output's. */
    override val throwableFlow: StateFlow<Throwable?> = MutableStateFlow<Throwable?>(null).asStateFlow()

    override val info: IEndpoint.IEndpointInfo by lazy { CompositeEndpoint.EndpointInfo(muxer.info) }

    override fun getInfo(type: MediaDescriptor.Type) = info

    /** The connected sink's, or empty while there is none (regulators then skip their turn). */
    @Suppress("UNCHECKED_CAST")
    override val metrics: EndpointMetrics<Any>
        get() = runCatching { (liveSink as? WithEndpointMetrics<Any>)?.metrics }.getOrNull()
            ?: EmptyEndpointMetrics

    init {
        muxer.listener = object : IMuxerInternal.IMuxerListener {
            // Always called with muxLock held: from write, addStreams or startStream below.
            override fun onOutputFrame(packet: Packet) = deliver(packet)

            override fun onRandomAccessPoint(ptsInUs: Long) {
                writingKeyFrame = true
                tsTap?.let { tap -> runCatching { tap.onRandomAccessPoint(ptsInUs) } }
            }
        }
    }

    /** Skips the current backoff, e.g. when the network comes back. */
    fun retryNow() {
        retrySignal.trySend(Unit)
    }

    /**
     * The phone moved to another network: the socket stays on the old one, so a connected link is
     * replaced at once rather than after it stalls.
     */
    fun networkChanged() {
        if (liveSink != null) failures.trySend("The network changed")
        retryNow()
    }

    private val droppedBytes = AtomicLong(0)

    /** Bytes the network could not take in time and that were dropped, since the endpoint was created. */
    val bytesDropped: Long get() = droppedBytes.get()

    /** One connection's queue, drained by its sender. */
    private class Outbox {
        val queue = Channel<Outgoing>(Channel.UNLIMITED)
        val queuedBytes = AtomicLong(0)

        /** Bytes the muxer wanted to send on this connection, queued or dropped. */
        val demandBytes = AtomicLong(0)

        /** Bytes the socket accepted. */
        val acceptedBytes = AtomicLong(0)
    }

    /** A datagram on its way; [opensGate] when it is part of the key frame the session starts on. */
    private class Outgoing(val packet: Packet, val opensGate: Boolean)

    override suspend fun open(descriptor: MediaDescriptor) {
        if (_isOpenFlow.value) {
            Logger.w(TAG, "Already opened")
            return
        }
        validator(descriptor)
        synchronized(muxLock) {
            (muxer as? TsMuxer)?.let { tsMuxer ->
                tsMuxer.removeServices()
                tsMuxer.addService(
                    descriptor.getCustomData(TSServiceInfo::class.java) ?: createDefaultTsServiceInfo()
                )
                val packetization = descriptor.getCustomData(TsPacketizationInfo::class.java)
                (packetization?.maxOutputPacketNumber ?: defaultMaxOutputPacketNumber)?.let {
                    tsMuxer.maxOutputPacketNumber = it
                }
            }
            awaitingKeyFrame = true
        }
        while (failures.tryReceive().isSuccess) Unit
        _isOpenFlow.value = true

        val firstAttempt = CompletableDeferred<Unit>()
        loopJob = scope.launch { linkLoop(descriptor, firstAttempt) }
        // Up to a first attempt, so a working network is connected when open returns, but never
        // failing on it: the stream runs either way and the loop keeps trying.
        withTimeoutOrNull(firstAttemptWaitMs) { firstAttempt.await() }
    }

    private suspend fun linkLoop(descriptor: MediaDescriptor, firstAttempt: CompletableDeferred<Unit>) {
        var failedAttempts = 0
        var everConnected = false
        var epoch = 0
        var lastError: String? = null
        while (currentCoroutineContext().isActive) {
            _linkStateFlow.value = SrtLinkState.Connecting(failedAttempts + 1, everConnected, lastError)
            val sink = sinkFactory()
            try {
                sink.open(descriptor)
                // A connect that ignored a close() must not publish its socket afterwards
                currentCoroutineContext().ensureActive()
                val box = Outbox()
                sinkMutex.withLock {
                    if (streamStarted) {
                        sink.configure(SinkConfiguration(synchronized(muxLock) { muxer.streamConfigs }))
                        sink.startStream()
                    }
                    while (failures.tryReceive().isSuccess) Unit
                    synchronized(muxLock) {
                        awaitingKeyFrame = true
                        outbox = box
                        liveSink = sink
                    }
                }
                val sender = scope.launch { send(sink, box) }
                everConnected = true
                failedAttempts = 0
                epoch++
                _linkStateFlow.value = SrtLinkState.Connected(epoch)
                firstAttempt.complete(Unit)
                Logger.i(TAG, "SRT link up (connection $epoch)")
                keyFrameRequester?.let { runCatching { it() } }

                try {
                    lastError = merge(
                        failures.receiveAsFlow(),
                        sink.isOpenFlow.filter { !it }.map { "Connection closed" },
                        stalls(sink, box, roundTripLimitMs(descriptor))
                    ).first()
                } finally {
                    sender.cancel()
                }
                Logger.w(TAG, "SRT link down: $lastError")
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                failedAttempts++
                lastError = t.message ?: t.javaClass.simpleName
                Logger.w(TAG, "SRT connection attempt $failedAttempts failed: $lastError")
                firstAttempt.complete(Unit)
            } finally {
                // Nothing may go to this sink any more, then it closes: a send still waiting on it
                // wakes up with an error instead of waiting for a socket nobody will close
                synchronized(muxLock) {
                    if (liveSink === sink) {
                        liveSink = null
                        outbox = null
                        awaitingKeyFrame = true
                    }
                }
                withContext(NonCancellable) {
                    withTimeoutOrNull(sinkCloseTimeoutMs) { runCatching { sink.close() } }
                }
            }
            val wait = backoffMs[failedAttempts.coerceIn(0, backoffMs.size - 1)]
            withTimeoutOrNull(wait) { retrySignal.receive() }
        }
    }

    /**
     * Muxer output, with [muxLock] held: to the tap always, to the queue while connected and past
     * a key frame. Never waits for the network.
     */
    private fun deliver(packet: Packet) {
        tsTap?.let { tap -> runCatching { tap.onTsPackets(packet.buffer) } }
        val box = outbox ?: return
        if (awaitingKeyFrame && !writingKeyFrame) return
        val size = packet.buffer.remaining().toLong()
        box.demandBytes.addAndGet(size)
        if (box.queuedBytes.get() + size > maxQueuedBytes) {
            // The socket cannot keep up: what is queued still goes, the rest waits for a key frame
            droppedBytes.addAndGet(size)
            if (!awaitingKeyFrame) {
                awaitingKeyFrame = true
                keyFrameWanted = true
                Logger.w(TAG, "The network cannot keep up: dropping to the next key frame")
            }
            if (writingKeyFrame) keyFrameRejected = true
            return
        }
        box.queuedBytes.addAndGet(size)
        box.queue.trySend(Outgoing(copyOf(packet), opensGate = awaitingKeyFrame && writingKeyFrame))
    }

    /**
     * The muxer reuses its buffers once the listener returns: a queued datagram is a copy, in a
     * direct buffer as the SRT socket requires, taken from [spareBuffers] when one is there.
     */
    private fun copyOf(packet: Packet): Packet {
        val source = packet.buffer.duplicate()
        val size = source.remaining()
        val copy = (spareBuffers.poll()?.takeIf { it.capacity() >= size } ?: ByteBuffer.allocateDirect(
            maxOf(size, DATAGRAM_CAPACITY)
        )).apply {
            clear()
            put(source)
            flip()
        }
        return if (packet is SrtPacket) {
            SrtPacket(copy, packet.isFirstPacketFrame, packet.isLastPacketFrame, packet.ts)
        } else {
            Packet(copy, packet.ts)
        }
    }

    /** Direct buffers back from the socket, for the next copies: allocating them is slow. */
    private val spareBuffers = java.util.concurrent.ConcurrentLinkedQueue<ByteBuffer>()

    private fun recycle(buffer: ByteBuffer) {
        if (buffer.isDirect && buffer.capacity() == DATAGRAM_CAPACITY && spareBuffers.size < MAX_SPARE_BUFFERS) {
            spareBuffers.offer(buffer)
        }
    }

    /** Drains [box] into [sink] until the connection ends. */
    private suspend fun send(sink: ISinkInternal, box: Outbox) {
        for (outgoing in box.queue) {
            val size = outgoing.packet.buffer.remaining().toLong()
            val written = try {
                sink.write(outgoing.packet)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                failures.trySend(t.message ?: t.javaClass.simpleName)
                return
            } finally {
                box.queuedBytes.addAndGet(-size)
                recycle(outgoing.packet.buffer)
            }
            box.acceptedBytes.addAndGet(size)
            // The sink drops what predates its connection; a dropped key frame must not open the
            // gate, or the receiver would start mid-GOP
            if (written < 0 && outgoing.opensGate) {
                synchronized(muxLock) { if (outbox === box) awaitingKeyFrame = true }
                while (true) {
                    val stale = box.queue.tryReceive().getOrNull() ?: break
                    box.queuedBytes.addAndGet(-stale.packet.buffer.remaining().toLong())
                    recycle(stale.packet.buffer)
                }
                keyFrameRequester?.let { runCatching { it() } }
            }
        }
    }

    /**
     * Emits why the link is stalled: nothing accepted by the socket, no answer from the server,
     * or a round trip above [roundTripLimit] (on 2026-10-05 a Wi-Fi took it from 2 to 36 s for
     * minutes, answering just enough to look alive), for [stallTimeoutMs] while there was
     * something to send.
     */
    private fun stalls(sink: ISinkInternal, box: Outbox, roundTripLimit: Long?) = flow {
        var slowSince: Long? = null
        var lastAccepted = box.acceptedBytes.get()
        var lastAnswers = peerAnswers(sink)
        var lastDemand = box.demandBytes.get()
        var acceptedAt = System.nanoTime()
        var answeredAt = acceptedAt
        while (true) {
            delay(stallCheckMs)
            val now = System.nanoTime()
            val demand = box.demandBytes.get()
            val wanted = demand != lastDemand || box.queuedBytes.get() > 0
            lastDemand = demand
            val accepted = box.acceptedBytes.get()
            if (accepted != lastAccepted || !wanted) {
                lastAccepted = accepted
                acceptedAt = now
            }
            val answers = peerAnswers(sink)
            if (answers == null || answers != lastAnswers || !wanted) {
                lastAnswers = answers
                answeredAt = now
            }
            val roundTrip = roundTripLimit?.let { roundTripMs(sink) }
            slowSince = if (wanted && roundTrip != null && roundTrip > roundTripLimit!!) slowSince ?: now else null
            val limitNs = stallTimeoutMs * 1_000_000
            when {
                now - acceptedAt >= limitNs -> emit("The socket took nothing for ${duration(stallTimeoutMs)}")
                now - answeredAt >= limitNs -> emit("No answer from the server for ${duration(stallTimeoutMs)}")
                slowSince?.let { now - it >= limitNs } == true ->
                    emit("Round trip above $roundTripLimit ms for ${duration(stallTimeoutMs)} ($roundTrip ms)")
            }
        }
    }

    override suspend fun write(frame: Frame, streamPid: Int) {
        var askForKeyFrame = false
        synchronized(muxLock) {
            writingKeyFrame = false
            keyFrameRejected = false
            try {
                muxer.write(frame, streamPid)
            } finally {
                if (writingKeyFrame && awaitingKeyFrame && outbox != null) {
                    if (keyFrameRejected) askForKeyFrame = true else awaitingKeyFrame = false
                }
                writingKeyFrame = false
                if (keyFrameWanted) {
                    keyFrameWanted = false
                    askForKeyFrame = true
                }
            }
        }
        if (askForKeyFrame) keyFrameRequester?.let { runCatching { it() } }
    }

    override suspend fun addStreams(streamConfigs: List<CodecConfig>): Map<CodecConfig, Int> =
        synchronized(muxLock) { muxer.addStreams(streamConfigs) }

    override suspend fun addStream(streamConfig: CodecConfig): Int =
        synchronized(muxLock) { muxer.addStream(streamConfig) }

    override suspend fun startStream() {
        sinkMutex.withLock {
            synchronized(muxLock) {
                muxer.startStream()
                streamStarted = true
            }
            liveSink?.let { sink ->
                try {
                    sink.configure(SinkConfiguration(synchronized(muxLock) { muxer.streamConfigs }))
                    sink.startStream()
                } catch (t: Throwable) {
                    failures.trySend(t.message ?: t.javaClass.simpleName)
                }
            }
        }
    }

    /** Stops muxing but keeps the connection, as the built-in SRT endpoint does. */
    override suspend fun stopStream() {
        synchronized(muxLock) {
            streamStarted = false
            muxer.stopStream()
        }
        runCatching { liveSink?.stopStream() }
    }

    override suspend fun close() {
        _isOpenFlow.value = false
        val job = loopJob
        loopJob = null
        job?.cancel()
        withTimeoutOrNull(sinkCloseTimeoutMs + 500) { job?.join() }
        synchronized(muxLock) {
            liveSink = null
            outbox = null
        }
        _linkStateFlow.value = SrtLinkState.Idle
    }

    private companion object {
        const val TAG = "ResilientSrtEndpoint"

        fun duration(ms: Long) = if (ms % 1000 == 0L) "${ms / 1000} s" else "$ms ms"

        /** Room for any datagram: SRT's payload is at most 1456 bytes. */
        const val DATAGRAM_CAPACITY = 1500

        /** About 2 MB of spare direct buffers at most. */
        const val MAX_SPARE_BUFFERS = 1400
    }
}
