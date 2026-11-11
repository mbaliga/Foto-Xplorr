package com.fotoxplorr.app.composite

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.fotoxplorr.app.composite.CompositeTestFiles.asset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * Export on Android 8-9, where MediaStore has no RELATIVE_PATH: the row is given its full path in
 * Pictures/Fotoz or Movies/Fotoz with its own extension, and never an existing file's. This checks
 * the row Fotoz asks for (Robolectric has no media provider); what a real Android 9 provider then
 * does with it is a device check.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CompositeExportLegacyTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Suppress("DEPRECATION")
    private fun publicDir(type: String) = File(Environment.getExternalStoragePublicDirectory(type), "Fotoz")

    private fun export(video: Boolean): ContentValues {
        val (bytes, _) = CompositeTestFiles.microVideo()
        val file = File(folder.root, "PXL_1.MP.jpg").apply { writeBytes(bytes) }
        val photo = asset(1, "PXL_1.MP.jpg", uri = Uri.fromFile(file).toString())
        val motion = CompositeProbe.facts(context, photo).motion!!
        val exporter = CompositeExporter(context)
        val result = runBlocking {
            if (video) exporter.exportVideo(photo, motion) { true } else exporter.exportStill(photo, motion) { true }
        }
        assertTrue(result is CompositeExportResult.Saved)
        return shadowOf(context.contentResolver).insertStatements.last().contentValues
    }

    @Suppress("DEPRECATION")
    @Test
    fun `a still goes to Pictures Fotoz under its own name and extension`() {
        val values = export(video = false)
        val path = values.getAsString(MediaStore.MediaColumns.DATA)
        assertEquals(File(publicDir(Environment.DIRECTORY_PICTURES), "PXL_1.MP-still.jpg").absolutePath, path)
        assertTrue(publicDir(Environment.DIRECTORY_PICTURES).isDirectory)
        assertEquals("image/jpeg", values.getAsString(MediaStore.MediaColumns.MIME_TYPE))
        assertEquals("PXL_1.MP-still.jpg", values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
        assertFalse(values.containsKey(MediaStore.MediaColumns.RELATIVE_PATH))
    }

    @Suppress("DEPRECATION")
    @Test
    fun `a clip goes to Movies Fotoz as mp4`() {
        val values = export(video = true)
        assertEquals(
            File(publicDir(Environment.DIRECTORY_MOVIES), "PXL_1.MP-motion.mp4").absolutePath,
            values.getAsString(MediaStore.MediaColumns.DATA),
        )
        assertEquals("video/mp4", values.getAsString(MediaStore.MediaColumns.MIME_TYPE))
    }

    @Suppress("DEPRECATION")
    @Test
    fun `an existing file is never the target`() {
        val dir = publicDir(Environment.DIRECTORY_PICTURES).apply { mkdirs() }
        val existing = File(dir, "PXL_1.MP-still.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        File(dir, "PXL_1.MP-still (2).jpg").writeBytes(byteArrayOf(4))
        val values = export(video = false)
        assertEquals(File(dir, "PXL_1.MP-still (3).jpg").absolutePath, values.getAsString(MediaStore.MediaColumns.DATA))
        assertEquals("PXL_1.MP-still (3).jpg", values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
        assertTrue(existing.readBytes().contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `unique names keep the extension and give up rather than overwrite`() {
        val dir = File("/x")
        assertEquals(File(dir, "a.heic"), CompositeExporter.uniqueFile(dir, "a.heic") { false })
        assertEquals(File(dir, "a (2).heic"), CompositeExporter.uniqueFile(dir, "a.heic") { it.name == "a.heic" })
        assertNull(CompositeExporter.uniqueFile(dir, "a.heic") { true })
    }

    @Test
    @Config(sdk = [34])
    fun `from Android 10 the row asks for a relative path and no file path`() {
        val values = export(video = false)
        assertEquals("Pictures/Fotoz/", values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
        @Suppress("DEPRECATION")
        assertFalse(values.containsKey(MediaStore.MediaColumns.DATA))
    }
}
