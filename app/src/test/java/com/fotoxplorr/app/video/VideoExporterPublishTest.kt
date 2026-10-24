package com.fotoxplorr.app.video

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.media3.common.util.UnstableApi
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.model.MediaId
import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * `VideoExporter.publish` must never leave a pending, empty MediaStore row behind when the copy
 * fails (it would show up as a broken video in every other gallery). A recording provider is
 * attached for the `media` authority so insert/delete/update are observable.
 *
 * What this does NOT cover: the "resolver would not open the new row" branch (Robolectric's
 * resolver shadow hands back a stream for any uri, so it cannot be provoked here), a successful
 * publish round trip (needs a writable provider row; left to the device checklist) and
 * cancellation of the surrounding export coroutine.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoExporterPublishTest {

    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun attachProvider() {
        RecordingMediaProvider.reset()
        Robolectric.buildContentProvider(RecordingMediaProvider::class.java).create(MediaStore.AUTHORITY)
    }

    private fun source() = MediaAsset(
        id = MediaId(1L),
        contentUriString = "content://media/external/video/media/1",
        displayName = "clip.mov",
        mimeType = "video/quicktime",
        bucketName = null,
        dateTakenMillis = 0L,
        dateModifiedSeconds = 0L,
        width = 1920,
        height = 1080,
        sizeBytes = 4L,
        durationMillis = 1000L,
        relativePath = null,
        isFavorite = false,
        isTrashed = false,
    )

    @Test
    fun `a failed copy deletes the pending row it inserted`() {
        // The finished file is missing, so reading it fails AFTER the row was inserted -- the
        // same position a disk-full or broken-pipe failure mid-copy would hit.
        val missing = File(context.cacheDir, "fotoz-never-written.mp4")
        try {
            VideoExporter(context).publish(source(), missing, "edited")
            fail("publish should have failed when the finished file cannot be read")
        } catch (expected: FileNotFoundException) {
            // rethrown unchanged so export() can turn it into a normal Result.failure
        }
        assertEquals("one pending row was inserted", 1, RecordingMediaProvider.inserted.size)
        assertEquals("and that same row was deleted", RecordingMediaProvider.inserted, RecordingMediaProvider.deleted)
        assertTrue("it was never promoted out of pending", RecordingMediaProvider.updated.isEmpty())
    }

    @Test
    fun `the inserted row is a pending video beside the original`() {
        val finished = File.createTempFile("fotoz-finished-", ".mp4", context.cacheDir)
        finished.writeBytes(byteArrayOf(1))
        try {
            runCatching { VideoExporter(context).publish(source(), finished, "edited") }
        } finally {
            finished.delete()
        }
        val values = RecordingMediaProvider.insertedValues.single()
        assertEquals("clip-edited.mp4", values.getAsString(MediaStore.Video.Media.DISPLAY_NAME))
        assertEquals("video/mp4", values.getAsString(MediaStore.Video.Media.MIME_TYPE))
        assertEquals(1, values.getAsInteger(MediaStore.Video.Media.IS_PENDING))
    }
}

/** Records the MediaStore writes `VideoExporter.publish` makes. */
class RecordingMediaProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val row = Uri.withAppendedPath(uri, (inserted.size + 1).toString())
        inserted += row
        insertedValues += ContentValues(values)
        return row
    }

    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int {
        deleted += uri
        return 1
    }

    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int {
        updated += uri
        return 1
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        throw FileNotFoundException("refused for test: $uri")

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null

    companion object {
        val inserted = mutableListOf<Uri>()
        val insertedValues = mutableListOf<ContentValues>()
        val deleted = mutableListOf<Uri>()
        val updated = mutableListOf<Uri>()

        fun reset() {
            inserted.clear()
            insertedValues.clear()
            deleted.clear()
            updated.clear()
        }
    }
}
