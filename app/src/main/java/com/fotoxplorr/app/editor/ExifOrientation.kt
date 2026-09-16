package com.fotoxplorr.app.editor

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.fotoxplorr.app.metadata.XmpPacket
import java.io.ByteArrayInputStream

/**
 * Reads and undoes a JPEG/TIFF/WebP/PNG's own EXIF orientation, and preserves the metadata that
 * survives an edit.
 *
 * `BitmapFactory` decodes pixels exactly as they are stored, ignoring `TAG_ORIENTATION` entirely —
 * a photo shot in portrait with the sensor read out landscape (Orientation 6 or 8, the case on
 * almost every phone camera) previews and exports sideways unless something applies that tag by
 * hand. This file is that "something", plus the other half of the same bug: once the pixels are
 * baked upright, the tag itself must be reset to 1 in whatever gets written out, or a reader that
 * DOES honour EXIF would rotate an already-upright image a second time.
 *
 * Pure enough to unit test with a real file the way [com.fotoxplorr.app.metadata.MetadataWriterTest]
 * does: everything here takes bytes or an already-open [ExifInterface], never a `Context` or a
 * `ContentResolver`.
 */

/**
 * The EXIF orientation constant recorded in [jpegBytes], or [ExifInterface.ORIENTATION_UNDEFINED]
 * when there is none or it cannot be read.
 *
 * [ExifInterface.ORIENTATION_UNDEFINED], not [ExifInterface.ORIENTATION_NORMAL], matches this
 * library's own real behaviour for a missing tag (confirmed against the real 1.4.1 jar, not
 * assumed: `getAttributeInt(TAG_ORIENTATION, ...)` answers 0 for an absent tag regardless of the
 * default passed in) and the platform-wide convention every other caller of this API follows for
 * the same reason. It makes no practical difference to [applyExifOrientation] below, which treats
 * both constants identically — "nothing to correct" either way.
 *
 * Reads out of an in-memory copy of the bytes already decoded for pixels, rather than reopening
 * the content Uri a second time: [ExifInterface]'s stream constructor buffers the whole input
 * anyway, so a fresh network/disk read would cost real time for a file this caller is already
 * holding.
 */
internal fun readExifOrientation(jpegBytes: ByteArray): Int = runCatching {
    ExifInterface(ByteArrayInputStream(jpegBytes))
        .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
}.getOrDefault(ExifInterface.ORIENTATION_UNDEFINED)

/**
 * Bakes [orientation] into [bitmap]'s own pixels, returning a new bitmap (or [bitmap] itself when
 * [orientation] is already [ExifInterface.ORIENTATION_NORMAL] or unrecognised).
 *
 * Every code this tag can legally hold, not just the three (6/8/3) that show up on a phone camera:
 * a scanner or an image downloaded off the web can carry any of the eight, and 5/7 (transpose and
 * transverse) are a flip AND a rotation, not one or the other.
 */
internal fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_NORMAL, ExifInterface.ORIENTATION_UNDEFINED -> return bitmap
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.postRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.postRotate(270f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        else -> return bitmap
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

/**
 * The tags copied from the ORIGINAL file into an edited copy or overwrite, so that straightening
 * out orientation (or any other edit) does not also strip a photo of when and where it was taken.
 *
 * Deliberately camera facts and rights information only — never [ExifInterface.TAG_ORIENTATION]
 * itself (the edited pixels are already upright; copying the source's own tag would re-introduce
 * exactly the bug this file exists to fix) and never the tags [applyMetadataToExif] in
 * [com.fotoxplorr.app.metadata.MetadataWriter] owns as a caption/rating/keyword EDIT surface —
 * this list is about not DESTROYING facts the edit had no opinion about.
 */
internal val EXIF_PRESERVED_TAGS = listOf(
    ExifInterface.TAG_DATETIME,
    ExifInterface.TAG_DATETIME_ORIGINAL,
    ExifInterface.TAG_DATETIME_DIGITIZED,
    ExifInterface.TAG_MAKE,
    ExifInterface.TAG_MODEL,
    ExifInterface.TAG_LENS_MAKE,
    ExifInterface.TAG_LENS_MODEL,
    ExifInterface.TAG_EXPOSURE_TIME,
    ExifInterface.TAG_F_NUMBER,
    ExifInterface.TAG_APERTURE_VALUE,
    ExifInterface.TAG_SHUTTER_SPEED_VALUE,
    ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
    ExifInterface.TAG_ISO_SPEED_RATINGS,
    ExifInterface.TAG_FOCAL_LENGTH,
    ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
    ExifInterface.TAG_GPS_LATITUDE,
    ExifInterface.TAG_GPS_LATITUDE_REF,
    ExifInterface.TAG_GPS_LONGITUDE,
    ExifInterface.TAG_GPS_LONGITUDE_REF,
    ExifInterface.TAG_GPS_ALTITUDE,
    ExifInterface.TAG_GPS_ALTITUDE_REF,
    ExifInterface.TAG_GPS_TIMESTAMP,
    ExifInterface.TAG_GPS_DATESTAMP,
    ExifInterface.TAG_GPS_PROCESSING_METHOD,
    ExifInterface.TAG_USER_COMMENT,
    ExifInterface.TAG_ARTIST,
    ExifInterface.TAG_COPYRIGHT,
)

/**
 * Copies [EXIF_PRESERVED_TAGS] and the XMP packet from [source] into [destination], forces
 * [destination]'s orientation to [ExifInterface.ORIENTATION_NORMAL] (the pixels are already
 * upright by the time this runs — see [applyExifOrientation]), and saves.
 *
 * The XMP packet is READ as raw UTF-8 bytes, not through [ExifInterface.getAttribute]: that
 * decodes the tag as if it were 7-bit ASCII (see [com.fotoxplorr.app.metadata.MetadataWriter]'s
 * own fix for the read side of the identical bug), and a packet this app just wrote — a caption
 * or keyword list with a non-ASCII character in it — is exactly the thing a naive read would
 * corrupt one edit after it was written.
 *
 * It is then re-serialized through [XmpPacket] rather than written straight back with
 * [ExifInterface.setAttribute]: that call has the mirror-image problem on the WRITE side — no
 * raw-bytes setter exists at all (see [XmpPacket.serialize]'s own doc, confirmed against the real
 * jar), so handing it a Kotlin `String` that still contains a real "é" or "©" would silently
 * corrupt that character to '?' while writing it into [destination]. [XmpPacket.serialize]
 * escapes every non-ASCII character to a numeric reference first, which is exactly what makes a
 * packet safe for [ExifInterface.setAttribute] at all. A source packet [XmpPacket] cannot parse
 * (some non-standard shape this app has no model for) is copied verbatim only when it is already
 * pure ASCII; otherwise it is skipped rather than risking silent corruption of content this class
 * cannot even inspect.
 *
 * Best-effort by design: a source with unreadable or absent EXIF/XMP leaves [destination] with
 * whatever it already had (nothing, for a freshly-encoded copy) rather than failing the edit that
 * is otherwise ready to save — a photograph saved with its rotation fixed and no lens model is a
 * far better outcome than the edit being refused entirely.
 */
internal fun copyPreservableExif(source: ExifInterface, destination: ExifInterface) {
    EXIF_PRESERVED_TAGS.forEach { tag ->
        source.getAttribute(tag)?.let { destination.setAttribute(tag, it) }
    }
    val rawXmp = runCatching {
        source.getAttributeBytes(ExifInterface.TAG_XMP)?.toString(Charsets.UTF_8)
    }.getOrNull()
    val safeXmp = rawXmp?.let { XmpPacket.parse(it)?.serialize() ?: it.takeIf { ascii -> ascii.all { c -> c.code <= 127 } } }
    safeXmp?.let { destination.setAttribute(ExifInterface.TAG_XMP, it) }
    destination.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
    destination.saveAttributes()
}
