package com.fotoxplorr.app.audiotags

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Reads and writes the `moov/udta/meta/ilst` metadata atom tree in an MP4/M4A container — the
 * iTunes-style tagging scheme every mainstream player and tagger uses for this format.
 *
 * ## Why a generic box tree, not a fixed struct
 * [Box] models exactly enough of the MP4 atom format to find, replace and re-size the six `ilst`
 * item atoms this app edits ([TITLE_ATOM] through [COVER_ATOM]) while passing every other atom —
 * `ftyp`, `mdat`, sample tables, an `aART` or `----` freeform atom this app has no field for —
 * through byte-for-byte. [CONTAINER_TYPES] is deliberately the SMALL set of atoms this codec must
 * recurse into to reach `ilst`; an `ilst` child this set does not name (an atom iTunes writes that
 * this app has no model for) is parsed as an opaque leaf and re-serialized unchanged, which is what
 * preserves it without this codec needing to understand it at all.
 *
 * ## The one structural hazard: `stco`/`co64` chunk offsets
 * A `trak`'s sample table stores every chunk's position as an ABSOLUTE byte offset from the start
 * of the file. When `moov` sits before `mdat` (common for a "fast start" file optimised for
 * streaming) and rewriting `ilst` changes `moov`'s size, every byte after `moov` — `mdat` among
 * them — shifts by that size delta, so every stored offset would point to the wrong place unless
 * it is shifted by the same amount. [shiftChunkOffsets] does exactly that, and only when it is
 * actually needed: when `moov` follows `mdat` (the other common layout), `mdat`'s absolute position
 * does not move at all, and shifting would corrupt otherwise-correct offsets.
 */
internal object Mp4TagCodec {

    fun read(bytes: ByteArray): AudioTags? {
        val moov = scanTopLevel(bytes).firstOrNull { it.type == "moov" } ?: return null
        val moovChildren = parseBoxes(bytes, moov.contentStart, moov.contentEnd)
        val ilst = findIlst(moovChildren) ?: return AudioTags()
        return ilstToTags(ilst)
    }

    fun write(input: ByteArray, output: OutputStream, tags: AudioTags) {
        val top = scanTopLevel(input)
        val moov = top.firstOrNull { it.type == "moov" }
        if (moov == null) {
            // No moov to hang tags on at all -- not a file this codec can tag; hand the bytes
            // back unchanged rather than fabricate a moov structure from nothing.
            output.write(input)
            return
        }

        val moovChildren = parseBoxes(input, moov.contentStart, moov.contentEnd)
        val updatedChildren = withUpdatedIlst(moovChildren, tags)
        val probe = Box.Container("moov", ByteArray(0), updatedChildren)
        val probeBytes = serialize(probe)
        val delta = probeBytes.size.toLong() - moov.size.toLong()

        val mdat = top.firstOrNull { it.type == "mdat" }
        val moovPrecedesMdat = mdat != null && moov.offset < mdat.offset

        val finalBytes = if (delta != 0L && moovPrecedesMdat) {
            serialize(shiftChunkOffsets(probe, delta) as Box.Container)
        } else {
            probeBytes
        }

        output.write(input, 0, moov.offset)
        output.write(finalBytes)
        val afterMoov = moov.offset + moov.size
        output.write(input, afterMoov, input.size - afterMoov)
    }

    // ── Top-level atom scan ─────────────────────────────────────────────────────────────
    //
    // Deliberately shallow and copy-free: `mdat` is the overwhelming majority of an audio file's
    // bytes, and this app only ever needs to locate and splice around it, never parse inside it.
    // Only `moov` (always small — no audio/video samples live in it) is deep-parsed into a [Box]
    // tree; every other top-level atom is streamed through by (offset, size) reference into the
    // original array in [write].

    internal data class TopLevelAtom(val type: String, val offset: Int, val headerSize: Int, val size: Int) {
        val contentStart: Int get() = offset + headerSize
        val contentEnd: Int get() = offset + size
    }

    internal fun scanTopLevel(bytes: ByteArray): List<TopLevelAtom> {
        val result = mutableListOf<TopLevelAtom>()
        var pos = 0
        while (pos + 8 <= bytes.size) {
            val size32 = bytes.beInt32(pos)
            val type = String(bytes, pos + 4, 4, Charsets.US_ASCII)
            val headerSize: Int
            val size: Int
            when {
                size32 == 1 -> {
                    if (pos + 16 > bytes.size) break
                    headerSize = 16
                    val big = bytes.beLong64(pos + 8)
                    if (big > Int.MAX_VALUE) break
                    size = big.toInt()
                }
                size32 == 0 -> {
                    headerSize = 8
                    size = bytes.size - pos
                }
                else -> {
                    headerSize = 8
                    size = size32
                }
            }
            if (size < headerSize || pos + size > bytes.size) break
            result += TopLevelAtom(type, pos, headerSize, size)
            pos += size
        }
        return result
    }

    // ── Box tree (moov subtree only) ────────────────────────────────────────────────────

    internal sealed class Box {
        abstract val type: String
        data class Leaf(override val type: String, val payload: ByteArray) : Box()
        data class Container(override val type: String, val fullBoxHeader: ByteArray, val children: List<Box>) : Box()
    }

    /** Atoms this codec recurses into. Everything else — including `ilst` items this app has no
     *  field for — is an opaque [Box.Leaf] and round-trips unchanged. `meta` is a FullBox (a
     *  4-byte version/flags header before its children); every other entry here is a plain
     *  container whose children start immediately after its 8-byte size+type header. */
    private val CONTAINER_TYPES = setOf(
        "moov", "trak", "mdia", "minf", "stbl", "udta", "meta", "ilst",
        TITLE_ATOM, ARTIST_ATOM, ALBUM_ATOM, DAY_ATOM, GENRE_ATOM, "trkn", "covr", "gnre",
    )

    /** MP4 4-character atom type codes are raw bytes, not text in any particular character set —
     *  but the `©`-prefixed iTunes item types (`©nam`, `©ART`, …) use byte 0xA9 for that marker,
     *  which is ISO-8859-1's own code point for `©` and is NOT representable in US-ASCII at all
     *  (encoding through `US_ASCII` silently substitutes `?`, and decoding a 0xA9 byte through it
     *  produces the Unicode replacement character) — so every atom type in this codec round-trips
     *  through ISO-8859-1, never US-ASCII, or every `©`-prefixed atom silently fails to match its
     *  own named constant after a write-then-read.
     */
    private val ATOM_TYPE_CHARSET = Charsets.ISO_8859_1

    private fun parseBoxes(bytes: ByteArray, start: Int, end: Int): List<Box> {
        val boxes = mutableListOf<Box>()
        var pos = start
        while (pos + 8 <= end) {
            val size32 = bytes.beInt32(pos)
            val type = String(bytes, pos + 4, 4, ATOM_TYPE_CHARSET)
            val headerSize: Int
            val size: Int
            when {
                size32 == 1 -> {
                    if (pos + 16 > end) break
                    headerSize = 16
                    val big = bytes.beLong64(pos + 8)
                    if (big > Int.MAX_VALUE) break
                    size = big.toInt()
                }
                size32 == 0 -> {
                    headerSize = 8
                    size = end - pos
                }
                else -> {
                    headerSize = 8
                    size = size32
                }
            }
            if (size < headerSize || pos + size > end) break
            val contentStart = pos + headerSize
            val contentEnd = pos + size

            if (type == "meta") {
                val fullBoxHeader = bytes.copyOfRange(contentStart, (contentStart + 4).coerceAtMost(contentEnd))
                boxes += Box.Container(type, fullBoxHeader, parseBoxes(bytes, contentStart + 4, contentEnd))
            } else if (type in CONTAINER_TYPES) {
                boxes += Box.Container(type, ByteArray(0), parseBoxes(bytes, contentStart, contentEnd))
            } else {
                boxes += Box.Leaf(type, bytes.copyOfRange(contentStart, contentEnd))
            }
            pos += size
        }
        return boxes
    }

    private fun serialize(box: Box): ByteArray {
        val out = ByteArrayOutputStream()
        when (box) {
            is Box.Leaf -> {
                out.writeBeInt32(8 + box.payload.size)
                out.write(box.type.toByteArray(ATOM_TYPE_CHARSET))
                out.write(box.payload)
            }
            is Box.Container -> {
                val childBytes = box.children.map { serialize(it) }
                val total = 8 + box.fullBoxHeader.size + childBytes.sumOf { it.size }
                out.writeBeInt32(total)
                out.write(box.type.toByteArray(ATOM_TYPE_CHARSET))
                out.write(box.fullBoxHeader)
                childBytes.forEach { out.write(it) }
            }
        }
        return out.toByteArray()
    }

    // ── Chunk offset shift ──────────────────────────────────────────────────────────────

    private fun shiftChunkOffsets(box: Box, delta: Long): Box = when (box) {
        is Box.Container -> box.copy(children = box.children.map { shiftChunkOffsets(it, delta) })
        is Box.Leaf -> when (box.type) {
            "stco" -> Box.Leaf(box.type, shiftStco(box.payload, delta))
            "co64" -> Box.Leaf(box.type, shiftCo64(box.payload, delta))
            else -> box
        }
    }

    /** `stco`: version/flags(4) + entryCount(4) + entryCount * 32-bit chunk offset. */
    private fun shiftStco(payload: ByteArray, delta: Long): ByteArray {
        if (payload.size < 8) return payload
        val out = payload.copyOf()
        val count = payload.beInt32(4)
        for (i in 0 until count) {
            val at = 8 + i * 4
            if (at + 4 > out.size) break
            val shifted = (payload.beInt32(at).toLong() and 0xFFFFFFFFL) + delta
            out[at] = ((shifted ushr 24) and 0xFF).toByte()
            out[at + 1] = ((shifted ushr 16) and 0xFF).toByte()
            out[at + 2] = ((shifted ushr 8) and 0xFF).toByte()
            out[at + 3] = (shifted and 0xFF).toByte()
        }
        return out
    }

    /** `co64`: version/flags(4) + entryCount(4) + entryCount * 64-bit chunk offset — the large-file
     *  variant of `stco`, same table shape with a wider offset field. */
    private fun shiftCo64(payload: ByteArray, delta: Long): ByteArray {
        if (payload.size < 8) return payload
        val out = payload.copyOf()
        val count = payload.beInt32(4)
        for (i in 0 until count) {
            val at = 8 + i * 8
            if (at + 8 > out.size) break
            val shifted = payload.beLong64(at) + delta
            for (b in 0 until 8) {
                out[at + b] = ((shifted ushr ((7 - b) * 8)) and 0xFF).toByte()
            }
        }
        return out
    }

    // ── ilst <-> AudioTags ──────────────────────────────────────────────────────────────

    private fun findIlst(moovChildren: List<Box>): Box.Container? {
        val udta = moovChildren.filterIsInstance<Box.Container>().firstOrNull { it.type == "udta" } ?: return null
        val meta = udta.children.filterIsInstance<Box.Container>().firstOrNull { it.type == "meta" } ?: return null
        return meta.children.filterIsInstance<Box.Container>().firstOrNull { it.type == "ilst" }
    }

    private fun ilstToTags(ilst: Box.Container): AudioTags {
        fun rawDataOf(type: String): ByteArray? {
            val item = ilst.children.filterIsInstance<Box.Container>().firstOrNull { it.type == type } ?: return null
            val data = item.children.filterIsInstance<Box.Leaf>().firstOrNull { it.type == "data" } ?: return null
            return if (data.payload.size >= 8) data.payload.copyOfRange(8, data.payload.size) else ByteArray(0)
        }
        fun textOf(type: String): String? = rawDataOf(type)?.let { String(it, Charsets.UTF_8) }?.takeIf(String::isNotBlank)

        val genre = textOf(GENRE_ATOM) ?: rawDataOf("gnre")?.takeIf { it.size >= 2 }
            ?.let { ID3V1_GENRES.getOrNull(it.beUShort(0) - 1) }
        val track = rawDataOf("trkn")?.takeIf { it.size >= 4 }?.let { it.beUShort(2) }?.takeIf { it > 0 }

        return AudioTags(
            title = textOf(TITLE_ATOM),
            artist = textOf(ARTIST_ATOM),
            album = textOf(ALBUM_ATOM),
            year = textOf(DAY_ATOM)?.let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() },
            trackNumber = track,
            genre = genre,
            coverArt = rawDataOf("covr"),
        )
    }

    /** Every `ilst` item atom this codec ever writes — used to strip the OLD copy of a field
     *  before appending its replacement, and to strip the legacy numeric `gnre` atom whenever a
     *  genre edit lands (both `gnre` and [GENRE_ATOM] must never coexist for the same file, or a
     *  reader that prefers one over the other could show a stale value). */
    private val REPLACEABLE_ATOM_TYPES =
        setOf(TITLE_ATOM, ARTIST_ATOM, ALBUM_ATOM, DAY_ATOM, "trkn", GENRE_ATOM, "gnre", "covr")

    private fun withUpdatedIlst(moovChildren: List<Box>, tags: AudioTags): List<Box> {
        val udta = moovChildren.filterIsInstance<Box.Container>().firstOrNull { it.type == "udta" }
            ?: Box.Container("udta", ByteArray(0), emptyList())
        val meta = udta.children.filterIsInstance<Box.Container>().firstOrNull { it.type == "meta" }
            ?: Box.Container("meta", byteArrayOf(0, 0, 0, 0), emptyList())
        val ilst = meta.children.filterIsInstance<Box.Container>().firstOrNull { it.type == "ilst" }
            ?: Box.Container("ilst", ByteArray(0), emptyList())

        val newIlstChildren = ilst.children.filterNot { it.type in REPLACEABLE_ATOM_TYPES }.toMutableList()
        tags.title?.let { newIlstChildren += textItemAtom(TITLE_ATOM, it) }
        tags.artist?.let { newIlstChildren += textItemAtom(ARTIST_ATOM, it) }
        tags.album?.let { newIlstChildren += textItemAtom(ALBUM_ATOM, it) }
        tags.year?.let { newIlstChildren += textItemAtom(DAY_ATOM, "%04d".format(it)) }
        tags.trackNumber?.let { newIlstChildren += trknItemAtom(it) }
        tags.genre?.let { newIlstChildren += textItemAtom(GENRE_ATOM, it) }
        tags.coverArt?.let { newIlstChildren += covrItemAtom(it) }

        val newIlst = Box.Container("ilst", ByteArray(0), newIlstChildren)
        val newMeta = Box.Container("meta", meta.fullBoxHeader, meta.children.filterNot { it.type == "ilst" } + newIlst)
        val newUdta = Box.Container("udta", udta.fullBoxHeader, udta.children.filterNot { it.type == "meta" } + newMeta)
        return moovChildren.filterNot { it.type == "udta" } + newUdta
    }

    private fun dataAtom(flags: Int, payload: ByteArray): Box.Leaf {
        val body = ByteArrayOutputStream()
        body.writeBeInt32(flags) // version (top byte, always 0) folded into a plain 32-bit write
        body.writeBeInt32(0) // reserved
        body.write(payload)
        return Box.Leaf("data", body.toByteArray())
    }

    private fun textItemAtom(type: String, text: String): Box.Container =
        Box.Container(type, ByteArray(0), listOf(dataAtom(DATA_FLAG_UTF8, text.toByteArray(Charsets.UTF_8))))

    private fun trknItemAtom(track: Int): Box.Container {
        val payload = ByteArrayOutputStream()
        payload.writeBeInt16(0)
        payload.writeBeInt16(track.coerceIn(0, 0xFFFF))
        payload.writeBeInt16(0) // total track count: unknown, matches most single-file taggers
        payload.writeBeInt16(0)
        return Box.Container("trkn", ByteArray(0), listOf(dataAtom(DATA_FLAG_IMPLICIT, payload.toByteArray())))
    }

    private fun covrItemAtom(imageBytes: ByteArray): Box.Container {
        val flags = if (sniffImageMime(imageBytes) == "image/png") DATA_FLAG_PNG else DATA_FLAG_JPEG
        return Box.Container("covr", ByteArray(0), listOf(dataAtom(flags, imageBytes)))
    }

    private const val DATA_FLAG_IMPLICIT = 0
    private const val DATA_FLAG_UTF8 = 1
    private const val DATA_FLAG_JPEG = 13
    private const val DATA_FLAG_PNG = 14
}

private const val TITLE_ATOM = "©nam"
private const val ARTIST_ATOM = "©ART"
private const val ALBUM_ATOM = "©alb"
private const val DAY_ATOM = "©day"
private const val GENRE_ATOM = "©gen"
