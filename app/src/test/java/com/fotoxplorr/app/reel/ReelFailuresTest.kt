package com.fotoxplorr.app.reel

import androidx.media3.transformer.ExportException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** What the job does about each way an export can fail. The codes are the export library's own constants. */
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
