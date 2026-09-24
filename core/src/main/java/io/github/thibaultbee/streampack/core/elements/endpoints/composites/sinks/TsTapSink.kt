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
package io.github.thibaultbee.streampack.core.elements.endpoints.composites.sinks

import io.github.thibaultbee.streampack.core.configuration.mediadescriptor.MediaDescriptor
import io.github.thibaultbee.streampack.core.elements.endpoints.MediaSinkType
import io.github.thibaultbee.streampack.core.elements.endpoints.composites.data.Packet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A sink that hands every TS datagram, and every random access point, to an [ITsPacketTap].
 *
 * It does no I/O itself: where the bytes go, and how failures there are handled, is the tap's
 * business. Opening only marks it open; the descriptor is not used, so any one will do.
 */
class TsTapSink(private val tap: ITsPacketTap) : AbstractSink(), IRandomAccessPointAware {
    override val supportedSinkTypes: List<MediaSinkType> = MediaSinkType.entries

    private val _isOpenFlow = MutableStateFlow(false)
    override val isOpenFlow = _isOpenFlow.asStateFlow()

    override suspend fun openImpl(mediaDescriptor: MediaDescriptor) {
        _isOpenFlow.value = true
    }

    override fun configure(config: SinkConfiguration) = Unit

    override suspend fun write(packet: Packet): Int {
        val size = packet.buffer.remaining()
        tap.onTsPackets(packet.buffer)
        return size
    }

    override fun onRandomAccessPoint(ptsInUs: Long) = tap.onRandomAccessPoint(ptsInUs)

    override suspend fun startStream() = Unit

    override suspend fun stopStream() = Unit

    override suspend fun close() {
        _isOpenFlow.value = false
    }
}
