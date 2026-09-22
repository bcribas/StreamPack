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
package io.github.thibaultbee.streampack.core.elements.sources.video.camera.utils

import android.util.Size
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Preview size selection, pinned against a real device's list.
 *
 * The sizes below are the SurfaceHolder outputs actually reported by a Samsung Galaxy S20 FE
 * (SM-G780F), read from `dumpsys media.camera`. They are what makes the aspect filter necessary
 * rather than defensive: the list has no 16:9 size anywhere near 540 high, so a target of 960x540
 * sits closer in area to the 4:3 960x720 than to the 16:9 640x360.
 */
@RunWith(AndroidJUnit4::class)
class CameraSizesTest {

    private val deviceSizes = listOf(
        Size(4032, 3024), Size(4032, 2268), Size(4032, 1816), Size(3024, 3024),
        Size(3840, 2160), Size(2400, 1080), Size(1920, 1440), Size(1920, 1080),
        Size(1920, 864), Size(1440, 1080), Size(1088, 1088), Size(1280, 720),
        Size(960, 720), Size(720, 480), Size(640, 480), Size(640, 360),
        Size(352, 288), Size(320, 240), Size(256, 144), Size(176, 144)
    )

    @Test
    fun `an exact match still wins`() {
        assertEquals(
            Size(1920, 1080),
            CameraSizes.closestSameShape(deviceSizes, Size(1920, 1080))
        )
    }

    @Test
    fun `a reduced 16x9 target does not fall back to a 4x3 size`() {
        // Area-only selection picks 960x720 here: |691200-518400| = 172800 beats
        // |230400-518400| = 288000. That is the bug this function exists to avoid.
        assertEquals(
            Size(640, 360),
            CameraSizes.closestSameShape(deviceSizes, Size(960, 540))
        )
    }

    @Test
    fun `720p target lands on the real 720p output`() {
        assertEquals(
            Size(1280, 720),
            CameraSizes.closestSameShape(deviceSizes, Size(1280, 720))
        )
    }

    @Test
    fun `360p target lands on the real 360p output`() {
        assertEquals(
            Size(640, 360),
            CameraSizes.closestSameShape(deviceSizes, Size(640, 360))
        )
    }

    @Test
    fun `a 4x3 target picks a 4x3 output`() {
        assertEquals(
            Size(960, 720),
            CameraSizes.closestSameShape(deviceSizes, Size(1000, 750))
        )
    }

    @Test
    fun `a shape nothing matches falls back to the whole list`() {
        // 3:1 is not available at all, so the closest by area is used rather than nothing.
        val result = CameraSizes.closestSameShape(deviceSizes, Size(1200, 400))
        assertEquals(Size(720, 480), result)
    }

    @Test
    fun `an empty list returns the target untouched`() {
        assertEquals(Size(1280, 720), CameraSizes.closestSameShape(emptyList(), Size(1280, 720)))
    }

    @Test
    fun `a degenerate target returns itself`() {
        assertEquals(Size(0, 0), CameraSizes.closestSameShape(deviceSizes, Size(0, 0)))
    }
}
