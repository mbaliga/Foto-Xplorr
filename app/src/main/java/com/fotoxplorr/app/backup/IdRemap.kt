package com.fotoxplorr.app.backup

import com.fotoxplorr.app.editor.JsonValue

/**
 * What identifies a photo besides its MediaStore id, which is only a row number on one phone:
 * after a restore, a reinstall or a move to another phone the same id can be a different photo.
 * The metadata backup writes this for every id it mentions; the encrypted backup has the same
 * facts in each [ManifestEntry].
 */
data class MediaIdentity(
    val displayName: String,
    val sizeBytes: Long,
    val dateTakenMillis: Long,
    val relativePath: String?,
)

class IdentityMatch(
    /** Backup id to the id of the same photo on this phone. */
    val oldToNew: Map<Long, Long>,
    /** Backup ids with no confident match; their metadata is dropped, never attached to a guess. */
    val unmatched: Set<Long>,
)

/** The result of rewriting metadata through an id map. */
class RemappedMetadata(
    val json: JsonValue,
    /** Distinct backup ids that had metadata but no mapping; the "412 photos were not restored" number. */
    val droppedIds: Set<Long>,
    /** Entries that were not a usable id at all (negative, fractional, not a number). */
    val malformedCount: Int,
) {
    val droppedCount: Int get() = droppedIds.size
}

/**
 * Pure id remapping, shared by the plain metadata backup (`MetadataBackup`) and the encrypted
 * backup's restore, so there is one definition of "which photo is this" and one list of every
 * place an id is stored. Both work on [JsonValue], not `org.json`, so they run in plain JVM
 * tests; the Android side converts at its boundary (`JSONObject.toString()` and
 * [com.fotoxplorr.app.editor.parseJson]).
 *
 * [LIBRARY_ID_ARRAYS], [LIBRARY_ID_ARRAYS_BY_NAME], [LIBRARY_CAPTIONS] and [LIBRARY_COLLECTIONS]
 * name every key of `LibraryStore.exportJson` that holds ids. A key added to the export without
 * being added here would keep the OLD ids after a remap, which attaches data to the wrong photos;
 * `IdRemapCoversExportTest` fails when the two drift apart.
 */
object IdRemap {
    /** Keys holding a flat array of ids. */
    val LIBRARY_ID_ARRAYS = setOf(
        "archivedIds",
        "machineCaptionIds",
        "everUnarchivedIds",
        "rejectedArchiveSuggestionIds",
        "suppressedMachineCaptionIds",
    )

    /** Keys holding an object of `name -> array of ids`. */
    val LIBRARY_ID_ARRAYS_BY_NAME = setOf("tags", "autoTags", "rejectedAutoTags")

    /** `{ "<id>": "caption text" }`. */
    const val LIBRARY_CAPTIONS = "captions"

    /** An array of `{ id, name, createdAtMillis, mediaIds }`. */
    const val LIBRARY_COLLECTIONS = "collections"

    /** Keys of the file around the library that hold ids. */
    val ENVELOPE_ID_ARRAYS = setOf("favoriteIds", "sensitiveIds")

    /** Every key of the exported library that the remap understands (the rest is copied untouched). */
    val LIBRARY_KEYS: Set<String> = LIBRARY_ID_ARRAYS + LIBRARY_ID_ARRAYS_BY_NAME + LIBRARY_CAPTIONS + LIBRARY_COLLECTIONS

    /**
     * Matches the photos a backup talks about against the phone's catalogue.
     *
     * First, a backup id that still exists with the same identity (name, size, and date taken
     * where both are known) keeps its id. The rest are matched by name and size, narrowed by date
     * taken and then by folder; more than one candidate left means the match is not confident and
     * the id is unmatched. A catalogue photo is claimed by at most one backup id. Ids are handled
     * in ascending order so the result does not depend on map ordering.
     */
    fun matchIdentities(wanted: Map<Long, MediaIdentity>, catalogue: Map<Long, MediaIdentity>): IdentityMatch {
        val oldToNew = LinkedHashMap<Long, Long>()
        val claimed = HashSet<Long>()
        val order = wanted.keys.sorted()

        for (old in order) {
            val here = catalogue[old] ?: continue
            if (sameFile(wanted.getValue(old), here)) {
                oldToNew[old] = old
                claimed += old
            }
        }

        val byNameAndSize = HashMap<Pair<String, Long>, MutableList<Long>>()
        for (id in catalogue.keys.sorted()) {
            val c = catalogue.getValue(id)
            byNameAndSize.getOrPut(c.displayName to c.sizeBytes) { ArrayList() } += id
        }
        for (old in order) {
            if (old in oldToNew) continue
            val w = wanted.getValue(old)
            var candidates = byNameAndSize[w.displayName to w.sizeBytes].orEmpty().filter { it !in claimed }
            if (candidates.size > 1) candidates = candidates.filter { datesCompatible(w.dateTakenMillis, catalogue.getValue(it).dateTakenMillis) }
            if (candidates.size > 1 && w.relativePath != null) {
                candidates = candidates.filter { catalogue.getValue(it).relativePath == w.relativePath }
            }
            val pick = candidates.singleOrNull() ?: continue
            if (!datesCompatible(w.dateTakenMillis, catalogue.getValue(pick).dateTakenMillis)) continue
            oldToNew[old] = pick
            claimed += pick
        }
        return IdentityMatch(oldToNew, order.filter { it !in oldToNew }.toSet())
    }

    /** The same file by name, size and date taken (a folder it moved to does not make it another file). */
    internal fun sameFile(a: MediaIdentity, b: MediaIdentity): Boolean =
        a.displayName == b.displayName && a.sizeBytes == b.sizeBytes && datesCompatible(a.dateTakenMillis, b.dateTakenMillis)

    /** A date of zero or less means "unknown" and agrees with anything. */
    private fun datesCompatible(a: Long, b: Long): Boolean = a <= 0 || b <= 0 || a == b

    /** Rewrites an exported library through [oldToNew]; keys it does not know are copied as they are. */
    fun remapLibrary(library: JsonValue, oldToNew: Map<Long, Long>): RemappedMetadata {
        val state = State(oldToNew)
        val out = remapLibraryWith(library, state)
        return RemappedMetadata(out, state.dropped, state.malformed)
    }

    /**
     * Rewrites a whole metadata file: the library (under `library`, or the file itself for the
     * older shape that had no envelope), `favoriteIds` and `sensitiveIds`. Other keys are kept.
     */
    fun remapEnvelope(root: JsonValue, oldToNew: Map<Long, Long>): RemappedMetadata {
        val state = State(oldToNew)
        val fields = LinkedHashMap(root.entriesOrEmpty())
        val nested = fields["library"] as? JsonValue.JsonObject
        if (nested != null) fields["library"] = remapLibraryWith(nested, state) else fields.putAll(remapLibraryWith(root, state).entriesOrEmpty())
        ENVELOPE_ID_ARRAYS.forEach { key -> fields[key]?.let { fields[key] = state.idArray(it) } }
        return RemappedMetadata(JsonValue.JsonObject(fields), state.dropped, state.malformed)
    }

    private fun remapLibraryWith(library: JsonValue, state: State): JsonValue {
        val fields = LinkedHashMap(library.entriesOrEmpty())
        for (key in LIBRARY_ID_ARRAYS) fields[key]?.let { fields[key] = state.idArray(it) }

        val keptTags = LinkedHashSet<String>()
        fields["tags"]?.let { tags ->
            val mapped = LinkedHashMap<String, JsonValue>()
            for ((tag, ids) in tags.entriesOrEmpty()) {
                val remapped = state.idArray(ids)
                // A tag with no photo left is not a tag; the import would not create it either.
                if (remapped.items.isNotEmpty()) {
                    mapped[tag] = remapped
                    keptTags += tag
                }
            }
            fields["tags"] = JsonValue.JsonObject(mapped)
        }
        fields["autoTags"]?.let { autoTags ->
            val mapped = LinkedHashMap<String, JsonValue>()
            for ((tag, ids) in autoTags.entriesOrEmpty()) {
                val remapped = state.idArray(ids)
                if (remapped.items.isNotEmpty() && tag in keptTags) mapped[tag] = remapped
            }
            fields["autoTags"] = JsonValue.JsonObject(mapped)
        }
        fields["rejectedAutoTags"]?.let { rejected ->
            val mapped = LinkedHashMap<String, JsonValue>()
            for ((tag, ids) in rejected.entriesOrEmpty()) {
                val remapped = state.idArray(ids)
                if (remapped.items.isNotEmpty()) mapped[tag] = remapped
            }
            fields["rejectedAutoTags"] = JsonValue.JsonObject(mapped)
        }
        fields[LIBRARY_CAPTIONS]?.let { fields[LIBRARY_CAPTIONS] = state.captions(it) }
        fields[LIBRARY_COLLECTIONS]?.let { collections ->
            val items = (collections as? JsonValue.JsonArray)?.items.orEmpty().map { collection ->
                val c = LinkedHashMap(collection.entriesOrEmpty())
                c["mediaIds"]?.let { c["mediaIds"] = state.idArray(it) }
                JsonValue.JsonObject(c)
            }
            fields[LIBRARY_COLLECTIONS] = JsonValue.JsonArray(items)
        }
        return JsonValue.JsonObject(fields)
    }

    private class State(val oldToNew: Map<Long, Long>) {
        val dropped = LinkedHashSet<Long>()
        var malformed = 0

        /** One id through the map, or null (and counted) when it has no mapping. */
        fun map(old: Long): Long? = oldToNew[old].also { if (it == null) dropped += old }

        fun wholeId(value: JsonValue): Long? {
            val number = (value as? JsonValue.JsonNumber)?.value
            if (number == null || number.isNaN() || number.isInfinite() || number != Math.floor(number) || number < 0) {
                malformed++
                return null
            }
            return number.toLong()
        }

        /** Maps each id, drops the unmapped, and removes duplicates the mapping created. */
        fun idArray(value: JsonValue): JsonValue.JsonArray {
            val out = LinkedHashSet<Long>()
            for (item in (value as? JsonValue.JsonArray)?.items.orEmpty()) {
                val old = wholeId(item) ?: continue
                map(old)?.let { out += it }
            }
            return JsonValue.JsonArray(out.map { JsonValue.JsonNumber(it.toDouble()) })
        }

        /** Captions are keyed by id; when two old ids land on one new id the lowest old id keeps it. */
        fun captions(value: JsonValue): JsonValue {
            val byOld = ArrayList<Pair<Long, JsonValue>>()
            for ((key, text) in value.entriesOrEmpty()) {
                val old = key.toLongOrNull()?.takeIf { it >= 0 }
                if (old == null) malformed++ else byOld += old to text
            }
            val out = LinkedHashMap<String, JsonValue>()
            for ((old, text) in byOld.sortedBy { it.first }) {
                val new = map(old) ?: continue
                out.putIfAbsent(new.toString(), text)
            }
            return JsonValue.JsonObject(out)
        }
    }

    private fun JsonValue.entriesOrEmpty(): Map<String, JsonValue> = (this as? JsonValue.JsonObject)?.entries.orEmpty()
}
