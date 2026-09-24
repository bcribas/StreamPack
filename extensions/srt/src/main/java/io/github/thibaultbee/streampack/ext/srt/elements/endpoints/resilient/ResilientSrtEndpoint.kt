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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
) : IEndpointInternal, WithEndpointMetrics<Any> {

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val defaultMaxOutputPacketNumber = (muxer as? TsMuxer)?.maxOutputPacketNumber

    /** Guards the muxer and everything the muxing thread reads below. */
    private val muxLock = Any()

    /** Serializes publishing a new sink against starting the stream on the current one. */
    private val sinkMutex = Mutex()

    @Volatile
    private var liveSink: ISinkInternal? = null
    private var awaitingKeyFrame = true
    private var writingKeyFrame = false
    private var keyFrameRejected = false
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
                sinkMutex.withLock {
                    if (streamStarted) {
                        sink.configure(SinkConfiguration(synchronized(muxLock) { muxer.streamConfigs }))
                        sink.startStream()
                    }
                    while (failures.tryReceive().isSuccess) Unit
                    synchronized(muxLock) {
                        awaitingKeyFrame = true
                        liveSink = sink
                    }
                }
                everConnected = true
                failedAttempts = 0
                epoch++
                _linkStateFlow.value = SrtLinkState.Connected(epoch)
                firstAttempt.complete(Unit)
                Logger.i(TAG, "SRT link up (connection $epoch)")
                keyFrameRequester?.let { runCatching { it() } }

                lastError = merge(
                    failures.receiveAsFlow(),
                    sink.isOpenFlow.filter { !it }.map { "Connection closed" }
                ).first()
                Logger.w(TAG, "SRT link down: $lastError")
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                failedAttempts++
                lastError = t.message ?: t.javaClass.simpleName
                Logger.w(TAG, "SRT connection attempt $failedAttempts failed: $lastError")
                firstAttempt.complete(Unit)
            } finally {
                synchronized(muxLock) {
                    if (liveSink === sink) {
                        liveSink = null
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

    /** Muxer output: to the tap always, to the socket while connected and past a key frame. */
    private fun deliver(packet: Packet) {
        tsTap?.let { tap -> runCatching { tap.onTsPackets(packet.buffer) } }
        val sink = liveSink ?: return
        if (awaitingKeyFrame && !writingKeyFrame) return
        try {
            val written = runBlocking { sink.write(packet) }
            // The sink drops what predates its connection; a dropped key frame must not open the
            // gate, or the receiver would start mid-GOP.
            if (written < 0 && writingKeyFrame) keyFrameRejected = true
        } catch (t: Throwable) {
            liveSink = null
            awaitingKeyFrame = true
            failures.trySend(t.message ?: t.javaClass.simpleName)
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
                if (writingKeyFrame && awaitingKeyFrame && liveSink != null) {
                    if (keyFrameRejected) askForKeyFrame = true else awaitingKeyFrame = false
                }
                writingKeyFrame = false
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
        synchronized(muxLock) { liveSink = null }
        _linkStateFlow.value = SrtLinkState.Idle
    }

    private companion object {
        const val TAG = "ResilientSrtEndpoint"
    }
}
