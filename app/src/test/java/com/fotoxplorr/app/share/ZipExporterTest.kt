package com.fotoxplorr.app.share

import android.content.pm.ProviderInfo
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.model.MediaId
import java.io.ByteArrayInputStream
import java.util.zip.ZipFile
import kotlin.io.path.createTempFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * P0-06: [ZipExporter] against real JPEGs served through a shadowed [android.content.ContentResolver]
 * -- the authority fix itself is pinned by [FileProviderAuthorityTest], since a wrong authority
 * would make every [ZipExporter.export] call here throw before this class's own behavior could be
 * exercised at all. NATIVE graphics mode for the same reason [SharePreparerTest] needs it: entries
 * go through [SharePreparer]'s own real `Bitmap.compress`/decode paths.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ZipExporterTest {

    private val context = RuntimeEnvironment.getApplication()
    private val shadowResolver = shadowOf(context.contentResolver)
    private var nextId = 1L

    private fun zipExporter() = ZipExporter(context)

    /** See [SharePreparerTest.attachFileProvider] for why this is needed before the first
     * `getUriForFile` call in a fresh Robolectric sandbox. */
    @Before
    fun attachFileProvider() {
        val providerInfo = ProviderInfo().apply {
            authority = fileProviderAuthority(context)
            grantUriPermissions = true
            exported = false
        }
        Robolectric.buildContentProvider(FileProvider::class.java).create(providerInfo)
    }

    /** Unlike a real device, Robolectric's [MimeTypeMap] shadow starts with no built-in
     * mime-type/extension table at all -- every lookup [entryNameFor] needs here has to be
     * registered by hand, or every mime type looks "unknown" to it regardless of how ordinary it
     * really is. */
    @Before
    fun registerMimeTypes() {
        shadowOf(MimeTypeMap.getSingleton()).apply {
            addExtensionMimeTypeMapping("jpg", "image/jpeg")
            addExtensionMimeTypeMapping("png", "image/png")
        }
    }

    private fun registerAsset(bytes: ByteArray, displayName: String, mimeType: String = "image/jpeg"): MediaAsset {
        val id = nextId++
        val uri = Uri.parse("content://zip-exporter-test/$id")
        shadowResolver.registerInputStreamSupplier(uri) { ByteArrayInputStream(bytes) }
        return MediaAsset(
            id = MediaId(id),
            contentUriString = uri.toString(),
            displayName = displayName,
            mimeType = mimeType,
            bucketName = null,
            dateTakenMillis = 0L,
            dateModifiedSeconds = 0L,
            width = 0,
            height = 0,
            sizeBytes = bytes.size.toLong(),
            relativePath = null,
            isFavorite = false,
            isTrashed = false,
        )
    }

    private fun realJpegWithGps(width: Int, height: Int): ByteArray {
        val file = createTempFile("zip-exporter-source", ".jpg").toFile()
        file.deleteOnExit()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "51/1,30/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "0/1,7/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "W")
            saveAttributes()
        }
        return file.readBytes()
    }

    private fun displayNameOf(uri: Uri): String =
        context.contentResolver.query(uri, null, null, null, null)!!.use { cursor ->
            cursor.moveToFirst()
            cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
        }

    /** Copies the archive out of its FileProvider Uri into a real temp [java.io.File] and opens it
     * with [ZipFile] rather than [java.util.zip.ZipInputStream] -- a streamed zip's entries report
     * `compressedSize`/`size` as -1 until fully read past their data descriptor, but [ZipFile]
     * reads the central directory up front and always has the real numbers. */
    private fun openArchive(uri: Uri): ZipFile {
        val temp = createTempFile("zip-exporter-readback", ".zip").toFile()
        temp.deleteOnExit()
        context.contentResolver.openInputStream(uri)!!.use { input -> temp.outputStream().use { input.copyTo(it) } }
        return ZipFile(temp)
    }

    private fun singleEntryBytes(uri: Uri): ByteArray =
        openArchive(uri).use { zip ->
            val entry = zip.entries().asSequence().single()
            zip.getInputStream(entry).use { it.readBytes() }
        }

    // --- archiveName --------------------------------------------------------------------------

    @Test
    fun `archiveName pluralizes photo or item, and says item for a single video-inclusive selection`() {
        assertEquals("Foto Xplorr 1 photo.zip", archiveName(1, includesVideo = false))
        assertEquals("Foto Xplorr 3 photos.zip", archiveName(3, includesVideo = false))
        assertEquals("Foto Xplorr 1 item.zip", archiveName(1, includesVideo = true))
        assertEquals("Foto Xplorr 5 items.zip", archiveName(5, includesVideo = true))
    }

    // --- uniqueName ----------------------------------------------------------------------------

    @Test
    fun `uniqueName returns the first request as is and numbers later collisions`() {
        val used = mutableSetOf<String>()
        assertEquals("photo.jpg", uniqueName("photo.jpg", used))
        assertEquals("photo (2).jpg", uniqueName("photo.jpg", used))
        assertEquals("photo (3).jpg", uniqueName("photo.jpg", used))
    }

    @Test
    fun `uniqueName falls back to photo for a blank name`() {
        assertEquals("photo", uniqueName("", mutableSetOf()))
    }

    // --- entryNameFor ----------------------------------------------------------------------------

    @Test
    fun `entryNameFor keeps the original name when the item's own type matches its extension`() {
        val ready = PreparedItem.Ready(Uri.parse("content://zip-exporter-test/entry"), "image/jpeg")
        assertEquals("photo.jpg", entryNameFor("photo.jpg", ready))
    }

    @Test
    fun `entryNameFor replaces the extension when SharePreparer re-encoded the item to a different type`() {
        val ready = PreparedItem.Ready(Uri.parse("content://zip-exporter-test/entry"), "image/png")
        assertEquals("photo.png", entryNameFor("photo.heic", ready))
    }

    @Test
    fun `entryNameFor falls back to the original extension for a type MimeTypeMap doesn't know`() {
        val ready = PreparedItem.Ready(Uri.parse("content://zip-exporter-test/entry"), "application/x-not-a-real-type")
        assertEquals("photo.heic", entryNameFor("photo.heic", ready))
    }

    // --- export ----------------------------------------------------------------------------------

    @Test
    fun `a failed item is skipped from the archive and reported, never written as an empty entry`() = runBlocking {
        val good = registerAsset(realJpegWithGps(4, 4), "a.jpg")
        val bad = registerAsset(byteArrayOf(1, 2, 3, 4), "b.jpg")

        val export = zipExporter().export(listOf(good, bad), stripMetadata = true).getOrThrow()

        assertEquals(1, export.failed.size)
        assertEquals(bad, export.failed.single().asset)
        openArchive(export.uri).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            assertEquals(listOf("a.jpg"), names)
        }
    }

    @Test
    fun `when every item fails, export fails rather than producing an empty archive`() = runBlocking {
        val bad = registerAsset(byteArrayOf(1, 2, 3, 4), "b.jpg")

        val result = zipExporter().export(listOf(bad), stripMetadata = true)

        assertTrue(result.isFailure)
    }

    @Test
    fun `export refuses an empty selection`() = runBlocking {
        val result = zipExporter().export(emptyList(), stripMetadata = true)

        assertTrue(result.isFailure)
    }

    @Test
    fun `zip entries follow the caller's strip-metadata preference, same as an ordinary share`() = runBlocking {
        val asset = registerAsset(realJpegWithGps(4, 4), "geo.jpg")

        val stripped = zipExporter().export(listOf(asset), stripMetadata = true).getOrThrow()
        assertNull(ExifInterface(ByteArrayInputStream(singleEntryBytes(stripped.uri))).latLong)
    }

    @Test
    fun `zip entries keep metadata when the caller's preference says so`() = runBlocking {
        val asset = registerAsset(realJpegWithGps(4, 4), "geo.jpg")

        val kept = zipExporter().export(listOf(asset), stripMetadata = false).getOrThrow()
        assertTrue(
            "a kept-metadata archive must not have its GPS stripped",
            ExifInterface(ByteArrayInputStream(singleEntryBytes(kept.uri))).latLong != null,
        )
    }

    @Test
    fun `the archive name says items, not photos, when the selection includes a video`() = runBlocking {
        val photo = registerAsset(realJpegWithGps(4, 4), "photo.jpg")
        // Garbage bytes, so this specific item will fail to remux -- archiveName keys on what was
        // SELECTED, not on what actually made it into the archive.
        val video = registerAsset(byteArrayOf(1, 2, 3, 4), "clip.mp4", mimeType = "video/mp4")

        val export = zipExporter().export(listOf(photo, video), stripMetadata = true).getOrThrow()

        val name = displayNameOf(export.uri)
        assertTrue("expected an 'item' archive name, got '$name'", name.contains("item"))
        assertFalse("expected no 'photo' wording, got '$name'", name.contains("photo"))
    }

    @Test
    fun `entries are stored with no compression, even for very compressible content`() = runBlocking {
        // Streamed through unchanged by prepareRawCopy (stripMetadata = false, non-video): default
        // DEFLATE would shrink 200,000 repeated bytes to a few hundred; NO_COMPRESSION keeps the
        // compressed size close to the real size instead.
        val redundant = ByteArray(200_000) { 7 }
        val asset = registerAsset(redundant, "raw.jpg")

        val export = zipExporter().export(listOf(asset), stripMetadata = false).getOrThrow()

        openArchive(export.uri).use { zip ->
            val entry = zip.entries().asSequence().single()
            assertEquals(redundant.size.toLong(), entry.size)
            assertTrue(
                "expected near-1:1 compressed size (NO_COMPRESSION) but got ${entry.compressedSize} " +
                    "for ${entry.size} real bytes",
                entry.compressedSize > entry.size / 2,
            )
        }
    }
}
