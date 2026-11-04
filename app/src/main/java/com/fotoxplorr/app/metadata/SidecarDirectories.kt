package com.fotoxplorr.app.metadata

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/** A plain folder: the app's own folders and Pictures/Fotoz-style paths. JVM-testable. */
class FileSidecarDirectory(private val folder: File) : SidecarDirectory {
    override val uri: String get() = folder.toURI().toString()
    override val canCreate: Boolean get() = folder.isDirectory && folder.canWrite()

    override fun find(fileName: String): SidecarEntry? {
        val f = File(folder, fileName)
        return if (f.isFile) SidecarEntry(f.toURI().toString(), fileName, f.canWrite()) else null
    }

    override fun read(entry: SidecarEntry): ByteArray? = runCatching { File(java.net.URI(entry.uri)).readBytes() }.getOrNull()

    override fun write(fileName: String, existing: SidecarEntry?, bytes: ByteArray): Boolean = runCatching {
        val target = File(folder, fileName)
        val temp = File(folder, "$fileName.tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(target)) {
            target.delete()
            check(temp.renameTo(target))
        }
    }.isSuccess
}

/**
 * A folder inside a SAF tree the user granted ([treeUri] from ACTION_OPEN_DOCUMENT_TREE, with a
 * persisted read+write permission), found by [parentDocumentId]. Child lookup is by display name
 * over the folder's children (SAF has no "find by name" query a provider is obliged to honour).
 *
 * UNVERIFIED on a device: written against the documented DocumentsContract API only. Things to
 * check there: a USB-drive tree (volume ids), providers that rename `x.xmp` on create for an
 * unknown mime type, and `"wt"` truncation on overwrite.
 */
class SafSidecarDirectory(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
    private val parentDocumentId: String,
    private val grantedWrite: Boolean,
) : SidecarDirectory {

    private val parentUri: Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocumentId)
    override val uri: String get() = parentUri.toString()
    override val canCreate: Boolean get() = grantedWrite

    override fun find(fileName: String): SidecarEntry? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
        return runCatching {
            resolver.query(children, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    if (c.getString(1) == fileName) {
                        val writable = grantedWrite &&
                            (c.getInt(2) and DocumentsContract.Document.FLAG_SUPPORTS_WRITE) != 0
                        return@use SidecarEntry(
                            DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0)).toString(),
                            fileName,
                            writable,
                        )
                    }
                }
                null
            }
        }.getOrNull()
    }

    override fun read(entry: SidecarEntry): ByteArray? = runCatching {
        resolver.openInputStream(Uri.parse(entry.uri))?.use { it.readBytes() }
    }.getOrNull()

    override fun write(fileName: String, existing: SidecarEntry?, bytes: ByteArray): Boolean = runCatching {
        val target = existing?.let { Uri.parse(it.uri) }
            ?: DocumentsContract.createDocument(resolver, parentUri, "application/octet-stream", fileName)
            ?: return false
        // "wt": truncate. Plain "w" may leave the old tail behind on some providers.
        resolver.openOutputStream(target, "wt")?.use { it.write(bytes) } != null
    }.getOrDefault(false)
}

/**
 * Pure mapping from a MediaStore row (volume + RELATIVE_PATH) to the SAF document id of its
 * folder inside a granted tree, for the external-storage provider's `volume:path` id scheme.
 * Null when the tree does not cover the folder (then the answer is [SidecarParent.NoGrant]).
 */
object SafParentResolver {

    /** MediaStore calls the primary volume "external_primary"; the provider calls it "primary". */
    fun providerVolumeId(mediaStoreVolume: String): String =
        if (mediaStoreVolume == "external_primary" || mediaStoreVolume == "external") "primary" else mediaStoreVolume

    fun parentDocumentId(treeDocumentId: String, mediaStoreVolume: String, relativePath: String): String? {
        val treeVolume = treeDocumentId.substringBefore(':', missingDelimiterValue = "")
        val treePath = treeDocumentId.substringAfter(':', "").trim('/')
        if (treeVolume.isEmpty() || !treeVolume.equals(providerVolumeId(mediaStoreVolume), ignoreCase = true)) return null
        val rel = relativePath.trim('/')
        if (rel.split('/').any { it == ".." }) return null
        val covered = treePath.isEmpty() || rel == treePath || rel.startsWith("$treePath/")
        if (!covered) return null
        return "$treeVolume:$rel"
    }
}
