package com.fotoxplorr.app.backup

import com.fotoxplorr.app.editor.JsonValue
import com.fotoxplorr.app.editor.asArray
import com.fotoxplorr.app.editor.get
import com.fotoxplorr.app.editor.jsonObjectOf
import com.fotoxplorr.app.editor.toJson

/**
 * Keeps a manifest's sections from forgetting. Every manifest is a whole snapshot and its sections
 * (tags, collections, favourites, sensitive marks, folder locks, pins, edit recipes) are read from
 * the phone's own stores at the moment of the run. A phone that was wiped, or a new one, has none
 * of them, so a run made before the person restores would write sections that are empty over a
 * backup that is not, and a later restore would apply the empty ones: protected photos back with no
 * lock record, tags and favourites gone. [union] folds the previous snapshot's sections into the new
 * ones before they are written, additively and with the phone's current state winning where both
 * have an entry; [isFewer] says the phone is behind the backup.
 *
 * The price of "only ever adds": a tag, favourite, lock or pin the person removed since the last
 * backup is still in the next snapshot, and comes back at a restore.
 *
 * Photo ids in these sections are MediaStore ids, which are row numbers on one phone. Pins and
 * recipes are merged by that id as they stand (the backup has nothing else to tell their photos
 * apart by). The tags file has an identity block, so there an id that means two different photos
 * in the two snapshots is renumbered on the previous side instead of being merged into one.
 */
object SectionMerge {
    /**
     * [previous] folded into [current], section by section: metadata through
     * [MetadataBackup.mergeLibraries] (current wins a conflict), locks by folder key, pins and
     * recipes by id (current wins). A section only one side has is carried. [avoidIdsAbove] is the
     * highest photo id the caller already uses elsewhere (the entries'), so a renumbered id cannot meet it.
     */
    fun union(previous: List<ManifestSection>, current: List<ManifestSection>, avoidIdsAbove: Long = 0): List<ManifestSection> {
        val before = previous.associateBy { it.name }
        val now = current.associateBy { it.name }
        val names = (current.map { it.name } + previous.map { it.name }).distinct()
        return names.map { name ->
            val old = before[name]
            val new = now[name]
            when {
                old == null -> new!!
                new == null -> old
                else -> ManifestSection(name, mergeOne(name, old.data, new.data, avoidIdsAbove))
            }
        }
    }

    private fun mergeOne(name: String, old: JsonValue, new: JsonValue, avoidIdsAbove: Long): JsonValue = when (name) {
        BackupSections.METADATA -> mergeMetadata(old, new, avoidIdsAbove) ?: new
        BackupSections.LOCKS -> mergeLocks(old, new)
        BackupSections.PINS -> mergeById(old, new)
        BackupSections.RECIPES -> mergeById(old, new)
        else -> new
    }

    private fun mergeLocks(old: JsonValue, new: JsonValue): JsonValue {
        val byKey = LinkedHashMap<String, BackupSections.Lock>()
        BackupSections.locks(old).forEach { byKey[it.folderKey] = it }
        BackupSections.locks(new).forEach { byKey[it.folderKey] = it }
        return JsonValue.JsonArray(
            byKey.values.map { jsonObjectOf("key" to it.folderKey.toJson(), "salt" to it.saltBase64.toJson(), "hash" to it.hashBase64.toJson()) },
        )
    }

    /** Pins and recipes are arrays of objects with a numeric `id`; the current one replaces an old one with the same id. */
    private fun mergeById(old: JsonValue, new: JsonValue): JsonValue {
        val byId = LinkedHashMap<Long, JsonValue>()
        val order = ArrayList<Long>()
        for (item in old.asArray() + new.asArray()) {
            val id = (item["id"] as? JsonValue.JsonNumber)?.value?.takeIf { it >= 0 && it == Math.floor(it) }?.toLong() ?: continue
            if (id !in byId) order += id
            byId[id] = item
        }
        return JsonValue.JsonArray(order.sorted().map { byId.getValue(it) })
    }

    /**
     * The tags file of both snapshots as one. Null when the old one cannot be read as a tags file
     * (the caller then keeps the new one alone: nothing of an unreadable file can be trusted).
     */
    private fun mergeMetadata(old: JsonValue, new: JsonValue, avoidIdsAbove: Long): JsonValue? {
        val oldFile = (MetadataBackup.readTree(old) as? MetadataBackup.FileRead.Usable)?.file ?: return null
        val newFile = (MetadataBackup.readTree(new) as? MetadataBackup.FileRead.Usable)?.file ?: return null

        val oldIds = MetadataBackup.mentionedIds(oldFile.envelope)
        val newIds = MetadataBackup.mentionedIds(newFile.envelope)
        val oldIdentity = oldFile.identity
        val newIdentity = newFile.identity.orEmpty()

        // An id both snapshots use is one photo only if both say who it is and say the same thing.
        val clashing = oldIds.filter { id ->
            id in newIds && (oldIdentity == null || oldIdentity[id] == null || oldIdentity[id] != newIdentity[id])
        }.sorted()
        var next = maxOf(
            avoidIdsAbove,
            oldIds.maxOrNull() ?: 0L,
            newIds.maxOrNull() ?: 0L,
            oldIdentity?.keys?.maxOrNull() ?: 0L,
            newIdentity.keys.maxOrNull() ?: 0L,
        ) + 1
        val renumbered = HashMap<Long, Long>()
        for (id in clashing) renumbered[id] = next++

        val idMap = oldIds.associateWith { renumbered[it] ?: it }
        val moved = IdRemap.remapEnvelope(oldFile.envelope, idMap).json
        val library = MetadataBackup.mergeLibraries(newFile.envelope[MetadataBackup.KEY_LIBRARY], moved[MetadataBackup.KEY_LIBRARY])

        val identity = LinkedHashMap<Long, MediaIdentity>()
        oldIdentity?.forEach { (id, who) -> identity[renumbered[id] ?: id] = who }
        identity.putAll(newIdentity)

        val favourites = ids(newFile.envelope[MetadataBackup.KEY_FAVORITES]) + ids(moved[MetadataBackup.KEY_FAVORITES])
        val sensitive = ids(newFile.envelope[MetadataBackup.KEY_SENSITIVE]) + ids(moved[MetadataBackup.KEY_SENSITIVE])
        return MetadataBackup.buildFile(library, favourites, sensitive, identity, newFile.exportedAtMillis ?: 0L).json
    }

    private fun ids(value: JsonValue): Set<Long> =
        value.asArray().mapNotNullTo(linkedSetOf()) { item ->
            (item as? JsonValue.JsonNumber)?.value?.takeIf { it >= 0 && it == Math.floor(it) }?.toLong()
        }

    // ---- how much a snapshot's sections hold ----

    /** The counts [isFewer] compares. A section that cannot be read counts as nothing. */
    class Size(
        val photos: Int,
        val favourites: Int,
        val sensitive: Int,
        val locks: Int,
        val pins: Int,
        val recipes: Int,
    ) {
        private fun all() = listOf(photos, favourites, sensitive, locks, pins, recipes)

        fun isEmpty(): Boolean = all().all { it == 0 }

        /** At most as much as [other] in every count, and less in at least one. */
        fun isFewerThan(other: Size): Boolean {
            val mine = all()
            val theirs = other.all()
            return mine.indices.all { mine[it] <= theirs[it] } && mine.indices.any { mine[it] < theirs[it] }
        }
    }

    fun sizeOf(sections: List<ManifestSection>): Size {
        val byName = sections.associate { it.name to it.data }
        val file = byName[BackupSections.METADATA]?.let { (MetadataBackup.readTree(it) as? MetadataBackup.FileRead.Usable)?.file }
        return Size(
            photos = file?.let { MetadataBackup.mentionedIds(it.envelope).size } ?: 0,
            favourites = file?.let { ids(it.envelope[MetadataBackup.KEY_FAVORITES]).size } ?: 0,
            sensitive = file?.let { ids(it.envelope[MetadataBackup.KEY_SENSITIVE]).size } ?: 0,
            locks = BackupSections.locks(byName[BackupSections.LOCKS]).size,
            pins = BackupSections.pins(byName[BackupSections.PINS]).size,
            recipes = BackupSections.recipes(byName[BackupSections.RECIPES]).size,
        )
    }

    /** True when [current] holds no more than [previous] anywhere and less somewhere: the phone is behind the backup. */
    fun isFewer(current: List<ManifestSection>, previous: List<ManifestSection>): Boolean =
        sizeOf(current).isFewerThan(sizeOf(previous))
}
