package com.fotoxplorr.app.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** The pure mapping from what the platform reports to [VideoDetails]; no video is opened. */
class VideoDetailsMappingTest {

    private val container = VideoContainerRaw(
        mimeType = "video/mp4",
        width = "3840",
        height = "2160",
        rotation = "0",
        bitrate = "48000000",
        durationMs = "10000",
        hasAudio = "yes",
        captureFps = "60",
        date = "20260801T223300.000Z",
    )

    private val hevc = VideoTrackRaw(
        mime = "video/hevc",
        profile = 2,
        frameRate = 30.0,
        width = 3840,
        height = 2160,
        colorTransfer = 3,
    )

    @Test
    fun `a full read maps every field`() {
        val d = VideoDetails.from(container, hevc, AudioTrackRaw("audio/mp4a-latm", 2), tracksRead = true)
        assertTrue(d.readOk)
        assertFalse(d.pending)
        assertEquals("video/mp4", d.containerMime)
        assertEquals("HEVC (H.265)", VideoDetails.codecName(d.videoMime))
        assertEquals("Main 10", d.profile)
        assertEquals(3840, d.width)
        assertEquals(2160, d.height)
        assertEquals(30.0, d.frameRate!!, 1e-9)
        assertEquals(60.0, d.captureFps!!, 1e-9)
        assertEquals(48_000_000L, d.bitrate)
        assertEquals(VideoHdr.SDR, d.hdr)
        assertEquals(true, d.hasAudio)
        assertEquals("AAC", VideoDetails.audioCodecName(d.audioMime))
        assertEquals(2, d.audioChannels)
        assertEquals(
            ZonedDateTime.of(2026, 8, 1, 22, 33, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli(),
            d.createdAtMillis,
        )
    }

    @Test
    fun `missing keys are null fields, not zeros or claims`() {
        val d = VideoDetails.from(VideoContainerRaw(), null, null, tracksRead = false)
        assertTrue("the retriever opened, so this is a read", d.readOk)
        assertNull(d.videoMime)
        assertNull(d.profile)
        assertEquals(0, d.width)
        assertNull(d.frameRate)
        assertNull(d.captureFps)
        assertNull(d.bitrate)
        assertNull(d.hdr)
        assertNull("audio is unknown, not absent, when the tracks were not read", d.hasAudio)
        assertNull(d.createdAtMillis)
    }

    @Test
    fun `a read that opened nothing is readOk false`() {
        val d = VideoDetails.from(null, null, null, tracksRead = false)
        assertFalse(d.readOk)
        assertNull(d.hasAudio)
    }

    @Test
    fun `either half failing alone still leaves the other's answers`() {
        val noTracks = VideoDetails.from(container, null, null, tracksRead = false)
        assertTrue(noTracks.readOk)
        assertEquals(true, noTracks.hasAudio)
        assertEquals(3840, noTracks.width)
        val noContainer = VideoDetails.from(null, hevc, null, tracksRead = true)
        assertTrue(noContainer.readOk)
        assertEquals(false, noContainer.hasAudio)
        assertEquals("video/hevc", noContainer.videoMime)
    }

    @Test
    fun `audio presence comes from the tracks when they were read`() {
        assertEquals(false, VideoDetails.from(container, hevc, null, tracksRead = true).hasAudio)
        assertEquals(true, VideoDetails.from(container, hevc, AudioTrackRaw("audio/opus", null), tracksRead = true).hasAudio)
    }

    @Test
    fun `HDR is claimed only on evidence`() {
        fun kind(track: VideoTrackRaw) = VideoDetails.hdrKind(track)
        assertEquals(VideoHdr.HDR10, kind(hevc.copy(colorTransfer = 6)))
        assertEquals(VideoHdr.HLG, kind(hevc.copy(colorTransfer = 7)))
        assertEquals(VideoHdr.SDR, kind(hevc.copy(colorTransfer = 3)))
        assertEquals(VideoHdr.HDR10_PLUS, kind(hevc.copy(colorTransfer = 6, profile = 0x2000)))
        assertEquals(VideoHdr.HDR10, kind(hevc.copy(colorTransfer = null, profile = 0x1000)))
        assertEquals(VideoHdr.DOLBY_VISION, kind(hevc.copy(mime = "video/dolby-vision", colorTransfer = null)))
        assertNull("no transfer and no HDR profile is unknown, not SDR", kind(hevc.copy(colorTransfer = null)))
        assertNull(VideoDetails.hdrKind(null))
    }

    @Test
    fun `rotation is folded to a quarter turn and swaps the displayed size`() {
        assertEquals(90, VideoDetails.normalisedRotation(90))
        assertEquals(270, VideoDetails.normalisedRotation(-90))
        assertEquals(90, VideoDetails.normalisedRotation(450))
        assertEquals(0, VideoDetails.normalisedRotation(45))
        assertEquals(0, VideoDetails.normalisedRotation(null))
        val d = VideoDetails.from(container.copy(rotation = "90"), hevc, null, tracksRead = true)
        assertEquals(90, d.rotationDegrees)
        assertEquals(2160, d.displayWidth)
        assertEquals(3840, d.displayHeight)
    }

    @Test
    fun `the frame rate falls back to frame count over duration`() {
        val noRate = hevc.copy(frameRate = null)
        val d = VideoDetails.from(container.copy(frameCount = "300", durationMs = "10000"), noRate, null, tracksRead = true)
        assertEquals(30.0, d.frameRate!!, 1e-9)
        assertNull(VideoDetails.from(container, noRate, null, tracksRead = true).frameRate)
    }

    @Test
    fun `the container size is used when the track names none`() {
        val d = VideoDetails.from(container, hevc.copy(width = null, height = null), null, tracksRead = true)
        assertEquals(3840, d.width)
        assertEquals(2160, d.height)
    }

    @Test
    fun `the track bitrate backs up a container without one`() {
        val d = VideoDetails.from(container.copy(bitrate = null), hevc.copy(bitrate = 9_000_000L), null, tracksRead = true)
        assertEquals(9_000_000L, d.bitrate)
    }

    @Test
    fun `profile names for the common codecs, null for the rest`() {
        assertEquals("High", VideoDetails.profileName("video/avc", 8))
        assertEquals("Constrained Baseline", VideoDetails.profileName("video/avc", 0x10000))
        assertEquals("Main 10 HDR10+", VideoDetails.profileName("video/hevc", 0x2000))
        assertEquals("Profile 2", VideoDetails.profileName("video/x-vnd.on2.vp9", 4))
        assertEquals("Main 10-bit", VideoDetails.profileName("video/av01", 2))
        assertNull(VideoDetails.profileName("video/avc", 12345))
        assertNull(VideoDetails.profileName("video/foo", 1))
        assertNull(VideoDetails.profileName(null, 1))
        assertNull(VideoDetails.profileName("video/avc", null))
    }

    @Test
    fun `codec names, with the subtype as the fallback`() {
        assertEquals("H.264", VideoDetails.codecName("video/avc"))
        assertEquals("AV1", VideoDetails.codecName("VIDEO/AV01"))
        assertEquals("FOO", VideoDetails.codecName("video/foo"))
        assertNull(VideoDetails.codecName(""))
        assertNull(VideoDetails.codecName(null))
        assertEquals("AAC", VideoDetails.audioCodecName("audio/mp4a-latm"))
        assertEquals("Opus", VideoDetails.audioCodecName("audio/opus"))
    }

    @Test
    fun `container dates parse in UTC, with or without a fraction, and unset clocks read as unknown`() {
        val expected = ZonedDateTime.of(2026, 8, 1, 22, 33, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals(expected, VideoDetails.parseContainerDate("20260801T223300.000Z"))
        assertEquals(expected, VideoDetails.parseContainerDate("20260801T223300Z"))
        assertEquals(expected + 500, VideoDetails.parseContainerDate("20260801T223300.5Z"))
        assertNull("the MP4 epoch is an unset clock", VideoDetails.parseContainerDate("19040101T000000.000Z"))
        assertNull(VideoDetails.parseContainerDate("19700101T000000.000Z"))
        assertNull(VideoDetails.parseContainerDate("not a date"))
        assertNull(VideoDetails.parseContainerDate("20261301T223300.000Z"))
        assertNull(VideoDetails.parseContainerDate(null))
    }

    @Test
    fun `PENDING claims nothing`() {
        assertTrue(VideoDetails.PENDING.pending)
        assertNull(VideoDetails.PENDING.hasAudio)
    }
}
