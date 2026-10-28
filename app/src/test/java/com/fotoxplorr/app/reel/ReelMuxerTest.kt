package com.fotoxplorr.app.reel

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Muxer
import com.google.common.collect.ImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer

/** The muxer wrapper that keeps a place (or anything else of a source's metadata) out of the reel. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReelMuxerTest {
    private class Place(val latitude: Float, val longitude: Float) : Metadata.Entry

    private class Recording : Muxer {
        val tracks = ArrayList<Format>()
        val samples = ArrayList<Triple<Int, Int, Long>>()
        val entries = ArrayList<Metadata.Entry>()
        var closed = 0

        override fun addTrack(format: Format): Int = tracks.size.also { tracks += format }

        override fun writeSampleData(trackId: Int, byteBuffer: ByteBuffer, bufferInfo: BufferInfo) {
            samples += Triple(trackId, byteBuffer.remaining(), bufferInfo.presentationTimeUs)
        }

        override fun addMetadataEntry(metadataEntry: Metadata.Entry) {
            entries += metadataEntry
        }

        override fun close() {
            closed++
        }
    }

    @Test
    fun `a place offered by a source never reaches the file`() {
        val inner = Recording()
        val muxer = PlaceFreeMuxer(inner)
        muxer.addMetadataEntry(Place(38.7f, -9.1f))
        muxer.addMetadataEntry(Place(0f, 0f))
        assertTrue(inner.entries.isEmpty())
    }

    @Test
    fun `tracks and samples pass through untouched, and the file is closed once`() {
        val inner = Recording()
        val muxer = PlaceFreeMuxer(inner)
        val format = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).setWidth(1920).setHeight(1080).setRotationDegrees(90).build()
        val id = muxer.addTrack(format)
        muxer.writeSampleData(id, ByteBuffer.allocate(40), BufferInfo(123_000L, 40, C.BUFFER_FLAG_KEY_FRAME))
        muxer.close()
        assertEquals(listOf(format), inner.tracks)
        assertEquals(90, inner.tracks.single().rotationDegrees)
        assertEquals(listOf(Triple(0, 40, 123_000L)), inner.samples)
        assertEquals(1, inner.closed)
    }

    @Test
    fun `the factory wraps what its inner factory makes and passes its answers on`() {
        val made = Recording()
        var path: String? = null
        val inner = object : Muxer.Factory {
            override fun create(path0: String): Muxer = made.also { path = path0 }
            override fun getSupportedSampleMimeTypes(trackType: Int): ImmutableList<String> = ImmutableList.of("video/avc")
            override fun supportsWritingNegativeTimestampsInEditList(): Boolean = true
        }
        val factory = PlaceFreeMuxerFactory(inner)
        val muxer = factory.create("/cache/reel.mp4")
        assertEquals("/cache/reel.mp4", path)
        muxer.addMetadataEntry(Place(1f, 2f))
        assertTrue(made.entries.isEmpty())
        assertEquals(ImmutableList.of("video/avc"), factory.getSupportedSampleMimeTypes(C.TRACK_TYPE_VIDEO))
        assertTrue(factory.supportsWritingNegativeTimestampsInEditList())
    }
}
