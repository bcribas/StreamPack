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
package io.github.thibaultbee.streampack.core.elements.endpoints.composites.muxers.ts.data

/**
 * How many MPEG-TS packets the muxer should put in one output datagram.
 *
 * Carried as custom data on the media descriptor rather than as a constructor parameter, because
 * the endpoint — muxer and sink both — is built reflectively and cached for the life of the
 * process. A constructor value would go stale as soon as the user changed the setting between two
 * streams.
 *
 * Absent means the default, which is what every existing caller gets.
 */
data class TsPacketizationInfo(val maxOutputPacketNumber: Int) {
    init {
        require(maxOutputPacketNumber >= 1) {
            "maxOutputPacketNumber must be at least 1, was $maxOutputPacketNumber"
        }
    }
}
