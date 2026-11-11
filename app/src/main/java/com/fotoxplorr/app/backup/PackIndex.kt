package com.fotoxplorr.app.backup

import com.fotoxplorr.app.editor.JsonValue
import com.fotoxplorr.app.editor.jsonObjectOf
import com.fotoxplorr.app.editor.parseJson
import com.fotoxplorr.app.editor.stringify
import com.fotoxplorr.app.editor.toJson
import java.io.Reader

/**
 * The small encrypted file written beside a pack (`pack-0003.fzbx` next to `pack-0003.fzbp`) the
 * moment the pack is closed: which pack it describes, how long that pack is, and the manifest line
 * of every file inside it. It is what lets a backup that died between two packs carry on from the
 * destination folder alone. There is no journal on the phone, so clearing the app's data, or
 * moving to another phone, loses nothing and no file name is ever written to the phone's storage.
 *
 * Plaintext is JSON lines like the manifest: one `x` line (version, pack name, pack length in
 * bytes), one `e` line per file (the same record a manifest holds), one `z` line with the count. An
 * index without its end line, or one whose entries name another pack, is refused, so a half-written
 * one is simply ignored and its pack is treated as a leftover. The index is written AFTER the
 * pack; a pack with no index was never finished.
 */
class PackIndex(val pack: ManifestPack, val entries: List<ManifestEntry>)

object PackIndexFormat {
    const val VERSION = 1
}

class PackIndexWriter(private val out: Appendable) {
    /** Writes the whole index. [pack].entryCount must equal [entries].size. */
    fun write(pack: ManifestPack, entries: List<ManifestEntry>) {
        require(BackupLayout.isPackName(pack.name)) { "Not a pack name" }
        require(pack.entryCount == entries.size) { "The pack says ${pack.entryCount} files, the index lists ${entries.size}" }
        line(
            jsonObjectOf(
                "t" to "x".toJson(),
                "v" to PackIndexFormat.VERSION.toJson(),
                "pack" to pack.name.toJson(),
                "bytes" to pack.fileBytes.toJson(),
            ),
        )
        for (entry in entries) {
            require(entry.packName == pack.name) { "An entry belongs to another pack" }
            line(manifestEntryJson(entry))
        }
        line(jsonObjectOf("t" to "z".toJson(), "entries" to entries.size.toJson()))
    }

    private fun line(value: JsonValue) {
        out.append(value.stringify()).append('\n')
    }
}

object PackIndexReader {
    /** Every failure is a [ManifestFormatException]. */
    fun read(source: Reader, maxLineChars: Int = ManifestFormat.MAX_LINE_CHARS): PackIndex {
        val lines = BoundedLines(source, maxLineChars)
        var pack: ManifestPack? = null
        var sawEnd = false
        val entries = ArrayList<ManifestEntry>()
        val ids = HashSet<Long>()
        var bytes = 0L
        while (true) {
            val text = lines.next() ?: break
            if (text.isBlank()) continue
            if (sawEnd) throw ManifestFormatException("Content after the end of the index")
            val fields = (parseJson(text) as? JsonValue.JsonObject)?.entries
                ?: throw ManifestFormatException("An index line is not a JSON object")
            val type = (fields["t"] as? JsonValue.JsonString)?.value
                ?: throw ManifestFormatException("An index line has no type")
            if (pack == null && type != "x") throw ManifestFormatException("The index does not start with its header")
            when (type) {
                "x" -> {
                    if (pack != null) throw ManifestFormatException("The index has two headers")
                    if (number(fields, "v") != PackIndexFormat.VERSION.toLong()) throw ManifestFormatException("Index version is not supported")
                    val name = (fields["pack"] as? JsonValue.JsonString)?.value
                    if (name == null || !BackupLayout.isPackName(name)) throw ManifestFormatException("The index names no valid pack")
                    bytes = number(fields, "bytes")
                    if (bytes < 0) throw ManifestFormatException("The index has a negative pack length")
                    pack = ManifestPack(name, bytes, 0)
                }
                "e" -> {
                    val entry = ManifestReader.parseEntry(fields)
                    if (entry.packName != pack?.name) throw ManifestFormatException("An index entry belongs to another pack")
                    if (!ids.add(entry.entryId)) throw ManifestFormatException("Entry id ${entry.entryId} appears twice")
                    entries += entry
                }
                "z" -> {
                    sawEnd = true
                    if (number(fields, "entries") != entries.size.toLong()) throw ManifestFormatException("The index's count does not match its records")
                }
                else -> Unit // a record type from a later version
            }
        }
        val header = pack
        if (header == null || !sawEnd) throw ManifestFormatException("The index ends early")
        return PackIndex(ManifestPack(header.name, header.fileBytes, entries.size), entries)
    }

    private fun number(fields: Map<String, JsonValue>, key: String): Long {
        val value = (fields[key] as? JsonValue.JsonNumber)?.value
            ?: throw ManifestFormatException("Field \"$key\" is missing or not a number")
        if (value.isNaN() || value.isInfinite() || value != Math.floor(value) || Math.abs(value) > 9_007_199_254_740_992.0) {
            throw ManifestFormatException("Field \"$key\" is not a whole number")
        }
        return value.toLong()
    }
}
