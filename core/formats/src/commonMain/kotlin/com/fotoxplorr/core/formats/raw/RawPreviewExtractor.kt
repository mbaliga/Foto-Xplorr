package com.fotoxplorr.core.formats.raw

/**
 * Finds the largest embedded JPEG preview inside a RAW file without decoding the sensor data and
 * without a native library (the plan's piex bundle needs an NDK build this repo does not have).
 * Pure Kotlin over a [ByteSource]; never reads a whole file; never throws; returns null rather than
 * a guess.
 *
 * Supported shapes (each also a test fixture CONSTRUCTED in `RawPreviewExtractorTest`):
 *  - TIFF-based (little/big endian; also the Olympus `IIRO`/`IIRS` and Panasonic `IIU` magics):
 *    IFD0/IFD1.. JPEGInterchangeFormat(+Length), SubIFDs, StripOffsets with Compression 6/7,
 *    Panasonic tag 0x2E JpgFromRaw, and vendor MakerNote previews for Olympus, Pentax, Sony and
 *    Nikon (PreviewIFD). This covers ORF, PEF, RW2, ARW, NEF/NRW, CR2, DNG and (generically) SRW.
 *  - Fujifilm RAF: header offset/length of the embedded JPEG.
 *  - Canon CR3 (ISOBMFF `ftyp crx `): `PRVW` and `THMB` boxes, orientation from `CMT1`.
 *
 * HONESTY: the vendor layouts were written from the public specifications and community
 * documentation FROM MEMORY. They have NOT been verified against real camera files (no RAW sample
 * was available and none is committed for copyright). Because every candidate must survive
 * [JpegProbe] (FFD8, a baseline/progressive SOF with plausible size, an EOI at the end) and
 * MakerNote offsets are tried against several bases, a wrong layout assumption costs a missed
 * preview (null, the app falls back to its old path), not a wrong image. Each family needs a real
 * sample on a device before it is trusted.
 *
 * Hostile-file guards: all reads are range-checked ([Reader]), single reads are capped, the total
 * bytes and read count are budgeted, IFDs are visited once (loops), IFD entry counts, SubIFD
 * counts, chain length, nesting depth and ISOBMFF box counts are all capped.
 */
object RawPreviewExtractor {
    private const val MAX_IFDS = 48
    private const val MAX_DEPTH = 6
    private const val MAX_CHAIN = 8
    private const val MAX_SUB_IFDS = 8
    private const val MAX_BOXES = 4000

    fun extract(source: ByteSource): RawPreview? = try {
        val r = Reader(source)
        val head = r.bytes(0, 16)
        when {
            head == null -> null
            head.startsWith("FUJIFILMCCD-RAW") -> Walk(r).raf()
            head.startsWith("ftyp", 4) && head.startsWith("crx ", 8) -> Walk(r).cr3()
            (head.u8(0) == 'I'.code && head.u8(1) == 'I'.code) || (head.u8(0) == 'M'.code && head.u8(1) == 'M'.code) ->
                Walk(r).tiff()
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    private fun ByteArray.startsWith(s: String, at: Int = 0): Boolean {
        if (size < at + s.length) return false
        for (i in s.indices) if (this[at + i].toInt() != s[i].code) return false
        return true
    }

    private class Walk(private val r: Reader) {
        private val out = Candidates(r)
        private val visited = HashSet<Long>()
        private var orientation: Int? = null

        // ---- RAF ----

        fun raf(): RawPreview? {
            val h = r.bytes(84, 8) ?: return null
            out.add(RawPreviewKind.RAF_EMBEDDED, h.u32(4, false), h.u32(0, false))
            return out.best(null)
        }

        // ---- TIFF family ----

        fun tiff(): RawPreview? {
            val t = Tiff.open(r, 0, r.length) ?: return null
            orientation = t.orientationOfIfd0()
            walk(t, t.ifd0, Role.MAIN, 0, null, longArrayOf(t.base))
            return out.best(orientation)
        }

        private enum class Role { MAIN, THUMB, SUB, EXIF, MAKER }

        private enum class Maker { OLYMPUS, OLYMPUS_CAMERA, PENTAX, SONY, NIKON }

        private fun walk(t: Tiff, rel: Long, role: Role, depth: Int, maker: Maker?, bases: LongArray, chain: Int = 0) {
            if (depth > MAX_DEPTH || visited.size >= MAX_IFDS || !visited.add(t.base * 31 + rel)) return
            val ifd = t.readIfd(rel) ?: return
            var jOff: Long? = null
            var jLen: Long? = null
            var comp: Long? = null
            var stripOff: Long? = null
            var stripLen: Long? = null
            var mStart: Long? = null
            var mLen: Long? = null
            for (e in ifd.entries) {
                when (e.tag) {
                    0x0103 -> comp = t.uint(e)
                    0x0111 -> stripOff = t.uint(e)
                    0x0117 -> stripLen = t.uint(e)
                    0x0201 -> jOff = t.uint(e)
                    0x0202 -> jLen = t.uint(e)
                    0x002E -> if (role == Role.MAIN && e.type == 7) {
                        t.dataPos(e)?.let { out.add(RawPreviewKind.JPG_FROM_RAW, e.count, it) }
                    }
                    0x014A -> if (maker == null) {
                        for (i in 0 until minOf(e.count, MAX_SUB_IFDS.toLong()).toInt()) {
                            t.uint(e, i)?.let { walk(t, it, Role.SUB, depth + 1, null, bases) }
                        }
                    }
                    0x8769 -> if (maker == null) t.uint(e)?.let { walk(t, it, Role.EXIF, depth + 1, null, bases) }
                    0x927C -> if (maker == null) makerNote(t, e, depth)
                }
                if (maker != null) {
                    when (maker) {
                        Maker.OLYMPUS -> {
                            if (e.tag == 0x2020) t.uint(e)?.let { walk(t, it, Role.MAKER, depth + 1, Maker.OLYMPUS_CAMERA, bases) }
                            if (e.tag == 0x0100 && e.type == 7) {
                                t.dataPos(e)?.let { out.add(RawPreviewKind.MAKER_NOTE_PREVIEW, e.count, it) }
                            }
                        }
                        Maker.OLYMPUS_CAMERA -> {
                            if (e.tag == 0x0101) mStart = t.uint(e)
                            if (e.tag == 0x0102) mLen = t.uint(e)
                        }
                        Maker.PENTAX -> {
                            if (e.tag == 0x0004) mStart = t.uint(e)
                            if (e.tag == 0x0003) mLen = t.uint(e)
                        }
                        Maker.SONY -> if (e.tag == 0x2001 && e.type == 7) {
                            t.dataPos(e)?.let { out.add(RawPreviewKind.MAKER_NOTE_PREVIEW, e.count, it) }
                        }
                        Maker.NIKON -> if (e.tag == 0x0011) {
                            t.uint(e)?.let { walk(t, it, Role.SUB, depth + 1, null, bases) }
                        }
                    }
                }
            }
            val kind = when (role) {
                Role.THUMB -> RawPreviewKind.IFD_THUMBNAIL
                Role.SUB -> RawPreviewKind.SUB_IFD_PREVIEW
                else -> RawPreviewKind.IFD0_JPEG
            }
            if (jOff != null && jLen != null) out.add(kind, jLen, *absolute(bases, jOff))
            if (comp == 6L || comp == 7L) {
                if (stripOff != null && stripLen != null) out.add(RawPreviewKind.STRIP_JPEG, stripLen, *absolute(bases, stripOff))
            }
            if (mStart != null && mLen != null) out.add(RawPreviewKind.MAKER_NOTE_PREVIEW, mLen, *absolute(bases, mStart))
            if ((role == Role.MAIN || role == Role.THUMB) && ifd.next != 0L && chain < MAX_CHAIN) {
                walk(t, ifd.next, Role.THUMB, depth, null, bases, chain + 1)
            }
        }

        private fun absolute(bases: LongArray, rel: Long) = LongArray(bases.size) { bases[it] + rel }

        /** Dispatches on the MakerNote's own signature; unknown makers are skipped. */
        private fun makerNote(t: Tiff, e: TiffEntry, depth: Int) {
            val pos = t.dataPos(e) ?: return
            val head = r.bytes(pos, minOf(e.count, 16L).toInt()) ?: return
            val outer = t.base
            fun leAt(i: Int) = head.size > i + 1 && head[i].toInt() == 'I'.code
            when {
                head.startsWith("OLYMPUS\u0000") && head.size >= 12 -> {
                    val w = Tiff.window(r, pos, t.limit, leAt(8))
                    walk(w, 12, Role.MAKER, depth + 1, Maker.OLYMPUS, longArrayOf(pos, outer, 0))
                }
                head.startsWith("OLYMP\u0000") -> {
                    walk(t, pos + 8 - outer, Role.MAKER, depth + 1, Maker.OLYMPUS, longArrayOf(outer, pos, 0))
                }
                head.startsWith("AOC\u0000") && head.size >= 6 -> {
                    val w = Tiff.window(r, outer, t.limit, leAt(4))
                    walk(w, pos + 6 - outer, Role.MAKER, depth + 1, Maker.PENTAX, longArrayOf(outer, pos, 0))
                }
                head.startsWith("PENTAX \u0000") && head.size >= 12 -> {
                    val w = Tiff.window(r, pos, t.limit, leAt(8))
                    walk(w, 10, Role.MAKER, depth + 1, Maker.PENTAX, longArrayOf(pos, outer, 0))
                }
                head.startsWith("SONY DSC \u0000") -> {
                    walk(t, pos + 12 - outer, Role.MAKER, depth + 1, Maker.SONY, longArrayOf(outer, pos, 0))
                }
                head.startsWith("Nikon\u0000") -> {
                    val inner = Tiff.open(r, pos + 10, t.limit) ?: return
                    walk(inner, inner.ifd0, Role.MAKER, depth + 1, Maker.NIKON, longArrayOf(inner.base))
                }
            }
        }

        // ---- ISOBMFF (Canon CR3) ----

        private var boxes = 0

        fun cr3(): RawPreview? {
            boxWalk(0, r.length, 0)
            return out.best(orientation)
        }

        private fun boxWalk(start: Long, end: Long, depth: Int) {
            var pos = start
            while (pos + 8 <= end && boxes++ < MAX_BOXES) {
                val h = r.bytes(pos, 16.coerceAtMost((end - pos).toInt())) ?: return
                var size = h.u32(0, false)
                var header = 8
                if (size == 1L) {
                    if (h.size < 16) return
                    if (h.u32(8, false) != 0L) return
                    size = h.u32(12, false)
                    header = 16
                } else if (size == 0L) {
                    size = end - pos
                }
                if (size < header || size > end - pos) return
                val type = CharArray(4) { h.u8(4 + it).toChar() }.concatToString()
                if (type.any { it.code < 0x20 || it.code > 0x7E }) return
                val body = pos + header
                val boxEnd = pos + size
                when (type) {
                    "moov", "trak", "mdia" -> if (depth < MAX_DEPTH) boxWalk(body, boxEnd, depth + 1)
                    "uuid" -> if (depth < MAX_DEPTH && boxEnd - body >= 16) {
                        // Canon's uuid payloads put children right after the 16-byte id, or after a
                        // short 8-byte preamble; a misparse fails the printable-type check above.
                        boxWalk(body + 16, boxEnd, depth + 1)
                        boxWalk(body + 24, boxEnd, depth + 1)
                    }
                    "PRVW" -> jpegIn(body, boxEnd, RawPreviewKind.ISOBMFF_PRVW)
                    "THMB" -> jpegIn(body, boxEnd, RawPreviewKind.ISOBMFF_THMB)
                    "CMT1" -> if (orientation == null) {
                        orientation = Tiff.open(r, body, boxEnd)?.orientationOfIfd0()
                    }
                }
                pos = boxEnd
            }
        }

        /** The JPEG sits after a short fixed header (version, size fields): find FFD8FF in the first 48 bytes. */
        private fun jpegIn(body: Long, boxEnd: Long, kind: RawPreviewKind) {
            val n = minOf(48L, boxEnd - body).toInt()
            val b = r.bytes(body, n) ?: return
            for (i in 0 until n - 3) {
                if (b.u8(i) == 0xFF && b.u8(i + 1) == 0xD8 && b.u8(i + 2) == 0xFF) {
                    val start = body + i
                    val rest = boxEnd - start
                    if (i >= 4) {
                        val declared = b.u32(i - 4, false)
                        if (declared in 64 until rest) out.add(kind, declared, start)
                    }
                    out.add(kind, rest, start)
                    return
                }
            }
        }
    }
}
