package com.fotoxplorr.app.sources.saf

import android.graphics.Bitmap
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import com.fotoxplorr.core.formats.MediaFormat
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class SourceEnrichmentTest {
    private fun jpeg(w: Int, h: Int, orientation: Int?, date: String?): File {
        val file = File.createTempFile("fx-enrich", ".jpg").apply { deleteOnExit() }
        FileOutputStream(file).use { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }
        ExifInterface(file.absolutePath).apply {
            orientation?.let { setAttribute(ExifInterface.TAG_ORIENTATION, it.toString()) }
            date?.let { setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, it) }
            saveAttributes()
        }
        return file
    }

    private fun enrich(file: File, format: MediaFormat = MediaFormat.Jpeg, isVirtual: Boolean = false): SourceFacts {
        FakeDocumentsProvider.reset()
        FakeDocumentsProvider.install()
        FakeDocumentsProvider.files["root/f"] = file
        val uri = android.provider.DocumentsContract.buildDocumentUriUsingTree(FakeDocumentsProvider.treeUri, "root/f")
        return runBlocking {
            SourceEnricher(ApplicationProvider.getApplicationContext()).enrich(uri, format, file.length(), isVirtual)
        }
    }

    @Test
    fun readsUprightSizeOrientationAndDateFromTheHead() {
        val facts = enrich(jpeg(40, 20, ExifInterface.ORIENTATION_ROTATE_90, "2024:05:06 07:08:09"))
        assertEquals(EnrichmentOutcome.READ, facts.outcome)
        assertEquals(20, facts.width)
        assertEquals(40, facts.height)
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, facts.orientation)
        assertNotNull(facts.dateTakenMillis)
    }

    @Test
    fun aFileWithoutExifIsReadWithNoDateRatherThanUnreadable() {
        val facts = enrich(jpeg(30, 10, null, null))
        assertEquals(EnrichmentOutcome.READ, facts.outcome)
        assertEquals(30, facts.width)
        assertNull(facts.dateTakenMillis)
        assertNull(facts.orientation)
    }

    @Test
    fun garbageIsUnreadableNotAbsent() {
        val file = File.createTempFile("fx-bad", ".jpg").apply { deleteOnExit(); writeBytes(ByteArray(300) { 1 }) }
        assertEquals(EnrichmentOutcome.UNREADABLE, enrich(file).outcome)
    }

    @Test
    fun remoteBackedDocumentsAreSkippedUnlessAsked() {
        assertEquals(EnrichmentOutcome.SKIPPED, enrich(jpeg(4, 4, null, null), isVirtual = true).outcome)
    }

    @Test
    fun theHeadReadIsBoundedAndNeverTheWholeFile() {
        val source = ByteArrayInputStream(ByteArray(10_000))
        val bounded = BoundedInputStream(source, 100)
        val sink = ByteArray(10_000)
        var total = 0
        while (true) {
            val n = bounded.read(sink, 0, sink.size)
            if (n < 0) break
            total += n
        }
        assertEquals(100, total)
        assertEquals(100L, bounded.consumed)
        assertEquals(-1, bounded.read())
    }

    @Test
    fun uprightAndDateAssembly() {
        assertEquals(20 to 40, SourceFactsAssembly.upright(40, 20, ExifInterface.ORIENTATION_ROTATE_270))
        assertEquals(40 to 20, SourceFactsAssembly.upright(40, 20, ExifInterface.ORIENTATION_ROTATE_180))
        assertEquals(40 to 20, SourceFactsAssembly.upright(40, 20, null))
        assertNull(SourceFactsAssembly.upright(0, 20, 1))
        assertNull(SourceFactsAssembly.upright(null, 20, 1))
        assertNull(SourceFactsAssembly.dateTaken("0000:00:00 00:00:00"))
        assertNull(SourceFactsAssembly.dateTaken("  "))
        assertNull(SourceFactsAssembly.dateTaken("yesterday"))
        assertNull(SourceFactsAssembly.dateTaken(null))
        assertNotNull(SourceFactsAssembly.dateTaken("2024:05:06 07:08:09"))
    }
}
