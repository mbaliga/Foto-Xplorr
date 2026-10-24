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
 * Photo ids in these sections are MediaStore ids, which are row numbers on one phone. The tags file
 * has an identity block, and so does each pin and recipe record (name, size, date taken, folder): an
 * id that means two different photos in the two snapshots is renumbered on the previous side instead
 * of being merged into one, so a wiped phone's pin for id 7 never overwrites the backup's pin for
 * another photo that had id 7, and one photo seen under two ids is one record. A record without an
 * identity (from before the field) is taken to be the same phone's photo, as it always was.
 */
object SectionMerge {
    /**
     * [previous] folded into [current], section by section: metadata through
     * [MetadataBackup.mergeLibraries] (current wins a conflict), locks by folder key, pins and
     * recipes by photo (current wins). A section only one side has is carried. [avoidIdsAbove] is the
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
        BackupSections.PINS -> mergeByPhoto(old, new, avoidIdsAbove)
        BackupSections.RECIPES -> mergeByPhoto(old, new, avoidIdsAbove)
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

    /**
     * Pins and recipes are arrays of objects with a numeric `id` and, in newer files, an `identity`.
     * The current record replaces an old one for the same photo: the same id with a compatible
     * identity (or none, which is the same phone's), or the same identity under another id. An old
     * record whose id the current side uses for a different photo keeps its place under a fresh id
     * above every id in use, so it is neither lost nor attached to the wrong photo.
     */
    private fun mergeByPhoto(old: JsonValue, new: JsonValue, avoidIdsAbove: Long): JsonValue {
        val current = recordsOf(new)
        val previous = recordsOf(old)
        val kept = LinkedHashMap<Long, Record>()
        current.forEach { kept[it.id] = it }
        val identities = current.mapNotNull { it.identity }.groupBy { it.displayName to it.sizeBytes }
        var next = maxOf(avoidIdsAbove, current.maxOfOrNull { it.id } ?: 0L, previous.maxOfOrNull { it.id } ?: 0L) + 1
        for (record in previous) {
            val who = record.identity
            if (who != null && identities[who.displayName to who.sizeBytes].orEmpty().any { IdRemap.sameFile(it, who) }) continue
            val holder = kept[record.id]
            when {
                holder == null -> kept[record.id] = record
                sameOwner(holder.identity, who) -> Unit
                else -> {
                    val id = next++
                    kept[id] = record.copy(id = id)
                }
            }
        }
        return JsonValue.JsonArray(kept.toSortedMap().values.map { it.item })
    }

    /** Whether two records under one id are about one photo; a record that does not say is the same phone's. */
    private fun sameOwner(a: MediaIdentity?, b: MediaIdentity?): Boolean = a == null || b == null || IdRemap.sameFile(a, b)

    private class Record(val id: Long, val identity: MediaIdentity?, val item: JsonValue) {
        /** The record under another id, every other field as it was. */
        fun copy(id: Long): Record {
            val fields = LinkedHashMap((item as JsonValue.JsonObject).entries)
            fields["id"] = id.toJson()
            return Record(id, identity, JsonValue.JsonObject(fields))
        }
    }

    /** The usable records of a pins or recipes section; later ones of the same id replace earlier ones. */
    private fun recordsOf(section: JsonValue): List<Record> {
        val byId = LinkedHashMap<Long, Record>()
        for (item in section.asArray()) {
            val fields = (item as? JsonValue.JsonObject)?.entries ?: continue
            val id = (fields["id"] as? JsonValue.JsonNumber)?.value?.takeIf { it >= 0 && it == Math.floor(it) }?.toLong() ?: continue
            byId[id] = Record(id, BackupSections.identityOf(fields), item)
        }
        return byId.values.toList()
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
