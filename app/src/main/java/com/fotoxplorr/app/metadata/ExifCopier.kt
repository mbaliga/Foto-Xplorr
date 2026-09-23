package com.fotoxplorr.app.metadata

import androidx.exifinterface.media.ExifInterface
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
     */
    fun copy(source: ExifInterface, target: ExifInterface) {
        COPIED_TAGS.forEach { tag ->
            source.getAttribute(tag)?.let { target.setAttribute(tag, it) }
        }
        target.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        // xmpAttributeUtf8(), not getAttribute(TAG_XMP) -- see that function's own doc (P0-08):
        // getAttribute decodes an XMP packet's bytes with the wrong charset.
        copiedXmpValue(source.xmpAttributeUtf8())?.let {
            target.setAttribute(ExifInterface.TAG_XMP, it)
        }
    }
}

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
 *    MakerNotes documentation on offset fix-ups) -- not a Foto Xplorr guess.
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
 *  - Parses: [resetXmpOrientationForCopy]'s adjusted packet, re-serialized.
 *  - Does not parse at all: [rawXmp] itself, untouched. This app cannot safely edit a packet it
 *    cannot parse -- see [XmpPacket.parse]'s own doc on why a failed parse must not become a fresh
 *    empty packet -- but a copy, unlike an edit, has nothing to merge in; passing the original
 *    bytes through unchanged loses nothing a tool that could read them originally still can, at
 *    the cost of only the `tiff:Orientation` reset for this one case.
 */
internal fun copiedXmpValue(rawXmp: String?): String? {
    if (rawXmp.isNullOrBlank()) return null
    val packet = XmpPacket.parse(rawXmp) ?: return rawXmp
    return resetXmpOrientationForCopy(packet).serialize()
}
