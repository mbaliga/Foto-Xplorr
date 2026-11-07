package com.fotoxplorr.core.formats.composite

import com.fotoxplorr.core.formats.raw.ByteSource
import com.fotoxplorr.core.formats.raw.ExtractionLimitException
import com.fotoxplorr.core.formats.raw.Reader
import com.fotoxplorr.core.formats.raw.u32

/** A byte range inside one file. Always inside the file it was read from: the locators check. */
data class EmbeddedRange(val offset: Long, val length: Long) {
    val end: Long get() = offset + length
}

/** Which layout told us where the clip is. */
enum class MotionPhotoLayout {
    /** Google Motion Photo 1.0: XMP `Container:Directory` items with `Item:Length` / `Item:Padding`. */
    GOOGLE_CONTAINER,

    /** The older Google "micro video": `GCamera:MicroVideoOffset`, counted back from the end of the file. */
    GOOGLE_MICRO_VIDEO,

    /** Samsung: an SEF trailer (`SEFH` ... `SEFT`) holding a `MotionPhoto_Data` block. */
    SAMSUNG_SEF,
}

/**
 * Where the short clip of a motion photo is. [video] starts with an MP4 `ftyp` box (checked);
 * [still] is the primary image in front of it (from 0), what an exported still is cut from.
 * [presentationUs] is the frame of the clip the still was taken from, when the writer said
 * (`GCamera:MotionPhotoPresentationTimestampUs` / `MicroVideoPresentationTimestampUs`; -1 means
 * unknown and is reported as null).
 */
data class MotionPhoto(
    val video: EmbeddedRange,
    val still: EmbeddedRange,
    val layout: MotionPhotoLayout,
    val presentationUs: Long?,
)

/**
 * Finds the MP4 inside a motion photo, from the file's own bytes. Pure: reads go through the bounded
 * [Reader] (every range checked against the file, a few hundred KB at most), and nothing is
 * allocated from a length the file declares: the result is a range for the caller to stream.
 *
 * Order: the Google container directory, then the legacy micro-video offset, then the Samsung
 * trailer. A layout whose range does not land on an `ftyp` box is not trusted and the next is
 * tried, so a file whose XMP lies (an editor that re-saved the JPEG and kept the old lengths) ends
 * as "no clip", never as garbage handed to a player.
 *
 * Written from Google's published Motion Photo format 1.0 and ExifTool's description of the Samsung
 * trailer; tested only on constructed files (`MotionPhotoLocatorTest`). UNVERIFIED on real camera
 * files.
 */
object MotionPhotoLocator {

    fun locate(source: ByteSource): MotionPhoto? = try {
        val r = Reader(source)
        val xmp = XmpLite.find(r)
        (xmp?.let { fromContainer(r, it) })
            ?: (xmp?.let { fromMicroVideo(r, it) })
            ?: fromSamsungTrailer(r)
    } catch (_: ExtractionLimitException) {
        null
    }

    private fun presentation(xmp: XmpLite, name: String): Long? =
        xmp.value(XmpLite.GCAMERA_NS, name)?.toLongOrNull()?.takeIf { it >= 0 }

    /**
     * Motion Photo 1.0: the directory lists the primary first, then each secondary item in file
     * order, packed at the end of the file. Item k starts at (file length - the lengths of items k
     * onward - the paddings between them). The clip is the item whose semantic is `MotionPhoto`.
     */
    internal fun fromContainer(r: Reader, xmp: XmpLite): MotionPhoto? {
        val items = xmp.elements(XmpLite.CONTAINER_NS, "Item").map { fragment ->
            ContainerItem(
                mime = xmp.valueIn(fragment, XmpLite.ITEM_NS, "Mime").orEmpty(),
                semantic = xmp.valueIn(fragment, XmpLite.ITEM_NS, "Semantic").orEmpty(),
                length = xmp.valueIn(fragment, XmpLite.ITEM_NS, "Length")?.toLongOrNull() ?: 0L,
                padding = xmp.valueIn(fragment, XmpLite.ITEM_NS, "Padding")?.toLongOrNull() ?: 0L,
            )
        }
        if (items.size < 2 || items.size > MAX_ITEMS) return null
        if (items.any { it.length < 0 || it.padding < 0 || it.length > r.length || it.padding > r.length }) return null
        val videoIndex = items.indexOfFirst { it.semantic.equals("MotionPhoto", ignoreCase = true) }
        if (videoIndex < 1) return null
        // Bytes from the start of item k to the end of the file.
        var tail = 0L
        for (k in items.indices.reversed()) {
            if (k < 1) break
            tail += items[k].length
            if (k < items.size - 1) tail += items[k].padding
            if (tail > r.length) return null
            if (k == videoIndex) break
        }
        val videoOffset = r.length - tail
        val video = EmbeddedRange(videoOffset, items[videoIndex].length)
        // The primary ends where item 1 begins, less the primary's own padding.
        var secondaries = 0L
        for (k in 1 until items.size) {
            secondaries += items[k].length
            if (k < items.size - 1) secondaries += items[k].padding
        }
        val stillEnd = r.length - secondaries - items[0].padding
        if (stillEnd <= 0 || !isMp4At(r, video)) return null
        return MotionPhoto(
            video = video,
            still = EmbeddedRange(0, stillEnd),
            layout = MotionPhotoLayout.GOOGLE_CONTAINER,
            presentationUs = presentation(xmp, "MotionPhotoPresentationTimestampUs"),
        )
    }

    /** The legacy form: the clip is the last `MicroVideoOffset` bytes of the file. */
    internal fun fromMicroVideo(r: Reader, xmp: XmpLite): MotionPhoto? {
        if (xmp.value(XmpLite.GCAMERA_NS, "MicroVideo")?.trim() != "1") return null
        val back = xmp.value(XmpLite.GCAMERA_NS, "MicroVideoOffset")?.toLongOrNull() ?: return null
        if (back <= 0 || back >= r.length) return null
        val video = EmbeddedRange(r.length - back, back)
        if (!isMp4At(r, video)) return null
        return MotionPhoto(
            video = video,
            still = EmbeddedRange(0, video.offset),
            layout = MotionPhotoLayout.GOOGLE_MICRO_VIDEO,
            presentationUs = presentation(xmp, "MicroVideoPresentationTimestampUs"),
        )
    }

    /**
     * Samsung's trailer, little-endian: the file ends `[dir length u32]["SEFT"]`; the directory
     * before that starts `"SEFH" [version u32] [count u32]`, then 12-byte entries
     * `[u16][type u16][distance back from SEFH u32][size u32]`. Each data block starts
     * `[u16][type u16][name length u32][name]`, then the payload. The clip is `MotionPhoto_Data`.
     * The still ends where the first data block begins.
     */
    internal fun fromSamsungTrailer(r: Reader): MotionPhoto? {
        if (r.length < 32) return null
        val tail = r.bytes(r.length - 8, 8) ?: return null
        if (!tail.isAscii(4, "SEFT")) return null
        val dirLen = tail.u32(0, true)
        if (dirLen < 12 || dirLen > MAX_SEF_DIR || dirLen > r.length - 8) return null
        val dirPos = r.length - 8 - dirLen
        val dir = r.bytes(dirPos, dirLen.toInt()) ?: return null
        if (!dir.isAscii(0, "SEFH")) return null
        val count = dir.u32(8, true)
        if (count < 1 || count > MAX_SEF_ENTRIES || 12 + count * 12 > dirLen) return null
        var firstBlock = dirPos
        var clip: EmbeddedRange? = null
        for (i in 0 until count.toInt()) {
            val e = 12 + i * 12
            val back = dir.u32(e + 4, true)
            val size = dir.u32(e + 8, true)
            val blockPos = dirPos - back
            if (back <= 0 || blockPos < 0 || size < 8 || size > dirPos - blockPos) continue
            firstBlock = minOf(firstBlock, blockPos)
            val head = r.bytes(blockPos, minOf(size, 8L + MAX_SEF_NAME).toInt()) ?: continue
            val nameLen = head.u32(4, true)
            if (nameLen < 1 || nameLen > MAX_SEF_NAME || 8 + nameLen > head.size) continue
            val name = head.decodeToString(8, 8 + nameLen.toInt())
            if (name == "MotionPhoto_Data") {
                val payload = EmbeddedRange(blockPos + 8 + nameLen, size - 8 - nameLen)
                if (payload.length > 0 && isMp4At(r, payload)) clip = payload
            }
        }
        val video = clip ?: return null
        if (firstBlock <= 0) return null
        return MotionPhoto(
            video = video,
            still = EmbeddedRange(0, firstBlock),
            layout = MotionPhotoLayout.SAMSUNG_SEF,
            presentationUs = null,
        )
    }

    /** True when [range] is inside the file and begins with an MP4/QuickTime `ftyp` box. */
    internal fun isMp4At(r: Reader, range: EmbeddedRange): Boolean {
        if (range.offset < 0 || range.length < MIN_CLIP || range.offset > r.length - range.length) return false
        val head = r.bytes(range.offset, 8) ?: return false
        val boxSize = head.u32(0, false)
        return head.isAscii(4, "ftyp") && boxSize in 8..range.length
    }

    private class ContainerItem(val mime: String, val semantic: String, val length: Long, val padding: Long)

    private const val MAX_ITEMS = 16
    private const val MAX_SEF_DIR = 64L * 1024
    private const val MAX_SEF_ENTRIES = 256L
    private const val MAX_SEF_NAME = 256L
    private const val MIN_CLIP = 16L
}

internal fun ByteArray.isAscii(at: Int, word: String): Boolean {
    if (at < 0 || at + word.length > size) return false
    for (i in word.indices) if (this[at + i].toInt() != word[i].code) return false
    return true
}

/**
 * The still of a motion photo, saved on its own, must not claim a clip it no longer has: a gallery
 * that trusts the flag would look for an MP4 at the end of a plain JPEG. [disarm] rewrites the
 * flags in place, `MotionPhoto` / `MicroVideo` from 1 to 0, in both the attribute and the element
 * form. Same length, so every offset in the file stays valid; the rest of the XMP (dates, GPS,
 * the directory) is kept as written.
 */
object MotionPhotoStill {
    private val PATTERNS = listOf(
        ":MotionPhoto=\"1\"", ":MotionPhoto='1'", ":MotionPhoto>1<",
        ":MicroVideo=\"1\"", ":MicroVideo='1'", ":MicroVideo>1<",
    ).map { it.encodeToByteArray() }

    /** Patches [head] (the first bytes of the still) in place; returns how many flags were turned off. */
    fun disarm(head: ByteArray): Int {
        var patched = 0
        for (pattern in PATTERNS) {
            val one = pattern.indexOfFirst { it == '1'.code.toByte() }
            var from = 0
            while (true) {
                val at = XmpLite.indexOf(head, pattern, from)
                if (at < 0) break
                head[at + one] = '0'.code.toByte()
                patched++
                from = at + pattern.size
            }
        }
        return patched
    }
}
