package com.fotoxplorr.app.metadata

import com.fotoxplorr.core.metadata.SidecarAnnotations

/** A folder held in memory; [readOnlyNames] model files we may read but not overwrite. */
class InMemorySidecarDirectory(
    override val canCreate: Boolean = true,
    override val uri: String = "mem://folder",
    private val readOnlyNames: Set<String> = emptySet(),
    var failWrites: Boolean = false,
) : SidecarDirectory {
    val files = linkedMapOf<String, ByteArray>()

    /** Other files in the folder (the originals): listed, never read. */
    val others = mutableSetOf<String>()

    override fun find(fileName: String): SidecarEntry? {
        val names = files.keys.toList()
        val name = SidecarNameMatch.indexOf(names, fileName)?.let { names[it] } ?: return null
        return SidecarEntry("$uri/$name", name, name !in readOnlyNames)
    }

    override fun listNames(): List<String> = files.keys.toList() + others

    override fun read(entry: SidecarEntry): ByteArray? = files[entry.fileName]

    override fun write(fileName: String, existing: SidecarEntry?, bytes: ByteArray): Boolean {
        if (failWrites) return false
        files[existing?.fileName ?: fileName] = bytes
        return true
    }
}

/**
 * Documents held in memory, with a failure switch for every step and a provider-style rename on
 * a clashing create (case-insensitive, as exFAT and the external-storage provider are).
 */
class InMemoryDocuments : DocumentAccess {
    val docs = linkedMapOf<String, ByteArray>()
    var failCreate = false
    var failWriteOf: (String) -> Boolean = { false }

    /** Bytes a failing write leaves behind (default: truncated to nothing, as "wt" does). */
    var partial: ByteArray = ByteArray(0)

    override fun create(displayName: String): String? {
        if (failCreate) return null
        var name = displayName
        var n = 1
        while (docs.keys.any { it.equals(name, ignoreCase = true) }) {
            name = displayName.substringBeforeLast('.') + " (${n++})." + displayName.substringAfterLast('.')
        }
        docs[name] = ByteArray(0)
        return name
    }

    override fun displayName(document: String): String? = document.takeIf { it in docs }

    override fun read(document: String): ByteArray? = docs[document]

    override fun write(document: String, bytes: ByteArray): Boolean {
        if (document !in docs) return false
        if (failWriteOf(document)) {
            docs[document] = partial
            return false
        }
        docs[document] = bytes
        return true
    }

    override fun delete(document: String): Boolean = docs.remove(document) != null
}

/** Scripted [SidecarService] for tests of whatever calls it later. Records every call. */
class FakeSidecarService(
    var readResult: (SidecarAsset) -> SidecarReadResult = { SidecarReadResult.NotFound(SidecarLocation.NeedsFolderGrant) },
    var writeResult: (SidecarAsset) -> SidecarWriteResult = { SidecarWriteResult.NeedsFolderGrant },
) : SidecarService {
    val reads = mutableListOf<SidecarAsset>()
    val writes = mutableListOf<Pair<SidecarAsset, SidecarAnnotations>>()

    override fun read(asset: SidecarAsset): SidecarReadResult {
        reads += asset
        return readResult(asset)
    }

    override fun write(
        asset: SidecarAsset,
        annotations: SidecarAnnotations,
        explicitUserAction: Boolean,
        removedKeywords: Set<String>,
        removedPaths: Set<String>,
    ): SidecarWriteResult {
        writes += asset to annotations
        return writeResult(asset)
    }
}
