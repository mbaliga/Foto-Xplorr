package com.fotoxplorr.app.video

import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [containerFor]'s truth table (P0-05) and [selectPrimaryTracks], the track-selection function the
 * brief asks to test on its own: given a list of track MIME types, which get copied.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContainerTest {

    // --- containerFor ---------------------------------------------------------------------------

    @Test
    fun `AVC or HEVC video with AAC, AMR or no audio goes to MP4`() {
        assertEquals(Container.MP4, containerFor(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_AUDIO_AAC))
        assertEquals(Container.MP4, containerFor(MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_AUDIO_AAC))
        assertEquals(Container.MP4, containerFor(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_AUDIO_AMR_NB))
        assertEquals(Container.MP4, containerFor(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_AUDIO_AMR_WB))
        assertEquals(Container.MP4, containerFor(MediaFormat.MIMETYPE_VIDEO_AVC, null))
    }

    @Test
    fun `VP8 or VP9 video with Vorbis, Opus or no audio goes to WebM`() {
        assertEquals(Container.WEBM, containerFor(MediaFormat.MIMETYPE_VIDEO_VP8, MediaFormat.MIMETYPE_AUDIO_VORBIS))
        assertEquals(Container.WEBM, containerFor(MediaFormat.MIMETYPE_VIDEO_VP9, MediaFormat.MIMETYPE_AUDIO_VORBIS))
        assertEquals(Container.WEBM, containerFor(MediaFormat.MIMETYPE_VIDEO_VP9, MediaFormat.MIMETYPE_AUDIO_OPUS))
        assertEquals(Container.WEBM, containerFor(MediaFormat.MIMETYPE_VIDEO_VP8, null))
    }

    @Test
    fun `an unrecognized video codec is null regardless of audio`() {
        assertNull(containerFor(MediaFormat.MIMETYPE_VIDEO_AV1, null))
        assertNull(containerFor(MediaFormat.MIMETYPE_VIDEO_AV1, MediaFormat.MIMETYPE_AUDIO_AAC))
        assertNull(containerFor("video/mp4v-es", null)) // MPEG-4 SP
    }

    @Test
    fun `a video and audio codec that each fit a different container is null, not either container`() {
        // AVC only fits MP4; Vorbis only fits WebM -- neither container can take both.
        assertNull(containerFor(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_AUDIO_VORBIS))
        // VP9 only fits WebM; AAC only fits MP4.
        assertNull(containerFor(MediaFormat.MIMETYPE_VIDEO_VP9, MediaFormat.MIMETYPE_AUDIO_AAC))
    }

    @Test
    fun `an unrecognized audio codec is null even with an otherwise-fitting video codec`() {
        assertNull(containerFor(MediaFormat.MIMETYPE_VIDEO_AVC, "audio/mp4a-mystery"))
        assertNull(containerFor(MediaFormat.MIMETYPE_VIDEO_VP8, "audio/mp4a-mystery"))
    }

    // --- selectPrimaryTracks ----------------------------------------------------------------------

    @Test
    fun `picks the first video and first audio track, in order`() {
        val selection = selectPrimaryTracks(listOf("video/avc", "audio/mp4a-latm"))
        assertEquals(PrimaryTrackSelection(videoTrackIndex = 0, audioTrackIndex = 1), selection)
    }

    @Test
    fun `works regardless of which track comes first`() {
        val selection = selectPrimaryTracks(listOf("audio/mp4a-latm", "video/avc"))
        assertEquals(PrimaryTrackSelection(videoTrackIndex = 1, audioTrackIndex = 0), selection)
    }

    @Test
    fun `drops a subtitle or timed-metadata track`() {
        val selection = selectPrimaryTracks(listOf("video/avc", "audio/mp4a-latm", "text/vtt", "application/x-mebx"))
        assertEquals(PrimaryTrackSelection(videoTrackIndex = 0, audioTrackIndex = 1), selection)
    }

    @Test
    fun `drops a second video or audio track, keeping only the first of each`() {
        val selection = selectPrimaryTracks(listOf("video/avc", "video/avc", "audio/mp4a-latm", "audio/mp4a-latm"))
        assertEquals(PrimaryTrackSelection(videoTrackIndex = 0, audioTrackIndex = 2), selection)
    }

    @Test
    fun `a silent video has no audio track index`() {
        val selection = selectPrimaryTracks(listOf("video/avc"))
        assertEquals(PrimaryTrackSelection(videoTrackIndex = 0, audioTrackIndex = null), selection)
    }

    @Test
    fun `no video track at all is null -- nothing to copy`() {
        assertNull(selectPrimaryTracks(listOf("audio/mp4a-latm", "text/vtt")))
        assertNull(selectPrimaryTracks(emptyList()))
    }
}
