package com.fotoxplorr.app.receive

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Another app's provider: serves `bytes of <name>` for any path except one called `missing`, which
 * is unreadable, and answers the type by the extension. The same shape a real share sender has.
 */
class ReceiveSenderProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val cursor = MatrixCursor(projection ?: arrayOf(OpenableColumns.DISPLAY_NAME))
        cursor.addRow((projection ?: arrayOf(OpenableColumns.DISPLAY_NAME)).map { if (it == OpenableColumns.DISPLAY_NAME) uri.lastPathSegment else null })
        return cursor
    }

    override fun getType(uri: Uri): String? = when (uri.lastPathSegment?.substringAfterLast('.', "")) {
        "jpg" -> "image/jpeg"
        "png" -> "image/png"
        "mp4" -> "video/mp4"
        "pdf" -> "application/pdf"
        else -> null
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val name = uri.lastPathSegment.orEmpty()
        if (name.startsWith("missing")) throw FileNotFoundException(name)
        val file = File(directory, name.replace('/', '_'))
        file.writeText("bytes of $name")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "com.example.sender"
        var directory: File = File(".")

        fun reset(cache: File) {
            directory = File(cache, "fake-sender").apply { deleteRecursively(); mkdirs() }
        }

        fun uri(name: String): Uri = Uri.parse("content://$AUTHORITY/files/$name")
    }
}
