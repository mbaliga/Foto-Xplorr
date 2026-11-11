package com.fotoxplorr.app.video

import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the two small policy decisions in `VideoExporter` that the Transformer pipeline made
 * testable off-device: which user-facing sentence each [ExportException] code maps to, and that
 * the HDR mode is always tone-map (never keep-HDR) -- see ADR-008 revision 2. Robolectric only
 * because [ExportException] is an Android-side class whose factory touches `SystemClock`.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoExporterMessageTest {

    private fun failure(code: Int, message: String = "raw transformer text") =
        ExportException.createForAssetLoader(RuntimeException(message), code)

    @Test
    fun `decoder failures say the device cannot decode the format`() {
        val expected = "This device cannot decode this video's format."
        assertEquals(expected, exportMessage(failure(ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)))
        assertEquals(expected, exportMessage(failure(ExportException.ERROR_CODE_DECODER_INIT_FAILED)))
    }

    @Test
    fun `encoder failures say the device cannot encode the export`() {
        val expected = "This device cannot encode the exported video."
        assertEquals(expected, exportMessage(failure(ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED)))
        assertEquals(expected, exportMessage(failure(ExportException.ERROR_CODE_ENCODER_INIT_FAILED)))
    }

    @Test
    fun `a frame processing failure hints at HDR content`() {
        val message = exportMessage(failure(ExportException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED))
        assertEquals(
            "This device could not process this video (it may be HDR content this device cannot tone-map).",
            message,
        )
    }

    @Test
    fun `an unmapped export code falls back to the exception message`() {
        val e = failure(ExportException.ERROR_CODE_IO_FILE_NOT_FOUND, message = "disk said no")
        assertEquals(e.message, exportMessage(e))
    }

    @Test
    fun `a non export throwable uses its own message or a generic one`() {
        assertEquals("Android would not create a new video file", exportMessage(IllegalStateException("Android would not create a new video file")))
        assertEquals("The export failed.", exportMessage(RuntimeException()))
    }

    @Test
    fun `HDR is always tone mapped to SDR and never kept`() {
        assertEquals(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL, hdrModeFor())
        assertNotEquals(Composition.HDR_MODE_KEEP_HDR, hdrModeFor())
    }
}
