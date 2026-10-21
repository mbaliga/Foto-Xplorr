package com.fotoxplorr.app.backup

import com.fotoxplorr.app.editor.JsonValue
import com.fotoxplorr.app.editor.asArray
import com.fotoxplorr.app.editor.asLong
import com.fotoxplorr.app.editor.asObject
import com.fotoxplorr.app.editor.asString
import com.fotoxplorr.app.editor.get
import com.fotoxplorr.app.editor.parseJson
import com.fotoxplorr.app.editor.toJson

/** What a restore does with what is already on the phone. */
enum class ImportMode {
    /** Adds the file's tags, captions, collections and marks to what is there; removes and overwrites nothing. */
    MERGE,

    /** Puts exactly the file's content in place of what is there. Only ever after an explicit confirmation. */
    REPLACE,
}

/**
 * The plain "Tags and collections" backup, as pure functions over [JsonValue]: the file's shape,
 * checking a file before anything is touched, which photo each id means on this phone, and the
 * merge. The Android side (`MetadataBackupIo`, `MetadataBackupUi`) only reads and writes bytes and
 * asks the person.
 *
 * Why this exists: a MediaStore id is a row number on one phone. The first version of this file
 * wrote bare ids and the restore wiped the library and wrote them back, so a file restored after a
 * reinstall or on another phone attached tags, captions and favourites to the wrong photos, with
 * nothing to say so. Now:
 *
 *  - The file carries an `identity` block (name, size, date taken, folder) for every id it
 *    mentions, and a restore matches those against this phone's catalogue ([IdRemap.matchIdentities])
 *    and rewrites the ids ([IdRemap.remapEnvelope]). An id with no confident match is dropped and
 *    counted, never attached to a guess.
 *  - A file from before the identity block (schema 1) is restored as it stands, which is only right
 *    on the phone it came from; [RestoreSummary.hasIdentity] is false and the screen says so first.
 *  - Everything is read, checked and remapped into a [PreparedRestore] before any store is written;
 *    a file that fails a check changes nothing.
 *  - [ImportMode.MERGE] is the default, [mergeLibraries] never overwrites something the person
 *    wrote here, and [ImportMode.REPLACE] is a separate, confirmed choice.
 *
 * The file is plain JSON and is NOT encrypted: it holds captions, tags and which photos are marked
 * sensitive. Every place that offers it says so.
 */
object MetadataBackup {
    /** The envelope that has the identity block. Schema 1 (no identity) is still read. */
    const val FILE_SCHEMA = 2
    const val LEGACY_FILE_SCHEMA = 1

    /** The schema of the `library` object inside, unchanged: it is `LibraryStore.exportJson`. */
    const val LIBRARY_SCHEMA = 1

    /** Marks a schema 2 file as ours, so some other JSON file is not mistaken for a backup. */
    const val KIND = "fotoz-tags-and-collections"

    const val SUGGESTED_FILE_NAME = "fotoz-tags-and-collections.json"

    /**
     * Larger than any real library (a 50,000-photo file with identities is about 7 MB). The whole
     * file becomes a tree in memory, so a file past this is refused rather than risking the process.
     */
    const val MAX_FILE_BYTES = 16 * 1024 * 1024

    const val KEY_SCHEMA = "schema"
    const val KEY_KIND = "kind"
    const val KEY_EXPORTED_AT = "exportedAtMillis"
    const val KEY_LIBRARY = "library"
    const val KEY_FAVORITES = "favoriteIds"
    const val KEY_SENSITIVE = "sensitiveIds"
    const val KEY_IDENTITY = "identity"

    // ---- writing ----

    /**
     * The file to save: the library as `LibraryStore.exportJson` wrote it, the favourite and
     * sensitive ids, and an identity for every id any of them mention that is in [catalogue].
     * An id the catalogue does not know (the photo was deleted) gets no identity, which makes a
     * restore skip it; see [ExportCoverage].
     */
    fun buildFile(
        library: JsonValue,
        favoriteIds: Set<Long>,
        sensitiveIds: Set<Long>,
        catalogue: Map<Long, MediaIdentity>,
        exportedAtMillis: Long,
    ): BuiltFile {
        val fields = linkedMapOf<String, JsonValue>(
            KEY_SCHEMA to FILE_SCHEMA.toJson(),
            KEY_KIND to KIND.toJson(),
            KEY_EXPORTED_AT to exportedAtMillis.toJson(),
            KEY_LIBRARY to library,
            KEY_FAVORITES to idArray(favoriteIds),
            KEY_SENSITIVE to idArray(sensitiveIds),
        )
        val mentioned = mentionedIds(JsonValue.JsonObject(fields))
        val identity = linkedMapOf<String, JsonValue>()
        var withoutIdentity = 0
        for (id in mentioned.sorted()) {
            val known = catalogue[id]
            if (known == null) withoutIdentity++ else identity[id.toString()] = identityJson(known)
        }
        fields[KEY_IDENTITY] = JsonValue.JsonObject(identity)
        return BuiltFile(JsonValue.JsonObject(fields), ExportCoverage(mentioned.size, identity.size, withoutIdentity))
    }

    class BuiltFile(val json: JsonValue, val coverage: ExportCoverage)

    /** How many photos the file speaks about and how many of them it could say which photo they are. */
    class ExportCoverage(val photos: Int, val withIdentity: Int, val withoutIdentity: Int) {
        /**
         * True when there are photos to describe and none could be: the catalogue was empty (the
         * scan has not finished, or access was withdrawn). Saving that would produce a file whose
         * every entry is skipped on restore, so the save is refused instead.
         */
        val catalogueMissing: Boolean get() = photos > 0 && withIdentity == 0
    }

    private fun identityJson(identity: MediaIdentity): JsonValue {
        val fields = linkedMapOf<String, JsonValue>(
            "name" to identity.displayName.toJson(),
            "size" to identity.sizeBytes.toJson(),
            "dateTaken" to identity.dateTakenMillis.toJson(),
        )
        identity.relativePath?.let { fields["path"] = it.toJson() }
        return JsonValue.JsonObject(fields)
    }

    private fun idArray(ids: Set<Long>): JsonValue =
        JsonValue.JsonArray(ids.sorted().map { it.toJson() })

    /** Every distinct valid id the file mentions, found through the one list [IdRemap] keeps. */
    fun mentionedIds(file: JsonValue): Set<Long> =
        // An empty map drops every id, and the remap reports exactly the ids it dropped.
        IdRemap.remapEnvelope(file, emptyMap()).droppedIds

    // ---- reading ----

    sealed interface FileRead {
        /** Not a backup this build can restore. [reason] is a sentence for the person. */
        class Unusable(val reason: String) : FileRead

        class Usable(val file: MetadataFile) : FileRead
    }

    /** A file that passed every check, in one shape whatever its age. */
    class MetadataFile internal constructor(
        /** `{ library, favoriteIds?, sensitiveIds? }` and nothing the remap does not understand. */
        internal val envelope: JsonValue,
        /** Null for a file from before the identity block. */
        val identity: Map<Long, MediaIdentity>?,
        val exportedAtMillis: Long?,
    ) {
        val hasIdentity: Boolean get() = identity != null
    }

    /**
     * Reads and checks [text]. Nothing outside this call is touched, and a file that fails any
     * check is [FileRead.Unusable] as a whole: there is no partial import of a damaged file.
     */
    fun read(text: String): FileRead {
        if (text.length > MAX_FILE_BYTES) return FileRead.Unusable("This file is too large to be a Fotoz backup.")
        return readTree(parseJson(text))
    }

    /**
     * [read] for a file that is already a tree: the encrypted backup carries this same file as a
     * section of its manifest ([BackupSections.METADATA]) and restores it through the same checks.
     */
    fun readTree(root: JsonValue?): FileRead {
        if (root !is JsonValue.JsonObject) return FileRead.Unusable("This is not a Fotoz backup, or it is damaged.")

        val nested = root.entries[KEY_LIBRARY]
        val library: JsonValue
        val schema: Int
        if (nested != null) {
            if (nested !is JsonValue.JsonObject) return FileRead.Unusable("This is not a Fotoz backup, or it is damaged.")
            library = nested
            schema = when (val declared = root.entries[KEY_SCHEMA]) {
                null -> LEGACY_FILE_SCHEMA
                else -> wholeNumber(declared)?.toInt() ?: return FileRead.Unusable("This is not a Fotoz backup, or it is damaged.")
            }
        } else {
            // The oldest shape had no envelope: the library itself was the file.
            if (root.entries.keys.none { it in IdRemap.LIBRARY_KEYS }) {
                return FileRead.Unusable("This is not a Fotoz backup of tags and collections.")
            }
            library = root
            schema = LEGACY_FILE_SCHEMA
        }
        if (schema > FILE_SCHEMA) {
            return FileRead.Unusable("This backup was made by a newer version of Fotoz. Update Fotoz to restore it.")
        }
        if (schema < LEGACY_FILE_SCHEMA) return FileRead.Unusable("This is not a Fotoz backup, or it is damaged.")

        checkLibrary(library)?.let { return FileRead.Unusable(it) }

        val envelope = linkedMapOf<String, JsonValue>(KEY_LIBRARY to library)
        for (key in IdRemap.ENVELOPE_ID_ARRAYS) {
            val ids = root.entries[key] ?: continue
            if (ids !is JsonValue.JsonArray) return FileRead.Unusable("This is not a Fotoz backup, or it is damaged.")
            envelope[key] = ids
        }

        val identity: Map<Long, MediaIdentity>? = if (schema >= FILE_SCHEMA) {
            val kind = root.entries[KEY_KIND]?.asString()
            if (kind != KIND) return FileRead.Unusable("This is not a Fotoz backup of tags and collections.")
            val block = root.entries[KEY_IDENTITY] as? JsonValue.JsonObject
                ?: return FileRead.Unusable("This backup does not say which photos it belongs to, so it cannot be restored safely.")
            readIdentity(block)
        } else {
            null
        }
        val exportedAt = (root.entries[KEY_EXPORTED_AT] as? JsonValue.JsonNumber)?.value?.toLong()?.takeIf { it > 0 }
        return FileRead.Usable(MetadataFile(JsonValue.JsonObject(envelope), identity, exportedAt))
    }

    /** An entry with no usable name or id is left out, which makes its photo "not found" at restore. */
    private fun readIdentity(block: JsonValue.JsonObject): Map<Long, MediaIdentity> {
        val out = LinkedHashMap<Long, MediaIdentity>()
        for ((key, value) in block.entries) {
            val id = key.toLongOrNull()?.takeIf { it >= 0 } ?: continue
            if (value !is JsonValue.JsonObject) continue
            val name = (value["name"] as? JsonValue.JsonString)?.value?.takeIf { it.isNotBlank() } ?: continue
            out[id] = MediaIdentity(
                displayName = name,
                sizeBytes = value["size"].asLong(-1L),
                dateTakenMillis = value["dateTaken"].asLong(0L),
                relativePath = (value["path"] as? JsonValue.JsonString)?.value?.takeIf { it.isNotEmpty() },
            )
        }
        return out
    }

    private fun wholeNumber(value: JsonValue): Long? {
        val number = (value as? JsonValue.JsonNumber)?.value ?: return null
        if (number.isNaN() || number.isInfinite() || number != Math.floor(number)) return null
        return number.toLong()
    }

    /**
     * Whether [library] is shaped like `LibraryStore.exportJson`: the right container types, so
     * nothing later hits a surprise halfway through writing. Returns a sentence, or null when fine.
     * Single bad ids are not a shape problem; they are skipped and counted by the remap.
     */
    fun checkLibrary(library: JsonValue): String? {
        if (library !is JsonValue.JsonObject) return "This is not a Fotoz backup, or it is damaged."
        val schema = library.entries[KEY_SCHEMA]?.let(::wholeNumber)
        if (schema == null) return "This is not a Fotoz backup, or it is damaged."
        if (schema > LIBRARY_SCHEMA) return "This backup was made by a newer version of Fotoz. Update Fotoz to restore it."
        if (schema < LIBRARY_SCHEMA) return "This is not a Fotoz backup, or it is damaged."
        val damaged = "This backup is damaged and was not restored."
        for (key in IdRemap.LIBRARY_ID_ARRAYS) {
            val value = library.entries[key] ?: continue
            if (value !is JsonValue.JsonArray) return damaged
        }
        for (key in IdRemap.LIBRARY_ID_ARRAYS_BY_NAME) {
            val value = library.entries[key] ?: continue
            if (value !is JsonValue.JsonObject) return damaged
            if (value.entries.values.any { it !is JsonValue.JsonArray }) return damaged
        }
        val captions = library.entries[IdRemap.LIBRARY_CAPTIONS]
        if (captions != null) {
            if (captions !is JsonValue.JsonObject) return damaged
            if (captions.entries.values.any { it !is JsonValue.JsonString }) return damaged
        }
        val collections = library.entries[IdRemap.LIBRARY_COLLECTIONS]
        if (collections != null) {
            if (collections !is JsonValue.JsonArray) return damaged
            for (collection in collections.items) {
                if (collection !is JsonValue.JsonObject) return damaged
                val name = collection.entries["name"]
                if (name != null && name !is JsonValue.JsonString) return damaged
                val id = collection.entries["id"]
                if (id != null && id !is JsonValue.JsonString) return damaged
                val media = collection.entries["mediaIds"]
                if (media != null && media !is JsonValue.JsonArray) return damaged
            }
        }
        return null
    }

    // ---- preparing ----

    /** A file made ready to apply. Producing it changes nothing on the phone. */
    class PreparedRestore(
        /** The file's library with every id as it is on this phone. */
        val library: JsonValue,
        val favoriteIds: Set<Long>,
        val sensitiveIds: Set<Long>,
        val summary: RestoreSummary,
    )

    class RestoreSummary(
        /** False for a file from before the identity block: its ids are used as they are. */
        val hasIdentity: Boolean,
        val exportedAtMillis: Long?,
        /** Distinct photos the file mentions. */
        val photosInFile: Int,
        /** Photos found on this phone (all of them, for a file without identity). */
        val photosFound: Int,
        /** Photos found under a different id than the file used. */
        val photosRenumbered: Int,
        /** Photos in the file that this phone does not have, or could not be told apart. */
        val photosMissing: Int,
        /** Entries that were not an id at all. */
        val unusableEntries: Int,
        val collections: Int,
        val tags: Int,
        val captions: Int,
        val favorites: Int,
        val sensitive: Int,
    ) {
        /**
         * Nothing in the file can be put on this phone: it is empty, or it names photos and this
         * phone has none of them (a collection with no photo left in it is not a reason to restore).
         */
        val nothingToRestore: Boolean
            get() = (hasIdentity && photosInFile > 0 && photosFound == 0) ||
                (collections == 0 && tags == 0 && captions == 0 && favorites == 0 && sensitive == 0)
    }

    /**
     * Matches the file against [catalogue] (this phone's photos by id) and rewrites every id.
     * For a file with identity that is a real match; for one without, the ids are kept and the
     * caller has already shown the "same phone only" warning.
     */
    fun prepare(file: MetadataFile, catalogue: Map<Long, MediaIdentity>, known: Map<Long, Long> = emptyMap()): PreparedRestore {
        val mentioned = mentionedIds(file.envelope)
        val identity = file.identity
        val oldToNew: Map<Long, Long> = if (identity == null) {
            mentioned.associateWith { it }
        } else {
            // [known] is what the caller already settled for certain (the encrypted backup's restore
            // knows exactly which new id each file it wrote or found got); only the rest is matched
            // by identity, and never onto a photo [known] already claimed.
            val result = LinkedHashMap<Long, Long>()
            for (id in mentioned) known[id]?.let { result[id] = it }
            val claimed = result.values.toSet()
            val wanted = LinkedHashMap<Long, MediaIdentity>()
            for (id in mentioned.sorted()) if (id !in result) identity[id]?.let { wanted[id] = it }
            if (wanted.isNotEmpty()) {
                result.putAll(IdRemap.matchIdentities(wanted, catalogue.filterKeys { it !in claimed }).oldToNew)
            }
            result
        }
        val remapped = IdRemap.remapEnvelope(file.envelope, oldToNew)
        val out = remapped.json
        val library = out[KEY_LIBRARY]
        val favorites = idsOf(out[KEY_FAVORITES])
        val sensitive = idsOf(out[KEY_SENSITIVE])
        val summary = RestoreSummary(
            hasIdentity = identity != null,
            exportedAtMillis = file.exportedAtMillis,
            photosInFile = mentioned.size,
            photosFound = oldToNew.size,
            photosRenumbered = oldToNew.count { (old, new) -> old != new },
            photosMissing = mentioned.size - oldToNew.size,
            unusableEntries = remapped.malformedCount,
            collections = library[IdRemap.LIBRARY_COLLECTIONS].asArray().size,
            tags = library["tags"].asObject().size,
            captions = library[IdRemap.LIBRARY_CAPTIONS].asObject().size,
            favorites = favorites.size,
            sensitive = sensitive.size,
        )
        return PreparedRestore(library, favorites, sensitive, summary)
    }

    private fun idsOf(value: JsonValue): Set<Long> =
        value.asArray().mapNotNullTo(linkedSetOf()) { wholeNumber(it)?.takeIf { id -> id >= 0 } }

    // ---- merging ----

    /**
     * [incoming] added to [current], as one library in the `exportJson` shape. Nothing is removed
     * and nothing the person wrote on this phone is overwritten:
     *
     *  - collections join by id, then by name (ignoring case), else are added; the existing name stays;
     *  - tags are the union; a tag the person typed (here or in the file) never becomes "machine
     *    applied" because the other side had it that way, or a bulk undo of auto tags could delete it;
     *  - a caption already here stays; a machine caption is not brought back for a photo whose
     *    machine caption the person cleared;
     *  - a photo the person un-archived here is not archived again by the file;
     *  - the "said no" memories are the union, and a tag a photo now carries is not also a rejection.
     *
     * Merging into an empty library is the normalisation [ImportMode.REPLACE] uses, so both modes
     * write the same shape.
     */
    fun mergeLibraries(current: JsonValue, incoming: JsonValue): JsonValue {
        val cur = Lib.from(current)
        val inc = Lib.from(incoming)

        val collections = cur.collections.map { it.copy(ids = it.ids.toMutableSet()) }.toMutableList()
        for (extra in inc.collections) {
            val name = extra.name.trim()
            if (name.isEmpty()) continue
            val into = collections.firstOrNull { extra.id.isNotBlank() && it.id == extra.id }
                ?: collections.firstOrNull { it.name.trim().equals(name, ignoreCase = true) }
            if (into != null) into.ids += extra.ids else collections += extra.copy(name = name, ids = extra.ids.toMutableSet())
        }

        val tags = LinkedHashMap<String, MutableSet<Long>>()
        val autoTags = LinkedHashMap<String, MutableSet<Long>>()
        for (tag in (cur.tags.keys + inc.tags.keys)) {
            val ids = (cur.tags[tag].orEmpty() + inc.tags[tag].orEmpty()).toMutableSet()
            if (ids.isEmpty()) continue
            tags[tag] = ids
            val typed = (cur.tags[tag].orEmpty() - cur.autoTags[tag].orEmpty()) +
                (inc.tags[tag].orEmpty() - inc.autoTags[tag].orEmpty())
            val auto = ((cur.autoTags[tag].orEmpty() + inc.autoTags[tag].orEmpty()) - typed).intersect(ids)
            if (auto.isNotEmpty()) autoTags[tag] = auto.toMutableSet()
        }
        val rejected = LinkedHashMap<String, MutableSet<Long>>()
        for (tag in (cur.rejectedAutoTags.keys + inc.rejectedAutoTags.keys)) {
            val ids = (cur.rejectedAutoTags[tag].orEmpty() + inc.rejectedAutoTags[tag].orEmpty()) - tags[tag].orEmpty()
            if (ids.isNotEmpty()) rejected[tag] = ids.toMutableSet()
        }

        val captions = LinkedHashMap<Long, String>()
        val machine = LinkedHashSet<Long>()
        for ((id, text) in cur.captions) {
            captions[id] = text
            if (id in cur.machineCaptions) machine += id
        }
        for ((id, text) in inc.captions) {
            if (id in captions) continue
            val machineWritten = id in inc.machineCaptions
            if (machineWritten && id in cur.suppressedMachineCaptions) continue
            captions[id] = text
            if (machineWritten) machine += id
        }

        val deliberatelyUnarchived = cur.everUnarchived - cur.archived
        return Lib(
            collections = collections,
            tags = tags,
            autoTags = autoTags,
            rejectedAutoTags = rejected,
            captions = captions,
            machineCaptions = machine,
            archived = cur.archived + (inc.archived - deliberatelyUnarchived),
            everUnarchived = cur.everUnarchived + inc.everUnarchived,
            rejectedArchiveSuggestions = cur.rejectedArchiveSuggestions + inc.rejectedArchiveSuggestions,
            suppressedMachineCaptions = cur.suppressedMachineCaptions + inc.suppressedMachineCaptions,
        ).toJson()
    }

    /** An empty library in the export shape, the "current" side of a replace. */
    fun emptyLibrary(): JsonValue = Lib.EMPTY.toJson()

    private class LibCollection(val id: String, val name: String, val created: Long, val ids: MutableSet<Long>) {
        fun copy(name: String = this.name, ids: MutableSet<Long> = this.ids) = LibCollection(id, name, created, ids)
    }

    /** `LibraryStore.exportJson` as sets, so the merge is set arithmetic and not tree surgery. */
    private class Lib(
        val collections: List<LibCollection>,
        val tags: Map<String, Set<Long>>,
        val autoTags: Map<String, Set<Long>>,
        val rejectedAutoTags: Map<String, Set<Long>>,
        val captions: Map<Long, String>,
        val machineCaptions: Set<Long>,
        val archived: Set<Long>,
        val everUnarchived: Set<Long>,
        val rejectedArchiveSuggestions: Set<Long>,
        val suppressedMachineCaptions: Set<Long>,
    ) {
        fun toJson(): JsonValue {
            fun ids(set: Set<Long>) = idArray(set)
            fun byName(map: Map<String, Set<Long>>) =
                JsonValue.JsonObject(map.mapValuesTo(LinkedHashMap()) { ids(it.value) })
            val out = linkedMapOf<String, JsonValue>()
            out[KEY_SCHEMA] = LIBRARY_SCHEMA.toJson()
            out[IdRemap.LIBRARY_COLLECTIONS] = JsonValue.JsonArray(
                collections.map {
                    JsonValue.JsonObject(
                        linkedMapOf(
                            "id" to it.id.toJson(),
                            "name" to it.name.toJson(),
                            "createdAtMillis" to it.created.toJson(),
                            "mediaIds" to ids(it.ids),
                        ),
                    )
                },
            )
            out["tags"] = byName(tags)
            out["archivedIds"] = ids(archived)
            out["autoTags"] = byName(autoTags)
            out[IdRemap.LIBRARY_CAPTIONS] = JsonValue.JsonObject(
                captions.entries.sortedBy { it.key }.associateTo(LinkedHashMap()) { it.key.toString() to it.value.toJson() },
            )
            out["machineCaptionIds"] = ids(machineCaptions.intersect(captions.keys))
            out["everUnarchivedIds"] = ids(everUnarchived)
            out["rejectedArchiveSuggestionIds"] = ids(rejectedArchiveSuggestions)
            out["rejectedAutoTags"] = byName(rejectedAutoTags)
            out["suppressedMachineCaptionIds"] = ids(suppressedMachineCaptions)
            return JsonValue.JsonObject(out)
        }

        companion object {
            val EMPTY = Lib(emptyList(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptySet(), emptySet(), emptySet(), emptySet(), emptySet())

            /** Reads a library already accepted by [checkLibrary]; an id that is not a whole number is skipped. */
            fun from(library: JsonValue): Lib {
                fun ids(value: JsonValue): Set<Long> = idsOf(value)
                fun byName(value: JsonValue): Map<String, Set<Long>> {
                    val out = LinkedHashMap<String, MutableSet<Long>>()
                    for ((rawName, list) in value.asObject()) {
                        val name = rawName.trim()
                        if (name.isEmpty()) continue
                        out.getOrPut(name) { linkedSetOf() } += ids(list)
                    }
                    return out
                }
                val tags = byName(library["tags"]).filterValues { it.isNotEmpty() }
                val captions = LinkedHashMap<Long, String>()
                for ((key, text) in library[IdRemap.LIBRARY_CAPTIONS].asObject()) {
                    val id = key.toLongOrNull()?.takeIf { it >= 0 } ?: continue
                    val clean = text.asString().trim()
                    if (clean.isNotEmpty()) captions[id] = clean
                }
                return Lib(
                    collections = library[IdRemap.LIBRARY_COLLECTIONS].asArray().mapNotNull { c ->
                        val name = c["name"].asString().trim()
                        if (name.isEmpty()) null else LibCollection(c["id"].asString(), name, c["createdAtMillis"].asLong(0L), ids(c["mediaIds"]).toMutableSet())
                    },
                    tags = tags,
                    // A provenance marker means something only on a photo that carries the tag.
                    autoTags = byName(library["autoTags"]).mapValues { (name, set) -> set.intersect(tags[name].orEmpty()) }
                        .filterValues { it.isNotEmpty() },
                    rejectedAutoTags = byName(library["rejectedAutoTags"]).filterValues { it.isNotEmpty() },
                    captions = captions,
                    machineCaptions = ids(library["machineCaptionIds"]),
                    archived = ids(library["archivedIds"]),
                    everUnarchived = ids(library["everUnarchivedIds"]),
                    rejectedArchiveSuggestions = ids(library["rejectedArchiveSuggestionIds"]),
                    suppressedMachineCaptions = ids(library["suppressedMachineCaptionIds"]),
                )
            }
        }
    }
}
