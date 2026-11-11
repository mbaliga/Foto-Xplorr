package com.fotoxplorr.app.editor

import android.Manifest
import android.graphics.Bitmap
import android.net.Uri
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.model.MediaId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/** [readOverwriteHazards] and the overwrite it gates, against real files served through a provider. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class OverwriteHazardScanTest {

    private val context = RuntimeEnvironment.getApplication()
    private val writer = EditedCopyWriter(context)
    private val made = mutableListOf<File>()
    private var nextId = 1

    init {
        FileBackedProvider.install("media")
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_MEDIA_LOCATION)
    }

    @After
    fun cleanUp() = made.forEach { it.delete() }

    private fun file(suffix: String, write: (FileOutputStream) -> Unit): File =
        File.createTempFile("fx-scan", suffix).also { made += it; FileOutputStream(it).use(write) }

    private fun assetFor(file: File?, mime: String, size: Long = file?.length() ?: 100L): MediaAsset {
        val id = "s${nextId++}"
        file?.let { FileBackedProvider.serve(id, it) }
        return MediaAsset(
            id = MediaId(nextId.toLong()),
            contentUriString = Uri.parse("content://media/external/images/media/$id").toString(),
            displayName = file?.name ?: "missing.jpg",
            mimeType = mime,
            bucketName = null,
            dateTakenMillis = 0L,
            dateModifiedSeconds = 0L,
            width = 8,
            height = 8,
            sizeBytes = size,
            relativePath = null,
            isFavorite = false,
            isTrashed = false,
        )
    }

    private fun translucent(): Bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(0x80FF0000.toInt()) }

    private fun overwrite(asset: MediaAsset) = runBlocking { writer.overwrite(asset, Bitmap.createBitmap(12, 10, Bitmap.Config.ARGB_8888)) }

    @Test
    fun `a lossless WebP with transparency is refused with both hazards and left untouched`() {
        val webp = file(".webp") { translucent().compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, it) }
        val before = webp.readBytes()

        val scan = context.readOverwriteHazards(assetFor(webp, "image/webp"))
        val refused = overwrite(assetFor(webp, "image/webp")).exceptionOrNull() as OverwriteException.Refused

        assertEquals(setOf(OverwriteHazard.TRANSPARENCY, OverwriteHazard.LOSSLESS), scan.hazards)
        assertEquals(OverwriteException.Refused.Reason.WOULD_DESTROY_CONTENT, refused.reason)
        assertEquals(scan.hazards, refused.hazards)
        assertArrayEquals(before, webp.readBytes())
    }

    @Test
    fun `an opaque lossy WebP can still be replaced`() {
        val webp = file(".webp") { Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF00FF00.toInt()) }.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, it) }

        val scan = context.readOverwriteHazards(assetFor(webp, "image/webp"))

        assertFalse("hazards ${scan.hazards}", scan.blocksOverwrite)
    }

    @Test
    fun `a PNG with transparency is not a hazard`() {
        val png = file(".png") { translucent().compress(Bitmap.CompressFormat.PNG, 100, it) }

        assertFalse(context.readOverwriteHazards(assetFor(png, "image/png")).blocksOverwrite)
    }

    @Test
    fun `a JPEG that cannot be read is unreadable rather than clean, and the replace is refused`() {
        val gone = assetFor(file = null, mime = "image/jpeg")

        val scan = context.readOverwriteHazards(gone)
        val refused = overwrite(gone).exceptionOrNull() as OverwriteException.Refused

        assertTrue(scan.unreadable)
        assertTrue(scan.blocksOverwrite)
        assertEquals(OverwriteException.Refused.Reason.BACKUP_FAILED, refused.reason)
    }

    @Test
    fun `a format with no in-place path is not read at all`() {
        // Nothing is served for this asset: reading it would report unreadable.
        val scan = context.readOverwriteHazards(assetFor(file = null, mime = "image/gif"))

        assertFalse(scan.blocksOverwrite)
    }

    @Test
    fun `a clean overwrite keeps no safety copy anywhere`() {
        val jpeg = file(".jpg") { Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }

        val result = overwrite(assetFor(jpeg, "image/jpeg"))

        assertTrue("overwrite should succeed: ${result.exceptionOrNull()}", result.isSuccess)
        assertTrue(OverwriteRecovery.dir(context).listFiles().orEmpty().isEmpty())
        assertTrue(File(context.cacheDir, "edit-backup").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `the recovery directory lives under the persistent files directory`() {
        assertTrue(OverwriteRecovery.dir(context).absolutePath.startsWith(context.filesDir.absolutePath))
        assertTrue(OverwriteRecovery.sweepOrphans(context).isEmpty())
    }
}
