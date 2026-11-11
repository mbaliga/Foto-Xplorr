package com.fotoxplorr.app.sources.saf

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import java.io.File
import org.robolectric.Robolectric

/**
 * A tree provider for Robolectric: answers child queries from [dirs] with the columns it was asked
 * for, can report EXTRA_LOADING for the first [loadingAnswers] asks, EXTRA_ERROR, a null cursor or a
 * throw, and serves file bytes from [files] by document id. Static state; call [reset] first.
 */
class FakeDocumentsProvider : ContentProvider() {
    class Row(val id: String, val name: String, val mime: String, val size: Long? = 10, val modified: Long? = 1_700_000_000_000L, val flags: Int = 0)

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor? {
        val dir = uri.pathSegments[3]
        queries += dir
        throwOnQuery?.let { throw it }
        if (nullCursor) return null
        val columns = projection ?: emptyArray()
        val cursor = MatrixCursor(columns)
        for (r in dirs[dir].orEmpty()) {
            cursor.addRow(columns.map { c ->
                when (c) {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID -> r.id
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME -> r.name
                    DocumentsContract.Document.COLUMN_MIME_TYPE -> r.mime
                    DocumentsContract.Document.COLUMN_SIZE -> r.size
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED -> r.modified
                    DocumentsContract.Document.COLUMN_FLAGS -> r.flags
                    else -> null
                }
            })
        }
        val extras = Bundle()
        if (queries.count { it == dir } <= loadingAnswers) extras.putBoolean(DocumentsContract.EXTRA_LOADING, true)
        errorText?.let { extras.putString(DocumentsContract.EXTRA_ERROR, it) }
        cursor.setExtras(extras)
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val id = uri.pathSegments[3]
        val file = files[id] ?: throw java.io.FileNotFoundException(id)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

    companion object {
        const val AUTHORITY = "fotoz.test.docs"
        val dirs = HashMap<String, List<Row>>()
        val files = HashMap<String, File>()
        val queries = ArrayList<String>()
        var loadingAnswers = 0
        var errorText: String? = null
        var nullCursor = false
        var throwOnQuery: RuntimeException? = null

        fun reset() {
            dirs.clear(); files.clear(); queries.clear()
            loadingAnswers = 0; errorText = null; nullCursor = false; throwOnQuery = null
        }

        fun install() {
            Robolectric.buildContentProvider(FakeDocumentsProvider::class.java).create(AUTHORITY)
        }

        val treeUri: Uri get() = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root")
        const val DIR = DocumentsContract.Document.MIME_TYPE_DIR
    }
}
