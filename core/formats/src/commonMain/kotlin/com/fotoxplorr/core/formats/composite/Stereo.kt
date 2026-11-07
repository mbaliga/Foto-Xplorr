package com.fotoxplorr.core.formats.composite

import com.fotoxplorr.core.formats.raw.ByteSource
import com.fotoxplorr.core.formats.raw.ExtractionLimitException
import com.fotoxplorr.core.formats.raw.JpegProbe
import com.fotoxplorr.core.formats.raw.Reader
import com.fotoxplorr.core.formats.raw.Tiff
import com.fotoxplorr.core.formats.raw.u16
import com.fotoxplorr.core.formats.raw.u32

/** One JPEG inside an MPO: where it is, its MP type code, and what [JpegProbe] read from it. */
data class MpoImage(
    val range: EmbeddedRange,
    val typeCode: Int,
    val width: Int,
    val height: Int,
    val orientation: Int?,
)

/** A stereo pair: [left] and [right] as the file orders them (the first disparity image is the left eye). */
data class StereoPair(val left: MpoImage, val right: MpoImage)

/**
 * Reads the CIPA DC-007 Multi-Picture index of an MPO (a stereo camera's or a 3DS's `.mpo`): the
 * first image's APP2 `MPF\0` segment holds a TIFF-style header and an index IFD whose MPEntry tag
 * (0xB002) lists every image as 16 bytes: attribute (type code in the low 24 bits), size, offset
 * (from the MP header; 0 for the first image) and two dependent-image numbers.
 *
 * Only images typed "disparity" (0x020002) make a stereo pair. A phone's MPF that only lists large
 * thumbnails (0x010001 / 0x010002) is not stereo. Each image is validated as a JPEG inside the file
 * (TRAPS #139's probe) before it is returned. Tested on constructed files only.
 */
object MpoReader {
    const val TYPE_DISPARITY = 0x020002

    fun images(source: ByteSource): List<MpoImage> = try {
        read(Reader(source))
    } catch (_: ExtractionLimitException) {
        emptyList()
    }

    fun stereoPair(source: ByteSource): StereoPair? {
        val disparity = images(source).filter { it.typeCode == TYPE_DISPARITY }
        return if (disparity.size >= 2) StereoPair(disparity[0], disparity[1]) else null
    }

    private fun read(r: Reader): List<MpoImage> {
        val app2 = findMpf(r) ?: return emptyList()
        val (headerPos, segmentEnd) = app2
        val bom = r.bytes(headerPos, 8) ?: return emptyList()
        val le = when {
            bom.isAscii(0, "II") -> true
            bom.isAscii(0, "MM") -> false
            else -> return emptyList()
        }
        if (bom.u16(2, le) != 42) return emptyList()
        val tiff = Tiff(r, headerPos, segmentEnd, le, bom.u32(4, le))
        val ifd = tiff.readIfd(tiff.ifd0) ?: return emptyList()
        val entry = ifd.entries.firstOrNull { it.tag == TAG_MP_ENTRY } ?: return emptyList()
        val count = entry.count / 16
        if (count < 1 || count > MAX_IMAGES) return emptyList()
        val at = tiff.dataPos(entry) ?: return emptyList()
        val table = r.bytes(at, (count * 16).toInt()) ?: return emptyList()
        val out = ArrayList<MpoImage>()
        for (i in 0 until count.toInt()) {
            val o = i * 16
            val type = (table.u32(o, le) and 0xFFFFFF).toInt()
            val size = table.u32(o + 4, le)
            val rel = table.u32(o + 8, le)
            val offset = if (i == 0) 0L else headerPos + rel
            if (i > 0 && rel == 0L) continue
            val info = JpegProbe.probe(r, offset, size) ?: continue
            out += MpoImage(EmbeddedRange(offset, size), type, info.width, info.height, info.orientation)
        }
        return out
    }

    /** (MP header position, end of the APP2 segment) of the first image's `MPF\0` segment. */
    private fun findMpf(r: Reader): Pair<Long, Long>? {
        val soi = r.bytes(0, 2) ?: return null
        if (soi.u8At(0) != 0xFF || soi.u8At(1) != 0xD8) return null
        var pos = 2L
        repeat(MAX_SEGMENTS) {
            val h = r.bytes(pos, 4) ?: return null
            if (h.u8At(0) != 0xFF) return null
            val marker = h.u8At(1)
            if (marker == 0xDA || marker == 0xD9) return null
            val len = h.u16(2, false)
            if (len < 2) return null
            if (marker == 0xE2 && len >= 12) {
                val tag = r.bytes(pos + 4, 4) ?: return null
                if (tag.isAscii(0, "MPF") && tag[3].toInt() == 0) return (pos + 8) to (pos + 2 + len)
            }
            pos += 2 + len
        }
        return null
    }

    private fun ByteArray.u8At(i: Int) = this[i].toInt() and 0xFF

    private const val TAG_MP_ENTRY = 0xB002
    private const val MAX_IMAGES = 32L
    private const val MAX_SEGMENTS = 64
}

/**
 * Whether a HEIF declares a stereo pair (Apple's spatial photos): a `ster` entity group inside
 * `meta/grpl`, naming the left and right image items. Detection only: showing the second eye needs
 * an item-level HEIF decoder the platform does not offer, so the viewer says the photo is spatial
 * and shows the primary image (honest scope, WP2.8).
 */
object HeifStereo {
    data class Group(val left: Long, val right: Long)

    fun find(source: ByteSource): Group? = try {
        val r = Reader(source)
        val meta = Boxes.child(r, 0, r.length, "meta")
        meta?.let { (start, end) ->
            val grpl = Boxes.child(r, start + 4, end, "grpl") ?: return@let null
            val ster = Boxes.child(r, grpl.first, grpl.second, "ster") ?: return@let null
            // FullBox version/flags, group_id, num_entities_in_group, entity ids.
            val b = r.bytes(ster.first, 20) ?: return@let null
            if (b.u32(8, false) < 2) return@let null
            Group(left = b.u32(12, false), right = b.u32(16, false))
        }
    } catch (_: ExtractionLimitException) {
        null
    }
}

/** How a stereo pair is drawn. */
enum class StereoMode { SIDE_BY_SIDE, CROSS_EYE, ANAGLYPH, WIGGLE }

/** The pixel side of the stereo modes: pure, on packed ARGB. */
object StereoPixels {
    /**
     * Red-cyan anaglyph: red from the left eye, green and blue from the right. With [halfColour] the
     * red channel is the left eye's luminance, which cuts the rivalry strongly red or cyan objects
     * cause. Both arrays must be the same size; alpha is opaque.
     */
    fun anaglyph(left: IntArray, right: IntArray, halfColour: Boolean = true): IntArray {
        require(left.size == right.size) { "eyes differ in size" }
        val out = IntArray(left.size)
        for (i in left.indices) {
            val l = left[i]
            val rr = right[i]
            val red = if (halfColour) {
                val lr = (l shr 16) and 0xFF
                val lg = (l shr 8) and 0xFF
                val lb = l and 0xFF
                ((lr * 299 + lg * 587 + lb * 114) / 1000).coerceIn(0, 255)
            } else {
                (l shr 16) and 0xFF
            }
            out[i] = (0xFF shl 24) or (red shl 16) or (rr and 0x00FFFF)
        }
        return out
    }

    /** Which eye a wiggle shows at [elapsedMs]: false = left. Never alternates when [motionAllowed] is off. */
    fun wiggleShowsRight(elapsedMs: Long, motionAllowed: Boolean, periodMs: Long = WIGGLE_PERIOD_MS): Boolean =
        motionAllowed && periodMs > 0 && (elapsedMs / (periodMs / 2)) % 2 == 1L

    /** Side-by-side order: parallel viewing puts the left eye on the left; cross-eye swaps them. */
    fun leftFirst(mode: StereoMode): Boolean = mode != StereoMode.CROSS_EYE

    /** Whether [mode] can be offered: wiggle is motion, so it is not offered with animations off. */
    fun offered(mode: StereoMode, motionAllowed: Boolean): Boolean = mode != StereoMode.WIGGLE || motionAllowed

    const val WIGGLE_PERIOD_MS = 300L
}
