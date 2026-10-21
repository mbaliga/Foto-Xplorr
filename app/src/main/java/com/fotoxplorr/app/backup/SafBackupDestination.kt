package com.fotoxplorr.app.backup

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * A backup folder the person picked with the system folder picker (a tree URI), reached through
 * [DocumentsContract] child-document queries. `DocumentFile.listFiles()` asks the provider about
 * every file one at a time, which is slow for a folder of hundreds of packs and answers an empty
 * list for a failed query; the queries here read one cursor and fail loudly.
 *
 * What the picker returns can be a card, a USB drive, the Downloads folder, or a folder owned by
 * another app (a cloud service's). Fotoz writes the same files to all of them; see
 * [isManagedByAnotherApp] for the one place that matters (what happens to the files afterwards is
 * that app's business, not Fotoz's).
 *
 * Not thread-safe: the name to document-id table is filled by [list] and [create]. One writer.
 */
class SafBackupDestination(context: Context, private val treeUri: Uri) : BackupDestination {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
    private val rootUri: Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocumentId)
    private val documentIds = HashMap<String, String>()

    override val label: String by lazy {
        queryString(rootUri, DocumentsContract.Document.COLUMN_DISPLAY_NAME)?.takeIf { it.isNotBlank() }
            ?: SafDocumentIds.fallbackLabel(rootDocumentId)
    }

    /** True when a provider other than the device's own storage serves the folder (for example a cloud app's). */
    val isManagedByAnotherApp: Boolean get() = SafDocumentIds.isManagedByAnotherApp(treeUri.authority)

    /** True when the folder is on the phone's main storage, the same place the photos are. */
    val isOnPhoneStorage: Boolean
        get() = treeUri.authority == SafDocumentIds.EXTERNAL_STORAGE_AUTHORITY &&
            SafDocumentIds.volumeOf(rootDocumentId) == SafDocumentIds.PRIMARY_VOLUME

    override fun list(): List<DestinationFile> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootDocumentId)
        val cursor = resolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null,
            null,
            null,
        ) ?: throw IOException("The folder could not be read")
        val files = ArrayList<DestinationFile>()
        val ids = HashMap<String, String>()
        cursor.use {
            val idColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val sizeColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val mimeColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (it.moveToNext()) {
                if (it.getString(mimeColumn) == DocumentsContract.Document.MIME_TYPE_DIR) continue
                val name = it.getString(nameColumn) ?: continue
                files += DestinationFile(name, if (it.isNull(sizeColumn)) -1L else it.getLong(sizeColumn))
                ids[name] = it.getString(idColumn)
            }
        }
        documentIds.clear()
        documentIds.putAll(ids)
        return files
    }

    override fun open(name: String): InputStream {
        val uri = documentUri(name)
        return resolver.openInputStream(uri) ?: throw IOException("The file could not be opened")
    }

    override fun create(name: String): OutputStream {
        if (documentIds.containsKey(name)) throw IOException("A file with this name is already in the folder")
        val created = DocumentsContract.createDocument(resolver, rootUri, OCTET_STREAM, name)
            ?: throw IOException("The folder would not accept a new file")
        val shownName = queryString(created, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        if (shownName != name) {
            // A provider that adds an extension or a number is one the set's file names cannot rely on.
            runCatching { DocumentsContract.deleteDocument(resolver, created) }
            throw IOException("The folder renamed a file Fotoz created, so it cannot be used for a backup")
        }
        documentIds[name] = DocumentsContract.getDocumentId(created)
        return resolver.openOutputStream(created, "w") ?: throw IOException("The file could not be opened for writing")
    }

    override fun sizeOf(name: String): Long {
        val uri = documentUri(name)
        val cursor = resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null) ?: return -1L
        return cursor.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L }
    }

    override fun delete(name: String): Boolean {
        val id = documentIds[name] ?: return false
        val deleted = runCatching {
            DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(treeUri, id))
        }.getOrDefault(false)
        if (deleted) documentIds.remove(name)
        return deleted
    }

    override fun freeBytes(): Long? {
        if (treeUri.authority != SafDocumentIds.EXTERNAL_STORAGE_AUTHORITY) return null
        val volume = SafDocumentIds.volumeOf(rootDocumentId)
        val free = runCatching {
            if (volume == SafDocumentIds.PRIMARY_VOLUME) {
                Environment.getExternalStorageDirectory().usableSpace
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val manager = appContext.getSystemService(StorageManager::class.java)
                manager?.storageVolumes?.firstOrNull { it.uuid.equals(volume, ignoreCase = true) }?.directory?.usableSpace
            } else {
                null
            }
        }.getOrNull()
        // 0 is what the platform answers when it cannot tell; a really full card fails on the first write.
        return free?.takeIf { it > 0 }
    }

    private fun documentUri(name: String): Uri {
        val id = documentIds[name] ?: throw IOException("This file is not in the folder")
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
    }

    private fun queryString(uri: Uri, column: String): String? =
        runCatching {
            resolver.query(uri, arrayOf(column), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()

    private companion object {
        const val OCTET_STREAM = "application/octet-stream"
    }
}

/** The parts of a storage-access document id and authority the destination reasons about; pure. */
object SafDocumentIds {
    const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    const val DOWNLOADS_AUTHORITY = "com.android.providers.downloads.documents"
    const val PRIMARY_VOLUME = "primary"

    /** `primary:Backups/Fotoz` is volume `primary`; `1A2B-3C4D:Photos` is the card with that uuid. */
    fun volumeOf(documentId: String): String = documentId.substringBefore(':')

    fun fallbackLabel(documentId: String): String =
        documentId.substringAfter(':', "").substringAfterLast('/').ifBlank { "the folder" }

    /** Anything that is not the device's own storage or the Downloads folder belongs to some other app. */
    fun isManagedByAnotherApp(authority: String?): Boolean =
        authority != null && authority != EXTERNAL_STORAGE_AUTHORITY && authority != DOWNLOADS_AUTHORITY
}
