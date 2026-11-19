package com.fotoxplorr.app.sources.saf

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.fotoxplorr.app.backup.SafListing
import com.fotoxplorr.app.backup.SafPage
import com.fotoxplorr.core.index.walker.DocumentEntry
import com.fotoxplorr.core.index.walker.DocumentTree
import com.fotoxplorr.core.index.walker.TreeAccessException
import com.fotoxplorr.core.index.walker.TreeFailure
import java.io.IOException

/**
 * The walker's [DocumentTree] over a `DocumentsContract` tree grant. One child query per
 * directory with the full projection (id, name, mime, size, last modified, flags), so the walk
 * costs one provider round trip per folder instead of one per file (what `DocumentFile.listFiles`
 * does). [SafListing.settle] owns the EXTRA_LOADING retries and turns EXTRA_ERROR into a throw;
 * here a throw becomes a [TreeAccessException], never an empty list.
 *
 * Never writes, creates or deletes. Not run against a real provider on a device; the cursor
 * parsing and the retries run under Robolectric against a registered fake provider.
 *
 * @param grantHeld asks the grant registry; false means REVOKED without bothering the provider.
 * @param retryDelaysMillis the waits of [SafListing.settle] between asks for a still-loading folder.
 */
class SafDocumentTree(
    context: Context,
    private val treeUri: Uri,
    private val grantHeld: () -> Boolean = { true },
    private val retryDelaysMillis: List<Long> = SafListing.DEFAULT_DELAYS_MILLIS,
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
) : DocumentTree {
    private val resolver: ContentResolver = context.applicationContext.contentResolver
    override val rootDocumentId: String = DocumentsContract.getTreeDocumentId(treeUri)

    /** How many child queries were made, for tests and for the "one query per directory" check. */
    var queryCount: Int = 0
        private set

    override fun children(directoryId: String): List<DocumentEntry> {
        if (!grantHeld()) throw TreeAccessException(TreeFailure.REVOKED, SafCopy.REVOKED)
        var last: List<DocumentEntry> = emptyList()
        try {
            SafListing.settle(delaysMillis = retryDelaysMillis, pause = pause) {
                val (page, rows) = queryOnce(directoryId)
                last = rows
                page
            }
        } catch (e: TreeAccessException) {
            throw e
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            val held = grantHeld()
            throw TreeAccessException(SafAccess.classify(e, held), e.message ?: SafCopy.ERROR)
        }
        return last
    }

    private fun queryOnce(directoryId: String): Pair<SafPage, List<DocumentEntry>> {
        queryCount++
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, directoryId)
        val cursor = resolver.query(childrenUri, PROJECTION, null, null, null)
            ?: throw IOException("The folder could not be read")
        return cursor.use {
            val extras = it.extras
            val loading = extras?.getBoolean(DocumentsContract.EXTRA_LOADING, false) == true
            val error = extras?.getString(DocumentsContract.EXTRA_ERROR)
            val id = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val name = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mime = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val size = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val modified = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val flags = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_FLAGS)
            val rows = ArrayList<DocumentEntry>(it.count.coerceAtLeast(0))
            while (it.moveToNext()) {
                val documentId = it.getString(id) ?: continue
                val displayName = it.getString(name) ?: continue
                val mimeType = it.getString(mime).orEmpty()
                rows += DocumentEntry(
                    documentId = documentId,
                    name = displayName,
                    mimeType = mimeType,
                    isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR,
                    sizeBytes = if (it.isNull(size)) -1L else it.getLong(size),
                    lastModifiedMillis = if (it.isNull(modified)) 0L else it.getLong(modified),
                    flags = if (it.isNull(flags)) 0 else it.getInt(flags),
                )
            }
            // settle() only reads loading and error; the rows travel beside the page.
            SafPage(emptyList(), loading, error) to rows
        }
    }

    override fun readHead(documentId: String, maxBytes: Int): ByteArray? = try {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        resolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArray(maxBytes)
            var n = 0
            while (n < buffer.size) {
                val r = stream.read(buffer, n, buffer.size - n)
                if (r <= 0) break
                n += r
            }
            buffer.copyOf(n)
        }
    } catch (e: Exception) {
        null
    }

    companion object {
        val PROJECTION: Array<String> = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
    }
}
