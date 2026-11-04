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

    override fun find(fileName: String): SidecarEntry? =
        files[fileName]?.let { SidecarEntry("$uri/$fileName", fileName, fileName !in readOnlyNames) }

    override fun read(entry: SidecarEntry): ByteArray? = files[entry.fileName]

    override fun write(fileName: String, existing: SidecarEntry?, bytes: ByteArray): Boolean {
        if (failWrites) return false
        files[fileName] = bytes
        return true
    }
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
