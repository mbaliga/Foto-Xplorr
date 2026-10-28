package com.fotoxplorr.app.reel

import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.transformer.ExportException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * What the job does about each way an export can fail. The codes are the export library's own constants. Robolectric
 * only because an [ExportException] reads the system clock when it is made.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReelFailuresTest {
    private fun kind(code: Int, video: Boolean? = null, sound: Boolean = true) = ReelFailures.classify(code, video, sound)

    @Test
    fun `a codec or compositor giving up takes the next rung`() {
        for (code in listOf(
            ExportException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED,
            ExportException.ERROR_CODE_ENCODER_INIT_FAILED,
            ExportException.ERROR_CODE_ENCODING_FAILED,
            ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED,
            ExportException.ERROR_CODE_DECODER_INIT_FAILED,
            ExportException.ERROR_CODE_DECODING_FAILED,
            ExportException.ERROR_CODE_MUXING_TIMEOUT,
            ExportException.ERROR_CODE_MUXING_APPEND,
            ExportException.ERROR_CODE_FAILED_RUNTIME_CHECK,
            ExportException.ERROR_CODE_UNSPECIFIED,
        )) {
            assertEquals("code $code", ReelFailureKind.STEP_DOWN, kind(code, video = true))
        }
    }

    @Test
    fun `an audio codec failing is a sound problem, but only when the render had sound`() {
        for (code in listOf(
            ExportException.ERROR_CODE_DECODER_INIT_FAILED,
            ExportException.ERROR_CODE_DECODING_FAILED,
            ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            ExportException.ERROR_CODE_ENCODER_INIT_FAILED,
            ExportException.ERROR_CODE_ENCODING_FAILED,
            ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED,
        )) {
            assertEquals("code $code", ReelFailureKind.AUDIO, kind(code, video = false, sound = true))
            assertFalse("code $code with no sound in the plan", kind(code, video = false, sound = false) == ReelFailureKind.AUDIO)
        }
        assertEquals(ReelFailureKind.AUDIO, kind(ExportException.ERROR_CODE_AUDIO_PROCESSING_FAILED, sound = true))
        assertEquals(ReelFailureKind.STEP_DOWN, kind(ExportException.ERROR_CODE_AUDIO_PROCESSING_FAILED, sound = false))
    }

    @Test
    fun `a video format this phone cannot read stays unreadable at any size`() {
        assertEquals(ReelFailureKind.SOURCE, kind(ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, video = true))
        assertEquals(ReelFailureKind.SOURCE, kind(ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, video = null, sound = false))
    }

    @Test
    fun `a file that cannot be opened is a source problem`() {
        for (code in listOf(
            ExportException.ERROR_CODE_IO_FILE_NOT_FOUND,
            ExportException.ERROR_CODE_IO_NO_PERMISSION,
            ExportException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            ExportException.ERROR_CODE_IO_UNSPECIFIED,
        )) {
            assertEquals("code $code", ReelFailureKind.SOURCE, kind(code))
        }
    }

    @Test
    fun `a muxer that cannot write is a storage problem`() {
        assertEquals(ReelFailureKind.STORAGE, kind(ExportException.ERROR_CODE_MUXING_FAILED))
    }

    @Test
    fun `an unknown code takes the next rung`() {
        assertEquals(ReelFailureKind.STEP_DOWN, kind(123456))
    }

    // The export library does not name a parse error: it arrives as "unspecified", with the extractor's error in the causes.
    private fun loaderFailed(cause: Throwable) = ExportException.createForAssetLoader(cause, ExportException.ERROR_CODE_UNSPECIFIED)

    @Test
    fun `a container the extractor cannot parse is its own kind, found in the causes`() {
        val malformed = ParserException.createForMalformedContainer("moov box cut short", null)
        assertEquals(ReelFailureKind.PARSE, ReelFailures.classify(loaderFailed(malformed), soundInPlan = true))
        assertEquals(ReelFailureKind.PARSE, ReelFailures.classify(loaderFailed(RuntimeException("player", malformed)), soundInPlan = false))
        val unsupported = ParserException.createForUnsupportedContainerFeature("no such box")
        assertEquals(ReelFailureKind.PARSE, ReelFailures.classify(loaderFailed(unsupported), soundInPlan = true))
        for (code in listOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        )) {
            val playback = PlaybackException("player", IOException("io"), code)
            assertEquals("code $code", ReelFailureKind.PARSE, ReelFailures.classify(loaderFailed(playback), soundInPlan = true))
        }
    }

    @Test
    fun `an asset loader failure that is not a parse error still takes the next rung`() {
        assertEquals(ReelFailureKind.STEP_DOWN, ReelFailures.classify(loaderFailed(IllegalStateException("no track")), soundInPlan = true))
        val other = PlaybackException("player", IOException("io"), PlaybackException.ERROR_CODE_UNSPECIFIED)
        assertEquals(ReelFailureKind.STEP_DOWN, ReelFailures.classify(loaderFailed(other), soundInPlan = true))
        // A source that cannot be opened stays a source problem whatever is in the causes.
        val gone = ExportException.createForAssetLoader(IOException("gone"), ExportException.ERROR_CODE_IO_FILE_NOT_FOUND)
        assertEquals(ReelFailureKind.SOURCE, ReelFailures.classify(gone, soundInPlan = true))
    }

    @Test
    fun `a parse error in a cycle of causes does not hang`() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        assertEquals(ReelFailureKind.STEP_DOWN, ReelFailures.classify(loaderFailed(a), soundInPlan = false))
    }

    @Test
    fun `out of space is found through the causes, by the app's own mark or the system's words`() {
        assertTrue(ReelFailures.isOutOfSpace(ReelNoSpaceException()))
        assertTrue(ReelFailures.isOutOfSpace(RuntimeException("wrapped", ReelNoSpaceException())))
        assertTrue(ReelFailures.isOutOfSpace(IOException("write failed: ENOSPC (No space left on device)")))
        assertTrue(ReelFailures.isOutOfSpace(RuntimeException("x", IOException("No space left on device"))))
        assertFalse("disk full in a message that is not an IO error is not enough", ReelFailures.isOutOfSpace(RuntimeException("disk full")))
        assertFalse(ReelFailures.isOutOfSpace(IOException("something else")))
        assertFalse(ReelFailures.isOutOfSpace(null))
    }

    @Test
    fun `a cycle in the causes does not hang`() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        assertFalse(ReelFailures.isOutOfSpace(a))
    }
}
