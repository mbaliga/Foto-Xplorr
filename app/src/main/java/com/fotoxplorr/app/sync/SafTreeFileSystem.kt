package com.fotoxplorr.app.sync

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import java.io.IOException
import java.io.OutputStream
import java.util.Locale

/**
 * [SafFileSystem] over a document tree the person picked (a tree URI with a persisted grant), reached
 * through [DocumentsContract] child queries, like `SafBackupDestination`. Path -> document id is
 * resolved one segment at a time and cached for the life of the object. Not thread-safe; one run.
 * Never run on a device or against a real provider yet.
 */
class SafTreeFileSystem(context: Context, private val treeUri: Uri) : SafFileSystem {
    private val resolver = context.applicationContext.contentResolver
    private val rootId: String = DocumentsContract.getTreeDocumentId(treeUri)
    private val ids = HashMap<String, String>().apply { put("", rootId) }
    private val listed = HashSet<String>()

    override val rootLabel: String by lazy {
        query(documentUri(rootId), arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)) { it.getString(0) }
            ?.takeIf { it.isNotBlank() } ?: SyncCopy.FOLDER_FALLBACK_LABEL
    }

    override fun isRootUsable(): Boolean = try {
        resolver.persistedUriPermissions.any { it.uri == treeUri && it.isWritePermission } &&
            query(documentUri(rootId), arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID)) { true } == true
    } catch (e: SecurityException) {
        false
    }

    override fun stat(path: String): RemoteEntry? {
        val id = resolve(path) ?: return null
        val uri = documentUri(id)
        return query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED),
        ) { c ->
            RemoteEntry(
                path = path,
                sizeBytes = if (c.isNull(1)) -1L else c.getLong(1),
                isDirectory = c.getString(0) == DocumentsContract.Document.MIME_TYPE_DIR,
                modifiedMillis = if (c.isNull(2)) null else c.getLong(2),
            )
        }
    }

    override fun list(folder: String): List<RemoteEntry> {
        val id = resolve(folder) ?: return emptyList()
        listed += folder
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, id)
        val cursor = resolver.query(children, CHILD_COLUMNS, null, null, null) ?: throw IOException("The folder could not be read")
        return cursor.use { c ->
            if (c.extras?.getBoolean(DocumentsContract.EXTRA_LOADING, false) == true) throw IOException("The folder is still loading")
            c.extras?.getString(DocumentsContract.EXTRA_ERROR)?.let { throw IOException(it) }
            val out = ArrayList<RemoteEntry>()
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                val childPath = if (folder.isEmpty()) name else "$folder/$name"
                ids[childPath] = c.getString(0) ?: continue
                out += RemoteEntry(
                    childPath,
                    if (c.isNull(3)) -1L else c.getLong(3),
                    c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                    if (c.isNull(4)) null else c.getLong(4),
                )
            }
            out
        }
    }

    override fun mkdirs(path: String) {
        var current = ""
        for (segment in path.split('/').filter { it.isNotEmpty() }) {
            val next = if (current.isEmpty()) segment else "$current/$segment"
            if (resolve(next) == null) {
                val parentId = resolve(current) ?: throw IOException("The folder disappeared")
                val created = DocumentsContract.createDocument(resolver, documentUri(parentId), DocumentsContract.Document.MIME_TYPE_DIR, segment)
                    ?: throw IOException("The folder would not accept a new folder")
                ids[next] = DocumentsContract.getDocumentId(created)
            }
            current = next
        }
    }

    override fun create(path: String, truncateExisting: Boolean): OutputStream {
        val name = path.substringAfterLast('/')
        val parent = path.substringBeforeLast('/', "")
        val existingId = resolve(path)
        if (existingId != null) {
            if (!truncateExisting) throw IOException("A file with this name is already in the folder")
            return resolver.openOutputStream(documentUri(existingId), "wt") ?: throw IOException("The file could not be opened")
        }
        val parentId = resolve(parent) ?: throw IOException("The folder disappeared")
        val created = DocumentsContract.createDocument(resolver, documentUri(parentId), mimeFor(name), name)
            ?: throw IOException("The folder would not accept a new file")
        val id = DocumentsContract.getDocumentId(created)
        // A provider that renames (adds an extension, appends a number) would break the manifest's idea of the path.
        val actual = query(documentUri(id), arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)) { it.getString(0) }
        if (actual != null && actual != name) throw IOException("The folder renamed the file, so it was not written")
        ids[path] = id
        return resolver.openOutputStream(documentUri(id), "w") ?: throw IOException("The file could not be opened")
    }

    /** Document id of [path], or null when it does not exist. Fills the cache as it walks. */
    private fun resolve(path: String): String? {
        ids[path]?.let { return it }
        var current = ""
        var currentId = rootId
        for (segment in path.split('/').filter { it.isNotEmpty() }) {
            val next = if (current.isEmpty()) segment else "$current/$segment"
            val known = ids[next]
            if (known != null) {
                currentId = known
            } else {
                // One listing per folder per run fills the cache for every child; a name missing after that is not there.
                if (!listed.add(current)) return null
                list(current)
                currentId = ids[next] ?: return null
            }
            current = next
        }
        return currentId
    }

    private fun documentUri(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)

    private fun <T> query(uri: Uri, columns: Array<String>, read: (android.database.Cursor) -> T): T? =
        resolver.query(uri, columns, null, null, null)?.use { if (it.moveToFirst()) read(it) else null }

    private fun mimeFor(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return if (extension.isEmpty()) OCTET_STREAM else MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: OCTET_STREAM
    }

    private companion object {
        const val OCTET_STREAM = "application/octet-stream"
        val CHILD_COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}
