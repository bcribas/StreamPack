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
package io.github.thibaultbee.streampack.ext.srt.elements.endpoints.composites.sinks

import kotlinx.coroutines.CoroutineDispatcher
import org.junit.Test

class SrtSinkTest {
    @Test
    fun `the core can still build it by reflection with the dispatcher alone`() {
        // CompositeEndpoints.createSrtSink looks this exact constructor up. A default parameter
        // without @JvmOverloads removes it and every SRT live crashes on open.
        SrtSink::class.java.getConstructor(CoroutineDispatcher::class.java)
    }
}
