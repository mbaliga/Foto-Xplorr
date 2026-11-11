package com.fotoxplorr.app.vision

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import org.robolectric.Robolectric
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * A stand-in for the `media` authority: serves the files a test hands it by the last path segment,
 * and answers `insert` the way MediaStore does (a new row at `content://media/external/images/media/<id>`
 * backed by a file the test can read afterwards). It keeps every [ContentValues] it was given so a
 * test can see the name, type and folder the writer asked for. It is the test's, not MediaStore's:
 * it enforces no rule of the real provider (the folder MediaProvider accepts is not checked here).
 */
class FakeMediaStoreProvider : ContentProvider() {
    class Inserted(val id: String, val file: File, val values: ContentValues)

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = files[uri.lastPathSegment] ?: throw java.io.FileNotFoundException("no file for $uri")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val id = "n${counter++}"
        val file = File.createTempFile("fx-fake-media-$id", ".bin")
        files[id] = file
        inserted += Inserted(id, file, ContentValues(values ?: ContentValues()))
        return Uri.parse("content://media/external/images/media/$id")
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = if (files.remove(uri.lastPathSegment) != null) 1 else 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 1

    companion object {
        private val files = ConcurrentHashMap<String, File>()
        val inserted: MutableList<Inserted> = java.util.Collections.synchronizedList(ArrayList())
        private var counter = 1

        fun install() {
            Robolectric.buildContentProvider(FakeMediaStoreProvider::class.java).create("media")
            inserted.clear()
        }

        /** Serves [file] as `content://media/external/images/media/<id>`. */
        fun serve(id: String, file: File): Uri {
            files[id] = file
            return Uri.parse("content://media/external/images/media/$id")
        }
    }
}
