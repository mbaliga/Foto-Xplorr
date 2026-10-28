package com.fotoxplorr.app.reel

import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Muxer
import androidx.media3.transformer.DefaultMuxer
import com.google.common.collect.ImmutableList
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * The muxer the reel is written with: the platform-independent MP4 muxer of the export library, with one
 * change. It is told about metadata of the pictures and clips that went in (a clip's recorded place and
 * time, a sound file's title and artist) and writes NONE of it: a reel is a new work, and a place that
 * belongs to one clip must not ride along on all of it (the person agreed to nothing like that). The
 * orientation of the frames is not metadata of this kind: it travels in the track's own format.
 */
@UnstableApi
class PlaceFreeMuxerFactory(private val inner: Muxer.Factory = DefaultMuxer.Factory()) : Muxer.Factory {
    override fun create(path: String): Muxer = PlaceFreeMuxer(inner.create(path))

    override fun getSupportedSampleMimeTypes(trackType: Int): ImmutableList<String> = inner.getSupportedSampleMimeTypes(trackType)

    override fun supportsWritingNegativeTimestampsInEditList(): Boolean = inner.supportsWritingNegativeTimestampsInEditList()
}

/** A [Muxer] that passes everything on except metadata entries; see [PlaceFreeMuxerFactory]. */
@UnstableApi
class PlaceFreeMuxer(private val inner: Muxer) : Muxer {
    override fun addTrack(format: Format): Int = inner.addTrack(format)

    override fun writeSampleData(trackId: Int, byteBuffer: ByteBuffer, bufferInfo: BufferInfo) = inner.writeSampleData(trackId, byteBuffer, bufferInfo)

    override fun addMetadataEntry(metadataEntry: Metadata.Entry) = Unit

    override fun close() = inner.close()
}

/**
 * A look at a finished MP4 for a recorded place, so a reel is not saved with one by a path nobody thought of.
 * It walks the top-level boxes without reading the picture data, then searches the `moov` box (where an
 * MP4 keeps its metadata, a few kilobytes to some megabytes) for the three spellings of a place: the
 * QuickTime `(c)xyz` atom, the 3GPP `loci` atom, and the word `location` in a metadata key. It cannot see a
 * place hidden in an unusual box, and a box it cannot parse is "no place found", not "clean".
 */
object ReelMp4Check {
    private const val MOOV = 0x6D6F6F76
    private const val HEADER = 8L
    private const val LARGE_HEADER = 16L

    /** The most of a `moov` box that is read; a box over this is searched in its first and last part. */
    const val MAX_MOOV_BYTES = 64 * 1024 * 1024

    private val HANDLER = "hdlr".toByteArray(Charsets.ISO_8859_1)
    private val SOUND = "soun".toByteArray(Charsets.ISO_8859_1)
    private const val HANDLER_KIND_OFFSET = 4 + 4 + 4 // 'hdlr', version and flags, a reserved word

    private val PATTERNS = listOf(
        byteArrayOf(0xA9.toByte(), 'x'.code.toByte(), 'y'.code.toByte(), 'z'.code.toByte()),
        "loci".toByteArray(Charsets.ISO_8859_1),
        "location".toByteArray(Charsets.ISO_8859_1),
    )

    fun carriesPlace(file: File): Boolean {
        val moov = readMoov(file) ?: return false
        return PATTERNS.any { indexOf(moov, it) >= 0 }
    }

    /**
     * Whether the finished file holds a sound track: true when a track's handler box says `soun`, false when the
     * metadata is readable and has none, null when it could not be read (no `moov` box, a box that does not parse):
     * the caller then goes by what it planned. The picture data is not read.
     */
    fun hasSoundTrack(file: File): Boolean? {
        val moov = readMoov(file) ?: return null
        var from = 0
        while (true) {
            val at = indexOf(moov, HANDLER, from)
            if (at < 0) return false
            // A handler box: its name, a version and flags word, a reserved word, then the handler's kind.
            if (at + HANDLER_KIND_OFFSET + SOUND.size <= moov.size && matches(moov, at + HANDLER_KIND_OFFSET, SOUND)) return true
            from = at + 1
        }
    }

    /** The bytes of the `moov` box (its first and last part when it is huge), or null when the file has none that can be walked to. */
    private fun readMoov(file: File): ByteArray? {
        if (!file.isFile) return null
        RandomAccessFile(file, "r").use { f ->
            val length = f.length()
            var position = 0L
            while (position + HEADER <= length) {
                f.seek(position)
                val size32 = f.readInt().toLong() and 0xFFFFFFFFL
                val type = f.readInt()
                var header = HEADER
                var size = size32
                if (size32 == 1L) {
                    if (position + LARGE_HEADER > length) return null
                    size = f.readLong()
                    header = LARGE_HEADER
                } else if (size32 == 0L) {
                    size = length - position
                }
                if (size < header || position + size > length) return null
                if (type == MOOV) return readBox(f, position + header, size - header)
                position += size
            }
        }
        return null
    }

    private fun readBox(f: RandomAccessFile, from: Long, bytes: Long): ByteArray {
        val keep = bytes.coerceAtMost(MAX_MOOV_BYTES.toLong()).toInt()
        val data = ByteArray(keep)
        if (bytes <= MAX_MOOV_BYTES) {
            f.seek(from)
            f.readFully(data)
        } else {
            val half = keep / 2
            f.seek(from)
            f.readFully(data, 0, half)
            f.seek(from + bytes - (keep - half))
            f.readFully(data, half, keep - half)
        }
        return data
    }

    private fun matches(data: ByteArray, at: Int, pattern: ByteArray): Boolean {
        for (j in pattern.indices) if (data[at + j] != pattern[j]) return false
        return true
    }

    internal fun indexOf(data: ByteArray, pattern: ByteArray, from: Int = 0): Int {
        if (pattern.isEmpty() || data.size < pattern.size) return -1
        val last = data.size - pattern.size
        var i = from.coerceAtLeast(0)
        while (i <= last) {
            var j = 0
            while (j < pattern.size && data[i + j] == pattern[j]) j++
            if (j == pattern.size) return i
            i++
        }
        return -1
    }
}
