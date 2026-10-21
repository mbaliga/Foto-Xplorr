package com.fotoxplorr.app.metadata

import androidx.exifinterface.media.ExifInterface
import com.fotoxplorr.core.metadata.XmpPacket
import java.lang.reflect.Modifier

/**
 * Copies EXIF tags from one already-open file's [ExifInterface] onto another's (P0-07) -- the
 * "keep the shoot's own metadata" half of an edited copy. [EXCLUDED_TAGS] is the only thing this
 * class hand-curates; [COPIED_TAGS] is everything else, computed rather than separately
 * hand-enumerated, so a tag this class has never heard of defaults to being copied, not silently
 * dropped.
 *
 * XMP is deliberately excluded here -- see [EXCLUDED_TAGS]'s own note -- since the caller edits
 * that side through the richer, non-destructive [XmpPacket] path instead of a flat string copy.
 */
object ExifCopier {

    /** Every real `ExifInterface.TAG_*` string value in the linked androidx artifact, minus
     * [EXCLUDED_TAGS]. [ExifCopierCompletenessTest] pins that this stays a derived set rather than
     * a hand-typed list that could silently drift from the real API on an androidx upgrade. */
    val COPIED_TAGS: Set<String> = allExifTags() - EXCLUDED_TAGS

    /**
     * Sets every tag in [COPIED_TAGS] on [target] from [source] (skipping a tag [source] does not
     * have at all -- [target] is never told to explicitly clear a tag the source also lacks),
     * resets [ExifInterface.TAG_ORIENTATION] to [ExifInterface.ORIENTATION_NORMAL] (the editor
     * always re-renders pixels upright -- [com.fotoxplorr.app.media.decodeUpright] -- so a copied
     * `Orientation` would rotate an already-upright image a second time, the same convention
     * [com.fotoxplorr.app.share.SharePreparer]'s own kept-metadata share path uses), and copies
     * XMP across via [copiedXmpValue]. Does not call [ExifInterface.saveAttributes] -- that is the
     * caller's job, once every field it wants on [target] is set.
     *
     * With [keepLocation] false (the default for a file that leaves the device's own library, see
     * [com.fotoxplorr.app.editor.EditedCopyWriter.saveTo]) no `GPS*` EXIF tag is copied and the
     * XMP side has its location properties cleared, or is skipped when it cannot be parsed and so
     * cannot be cleaned.
     *
     * Returns what it had to leave behind ([ExifCopyReport]). A caller for whom a silent loss is
     * not acceptable (replacing the original) must look at it: this never throws for a packet it
     * skipped.
     */
    fun copy(source: ExifInterface, target: ExifInterface, keepLocation: Boolean = true): ExifCopyReport {
        COPIED_TAGS.forEach { tag ->
            if (!keepLocation && tag in GPS_TAGS) return@forEach
            source.getAttribute(tag)?.let { target.setAttribute(tag, it) }
        }
        target.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        // xmpAttributeUtf8(), not getAttribute(TAG_XMP) -- see that function's own doc (P0-08):
        // getAttribute decodes an XMP packet's bytes with the wrong charset.
        val sourceXmp = source.xmpAttributeUtf8()
        val copiedXmp = copiedXmpValue(sourceXmp, keepLocation)
        copiedXmp?.let { target.setAttribute(ExifInterface.TAG_XMP, it) }
        return ExifCopyReport(xmpSkipped = !sourceXmp.isNullOrBlank() && copiedXmp == null)
    }

    /** Every EXIF GPS tag ([ExifInterface.TAG_GPS_LATITUDE] and the rest of the `GPS*` group,
     *  including capture direction and the processing-method strings). */
    val GPS_TAGS: Set<String> = COPIED_TAGS.filterTo(mutableSetOf()) { it.startsWith("GPS") }
}

/**
 * What [ExifCopier.copy] did not carry onto the target.
 *
 * @property xmpSkipped the source held a non-blank XMP packet and none was written: it could not
 *   be parsed and was not plain ASCII (see [copiedXmpValue]), or it was unparseable and location
 *   had to be stripped.
 */
class ExifCopyReport(val xmpSkipped: Boolean)

/** Embedded metadata [ExifCopier] has no path for at all, found by [uncarriedMetadataIn]. */
enum class UncarriedMetadata(val label: String) {
    /** IPTC-IIM (Photoshop APP13): captions, keywords, credits written by older desktop tools. */
    IPTC_IIM("IPTC captions and keywords"),

    /** Extended XMP: the overflow packet for XMP larger than one JPEG segment. */
    EXTENDED_XMP("extended XMP"),

    /** PNG `tEXt` / `zTXt` / `iTXt` text other than the XMP packet: where image generators keep
     *  the prompt and settings, and where cameras and editors keep comments. */
    PNG_TEXT("PNG text such as a generation prompt"),

    /** A PNG `iCCP` chunk: the embedded colour profile. */
    COLOR_PROFILE("embedded colour profile"),

    /** A WebP chunk that is neither image data, EXIF, XMP nor a colour profile. */
    WEBP_CHUNK("other WebP metadata"),
}

/** How much of a file's start [uncarriedMetadataIn] looks at: a JPEG keeps all of this before the scan,
 *  and a PNG or WebP keeps its metadata chunks ahead of the image data in the files this app writes. */
internal const val UNCARRIED_SCAN_BYTES = 256 * 1024

/**
 * The metadata in [head] (a file's first bytes) that [ExifCopier.copy] cannot carry to another
 * file, because [ExifInterface] neither reads nor writes it.
 *
 *  - JPEG: an IPTC-IIM block and extended XMP, found by walking the marker segments up to the
 *    start of the image data, so a coincidental string inside pixel data or a thumbnail is not a
 *    match.
 *  - PNG: text chunks other than the XMP packet, and a colour profile, found by walking the chunk
 *    list (image data is skipped by its length, not read).
 *  - WebP: any chunk other than image data, EXIF, XMP and a colour profile.
 *
 * Anything else answers empty; a segment or chunk cut off by the end of [head] is not seen, though
 * a chunk whose header is there is reported even when its body is cut off.
 *
 * Not covered, on purpose: `MakerNote` and the embedded thumbnail, which [EXCLUDED_TAGS] drops for
 * a stated reason and which nearly every camera file has, so refusing on them would refuse nearly
 * every replace.
 */
internal fun uncarriedMetadataIn(head: ByteArray): Set<UncarriedMetadata> = when {
    isPng(head) -> uncarriedPngMetadata(head)
    isWebp(head) -> uncarriedWebpMetadata(head)
    else -> uncarriedJpegMetadata(head)
}

private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
private val XMP_ITXT_KEYWORD = "XML:com.adobe.xmp\u0000".toByteArray(Charsets.ISO_8859_1)

private fun isPng(head: ByteArray) = head.size >= PNG_SIGNATURE.size && startsWith(head, 0, head.size, PNG_SIGNATURE)

private fun isWebp(head: ByteArray) = head.size >= 12 &&
    fourCc(head, 0) == "RIFF" && fourCc(head, 8) == "WEBP"

private fun fourCc(b: ByteArray, at: Int) = String(b, at, 4, Charsets.ISO_8859_1)

private fun uncarriedPngMetadata(head: ByteArray): Set<UncarriedMetadata> {
    val found = mutableSetOf<UncarriedMetadata>()
    var pos = PNG_SIGNATURE.size.toLong()
    while (pos + 8 <= head.size) {
        val at = pos.toInt()
        val length = ((head[at].toLong() and 0xFF) shl 24) or ((head[at + 1].toLong() and 0xFF) shl 16) or
            ((head[at + 2].toLong() and 0xFF) shl 8) or (head[at + 3].toLong() and 0xFF)
        when (val type = fourCc(head, at + 4)) {
            "IEND" -> break
            "tEXt", "zTXt" -> found += UncarriedMetadata.PNG_TEXT
            "iTXt" -> {
                val start = at + 8
                val end = minOf(pos + 8 + length, head.size.toLong()).toInt()
                // The one iTXt ExifInterface carries is the XMP packet; one whose keyword is cut
                // off cannot be told from a prompt, so it counts.
                if (!startsWith(head, start, end, XMP_ITXT_KEYWORD)) found += UncarriedMetadata.PNG_TEXT
            }
            "iCCP" -> found += UncarriedMetadata.COLOR_PROFILE
            else -> Unit
        }
        // Length, type, data, CRC.
        pos += 12 + length
    }
    return found
}

private val WEBP_CARRIED_CHUNKS = setOf("VP8 ", "VP8L", "VP8X", "ALPH", "EXIF", "XMP ", "ICCP")

private fun uncarriedWebpMetadata(head: ByteArray): Set<UncarriedMetadata> {
    val found = mutableSetOf<UncarriedMetadata>()
    var pos = 12L
    while (pos + 8 <= head.size) {
        val at = pos.toInt()
        if (fourCc(head, at) !in WEBP_CARRIED_CHUNKS) found += UncarriedMetadata.WEBP_CHUNK
        val size = (head[at + 4].toLong() and 0xFF) or ((head[at + 5].toLong() and 0xFF) shl 8) or
            ((head[at + 6].toLong() and 0xFF) shl 16) or ((head[at + 7].toLong() and 0xFF) shl 24)
        // Header, payload padded to an even length.
        pos += 8 + size + (size and 1)
    }
    return found
}

private fun uncarriedJpegMetadata(head: ByteArray): Set<UncarriedMetadata> {
    val found = mutableSetOf<UncarriedMetadata>()
    if (head.size < 4 || head[0] != 0xFF.toByte() || head[1] != 0xD8.toByte()) return found
    var pos = 2
    while (pos + 4 <= head.size) {
        if (head[pos] != 0xFF.toByte()) break
        val marker = head[pos + 1].toInt() and 0xFF
        when {
            marker == 0xFF -> { pos++; continue }
            // Markers without a length: TEM, RSTn, SOI.
            marker == 0x01 || marker in 0xD0..0xD8 -> { pos += 2; continue }
            // Start of scan or end of image: no metadata segment follows.
            marker == 0xDA || marker == 0xD9 -> break
        }
        val length = ((head[pos + 2].toInt() and 0xFF) shl 8) or (head[pos + 3].toInt() and 0xFF)
        if (length < 2) break
        val start = pos + 4
        val end = minOf(pos + 2 + length, head.size)
        if (marker == 0xED && startsWith(head, start, end, PHOTOSHOP_HEADER) && photoshopCarriesIptc(head, start + PHOTOSHOP_HEADER.size, end)) {
            found += UncarriedMetadata.IPTC_IIM
        }
        if (marker == 0xE1 && startsWith(head, start, end, EXTENDED_XMP_HEADER)) found += UncarriedMetadata.EXTENDED_XMP
        pos += 2 + length
    }
    return found
}

private val PHOTOSHOP_HEADER = "Photoshop 3.0\u0000".toByteArray(Charsets.ISO_8859_1)
private val EXTENDED_XMP_HEADER = "http://ns.adobe.com/xmp/extension/\u0000".toByteArray(Charsets.ISO_8859_1)
private val EIGHT_BIM = "8BIM".toByteArray(Charsets.ISO_8859_1)

private fun startsWith(bytes: ByteArray, from: Int, end: Int, prefix: ByteArray): Boolean {
    if (from + prefix.size > end) return false
    for (i in prefix.indices) if (bytes[from + i] != prefix[i]) return false
    return true
}

/** Whether the Photoshop image-resource blocks in `[from, end)` include a non-empty IPTC-IIM (resource
 *  0x0404). A block list that does not parse counts as carrying one: fail closed. */
private fun photoshopCarriesIptc(b: ByteArray, from: Int, end: Int): Boolean {
    var p = from
    while (p < end) {
        if (p + 12 > end || !startsWith(b, p, end, EIGHT_BIM)) return true
        val id = ((b[p + 4].toInt() and 0xFF) shl 8) or (b[p + 5].toInt() and 0xFF)
        // Pascal-string name, padded so name length byte + text is even.
        var q = p + 6 + 1 + (b[p + 6].toInt() and 0xFF)
        if ((q - (p + 6)) % 2 != 0) q++
        if (q + 4 > end) return true
        val size = ((b[q].toInt() and 0xFF) shl 24) or ((b[q + 1].toInt() and 0xFF) shl 16) or
            ((b[q + 2].toInt() and 0xFF) shl 8) or (b[q + 3].toInt() and 0xFF)
        if (size < 0) return true
        if (id == IPTC_RESOURCE_ID && size > 0) return true
        p = q + 4 + size + (size and 1)
    }
    return false
}

private const val IPTC_RESOURCE_ID = 0x0404

/** Every `public static final String` field on [ExifInterface] whose name starts with `TAG_` --
 * read by reflection against the real linked artifact, not hand-copied from documentation, so
 * this tracks whatever tag set androidx actually ships rather than a driftable snapshot of it. */
internal fun allExifTags(): Set<String> =
    ExifInterface::class.java.fields
        .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java && it.name.startsWith("TAG_") }
        .mapNotNull { it.get(null) as? String }
        .toSet()

/**
 * Tags [ExifCopier] never copies onto a different file, each for its own reason -- there is no
 * single rule that covers all of them:
 *  - [ExifInterface.TAG_ORIENTATION]: the editor already re-renders pixels upright
 *    ([com.fotoxplorr.app.media.decodeUpright]), so a copied `Orientation` would rotate an
 *    already-upright image a second time -- the caller writes `1` explicitly instead, the same
 *    convention [com.fotoxplorr.app.share.SharePreparer]'s own kept-metadata share path uses.
 *  - Dimensions ([ExifInterface.TAG_IMAGE_WIDTH]/[TAG_IMAGE_LENGTH]/[TAG_PIXEL_X_DIMENSION]/
 *    [TAG_PIXEL_Y_DIMENSION]): the edited bitmap has its own size, never the source's.
 *  - Thumbnail tags ([ExifInterface.TAG_THUMBNAIL_IMAGE_LENGTH]/[TAG_THUMBNAIL_IMAGE_WIDTH]/
 *    `"ThumbnailOrientation"` (`TAG_THUMBNAIL_ORIENTATION`'s own literal -- see [EXCLUDED_TAGS]'s
 *    inline note on why this list uses the string, not the restricted-access constant),
 *    [TAG_JPEG_INTERCHANGE_FORMAT]/`_LENGTH`, and the Olympus/
 *    Panasonic vendor equivalents [TAG_ORF_THUMBNAIL_IMAGE]/[TAG_RW2_JPG_FROM_RAW]): embedded
 *    preview bytes (or a pointer to them) belonging to the SOURCE file, meaningless -- and, for
 *    the offset-shaped ones, actively wrong -- attached to a different file's own bytes.
 *  - Strip offsets ([ExifInterface.TAG_STRIP_OFFSETS]/[TAG_STRIP_BYTE_COUNTS]) and the Olympus
 *    RAW preview pointer pair ([TAG_ORF_PREVIEW_IMAGE_START]/[TAG_ORF_PREVIEW_IMAGE_LENGTH]):
 *    byte offsets into the SOURCE file's own TIFF/RAW container layout -- copying them onto a
 *    different file's bytes points at the wrong data entirely, the same failure mode as the
 *    thumbnail-offset tags above.
 *  - The Panasonic RW2 sensor-crop border quartet ([TAG_RW2_SENSOR_TOP_BORDER]/`_BOTTOM_`/`_LEFT_`/
 *    `_RIGHT_BORDER`): the RAW sensor's own readout crop for the SOURCE capture -- a dimension-
 *    shaped fact about that file, not the edited copy, for the same reason plain image dimensions
 *    are excluded above.
 *  - [ExifInterface.TAG_MAKER_NOTE]: manufacturer-proprietary and widely documented to embed
 *    absolute byte offsets into the SOURCE file that a copy invalidates (see e.g. ExifTool's own
 *    MakerNotes documentation on offset fix-ups) -- not a Fotoz guess.
 *  - [ExifInterface.TAG_XMP]: handled by the caller's own XMP-specific copy path (parse, reset
 *    `tiff:Orientation`, re-serialize -- see [XmpPacket]), richer than a flat string copy since it
 *    preserves every XMP property this app has no model for, which a raw tag copy would not.
 */
internal val EXCLUDED_TAGS: Set<String> = setOf(
    ExifInterface.TAG_ORIENTATION,
    ExifInterface.TAG_IMAGE_WIDTH,
    ExifInterface.TAG_IMAGE_LENGTH,
    ExifInterface.TAG_PIXEL_X_DIMENSION,
    ExifInterface.TAG_PIXEL_Y_DIMENSION,
    ExifInterface.TAG_THUMBNAIL_IMAGE_LENGTH,
    ExifInterface.TAG_THUMBNAIL_IMAGE_WIDTH,
    // Not ExifInterface.TAG_THUMBNAIL_ORIENTATION: androidx restricts that constant to its own
    // library group (a lint RestrictedApi error, not a runtime one -- the field itself is public
    // and allExifTags()'s reflection still finds it). The literal is the tag's own real, stable
    // name -- the exact string ExifInterface.getAttribute/setAttribute take regardless of which
    // constant a caller reaches it through.
    "ThumbnailOrientation",
    ExifInterface.TAG_JPEG_INTERCHANGE_FORMAT,
    ExifInterface.TAG_JPEG_INTERCHANGE_FORMAT_LENGTH,
    ExifInterface.TAG_ORF_THUMBNAIL_IMAGE,
    ExifInterface.TAG_RW2_JPG_FROM_RAW,
    ExifInterface.TAG_STRIP_OFFSETS,
    ExifInterface.TAG_STRIP_BYTE_COUNTS,
    ExifInterface.TAG_ORF_PREVIEW_IMAGE_START,
    ExifInterface.TAG_ORF_PREVIEW_IMAGE_LENGTH,
    ExifInterface.TAG_RW2_SENSOR_TOP_BORDER,
    ExifInterface.TAG_RW2_SENSOR_BOTTOM_BORDER,
    ExifInterface.TAG_RW2_SENSOR_LEFT_BORDER,
    ExifInterface.TAG_RW2_SENSOR_RIGHT_BORDER,
    ExifInterface.TAG_MAKER_NOTE,
    ExifInterface.TAG_XMP,
)

/**
 * Resets `tiff:Orientation` to 1 in [packet], in place (P0-07): a copied XMP packet, like a copied
 * EXIF `Orientation` tag ([EXCLUDED_TAGS]'s own note), would otherwise rotate an already-upright
 * edited image a second time. Only touches the property when it is actually present -- a packet
 * that never carried `tiff:Orientation` gets no new one invented. If the reset write itself
 * somehow fails, the property is dropped rather than left holding a stale, wrong value, reusing
 * [XmpPacket.setIntValue]'s own documented null-clears-it contract as the fallback.
 */
fun resetXmpOrientationForCopy(packet: XmpPacket): XmpPacket {
    if (packet.intValue(XmpPacket.TIFF_NS, "Orientation") == null) return packet
    runCatching {
        packet.setIntValue(XmpPacket.TIFF_NS, "tiff", "Orientation", 1)
    }.onFailure {
        runCatching { packet.setIntValue(XmpPacket.TIFF_NS, "tiff", "Orientation", null) }
    }
    return packet
}

/**
 * The XMP string [ExifCopier.copy] should write to the target's [ExifInterface.TAG_XMP], or null
 * to write none. [rawXmp] is the SOURCE's own raw attribute value, exactly as
 * `ExifInterface.getAttribute(TAG_XMP)` returned it.
 *  - No source XMP ([rawXmp] null or blank): null -- an edited copy with nothing to copy gets no
 *    XMP packet invented for it.
 *  - Parses: [resetXmpOrientationForCopy]'s adjusted packet, re-serialized, with its location
 *    properties and place names cleared first ([clearXmpLocationForExport]) when [keepLocation] is false.
 *  - Does not parse at all: [rawXmp] itself, untouched, but ONLY when every character is 7-bit
 *    ASCII, otherwise null (the packet is skipped). This app cannot safely edit a packet it
 *    cannot parse -- see [XmpPacket.parse]'s own doc on why a failed parse must not become a fresh
 *    empty packet -- but a copy, unlike an edit, has nothing to merge in; passing the original
 *    bytes through unchanged loses nothing a tool that could read them originally still can, at
 *    the cost of only the `tiff:Orientation` reset for this one case. The ASCII condition exists
 *    because the string reaches [ExifInterface.setAttribute], which writes any character above
 *    U+007F as `?`: the parsed path escapes those as numeric references in
 *    [XmpPacket.serialize], but this verbatim path has no such step, so a non-ASCII packet would
 *    be silently corrupted rather than copied. Skipping it drops the packet; corrupting it would
 *    drop the same data and leave a broken one behind. With [keepLocation] false it is skipped
 *    outright: a packet this app cannot parse cannot be cleaned of its location either.
 */
internal fun copiedXmpValue(rawXmp: String?, keepLocation: Boolean = true): String? {
    if (rawXmp.isNullOrBlank()) return null
    val packet = XmpPacket.parse(rawXmp)
        ?: return rawXmp.takeIf { raw -> keepLocation && raw.all { it.code <= MAX_ASCII } }
    if (!keepLocation) clearXmpLocationForExport(packet)
    return resetXmpOrientationForCopy(packet).serialize()
}

private const val MAX_ASCII = 127
