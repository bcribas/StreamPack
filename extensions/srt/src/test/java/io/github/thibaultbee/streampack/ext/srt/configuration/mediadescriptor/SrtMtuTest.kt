/*
 * Copyright 2026 Thibault B.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.thibaultbee.streampack.ext.srt.configuration.mediadescriptor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The MTU derivation. Pure arithmetic, so a wrong formula is caught here in a second rather than
 * as a stream that dies on its first frame.
 */
class SrtMtuTest {

    @Test
    fun `the approved table`() {
        // Between 1360 and 1500 nothing changes but the MSS: the payload stays at the SRT live
        // default, so the muxer is untouched. That is the common Starlink case.
        assertEquals(1316, SrtMtu.payloadSizeForMtu(1500))
        assertEquals(1316, SrtMtu.payloadSizeForMtu(1400))
        assertEquals(1316, SrtMtu.payloadSizeForMtu(1360))

        assertEquals(1128, SrtMtu.payloadSizeForMtu(1200))
        assertEquals(940, SrtMtu.payloadSizeForMtu(1000))
        assertEquals(752, SrtMtu.payloadSizeForMtu(800))
    }

    @Test
    fun `the step between 1359 and 1360 is where the muxer starts moving`() {
        assertEquals(1316, SrtMtu.payloadSizeForMtu(1360))
        assertEquals(1128, SrtMtu.payloadSizeForMtu(1359))
    }

    @Test
    fun `payload is always a whole number of TS packets`() {
        for (mtu in SrtMtu.MIN_MTU..SrtMtu.DEFAULT_MTU) {
            assertEquals(
                "payload for MTU $mtu is not a multiple of 188",
                0,
                SrtMtu.payloadSizeForMtu(mtu) % 188
            )
        }
    }

    @Test
    fun `payload always fits inside the mtu it was derived from`() {
        for (mtu in SrtMtu.MIN_MTU..SrtMtu.DEFAULT_MTU) {
            val payload = SrtMtu.payloadSizeForMtu(mtu)
            assert(payload + SrtMtu.SRT_HEADER_OVERHEAD <= mtu) {
                "payload $payload plus headers exceeds MTU $mtu"
            }
        }
    }

    @Test
    fun `the smallest usable mtu fits exactly one packet`() {
        assertEquals(232, SrtMtu.MIN_MTU)
        assertEquals(188, SrtMtu.payloadSizeForMtu(SrtMtu.MIN_MTU))
    }

    @Test
    fun `below the smallest usable mtu it throws rather than clamping`() {
        // Clamping would emit 232-byte datagrams for a smaller requested MTU, which breaks the
        // very invariant the setting exists to enforce.
        assertThrows(IllegalArgumentException::class.java) {
            SrtMtu.payloadSizeForMtu(SrtMtu.MIN_MTU - 1)
        }
    }

    @Test
    fun `a jumbo mtu is still capped at the SRT live maximum`() {
        // libsrt rejects SRTO_PAYLOADSIZE above 1456, so this cap is not cosmetic.
        assertEquals(1316, SrtMtu.payloadSizeForMtu(9000))
        assertEquals(7, SrtMtu.tsPacketsForMtu(9000))
    }

    @Test
    fun `coerce keeps stored values away from the throwing path`() {
        assertEquals(SrtMtu.UI_MIN_MTU, SrtMtu.coerceMtu(1))
        assertEquals(SrtMtu.UI_MIN_MTU, SrtMtu.coerceMtu(-9999))
        assertEquals(SrtMtu.DEFAULT_MTU, SrtMtu.coerceMtu(9000))
        assertEquals(1200, SrtMtu.coerceMtu(1200))
    }

    @Test
    fun `packets round-trip through payload size`() {
        for (mtu in intArrayOf(1500, 1400, 1360, 1200, 1000, 800, 600)) {
            val payload = SrtMtu.payloadSizeForMtu(mtu)
            assertEquals(SrtMtu.tsPacketsForMtu(mtu), SrtMtu.tsPacketsForPayloadSize(payload))
        }
    }

    @Test
    fun `the default is the SRT live default`() {
        assertEquals(1316, SrtMtu.DEFAULT_PAYLOAD_SIZE)
        assertEquals(SrtMtu.DEFAULT_PAYLOAD_SIZE, SrtMtu.payloadSizeForMtu(SrtMtu.DEFAULT_MTU))
    }
}
