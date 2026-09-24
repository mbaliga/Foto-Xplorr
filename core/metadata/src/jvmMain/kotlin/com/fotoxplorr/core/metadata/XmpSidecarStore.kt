package com.fotoxplorr.core.metadata

import java.io.File

/**
 * Reads and writes an `.xmp` sidecar file next to a photo, over plain [File] -- the JVM-testable
 * half of WP1.10's sidecar support (MASTER-PLAN.md §3). [SidecarNaming] (commonMain) supplies the
 * filenames; this is only the I/O around them, kept in `jvmMain` for the same reason [XmpPacket]
 * itself is (its own class doc, ADR-010): a sidecar file's content IS an XMP packet, so writing
 * and parsing it reuses [XmpPacket.serialize]/[XmpPacket.parse] unchanged rather than duplicating
 * either.
 *
 * Deliberately plain-[File]-based, not `ContentResolver`/SAF-based: on Android, locating or
 * creating a sibling file next to a MediaStore asset under scoped storage is real, additional
 * platform work (resolving the asset's own tree/volume, requesting write consent for a NEW
 * document the user never explicitly picked) that needs an `Activity` for consent exactly the way
 * `MetadataWriter`'s own write path already does for the asset's own file -- see that class's
 * "Write permission" doc. That wiring is real Android integration work belonging in `:app`, not a
 * platform-agnostic capability this module can supply, and it is device-dependent to verify
 * properly (WP1.5/1.6/1.8's own reasoning for deferring device-only work this session). It is
 * deliberately NOT attempted in this increment; [read]/[write] here work against any [File] a
 * caller already has permission to touch (a `java.io.File`, or a copy staged the same way
 * `MetadataWriter.stageOriginalBytes` already stages the asset's own bytes today).
 */
object XmpSidecarStore {

    /**
     * The parsed sidecar for [originalFile], checking every name [SidecarNaming.candidateSidecarFileNames]
     * lists (both styles) in [originalFile]'s own directory, first match wins.
     *
     * Mirrors `:app`'s `readXmpAttribute`'s own three-way contract for the embedded case, not a
     * simpler two-way one: no sidecar file present at all is [XmpPacket.empty] (a safe, blank slate a
     * caller may populate and write out), while a sidecar file that EXISTS but fails to parse as
     * XML is `null` -- "skip this file's XMP entirely, don't touch it" -- never silently treated
     * as if nothing were there. See [XmpPacket.parse]'s own doc for why those two states must stay
     * distinguishable.
     */
    fun read(originalFile: File): XmpPacket? {
        val directory = originalFile.parentFile ?: return XmpPacket.empty()
        val sidecar = SidecarNaming.candidateSidecarFileNames(originalFile.name)
            .map { File(directory, it) }
            .firstOrNull { it.isFile }
            ?: return XmpPacket.empty()
        return XmpPacket.parse(sidecar.readText(Charsets.UTF_8))
    }

    /**
     * Serializes [packet] to the sidecar path [style] names for [originalFile], overwriting
     * whatever (if anything) is already at that exact path. Does NOT remove a sidecar under the
     * OTHER style that may also exist beside the same photo -- reconciling two sidecars for one
     * photo into a single tracked state is `asset_user.sidecar_state`'s own job
     * (ADR-011 §2: "Phase 3/4: IN_SYNC | DIRTY | CONFLICT"), not this increment's.
     */
    fun write(originalFile: File, packet: XmpPacket, style: SidecarStyle) {
        val directory = originalFile.parentFile
            ?: error("${originalFile.path} has no parent directory to write a sidecar into")
        val sidecar = File(directory, SidecarNaming.sidecarFileName(originalFile.name, style))
        sidecar.writeText(packet.serialize(), Charsets.UTF_8)
    }
}
