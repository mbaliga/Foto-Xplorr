package com.fotoxplorr.app.metadata

import com.fotoxplorr.core.metadata.SidecarNaming
import com.fotoxplorr.core.metadata.SidecarStyle

/**
 * Where an asset's `.xmp` sidecar is, or why we cannot say. Under scoped storage a MediaStore row
 * gives us the photo's bytes but NO right to create or read a sibling file: that needs either a
 * granted SAF tree covering the folder or a plain path the app owns. This type exists so the UI
 * never has to guess, and so no code path pretends a write happened.
 */
sealed interface SidecarLocation {
    /**
     * We may write. [exists] true: [uri] is the sidecar itself. [exists] false: [uri] is the
     * FOLDER the sidecar [fileName] would be created in (a SAF document has no uri before it exists).
     */
    data class Writable(val uri: String, val fileName: String, val exists: Boolean) : SidecarLocation

    /** A sidecar exists and can be read, but not written (read-only grant, read-only volume). */
    data class ReadOnly(val uri: String, val fileName: String) : SidecarLocation

    /** The asset's folder is not reachable with what we hold: ask the user to pick/grant the folder. */
    data object NeedsFolderGrant : SidecarLocation

    /** Not possible here at all (no parent folder, a virtual/cloud-only provider ...). */
    data class Unsupported(val reason: String) : SidecarLocation
}

/** One file found in a folder. */
data class SidecarEntry(val uri: String, val fileName: String, val writable: Boolean)

/**
 * A folder we can look into, by name. Two real implementations ([FileSidecarDirectory],
 * `SafSidecarDirectory`) and an in-memory one in tests; the glue above is the same for all.
 */
interface SidecarDirectory {
    /** Identifies the folder (a file: URI or a SAF document uri). */
    val uri: String

    /** We may create a new file in this folder. */
    val canCreate: Boolean

    fun find(fileName: String): SidecarEntry?

    fun read(entry: SidecarEntry): ByteArray?

    /** Creates [fileName] (or overwrites [existing]) with [bytes]. False on any failure; never throws. */
    fun write(fileName: String, existing: SidecarEntry?, bytes: ByteArray): Boolean
}

/** What we know about the folder holding an asset. */
sealed interface SidecarParent {
    data class Directory(val directory: SidecarDirectory) : SidecarParent

    /** Only a MediaStore row: no path we can use and no granted tree. */
    data object NoGrant : SidecarParent

    data class Unsupported(val reason: String) : SidecarParent
}

object SidecarLocator {

    /** Pure and total: every input maps to exactly one honest [SidecarLocation]. */
    fun locate(originalFileName: String, parent: SidecarParent, style: SidecarStyle): SidecarLocation = when (parent) {
        SidecarParent.NoGrant -> SidecarLocation.NeedsFolderGrant
        is SidecarParent.Unsupported -> SidecarLocation.Unsupported(parent.reason)
        is SidecarParent.Directory -> {
            val dir = parent.directory
            val existing = SidecarNaming.candidateSidecarFileNames(originalFileName, style)
                .firstNotNullOfOrNull { dir.find(it) }
            when {
                existing != null && existing.writable -> SidecarLocation.Writable(existing.uri, existing.fileName, true)
                existing != null -> SidecarLocation.ReadOnly(existing.uri, existing.fileName)
                dir.canCreate -> SidecarLocation.Writable(
                    dir.uri, SidecarNaming.sidecarFileName(originalFileName, style), false,
                )
                // a folder we can look into but not create in: a read grant is not enough
                else -> SidecarLocation.NeedsFolderGrant
            }
        }
    }
}
