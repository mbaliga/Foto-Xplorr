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
        val exact = File(folder, fileName)
        val f = exact.takeIf { it.isFile }
            ?: listNames()?.let { names -> SidecarNameMatch.indexOf(names, fileName)?.let { File(folder, names[it]) } }
                ?.takeIf { it.isFile }
            ?: return null
        return SidecarEntry(f.toURI().toString(), f.name, f.canWrite())
    }

    override fun listNames(): List<String>? = folder.list()?.toList()

    override fun read(entry: SidecarEntry): ByteArray? = runCatching { File(java.net.URI(entry.uri)).readBytes() }.getOrNull()

    override fun write(fileName: String, existing: SidecarEntry?, bytes: ByteArray): Boolean = runCatching {
        // An existing file keeps its own name (IMG.CR3.XMP is not joined by a second IMG.CR3.xmp).
        val target = File(folder, existing?.fileName ?: fileName)
        val temp = File(folder, "${target.name}.tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(target)) {
            target.delete()
            check(temp.renameTo(target))
        }
    }.isSuccess
}

/** Display-name matching for a folder listing: an exact match first, else one that differs only in case. */
object SidecarNameMatch {
    fun indexOf(names: List<String>, fileName: String): Int? =
        names.indexOf(fileName).takeIf { it >= 0 }
            ?: names.indexOfFirst { it.equals(fileName, ignoreCase = true) }.takeIf { it >= 0 }
}

/**
 * The document operations [SafeDocumentWrite] needs, by document uri string. The SAF one lives in
 * [SafSidecarDirectory]; tests use an in-memory one that can fail at any step.
 */
interface DocumentAccess {
    /** Creates a document asking for [displayName]; its uri, or null. The provider may rename it. */
    fun create(displayName: String): String?

    fun displayName(document: String): String?

    fun read(document: String): ByteArray?

    /** Truncates and writes. False (never throws) on any failure, which may leave it partial. */
    fun write(document: String, bytes: ByteArray): Boolean

    fun delete(document: String): Boolean
}

/**
 * Writes over SAF without ever leaving a sidecar truncated or under a name we did not ask for.
 * SAF has no atomic replace (renaming onto an existing name is not offered), so an overwrite
 * keeps the original document and guards it: its old bytes go to a `.bak` document first, the
 * new bytes are written and READ BACK, and on any mismatch the old bytes are written back. The
 * backup is deleted only once the original is known good (new or restored); if even the restore
 * fails the backup stays beside it, so a darktable history is never only in memory.
 */
object SafeDocumentWrite {

    /** A new document named exactly [fileName] holding [bytes]; false (and nothing left behind) otherwise. */
    fun create(access: DocumentAccess, fileName: String, bytes: ByteArray): Boolean {
        val document = access.create(fileName) ?: return false
        // A provider that found a clash (case-insensitive storage) names it "x (1).xmp": not ours.
        val ok = access.displayName(document) == fileName &&
            access.write(document, bytes) &&
            access.read(document)?.contentEquals(bytes) == true
        if (!ok) access.delete(document)
        return ok
    }

    fun overwrite(access: DocumentAccess, existing: String, fileName: String, bytes: ByteArray): Boolean {
        val old = access.read(existing) ?: return false
        val backup = access.create("$fileName.bak") ?: return false
        if (!(access.write(backup, old) && access.read(backup)?.contentEquals(old) == true)) {
            access.delete(backup)
            return false
        }
        if (access.write(existing, bytes) && access.read(existing)?.contentEquals(bytes) == true) {
            access.delete(backup)
            return true
        }
        val intact = access.read(existing)?.contentEquals(old) == true ||
            (access.write(existing, old) && access.read(existing)?.contentEquals(old) == true)
        if (intact) access.delete(backup)
        return false
    }
}

/**
 * A folder inside a SAF tree the user granted ([treeUri] from ACTION_OPEN_DOCUMENT_TREE, with a
 * persisted read+write permission), found by [parentDocumentId]. Child lookup is by display name
 * over the folder's children (SAF has no "find by name" query a provider is obliged to honour),
 * ignoring case; writes go through [SafeDocumentWrite].
 *
 * UNVERIFIED on a device: written against the documented DocumentsContract API only. Things to
 * check there: a USB-drive tree (volume ids), providers that rename `x.xmp` on create for an
 * unknown mime type (refused by [SafeDocumentWrite.create]), and `"wt"` truncation on overwrite.
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

    private data class Child(val documentId: String, val name: String, val flags: Int)

    private fun children(): List<Child>? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
        return runCatching {
            resolver.query(children, projection, null, null, null)?.use { c ->
                buildList { while (c.moveToNext()) add(Child(c.getString(0), c.getString(1) ?: "", c.getInt(2))) }
            }
        }.getOrNull()
    }

    override fun listNames(): List<String>? = children()?.map { it.name }

    override fun find(fileName: String): SidecarEntry? {
        val all = children() ?: return null
        val child = all.getOrNull(SidecarNameMatch.indexOf(all.map { it.name }, fileName) ?: return null) ?: return null
        val writable = grantedWrite && (child.flags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE) != 0
        return SidecarEntry(
            DocumentsContract.buildDocumentUriUsingTree(treeUri, child.documentId).toString(),
            child.name,
            writable,
        )
    }

    override fun read(entry: SidecarEntry): ByteArray? = access.read(entry.uri)

    override fun write(fileName: String, existing: SidecarEntry?, bytes: ByteArray): Boolean =
        if (existing != null) SafeDocumentWrite.overwrite(access, existing.uri, existing.fileName, bytes)
        else SafeDocumentWrite.create(access, fileName, bytes)

    private val access = object : DocumentAccess {
        override fun create(displayName: String): String? = runCatching {
            DocumentsContract.createDocument(resolver, parentUri, "application/octet-stream", displayName)?.toString()
        }.getOrNull()

        override fun displayName(document: String): String? = runCatching {
            resolver.query(Uri.parse(document), arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()

        override fun read(document: String): ByteArray? = runCatching {
            resolver.openInputStream(Uri.parse(document))?.use { it.readBytes() }
        }.getOrNull()

        // "wt": truncate. Plain "w" may leave the old tail behind on some providers.
        override fun write(document: String, bytes: ByteArray): Boolean = runCatching {
            resolver.openOutputStream(Uri.parse(document), "wt")?.use { it.write(bytes); it.flush() } != null
        }.getOrDefault(false)

        override fun delete(document: String): Boolean = runCatching {
            DocumentsContract.deleteDocument(resolver, Uri.parse(document))
        }.getOrDefault(false)
    }
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
