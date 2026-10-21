package com.fotoxplorr.app.backup

import com.fotoxplorr.app.editor.JsonValue
import com.fotoxplorr.app.editor.asObject
import com.fotoxplorr.app.editor.jsonArrayOf
import com.fotoxplorr.app.editor.jsonObjectOf
import com.fotoxplorr.app.editor.parseJson
import com.fotoxplorr.app.editor.stringify
import com.fotoxplorr.app.editor.toJson
import java.io.IOException
import java.io.Reader

/**
 * The plaintext of a manifest file (ADR-014): JSON lines, one record per line, so a library of a
 * hundred thousand photos is written and read a line at a time and never exists as one
 * [JsonValue] tree. Each manifest is a complete snapshot of the set; the highest generation that
 * authenticates in full wins.
 *
 * Lines, in this order: one `m` (header), any number of `e` (entry), `p` (pack) and `s` (section)
 * lines, then one `z` (end, with the counts). The end line is the writer saying "I finished"; a
 * manifest without it, or whose counts disagree, is refused as truncated even if the cipher layer
 * let it through. A record type or key this reader does not know is ignored, so a later version
 * can add fields without a format change.
 */
object ManifestFormat {
    const val VERSION = 1

    /** A single line may hold a whole metadata section; anything longer is refused, not buffered. */
    const val MAX_LINE_CHARS = 64 shl 20
}

/** File names of a set. They carry no information about the content (ADR-014). */
object BackupLayout {
    private val packPattern = Regex("^pack-(\\d{4,9})\\.fzbp$")
    private val manifestPattern = Regex("^manifest-(\\d{4,9})\\.fzbm$")

    fun packName(number: Int): String {
        require(number in 1..999_999_999) { "Pack number out of range" }
        return "pack-%04d.fzbp".format(number)
    }

    fun manifestName(generation: Int): String {
        require(generation in 1..999_999_999) { "Generation out of range" }
        return "manifest-%04d.fzbm".format(generation)
    }

    fun isPackName(name: String): Boolean = packPattern.matches(name)

    fun packNumber(name: String): Int? = packPattern.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()

    fun manifestGeneration(name: String): Int? =
        manifestPattern.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()

    /** The manifest files of a folder listing, newest generation first; other names are ignored. */
    fun manifestsNewestFirst(listing: Collection<String>): List<String> =
        listing.mapNotNull { name -> manifestGeneration(name)?.let { it to name } }
            .sortedByDescending { it.first }
            .map { it.second }
}

/** Why an entry's bytes (or the phone's file) mean something to the planners. */
enum class EntryFlag(val code: String) {
    /** The packed bytes carry the photo's original GPS (the location read was granted). */
    ORIGINAL_LOCATION("loc"),
    WAS_PROTECTED("prot"),
    WAS_SENSITIVE("sens"),
    WAS_ARCHIVED("arch"),
    ;

    companion object {
        fun fromCode(code: String): EntryFlag? = entries.firstOrNull { it.code == code }
    }
}

/**
 * One backed-up file. [relativePath] is as MediaStore reported it ("DCIM/Camera/") and empty when
 * there was none. [mediaId] is the MediaStore id on the phone that made the backup: a hint for
 * remapping metadata, never an identity ([IdRemap]). [packName] holds the bytes; [entryId] is
 * unique in the set, never reused, and is how a pack record is found.
 */
data class ManifestEntry(
    val entryId: Long,
    val mediaId: Long,
    val displayName: String,
    val relativePath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val dateTakenMillis: Long,
    val dateModifiedSeconds: Long,
    val sha256: String,
    val quickHash: String?,
    val packName: String,
    val flags: Set<EntryFlag> = emptySet(),
) {
    val isVideo: Boolean get() = mimeType.startsWith("video/", ignoreCase = true)

    /** What "the same file" means across runs: where it lives and what it is called. */
    val fileKey: String get() = fileKeyOf(relativePath, displayName)

    companion object {
        fun fileKeyOf(relativePath: String?, displayName: String): String = "${relativePath.orEmpty()}\u0000$displayName"
    }
}

/** A pack as the writer finished it. [fileBytes] is its length on disk, which verify compares. */
data class ManifestPack(val name: String, val fileBytes: Long, val entryCount: Int)

/**
 * Opaque, named extra data (portable metadata, pins, recipes, folder locks). The manifest codec
 * does not look inside; the units that own the data do.
 */
data class ManifestSection(val name: String, val data: JsonValue)

/**
 * [iterations] and [segmentSize] are the set's cipher parameters, so a later run extending the
 * set writes new files the same way; they are range-checked on read, before anything could use
 * them to run a key derivation.
 */
data class ManifestHeader(
    val generation: Int,
    val createdAtMillis: Long,
    val appVersion: String,
    val iterations: Int,
    val segmentSize: Int,
)

class BackupManifest(
    val header: ManifestHeader,
    val entries: List<ManifestEntry>,
    val packs: List<ManifestPack>,
    val sections: List<ManifestSection>,
) {
    fun section(name: String): JsonValue? = sections.firstOrNull { it.name == name }?.data
}

/** The manifest is not valid: damaged, hostile, or written by something that never finished. */
class ManifestFormatException(message: String) : IOException(message)

sealed class ManifestRecord {
    data class Header(val header: ManifestHeader) : ManifestRecord()
    data class Entry(val entry: ManifestEntry) : ManifestRecord()
    data class Pack(val pack: ManifestPack) : ManifestRecord()
    data class Section(val section: ManifestSection) : ManifestRecord()
}

/**
 * Writes a manifest one line at a time to [out]; wrap the encrypted stream in a UTF-8 writer.
 * The header must come first and [finish] last. Not thread-safe.
 */
class ManifestWriter(private val out: Appendable) {
    private var started = false
    private var finished = false
    private var entries = 0L
    private var packs = 0L

    fun writeHeader(header: ManifestHeader) {
        check(!started) { "The header was already written" }
        check(!finished) { "The manifest is finished" }
        require(header.generation >= 1) { "Generation starts at 1" }
        started = true
        line(
            jsonObjectOf(
                "t" to "m".toJson(),
                "v" to ManifestFormat.VERSION.toJson(),
                "gen" to header.generation.toJson(),
                "created" to header.createdAtMillis.toJson(),
                "app" to header.appVersion.toJson(),
                "iter" to header.iterations.toJson(),
                "seg" to header.segmentSize.toJson(),
            ),
        )
    }

    fun writeEntry(entry: ManifestEntry) {
        ready()
        validateEntry(entry)
        entries++
        val fields = linkedMapOf<String, JsonValue>(
            "t" to "e".toJson(),
            "id" to entry.entryId.toJson(),
            "mid" to entry.mediaId.toJson(),
            "name" to entry.displayName.toJson(),
            "dir" to entry.relativePath.toJson(),
            "mime" to entry.mimeType.toJson(),
            "size" to entry.sizeBytes.toJson(),
            "taken" to entry.dateTakenMillis.toJson(),
            "mod" to entry.dateModifiedSeconds.toJson(),
            "sha" to entry.sha256.toJson(),
            "pack" to entry.packName.toJson(),
        )
        entry.quickHash?.let { fields["qh"] = it.toJson() }
        if (entry.flags.isNotEmpty()) {
            fields["f"] = jsonArrayOf(entry.flags.sortedBy { it.ordinal }.map { it.code.toJson() })
        }
        line(JsonValue.JsonObject(fields))
    }

    fun writePack(pack: ManifestPack) {
        ready()
        require(BackupLayout.isPackName(pack.name)) { "Not a pack name" }
        require(pack.fileBytes >= 0 && pack.entryCount >= 0) { "Negative pack size" }
        packs++
        line(
            jsonObjectOf(
                "t" to "p".toJson(),
                "name" to pack.name.toJson(),
                "bytes" to pack.fileBytes.toJson(),
                "n" to pack.entryCount.toJson(),
            ),
        )
    }

    fun writeSection(section: ManifestSection) {
        ready()
        require(section.name.isNotBlank()) { "A section needs a name" }
        line(jsonObjectOf("t" to "s".toJson(), "name" to section.name.toJson(), "data" to section.data))
    }

    /** Writes the end line. Without it the manifest is refused on read. */
    fun finish() {
        ready()
        finished = true
        line(jsonObjectOf("t" to "z".toJson(), "entries" to entries.toJson(), "packs" to packs.toJson()))
    }

    private fun ready() {
        check(started) { "Write the header first" }
        check(!finished) { "The manifest is finished" }
    }

    private fun line(value: JsonValue) {
        out.append(value.stringify()).append('\n')
    }
}

/** Reads manifests; every failure is a [ManifestFormatException] (or [BackupException.KdfOutOfRange]). */
object ManifestReader {
    /**
     * Streams [source] and hands each header, entry, pack and section to [onRecord] in file
     * order. Returns only if the whole manifest, including its end line and counts, is valid;
     * records delivered before a failure must be discarded by the caller.
     */
    fun read(source: Reader, maxLineChars: Int = ManifestFormat.MAX_LINE_CHARS, onRecord: (ManifestRecord) -> Unit) {
        val lines = BoundedLines(source, maxLineChars)
        var sawHeader = false
        var sawEnd = false
        var entries = 0L
        var packs = 0L
        val entryIds = HashSet<Long>()
        while (true) {
            val text = lines.next() ?: break
            if (text.isBlank()) continue
            if (sawEnd) throw ManifestFormatException("Content after the end of the manifest")
            val line = parseJson(text)?.takeIf { it is JsonValue.JsonObject }
                ?: throw ManifestFormatException("A manifest line is not a JSON object")
            val fields = line.asObject()
            val type = (fields["t"] as? JsonValue.JsonString)?.value
                ?: throw ManifestFormatException("A manifest line has no type")
            if (!sawHeader && type != "m") throw ManifestFormatException("The manifest does not start with its header")
            when (type) {
                "m" -> {
                    if (sawHeader) throw ManifestFormatException("The manifest has two headers")
                    sawHeader = true
                    onRecord(ManifestRecord.Header(parseHeader(fields)))
                }
                "e" -> {
                    val entry = parseEntry(fields)
                    if (!entryIds.add(entry.entryId)) throw ManifestFormatException("Entry id ${entry.entryId} appears twice")
                    entries++
                    onRecord(ManifestRecord.Entry(entry))
                }
                "p" -> {
                    packs++
                    onRecord(ManifestRecord.Pack(parsePack(fields)))
                }
                "s" -> {
                    val name = str(fields, "name")
                    if (name.isBlank()) throw ManifestFormatException("A section has no name")
                    onRecord(ManifestRecord.Section(ManifestSection(name, fields["data"] ?: JsonValue.JsonNull)))
                }
                "z" -> {
                    sawEnd = true
                    if (long(fields, "entries") != entries || long(fields, "packs") != packs) {
                        throw ManifestFormatException("The manifest's counts do not match its records")
                    }
                }
                else -> Unit // a record type from a later version
            }
        }
        if (!sawHeader || !sawEnd) throw ManifestFormatException("The manifest ends early")
    }

    /** [read] into memory. Fine for entries and packs; a very large section is held whole. */
    fun readAll(source: Reader, maxLineChars: Int = ManifestFormat.MAX_LINE_CHARS): BackupManifest {
        var header: ManifestHeader? = null
        val entries = ArrayList<ManifestEntry>()
        val packs = ArrayList<ManifestPack>()
        val sections = ArrayList<ManifestSection>()
        read(source, maxLineChars) { record ->
            when (record) {
                is ManifestRecord.Header -> header = record.header
                is ManifestRecord.Entry -> entries += record.entry
                is ManifestRecord.Pack -> packs += record.pack
                is ManifestRecord.Section -> sections += record.section
            }
        }
        return BackupManifest(header ?: throw ManifestFormatException("The manifest ends early"), entries, packs, sections)
    }

    private fun parseHeader(f: Map<String, JsonValue>): ManifestHeader {
        val version = long(f, "v")
        if (version != ManifestFormat.VERSION.toLong()) throw ManifestFormatException("Manifest version $version is not supported")
        val generation = long(f, "gen")
        if (generation < 1 || generation > 999_999_999) throw ManifestFormatException("Bad generation $generation")
        val iterations = long(f, "iter")
        val segment = long(f, "seg")
        // Before anything can use them: these decide how much work a key derivation does.
        BackupFormat.checkIterations(iterations)
        BackupFormat.checkSegmentSize(segment)
        return ManifestHeader(
            generation = generation.toInt(),
            createdAtMillis = long(f, "created"),
            appVersion = str(f, "app"),
            iterations = iterations.toInt(),
            segmentSize = segment.toInt(),
        )
    }

    private fun parseEntry(f: Map<String, JsonValue>): ManifestEntry {
        val entry = ManifestEntry(
            entryId = nonNegative(f, "id"),
            mediaId = long(f, "mid"),
            displayName = str(f, "name"),
            relativePath = str(f, "dir"),
            mimeType = str(f, "mime"),
            sizeBytes = nonNegative(f, "size"),
            dateTakenMillis = long(f, "taken"),
            dateModifiedSeconds = long(f, "mod"),
            sha256 = str(f, "sha"),
            quickHash = (f["qh"] as? JsonValue.JsonString)?.value,
            packName = str(f, "pack"),
            flags = (f["f"] as? JsonValue.JsonArray)?.items.orEmpty()
                .mapNotNull { (it as? JsonValue.JsonString)?.value?.let(EntryFlag::fromCode) }
                .toSet(),
        )
        try {
            validateEntry(entry)
        } catch (e: IllegalArgumentException) {
            throw ManifestFormatException(e.message ?: "A manifest entry is invalid")
        }
        return entry
    }

    private fun parsePack(f: Map<String, JsonValue>): ManifestPack {
        val name = str(f, "name")
        if (!BackupLayout.isPackName(name)) throw ManifestFormatException("A pack has an invalid name")
        return ManifestPack(name, nonNegative(f, "bytes"), nonNegative(f, "n").toInt())
    }

    private fun long(f: Map<String, JsonValue>, key: String): Long {
        val value = (f[key] as? JsonValue.JsonNumber)?.value
            ?: throw ManifestFormatException("Field \"$key\" is missing or not a number")
        if (value.isNaN() || value.isInfinite() || value != Math.floor(value) || Math.abs(value) > MAX_EXACT) {
            throw ManifestFormatException("Field \"$key\" is not a whole number")
        }
        return value.toLong()
    }

    private fun nonNegative(f: Map<String, JsonValue>, key: String): Long =
        long(f, key).also { if (it < 0) throw ManifestFormatException("Field \"$key\" is negative") }

    private fun str(f: Map<String, JsonValue>, key: String): String =
        (f[key] as? JsonValue.JsonString)?.value ?: throw ManifestFormatException("Field \"$key\" is missing or not text")

    /** JSON numbers are doubles here; beyond 2^53 a whole number is no longer exact. */
    private const val MAX_EXACT = 9_007_199_254_740_992.0
}

private val HEX64 = Regex("^[0-9a-f]{64}$")

private fun validateEntry(entry: ManifestEntry) {
    require(entry.entryId >= 0) { "Negative entry id" }
    require(entry.sizeBytes >= 0) { "Negative size" }
    require(entry.displayName.isNotEmpty()) { "An entry has no name" }
    require(HEX64.matches(entry.sha256)) { "An entry's SHA-256 is not 64 lowercase hex digits" }
    require(entry.quickHash == null || HEX64.matches(entry.quickHash)) { "An entry's quick hash is not 64 lowercase hex digits" }
    require(BackupLayout.isPackName(entry.packName)) { "An entry names an invalid pack" }
}

/** Splits on newline only, with a ceiling on one line so a hostile file cannot fill memory. */
private class BoundedLines(source: Reader, private val maxChars: Int) {
    private val reader = if (source is java.io.BufferedReader) source else java.io.BufferedReader(source, 1 shl 16)

    fun next(): String? {
        val line = StringBuilder()
        while (true) {
            val c = reader.read()
            if (c < 0) return if (line.isEmpty()) null else line.toString()
            if (c == '\n'.code) return line.toString()
            if (c == '\r'.code) continue
            if (line.length >= maxChars) throw ManifestFormatException("A manifest line is too long")
            line.append(c.toChar())
        }
    }
}
