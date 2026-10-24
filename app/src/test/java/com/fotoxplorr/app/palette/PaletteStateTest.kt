package com.fotoxplorr.app.palette

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.model.MediaId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The four answers the details room gives about a photo's colours, and the video frame the
 * palette is taken from. A failed read must never come out as an empty palette (TRAPS 32).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PaletteStateTest {

    private val teal = PaletteSwatch(0xFF1ABCF0.toInt(), 1f)

    @Test
    fun `a null read is a failure, not an empty palette`() {
        assertSame(PaletteState.Failed, PaletteState.of(null))
    }

    @Test
    fun `an empty read is a fact about the photo`() {
        assertSame(PaletteState.None, PaletteState.of(emptyList()))
    }

    @Test
    fun `a read with colours is ready with exactly those colours`() {
        assertEquals(PaletteState.Ready(listOf(teal)), PaletteState.of(listOf(teal)))
    }

    @Test
    fun `the palette frame is one second in`() {
        assertEquals(1_000_000L, PaletteSampling.videoFrameTimeUs(64_000L))
    }

    @Test
    fun `a clip shorter than two seconds is read from its middle`() {
        assertEquals(750_000L, PaletteSampling.videoFrameTimeUs(1_500L))
        assertEquals(500_000L, PaletteSampling.videoFrameTimeUs(1_000L))
    }

    @Test
    fun `an unknown or empty duration reads the first frame`() {
        assertEquals(0L, PaletteSampling.videoFrameTimeUs(0L))
        assertEquals(0L, PaletteSampling.videoFrameTimeUs(-1L))
    }

    @Test
    fun `the palette frame is never at or past the end of the clip`() {
        for (duration in listOf(1L, 2L, 999L, 1_999L, 2_000L, 90_000L)) {
            val atMs = PaletteSampling.videoFrameTimeUs(duration) / 1_000L
            assertTrue("frame at $atMs ms of a $duration ms clip", atMs < duration)
        }
    }

    @Test
    fun `a video that cannot be opened reads as failed`() = runBlocking {
        val state = PaletteSampling.readState(context, missing("clip.mp4", "video/mp4"))
        assertSame(PaletteState.Failed, state)
    }

    @Test
    fun `a still that cannot be decoded reads as failed`() = runBlocking {
        val state = PaletteSampling.readState(context, missing("pic.jpg", "image/jpeg"))
        assertSame(PaletteState.Failed, state)
    }

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun missing(name: String, mime: String) = MediaAsset(
        id = MediaId(41L),
        contentUriString = "file:///definitely/not/here/$name",
        displayName = name,
        mimeType = mime,
        bucketName = null,
        bucketId = 1L,
        dateTakenMillis = 0L,
        dateModifiedSeconds = 0L,
        width = 100,
        height = 100,
        sizeBytes = 1L,
        relativePath = null,
        isFavorite = false,
        isTrashed = false,
        durationMillis = if (mime.startsWith("video/")) 5_000L else 0L,
    )
}
