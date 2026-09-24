package io.github.thibaultbee.streampack.core.elements.endpoints

import android.content.Context
import android.media.MediaFormat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.thibaultbee.streampack.core.configuration.mediadescriptor.UriMediaDescriptor
import io.github.thibaultbee.streampack.core.elements.endpoints.composites.CompositeEndpoint
import io.github.thibaultbee.streampack.core.elements.endpoints.composites.muxers.ts.TsMuxer
import io.github.thibaultbee.streampack.core.elements.endpoints.composites.sinks.FakeSink
import io.github.thibaultbee.streampack.core.elements.utils.DescriptorUtils
import io.github.thibaultbee.streampack.core.elements.utils.FakeFrameFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DynamicEndpointTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `isOpenFlow test`() = runTest {
        val dynamicEndpoint = DynamicEndpoint(context, Dispatchers.Default, Dispatchers.IO)
        assertFalse(dynamicEndpoint.isOpenFlow.value)
        dynamicEndpoint.open(DescriptorUtils.createFileDescriptor("dynamic.ts"))
        assertTrue(dynamicEndpoint.isOpenFlow.value)
        dynamicEndpoint.close()
    }

    @Test
    fun `test open mp4 file descriptor`() = runTest {
        val dynamicEndpoint = DynamicEndpoint(context, Dispatchers.Default, Dispatchers.IO)
        dynamicEndpoint.open(DescriptorUtils.createFileDescriptor("dynamic.mp4"))
        assertTrue(dynamicEndpoint.isOpenFlow.value)
        dynamicEndpoint.close()
    }

    @Test
    fun `test open ts file descriptor`() = runTest {
        val dynamicEndpoint = DynamicEndpoint(context, Dispatchers.Default, Dispatchers.IO)
        dynamicEndpoint.open(DescriptorUtils.createFileDescriptor("dynamic.ts"))
        assertTrue(dynamicEndpoint.isOpenFlow.value)
        dynamicEndpoint.close()
    }

    @Test
    fun `test open flv file descriptor`() = runTest {
        val dynamicEndpoint = DynamicEndpoint(context, Dispatchers.Default, Dispatchers.IO)
        dynamicEndpoint.open(DescriptorUtils.createFileDescriptor("dynamic.flv"))
        assertTrue(dynamicEndpoint.isOpenFlow.value)
        dynamicEndpoint.close()
    }

    @Test
    fun `test open unknown file extension`() = runTest {
        val dynamicEndpoint = DynamicEndpoint(context, Dispatchers.Default, Dispatchers.IO)
        try {
            dynamicEndpoint.open(DescriptorUtils.createFileDescriptor("dynamic.unknown"))
            fail("IllegalArgumentException expected")
        } catch (_: Throwable) {
            assertFalse(dynamicEndpoint.isOpenFlow.value)
        } finally {
            dynamicEndpoint.close()
        }
    }

    @Test
    fun `test open flv content descriptor`() = runTest {
        val dynamicEndpoint = DynamicEndpoint(context, Dispatchers.Default, Dispatchers.IO)
        dynamicEndpoint.open(
            UriMediaDescriptor(
                DescriptorUtils.createContentUri(
                    context,
                    "dynamic.flv"
                ), containerType = MediaContainerType.FLV
            )
        )
        assertTrue(dynamicEndpoint.isOpenFlow.value)
        dynamicEndpoint.close()
    }

    @Test
    fun `test write to non open endpoint`() = runTest {
        val dynamicEndpoint = DynamicEndpoint(context, Dispatchers.Default, Dispatchers.IO)
        try {
            dynamicEndpoint.write(
                FakeFrameFactory.create(MediaFormat.MIMETYPE_AUDIO_AAC),
                0
            )
            fail("Throwable expected")
        } catch (_: Throwable) {
            assertFalse(dynamicEndpoint.isOpenFlow.value)
        } finally {
            dynamicEndpoint.close()
        }
    }

    @Test
    fun `SRT override replaces the built-in SRT endpoint`() = runTest {
        var calls = 0
        val overrideEndpoint = CompositeEndpoint(TsMuxer(), FakeSink())
        val dynamicEndpoint = DynamicEndpoint(context, Dispatchers.Default, Dispatchers.IO) {
            calls++
            overrideEndpoint
        }
        // No network is touched: the override's sink is a fake
        dynamicEndpoint.open(UriMediaDescriptor("srt://127.0.0.1:9999"))
        assertEquals(1, calls)
        assertTrue(dynamicEndpoint.isOpenFlow.value)
        dynamicEndpoint.close()

        // Not consulted for anything but SRT
        dynamicEndpoint.open(DescriptorUtils.createFileDescriptor("dynamic.ts"))
        assertEquals(1, calls)
        dynamicEndpoint.close()
    }

    @Test
    fun `isOpenFlow follows the endpoint in use, not the first one opened`() = runTest {
        val fakeSink = FakeSink()
        val dynamicEndpoint = DynamicEndpoint(context, Dispatchers.Default, Dispatchers.IO) {
            CompositeEndpoint(TsMuxer(), fakeSink)
        }
        dynamicEndpoint.open(DescriptorUtils.createFileDescriptor("dynamic.ts"))
        dynamicEndpoint.close()

        dynamicEndpoint.open(UriMediaDescriptor("srt://127.0.0.1:9999"))
        assertTrue(dynamicEndpoint.isOpenFlow.value)

        // The second endpoint drops on its own (a broken link): the mirror must see it
        fakeSink.close()
        withContext(Dispatchers.Default) {
            withTimeout(2_000) { dynamicEndpoint.isOpenFlow.first { !it } }
        }
        dynamicEndpoint.close()
    }
}
