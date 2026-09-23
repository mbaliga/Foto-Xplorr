package com.fotoxplorr.app.video

import android.media.MediaCodecInfo
import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure decisions [VideoTranscoder]'s pre-flight check makes over already-extracted format values
 *  (P0-11) -- neither needs a real device, so no Robolectric. */
class VideoCodecCapabilityTest {

    @Test
    fun `an odd dimension rounds down to even`() {
        assertEquals(1920, evenSize(1920))
        assertEquals(1080, evenSize(1081))
        assertEquals(0, evenSize(0))
        assertEquals(2, evenSize(3))
    }

    @Test
    fun `ST2084 or HLG color transfer means HDR`() {
        assertEquals(
            "HDR video conversion isn't supported yet",
            hdrRefusalReason(colorTransfer = MediaFormat.COLOR_TRANSFER_ST2084, profile = null),
        )
        assertEquals(
            "HDR video conversion isn't supported yet",
            hdrRefusalReason(colorTransfer = MediaFormat.COLOR_TRANSFER_HLG, profile = null),
        )
    }

    @Test
    fun `an HDR profile means HDR even with no color transfer reported`() {
        assertEquals(
            "HDR video conversion isn't supported yet",
            hdrRefusalReason(colorTransfer = null, profile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10),
        )
    }

    @Test
    fun `SDR color transfer with no HDR profile is not refused`() {
        assertNull(hdrRefusalReason(colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO, profile = null))
    }

    @Test
    fun `no information available at all is not treated as HDR`() {
        assertNull(hdrRefusalReason(colorTransfer = null, profile = null))
    }

    @Test
    fun `an ordinary SDR profile is not treated as HDR`() {
        assertNull(hdrRefusalReason(colorTransfer = null, profile = MediaCodecInfo.CodecProfileLevel.AVCProfileHigh))
    }
}
