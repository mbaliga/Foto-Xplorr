package com.fotoxplorr.app.video

import android.media.MediaCodec
import android.media.MediaExtractor
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [muxerBufferFlagsFor] exists because [MediaExtractor] and [MediaCodec.BufferInfo] flags share
 * bit VALUES while meaning different things -- passing one straight through is exactly the
 * `WrongConstant` bug this project already shipped once in `ClipExporter` (`docs/TRAPS.md` #24).
 * These pin the translation table down explicitly rather than trusting it not to regress the same
 * way twice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaSampleFlagsTest {

    @Test
    fun `a sync sample becomes a key frame`() {
        assertEquals(
            MediaCodec.BUFFER_FLAG_KEY_FRAME,
            muxerBufferFlagsFor(MediaExtractor.SAMPLE_FLAG_SYNC),
        )
    }

    @Test
    fun `an ordinary sample carries no flags`() {
        assertEquals(0, muxerBufferFlagsFor(0))
    }

    /**
     * The exact bug this function exists to prevent: [MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME]
     * shares its bit with [MediaCodec.BUFFER_FLAG_END_OF_STREAM]. A pass-through implementation
     * would tell a muxer or an encoder that a merely-partial sample is the end of the whole track.
     */
    @Test
    fun `a partial-frame sample never becomes end-of-stream`() {
        val translated = muxerBufferFlagsFor(MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME)
        assertEquals(0, translated and MediaCodec.BUFFER_FLAG_END_OF_STREAM)
    }

    /** Likewise for the encrypted-sample bit colliding with codec-config. */
    @Test
    fun `an encrypted sample never becomes a codec-config buffer`() {
        val translated = muxerBufferFlagsFor(MediaExtractor.SAMPLE_FLAG_ENCRYPTED)
        assertEquals(0, translated and MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
    }

    @Test
    fun `a sync AND partial sample still carries only the key-frame flag`() {
        val combined = MediaExtractor.SAMPLE_FLAG_SYNC or MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME
        assertEquals(MediaCodec.BUFFER_FLAG_KEY_FRAME, muxerBufferFlagsFor(combined))
    }
}
