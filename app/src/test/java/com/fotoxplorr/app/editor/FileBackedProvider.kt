package com.fotoxplorr.app.editor

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import org.robolectric.Robolectric
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * A `ContentProvider` that serves real files by the last path segment of the uri, so the editor's
 * `ContentResolver.openFileDescriptor(uri, "r" / "wt")` calls run against real bytes under
 * Robolectric. The shadow resolver returns null for `file://` uris and throws for an unattached
 * authority (see [com.fotoxplorr.app.metadata.MetadataWriterWriteTest]'s own note); an attached
 * provider is what makes the descriptor path testable at all.
 *
 * Register one per authority with [install], then [serve] a file under an id and build the uri
 * with `Uri.parse("content://<authority>/<anything>/<id>")`. Query parameters (such as the
 * `requireOriginal` one `MediaStore.setRequireOriginal` appends) are ignored.
 */
class FileBackedProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = files[uri.lastPathSegment] ?: throw java.io.FileNotFoundException("no file for $uri")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0

    companion object {
        private val files = ConcurrentHashMap<String, File>()

        fun install(authority: String) {
            Robolectric.buildContentProvider(FileBackedProvider::class.java).create(authority)
        }

        fun serve(id: String, file: File) {
            files[id] = file
        }
    }
}
