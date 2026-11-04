package com.fotoxplorr.app.formats

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fotoxplorr.app.media.DecodeLimits
import com.fotoxplorr.app.media.decodeUpright
import com.fotoxplorr.core.formats.FormatSniffer
import com.fotoxplorr.core.formats.formatId
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * WP1.8 [DEVICE]: decodes every file of the synthetic format corpus (`testdata/formats/`, shipped
 * into this test APK's assets by app/build.gradle.kts) through the app's own [decodeUpright], and
 * runs [FormatSniffer] over the same bytes. `manifest.txt` says what to expect:
 *
 *  - `decode = platform`: Android's codecs must open it, at the manifest's upright size, and for
 *    `check = quad` the four quadrants must be red / green / blue / yellow (a tolerance covers the
 *    lossy formats). This is where a device's real ImageDecoder / BitmapFactory is held to the
 *    claims in docs/testdata-formats.md.
 *  - `decode = none` and a `stub-` file: header-only bytes, so the decode must return null and
 *    must not throw.
 *  - `decode = none` and not a stub (TIFF, PSD, TGA, QOI...): valid files the app has no decoder
 *    for. Nothing is asserted about the outcome (some OEM builds decode a few); the result is only
 *    logged, so a surprise shows up in the test report without making the run fail.
 *
 * Not run anywhere yet: it compiles in the build (`:app:compileOfflineDebugAndroidTestKotlin`);
 * running it needs a device or emulator (`:app:connectedOfflineDebugAndroidTest`).
 */
@RunWith(AndroidJUnit4::class)
class FormatCorpusDecodeTest {

    private data class Row(val file: String, val sniff: String, val decode: String, val width: Int, val height: Int, val check: String)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target: Context = instrumentation.targetContext
    private val assets = instrumentation.context.assets

    private val rows: List<Row> by lazy {
        assets.open("manifest.txt").bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map {
                val p = it.trim().split(Regex("\\s+"))
                Row(p[0], p[1], p[2], p[3].toInt(), p[4].toInt(), p[5])
            }
    }

    private fun uriOf(name: String): Uri {
        val f = File(target.cacheDir, "corpus-$name")
        assets.open(name).use { input -> f.outputStream().use { input.copyTo(it) } }
        return Uri.fromFile(f)
    }

    @Test
    fun manifestIsShippedAndNotEmpty() {
        assertTrue("manifest.txt has no rows", rows.size > 20)
        assertEquals("every manifest row has its asset", emptyList<String>(), rows.filter { r -> runCatching { assets.open(r.file).close() }.isFailure }.map { it.file })
    }

    @Test
    fun snifferAgreesWithTheManifestOnDevice() {
        val wrong = rows.mapNotNull { r ->
            val head = assets.open(r.file).use { it.readNBytes(FormatSniffer.REQUIRED_HEADER_BYTES) }
            val got = FormatSniffer.sniff(head)?.formatId?.value ?: "none"
            if (got == r.sniff) null else "${r.file}: expected ${r.sniff}, got $got"
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test
    fun platformFilesDecodeAtTheirDeclaredUprightSize() {
        val limits = DecodeLimits(maxLongEdge = 4096, maxPixels = 16_000_000L)
        val problems = mutableListOf<String>()
        for (r in rows.filter { it.decode == "platform" }) {
            val decoded = runBlocking { decodeUpright(target, uriOf(r.file), limits) }
            if (decoded == null) {
                problems += "${r.file}: decodeUpright returned null"
                continue
            }
            val bmp = decoded.bitmap
            if (bmp.width != r.width || bmp.height != r.height) problems += "${r.file}: ${bmp.width}x${bmp.height}, expected ${r.width}x${r.height}"
            if (r.check == "quad") problems += quadrantProblems(r.file, bmp.getPixel(2, 2), bmp.getPixel(bmp.width - 3, 2), bmp.getPixel(2, bmp.height - 3), bmp.getPixel(bmp.width - 3, bmp.height - 3))
        }
        assertTrue("corpus decode problems:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun stubsNeverDecodeAndNeverThrow() {
        val limits = DecodeLimits(maxLongEdge = 4096, maxPixels = 16_000_000L)
        for (r in rows.filter { it.file.startsWith("stub-") }) {
            val decoded = runBlocking { decodeUpright(target, uriOf(r.file), limits) }
            assertNull("${r.file} is a header-only stub", decoded)
        }
    }

    @Test
    fun validFilesTheAppCannotDecodeAreOnlyReported() {
        val limits = DecodeLimits(maxLongEdge = 4096, maxPixels = 16_000_000L)
        for (r in rows.filter { it.decode == "none" && !it.file.startsWith("stub-") }) {
            val decoded = runCatching { runBlocking { decodeUpright(target, uriOf(r.file), limits) } }
            android.util.Log.i("FormatCorpus", "${r.file}: ${if (decoded.isFailure) "threw ${decoded.exceptionOrNull()}" else if (decoded.getOrNull() == null) "not decoded" else "decoded by this device"}")
        }
        assertNotNull(rows)
    }

    private fun quadrantProblems(file: String, tl: Int, tr: Int, bl: Int, br: Int): List<String> {
        val want = listOf(tl to 0xFF0000, tr to 0x00FF00, bl to 0x0000FF, br to 0xFFFF00)
        return want.withIndex().mapNotNull { (i, p) ->
            val (got, rgb) = p
            val d = maxOf(
                kotlin.math.abs(((got shr 16) and 0xFF) - ((rgb shr 16) and 0xFF)),
                kotlin.math.abs(((got shr 8) and 0xFF) - ((rgb shr 8) and 0xFF)),
                kotlin.math.abs((got and 0xFF) - (rgb and 0xFF)),
            )
            if (d > 24) "$file: quadrant $i is #${Integer.toHexString(got and 0xFFFFFF)}, expected #${Integer.toHexString(rgb)}" else null
        }
    }
}
