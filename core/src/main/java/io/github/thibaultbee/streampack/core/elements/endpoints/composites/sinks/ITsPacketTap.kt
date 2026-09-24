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

import java.nio.ByteBuffer

/**
 * Receives a copy of an MPEG-TS stream as it is muxed, for example to record it to a file.
 *
 * Every call happens on the muxing thread, inside the muxer's lock, so the order of the calls is
 * exactly the order of the bytes. An implementation must therefore never block and never throw:
 * whatever it does to the stream would otherwise be done to the live too.
 */
interface ITsPacketTap {
    /**
     * One datagram of whole 188-byte TS packets.
     *
     * The buffer comes from a pool and is reused as soon as this returns: copy what is needed,
     * and leave its position and limit alone.
     */
    fun onTsPackets(buffer: ByteBuffer)

    /**
     * The next bytes start a video key frame, preceded by a fresh PAT and PMT: a point where a
     * new file can begin and play on its own.
     */
    fun onRandomAccessPoint(ptsInUs: Long)

    /**
     * The stream that follows does not continue the previous one (a new muxer, new continuity
     * counters). A recorder should start a new file at the next random access point.
     */
    fun onDiscontinuity() {}
}

/**
 * A sink that wants to know where random access points are, see
 * [ITsPacketTap.onRandomAccessPoint]. [io.github.thibaultbee.streampack.core.elements.endpoints.composites.CompositeEndpoint]
 * forwards them to its sink when the sink implements this.
 */
interface IRandomAccessPointAware {
    fun onRandomAccessPoint(ptsInUs: Long)
}
