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
package io.github.thibaultbee.streampack.ext.srt.configuration.mediadescriptor

import io.github.thibaultbee.streampack.core.elements.endpoints.composites.muxers.ts.packets.TS
import io.github.thibaultbee.streampack.core.elements.endpoints.composites.muxers.ts.utils.MuxerConst

/**
 * Turns a link MTU into the SRT payload size and TS packet count that fit inside it.
 *
 * Needed for links whose path MTU is smaller than a normal 1500 — Starlink, some mobile APNs,
 * VPNs — where a datagram larger than the path is fragmented or dropped outright.
 *
 * Setting `SRTO_MSS` alone is not enough: SRT requires `payloadsize <= MSS - 44`, so lowering the
 * MSS without lowering the payload is simply rejected. And the payload has to stay a whole number
 * of 188-byte MPEG-TS packets, which is why the muxer's packet count is derived here too.
 */
object SrtMtu {
    /** IPv4 header 20 + UDP header 8 + SRT header 16. */
    const val SRT_HEADER_OVERHEAD = 44

    /**
     * The muxer's own maximum, which is also the SRT live default: 7 x 188 = 1316.
     */
    const val MAX_TS_PACKETS = MuxerConst.MAX_OUTPUT_PACKET_NUMBER

    /** libsrt rejects `SRTO_PAYLOADSIZE` above this. */
    const val SRT_LIVE_MAX_PAYLOAD_SIZE = 1456

    const val DEFAULT_PAYLOAD_SIZE = MAX_TS_PACKETS * TS.PACKET_SIZE

    const val DEFAULT_MTU = 1500

    /** The smallest MTU that still fits one TS packet plus headers. */
    const val MIN_MTU = TS.PACKET_SIZE + SRT_HEADER_OVERHEAD

    /**
     * The smallest MTU worth offering. Below the IPv4 minimum reassembly buffer the header
     * overhead exceeds 10% and the packet rate roughly triples.
     */
    const val UI_MIN_MTU = 576

    /**
     * How many TS packets fit in one datagram of [mtu] bytes.
     *
     * Clamped upwards at [MAX_TS_PACKETS] because libsrt rejects a payload above
     * [SRT_LIVE_MAX_PAYLOAD_SIZE], so a jumbo MTU must still produce 1316.
     */
    fun tsPacketsForMtu(mtu: Int): Int {
        require(mtu >= MIN_MTU) {
            "MTU must be at least $MIN_MTU to fit one TS packet, was $mtu"
        }
        return ((mtu - SRT_HEADER_OVERHEAD) / TS.PACKET_SIZE).coerceAtMost(MAX_TS_PACKETS)
    }

    /**
     * The SRT payload size for [mtu]: always a whole number of TS packets.
     *
     * Deliberately throws below [MIN_MTU] rather than clamping. Clamping to one packet would emit
     * 232-byte datagrams for a smaller requested MTU, breaking the very invariant this exists to
     * enforce. Callers reading stored settings should run [coerceMtu] first.
     */
    fun payloadSizeForMtu(mtu: Int): Int = tsPacketsForMtu(mtu) * TS.PACKET_SIZE

    fun tsPacketsForPayloadSize(payloadSize: Int): Int = payloadSize / TS.PACKET_SIZE

    /**
     * Brings any stored or typed value into the range the UI offers, so a corrupt preference can
     * never reach the throwing path above.
     */
    fun coerceMtu(mtu: Int): Int = mtu.coerceIn(UI_MIN_MTU, DEFAULT_MTU)

    /**
     * A one-line explanation of what an MTU turns into, for the settings summary — so the operator
     * sees the derivation instead of guessing where the steps are.
     */
    fun describe(mtu: Int): String {
        val coerced = coerceMtu(mtu)
        val packets = tsPacketsForMtu(coerced)
        return "$coerced -> ${packets * TS.PACKET_SIZE} B payload ($packets x ${TS.PACKET_SIZE})"
    }
}
