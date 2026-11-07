package com.fotoxplorr.app.viewer

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.fotoxplorr.app.gallery.TransparencyBackground
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules behind the transparent-photo backdrop (owner, #17), pinned without Compose: when the
 * chrome button and the backdrop exist, how the button reads, where the backdrop is drawn, and
 * what the header probe makes of a file's first bytes.
 */
class ViewerTransparencyPolicyTest {

    // ── the chrome button ──────────────────────────────────────────────────────────────────────

    @Test
    fun `the button is shown only with the chrome up on a transparent photo`() {
        fun shown(chrome: Boolean = true, video: Boolean = false, alpha: Boolean = true) =
            ViewerChromePolicy.transparencyControlShown(chrome, video, alpha)

        assertTrue(shown())
        assertFalse("chrome down", shown(chrome = false))
        assertFalse("an opaque photo has nothing to choose", shown(alpha = false))
        assertFalse("a video has no alpha to show through", shown(video = true))
        assertFalse("a video that somehow reports alpha", shown(video = true, alpha = true))
    }

    @Test
    fun `the button is not a function of the catalogue`() {
        // The signature is the pin: a photo opened from another app must still get the choice,
        // so nothing about catalogueActions or isExternal can reach this rule.
        val parameters = ViewerChromePolicy::class.java.methods
            .first { it.name == "transparencyControlShown" }.parameterTypes
        assertEquals(3, parameters.size)
    }

    @Test
    fun `the backdrop is image content and needs no chrome`() {
        assertTrue(ViewerChromePolicy.transparencyBackdropShown(isVideo = false, hasAlpha = true, lifting = false))
    }

    @Test
    fun `no backdrop for an opaque photo a video or under the lift editor`() {
        assertFalse(ViewerChromePolicy.transparencyBackdropShown(isVideo = false, hasAlpha = false, lifting = false))
        assertFalse(ViewerChromePolicy.transparencyBackdropShown(isVideo = true, hasAlpha = true, lifting = false))
        assertFalse(ViewerChromePolicy.transparencyBackdropShown(isVideo = false, hasAlpha = true, lifting = true))
    }

    @Test
    fun `the button names the current choice and what a tap does`() {
        assertEquals(
            "Transparent background: grid (tap for white)",
            ViewerChromePolicy.transparencyControlDescription(TransparencyBackground.CHECKERBOARD),
        )
        assertEquals(
            "Transparent background: white (tap for black)",
            ViewerChromePolicy.transparencyControlDescription(TransparencyBackground.WHITE),
        )
        assertEquals(
            "Transparent background: black (tap for grid)",
            ViewerChromePolicy.transparencyControlDescription(TransparencyBackground.BLACK),
        )
    }

    @Test
    fun `every choice has a distinct label and a capitalised title`() {
        val labels = TransparencyBackground.entries.map { it.label() }
        assertEquals(labels.size, labels.toSet().size)
        assertEquals(listOf("Grid", "White", "Black"), TransparencyBackground.entries.map { it.title() })
    }

    // ── where the backdrop is drawn ────────────────────────────────────────────────────────────

    private val fallback = Rect(0f, 100f, 400f, 300f)

    @Test
    fun `before the image has decoded the backdrop uses the viewer's own rectangle`() {
        assertEquals(fallback, TransparencyBackdropGeometry.rect(400f, 800f, null, fallback))
    }

    @Test
    fun `a decoded size fits the container centred with its aspect kept`() {
        val rect = TransparencyBackdropGeometry.rect(400f, 800f, Size(200f, 100f), fallback)
        assertEquals(Rect(0f, 300f, 400f, 500f), rect)
    }

    @Test
    fun `a degenerate decoded size is ignored`() {
        assertEquals(fallback, TransparencyBackdropGeometry.rect(400f, 800f, Size(0f, 100f), fallback))
        assertEquals(fallback, TransparencyBackdropGeometry.rect(400f, 800f, Size(100f, -1f), fallback))
        assertEquals(fallback, TransparencyBackdropGeometry.rect(400f, 800f, Size(Float.NaN, 100f), fallback))
    }

    // ── the header probe ───────────────────────────────────────────────────────────────────────

    private fun pngHead(colourType: Int, extraChunk: String? = null): ByteArray {
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        fun chunk(type: String, data: ByteArray): ByteArray {
            val length = data.size
            return byteArrayOf(
                (length shr 24).toByte(), (length shr 16).toByte(), (length shr 8).toByte(), length.toByte(),
            ) + type.encodeToByteArray() + data + ByteArray(4) // CRC, which the sniffer does not check
        }
        val ihdr = ByteArray(13).also { it[9] = colourType.toByte(); it[3] = 1; it[7] = 1; it[8] = 8 }
        return signature + chunk("IHDR", ihdr) +
            (extraChunk?.let { chunk(it, ByteArray(1)) } ?: ByteArray(0)) +
            chunk("IDAT", ByteArray(4))
    }

    @Test
    fun `an RGBA png reads as transparent and an RGB png as opaque`() {
        assertTrue(TransparencyProbe.verdict(pngHead(colourType = 6), "image/png"))
        assertFalse(TransparencyProbe.verdict(pngHead(colourType = 2), "image/png"))
    }

    @Test
    fun `a palette png with a transparency chunk reads as transparent`() {
        assertTrue(TransparencyProbe.verdict(pngHead(colourType = 3, extraChunk = "tRNS"), "image/png"))
        assertFalse(TransparencyProbe.verdict(pngHead(colourType = 3), "image/png"))
    }

    @Test
    fun `a format the sniffer does not know reads as opaque not as transparent`() {
        assertFalse(TransparencyProbe.verdict(ByteArray(64), "image/x-something"))
        assertFalse(TransparencyProbe.verdict(ByteArray(0), "image/png"))
        assertFalse(TransparencyProbe.verdict(ByteArray(0), ""))
    }

    @Test
    fun `jpeg ico and svg are settled from the type alone with no file read`() {
        assertEquals(false, TransparencyProbe.fromMimeAlone("image/jpeg"))
        assertEquals(true, TransparencyProbe.fromMimeAlone("image/svg+xml"))
        assertEquals(true, TransparencyProbe.fromMimeAlone("image/x-icon"))
    }

    @Test
    fun `png gif and webp need their header read`() {
        assertNull(TransparencyProbe.fromMimeAlone("image/png"))
        assertNull(TransparencyProbe.fromMimeAlone("image/gif"))
        assertNull(TransparencyProbe.fromMimeAlone("image/webp"))
        assertNull(TransparencyProbe.fromMimeAlone("image/heic"))
    }
}
