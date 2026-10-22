package com.fotoxplorr.app.backup

import com.fotoxplorr.app.editor.JsonValue

/**
 * What a restore works from: the files of the newest backup state in the folder, as
 * [BackupSetLoader.load] found them, tidied for restoring.
 *
 *  - A file the backup knows about but whose pack is not in the folder any more is flagged
 *    [EntryFlag.MISSING] HERE, from the listing, not only from what the last backup run saw: the
 *    person may have lost a pack since. The planner reports it and writes nothing.
 *  - When a changed file has two records (the manifest still has the old one and a finished pack
 *    the manifest does not list yet has the new one), only the newest is restored: two versions of
 *    one file name would otherwise come back as a photo and its "(restored)" copy.
 *  - Sections (tags, locks, pins, edits) are only in a manifest. A folder that holds packs a run
 *    finished but never wrote a manifest for restores their photos and nothing else.
 *  - A manifest written before the sections were carried forward ([SectionMerge]) can hold less than
 *    the one before it (a run made on a phone that had lost its tags). When [from] is given that
 *    earlier snapshot's sections and the newest holds no more than them anywhere, they are folded in.
 */
class RestoreSet internal constructor(
    val entries: List<ManifestEntry>,
    private val manifest: BackupManifest?,
    /** The newest manifest's sections with the snapshot before it folded in; null when the newest is used as it is. */
    private val foldedSections: List<ManifestSection>? = null,
) {
    val header: ManifestHeader? get() = manifest?.header

    /** True when the newest snapshot held less than the one before it, so the earlier tags, locks and pins are put back too. */
    val usesEarlierSnapshot: Boolean get() = foldedSections != null

    fun section(name: String): JsonValue? =
        foldedSections?.firstOrNull { it.name == name }?.data ?: manifest?.section(name)

    /** Nothing a restore could put back: no file and no tags section. */
    val isEmpty: Boolean get() = entries.isEmpty() && info.metadata == null

    /** The facts the first card shows, before any choice is made. */
    val info: RestoreSetInfo by lazy { describe() }

    private fun describe(): RestoreSetInfo {
        val metadata = RestoreMetadata.describe(this)
        return RestoreSetInfo(
            generation = header?.generation,
            createdAtMillis = header?.createdAtMillis,
            appVersion = header?.appVersion,
            photos = entries.count { !it.isVideo },
            videos = entries.count { it.isVideo },
            totalBytes = entries.sumOf { it.sizeBytes },
            missing = entries.count { EntryFlag.MISSING in it.flags },
            hasProtected = entries.any { EntryFlag.WAS_PROTECTED in it.flags },
            metadata = metadata,
            folderLocks = BackupSections.locks(section(BackupSections.LOCKS)).size,
            pins = BackupSections.pins(section(BackupSections.PINS)).size,
            recipes = BackupSections.recipes(section(BackupSections.RECIPES)).size,
            earlierSnapshotUsed = usesEarlierSnapshot,
        )
    }

    companion object {
        fun from(loaded: LoadedSet, earlierSections: List<ManifestSection>? = null): RestoreSet {
            val present = loaded.packFileNames
            val newestPerFile = HashMap<String, ManifestEntry>()
            for (entry in loaded.known) {
                val held = newestPerFile[entry.fileKey]
                if (held == null || entry.entryId > held.entryId) newestPerFile[entry.fileKey] = entry
            }
            val entries = newestPerFile.values.sortedBy { it.entryId }.map { entry ->
                if (entry.packName in present || EntryFlag.MISSING in entry.flags) {
                    entry
                } else {
                    entry.copy(flags = entry.flags + EntryFlag.MISSING)
                }
            }
            val newest = loaded.manifest?.sections
            val folded = if (newest != null && earlierSections != null && SectionMerge.isFewer(newest, earlierSections)) {
                SectionMerge.union(earlierSections, newest, avoidIdsAbove = entries.maxOfOrNull { it.mediaId } ?: 0L)
            } else {
                null
            }
            return RestoreSet(entries, loaded.manifest, folded)
        }
    }
}

class RestoreSetInfo(
    val generation: Int?,
    val createdAtMillis: Long?,
    val appVersion: String?,
    val photos: Int,
    val videos: Int,
    val totalBytes: Long,
    val missing: Int,
    val hasProtected: Boolean,
    /** Null when the backup carries no tags section, or one this version cannot read. */
    val metadata: MetadataBackup.RestoreSummary?,
    val folderLocks: Int,
    val pins: Int,
    val recipes: Int,
    /** The newest snapshot held less than the one before it; the earlier tags, locks and pins are included. */
    val earlierSnapshotUsed: Boolean = false,
) {
    val files: Int get() = photos + videos
}

/** Reads the tags-and-collections file a manifest carries, and prepares it for this phone. */
object RestoreMetadata {
    /** The file in the manifest's `metadata` section, or null when there is none or it is unusable. */
    fun file(set: RestoreSet): MetadataBackup.MetadataFile? {
        val section = set.section(BackupSections.METADATA) ?: return null
        return (MetadataBackup.readTree(section) as? MetadataBackup.FileRead.Usable)?.file
    }

    /** Counts for the first card: what the section holds, with every backed-up photo taken as present. */
    fun describe(set: RestoreSet): MetadataBackup.RestoreSummary? {
        val file = file(set) ?: return null
        val everyone = set.entries.associate { it.mediaId to it.mediaId }
        return MetadataBackup.prepare(file, emptyMap(), everyone).summary
    }

    /**
     * The file made ready for this phone. [known] is old media id to new media id for every photo
     * the backup's own records settle; the rest are matched by identity against [catalogue].
     */
    fun prepare(set: RestoreSet, known: Map<Long, Long>, catalogue: Map<Long, MediaIdentity>): MetadataBackup.PreparedRestore? {
        val file = file(set) ?: return null
        return MetadataBackup.prepare(file, catalogue, known)
    }
}
