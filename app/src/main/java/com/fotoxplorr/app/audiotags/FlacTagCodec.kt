package com.fotoxplorr.app.audiotags

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Reads and writes FLAC's native tagging scheme: a `VORBIS_COMMENT` metadata block for text
 * fields, a `PICTURE` block for cover art, both sitting alongside `STREAMINFO` and whatever else
 * (`SEEKTABLE`, `CUESHEET`, `APPLICATION`, `PADDING`) a file already carries in its metadata-block
 * chain before the first audio frame.
 *
 * A write REBUILDS `VORBIS_COMMENT` from [AudioTags] alone (any custom Vorbis field this app has
 * no model for — `REPLAYGAIN_TRACK_GAIN`, a custom `COMMENT` — is not carried across) but preserves
 * every OTHER block byte-for-byte, `SEEKTABLE`/`CUESHEET` included: unlike MP4's `stco`, nothing in
 * a FLAC metadata block encodes an absolute file offset, so growing or shrinking the tag blocks
 * never invalidates anything else in the file.
 */
internal object FlacTagCodec {

    fun read(bytes: ByteArray): AudioTags? {
        val (blocks, _) = parseBlocks(bytes) ?: return null
        val fields = blocks.firstOrNull { it.type == BLOCK_VORBIS_COMMENT }?.let { parseVorbisComment(it.data) }
            ?: emptyMap()
        val cover = blocks.firstOrNull { it.type == BLOCK_PICTURE }?.let { parsePicture(it.data) }
        return AudioTags(
            title = fields["TITLE"],
            artist = fields["ARTIST"],
            album = fields["ALBUM"],
            year = (fields["DATE"] ?: fields["YEAR"])?.let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() },
            trackNumber = fields["TRACKNUMBER"]?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() },
            genre = fields["GENRE"],
            coverArt = cover,
        )
    }

    fun write(input: ByteArray, output: OutputStream, tags: AudioTags) {
        val parsed = parseBlocks(input)
        if (parsed == null) {
            output.write(input)
            return
        }
        val (blocks, audioStart) = parsed

        val kept = blocks.filterNot { it.type == BLOCK_VORBIS_COMMENT || it.type == BLOCK_PICTURE }
        val streamInfo = kept.filter { it.type == BLOCK_STREAMINFO }
        val oldPadding = kept.firstOrNull { it.type == BLOCK_PADDING }
        val others = kept.filterNot { it.type == BLOCK_STREAMINFO || it.type == BLOCK_PADDING }

        val rebuilt = buildList {
            addAll(streamInfo)
            add(RawBlock(BLOCK_VORBIS_COMMENT, buildVorbisComment(tags)))
            tags.coverArt?.let { add(RawBlock(BLOCK_PICTURE, buildPicture(it))) }
            addAll(others)
            // Reuses whatever padding budget the file already reserved for tag growth rather
            // than a fixed guess, so a file a previous, larger edit padded generously does not
            // get silently shrunk back down by this one.
            add(RawBlock(BLOCK_PADDING, ByteArray(oldPadding?.data?.size ?: DEFAULT_PADDING_BYTES)))
        }

        output.write(FLAC_MAGIC)
        rebuilt.forEachIndexed { index, block ->
            writeBlockHeader(output, block.type, isLast = index == rebuilt.lastIndex, size = block.data.size)
            output.write(block.data)
        }
        output.write(input, audioStart, input.size - audioStart)
    }

    // ── Metadata block chain ────────────────────────────────────────────────────────────

    private data class RawBlock(val type: Int, val data: ByteArray)

    /** @return the block list and the byte offset where audio frames begin, or null if [bytes] is
     *  not a well-formed FLAC stream (no magic, or no block ever set the "last metadata block"
     *  bit). */
    private fun parseBlocks(bytes: ByteArray): Pair<List<RawBlock>, Int>? {
        if (bytes.size < 4 || String(bytes, 0, 4, Charsets.US_ASCII) != "fLaC") return null
        var pos = 4
        val blocks = mutableListOf<RawBlock>()
        while (pos + 4 <= bytes.size) {
            val header = bytes[pos].toInt() and 0xFF
            val isLast = header and 0x80 != 0
            val type = header and 0x7F
            val length = bytes.beUInt24(pos + 1)
            val dataStart = pos + 4
            val dataEnd = dataStart + length
            if (dataEnd > bytes.size) return null
            blocks += RawBlock(type, bytes.copyOfRange(dataStart, dataEnd))
            pos = dataEnd
            if (isLast) return blocks to pos
        }
        return null
    }

    private fun writeBlockHeader(output: OutputStream, type: Int, isLast: Boolean, size: Int) {
        output.write((if (isLast) 0x80 else 0) or (type and 0x7F))
        output.write((size ushr 16) and 0xFF)
        output.write((size ushr 8) and 0xFF)
        output.write(size and 0xFF)
    }

    // ── VORBIS_COMMENT ──────────────────────────────────────────────────────────────────

    private fun parseVorbisComment(data: ByteArray): Map<String, String> {
        if (data.size < 8) return emptyMap()
        var pos = 0
        val vendorLength = data.leInt32(pos)
        pos += 4 + vendorLength
        if (pos + 4 > data.size) return emptyMap()
        val count = data.leInt32(pos)
        pos += 4

        val fields = LinkedHashMap<String, String>()
        repeat(count) {
            if (pos + 4 > data.size) return@repeat
            val length = data.leInt32(pos)
            pos += 4
            if (length < 0 || pos + length > data.size) return@repeat
            val entry = String(data, pos, length, Charsets.UTF_8)
            pos += length
            val separator = entry.indexOf('=')
            if (separator > 0) {
                val key = entry.substring(0, separator).uppercase()
                // First occurrence wins -- matches how most Vorbis-comment readers resolve a key
                // repeated across multiple comment entries (rare, but legal per the format).
                fields.putIfAbsent(key, entry.substring(separator + 1))
            }
        }
        return fields
    }

    private fun buildVorbisComment(tags: AudioTags): ByteArray {
        val out = ByteArrayOutputStream()
        val vendor = "Foto Xplorr".toByteArray(Charsets.UTF_8)
        out.writeLeInt32(vendor.size)
        out.write(vendor)

        val fields = buildList {
            tags.title?.let { add("TITLE=$it") }
            tags.artist?.let { add("ARTIST=$it") }
            tags.album?.let { add("ALBUM=$it") }
            tags.year?.let { add("DATE=%04d".format(it)) }
            tags.trackNumber?.let { add("TRACKNUMBER=$it") }
            tags.genre?.let { add("GENRE=$it") }
        }
        out.writeLeInt32(fields.size)
        fields.forEach { field ->
            val bytes = field.toByteArray(Charsets.UTF_8)
            out.writeLeInt32(bytes.size)
            out.write(bytes)
        }
        return out.toByteArray()
    }

    // ── PICTURE ─────────────────────────────────────────────────────────────────────────

    private fun parsePicture(data: ByteArray): ByteArray? {
        var pos = 4 // picture type: not modelled by AudioTags, always written as "Cover (front)"
        if (pos + 4 > data.size) return null
        val mimeLength = data.beInt32(pos)
        pos += 4 + mimeLength
        if (pos + 4 > data.size) return null
        val descriptionLength = data.beInt32(pos)
        pos += 4 + descriptionLength
        pos += 16 // width, height, color depth, colors-used: four 4-byte fields, all unused here
        if (pos + 4 > data.size) return null
        val pictureLength = data.beInt32(pos)
        pos += 4
        if (pictureLength < 0 || pos + pictureLength > data.size) return null
        return data.copyOfRange(pos, pos + pictureLength)
    }

    private fun buildPicture(imageBytes: ByteArray): ByteArray {
        val mime = sniffImageMime(imageBytes).toByteArray(Charsets.US_ASCII)
        val out = ByteArrayOutputStream()
        out.writeBeInt32(3) // picture type: "Cover (front)"
        out.writeBeInt32(mime.size)
        out.write(mime)
        out.writeBeInt32(0) // description: empty
        out.writeBeInt32(0) // width: unknown
        out.writeBeInt32(0) // height: unknown
        out.writeBeInt32(0) // color depth: unknown
        out.writeBeInt32(0) // colors used: 0 (not palette-indexed)
        out.writeBeInt32(imageBytes.size)
        out.write(imageBytes)
        return out.toByteArray()
    }

    private const val BLOCK_STREAMINFO = 0
    private const val BLOCK_PADDING = 1
    private const val BLOCK_VORBIS_COMMENT = 4
    private const val BLOCK_PICTURE = 6
    private const val DEFAULT_PADDING_BYTES = 1024
    private val FLAC_MAGIC = byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte())
}
