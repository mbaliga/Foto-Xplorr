package com.fotoxplorr.app.palettebuilder

import android.content.Intent
import android.graphics.BitmapFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** What leaves the app: the send intent for each text format, the card file, and the folder the file provider must know. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class PaletteShareTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val sunset = Palette.of("Sunset", listOf(0xFFFF8800.toInt(), 0xFF00AAFF.toInt(), 0xFF1A1A1A.toInt()))!!

    @Test
    fun `each text format is sent as plain text, named for the file it would be`() {
        for (format in listOf(ExportFormat.GPL, ExportFormat.CSS, ExportFormat.JSON)) {
            val intent = AndroidPaletteShare.textIntent(sunset, format)!!
            assertEquals(Intent.ACTION_SEND, intent.action)
            assertEquals("text/plain", intent.type)
            assertEquals(PaletteExport.text(sunset, format), intent.getStringExtra(Intent.EXTRA_TEXT))
            assertEquals(PaletteExport.fileName(sunset, format), intent.getStringExtra(Intent.EXTRA_SUBJECT))
        }
    }

    @Test
    fun `the card is a picture, so it has no text intent`() {
        assertNull(AndroidPaletteShare.textIntent(sunset, ExportFormat.CARD))
    }

    @Test
    fun `a text share carries no stream, no grant and no photo`() {
        val intent = AndroidPaletteShare.textIntent(sunset, ExportFormat.JSON)!!
        assertNull(intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM))
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    @Test
    fun `the card is written as a PNG of the layout's size, whole, in a folder of its own`() {
        val file = AndroidPaletteShare.writeCard(temp.root, sunset, nowMillis = 1_000L)
        assertEquals(File(File(temp.root, "1000"), "sunset.png"), file)
        val spec = SwatchCard.layout(sunset)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        assertEquals(spec.widthPx, bounds.outWidth)
        assertEquals(spec.heightPx, bounds.outHeight)
        assertEquals("image/png", bounds.outMimeType)
        assertEquals(listOf("sunset.png"), file.parentFile!!.list()!!.toList())
    }

    @Test
    fun `each colour of the card is painted where the layout puts it`() {
        val file = AndroidPaletteShare.writeCard(temp.root, sunset, nowMillis = 5L)
        val bmp = BitmapFactory.decodeFile(file.path)
        assertNotNull(bmp)
        val spec = SwatchCard.layout(sunset)
        for (cell in spec.cells) {
            // A pixel in the block's top-left corner, clear of the label near the bottom.
            assertEquals(cell.argb, bmp.getPixel(cell.left + 2, cell.top + 2))
        }
        assertEquals(spec.backgroundArgb, bmp.getPixel(1, 1))
    }

    @Test
    fun `a second card never writes over the first`() {
        val first = AndroidPaletteShare.writeCard(temp.root, sunset, nowMillis = 10L)
        val second = AndroidPaletteShare.writeCard(temp.root, sunset, nowMillis = 11L)
        assertTrue(first.isFile)
        assertTrue(second.isFile)
        assertFalse(first == second)
    }

    @Test
    fun `cards more than a day old are swept when the next one is made, newer ones stay`() {
        val old = AndroidPaletteShare.writeCard(temp.root, sunset, nowMillis = 1_000L)
        val recent = AndroidPaletteShare.writeCard(temp.root, sunset, nowMillis = 2_000L)
        old.parentFile!!.setLastModified(1_000L)
        recent.parentFile!!.setLastModified(2_000L)
        val now = 2_000L + AndroidPaletteShare.CARD_MAX_AGE_MILLIS - 10
        val fresh = AndroidPaletteShare.writeCard(temp.root, sunset, nowMillis = now)
        assertFalse("the day-old card is gone", old.exists())
        assertFalse(old.parentFile!!.exists())
        assertTrue(recent.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun `a name with odd characters still makes a safe file name`() {
        val odd = Palette.of("../../etc/passwd éè", listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt()))!!
        val file = AndroidPaletteShare.writeCard(temp.root, odd, nowMillis = 3L)
        assertEquals(File(temp.root, "3"), file.parentFile)
        assertTrue(file.isFile)
        assertFalse(file.name.contains('/'))
    }

    // ---- the text on the card ----

    @Test
    fun `text that already fits keeps its size`() {
        assertEquals(40f, SwatchCardBitmap.fittedSize(40f, 500f) { it * 5 }, 0f)
    }

    @Test
    fun `text that is too wide shrinks until it fits`() {
        val size = SwatchCardBitmap.fittedSize(40f, 100f) { it * 5 }
        assertTrue("size $size", size * 5 <= 100f)
        assertTrue("size $size", size > 15f)
    }

    @Test
    fun `text is never shrunk below a readable floor, and the search always ends`() {
        val size = SwatchCardBitmap.fittedSize(40f, 1f) { it * 1000 }
        assertEquals(6f, size, 0f)
        assertEquals(6f, SwatchCardBitmap.fittedSize(2f, 100f) { 0f }, 0f)
    }
}

/**
 * The card folder this app writes and the file provider's declared roots must name the same folder, or sharing a
 * card throws at the moment it is tried (see the share and moments directory tests for the same rule).
 */
class PaletteCardDirectoryTest {

    private fun filePathsXml(): File {
        val candidates = listOf(File("src/main/res/xml/file_paths.xml"), File("app/src/main/res/xml/file_paths.xml"))
        return candidates.firstOrNull { it.isFile } ?: error("file_paths.xml not found; looked in ${candidates.map { it.absolutePath }}")
    }

    @Test
    fun `the file provider declares the folder the cards are written to`() {
        val declared = Regex("""<cache-path[^>]*path="([^"]+)"""").findAll(filePathsXml().readText()).map { it.groupValues[1] }.toList()
        assertTrue(
            "file_paths.xml declares $declared, which does not cover '${AndroidPaletteShare.CARD_DIRECTORY}'",
            declared.any { it.trimEnd('/') == AndroidPaletteShare.CARD_DIRECTORY },
        )
    }

    @Test
    fun `the card folder is relative and not empty`() {
        assertTrue(AndroidPaletteShare.CARD_DIRECTORY.isNotBlank())
        assertFalse(AndroidPaletteShare.CARD_DIRECTORY.startsWith("/"))
    }
}
