package com.fotoxplorr.app.viewer

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.openForLocationRead
import com.fotoxplorr.app.media.uriForLocationRead
import com.fotoxplorr.app.metadata.CurrentMetadata
import com.fotoxplorr.app.metadata.currentMetadataFrom
import com.fotoxplorr.app.metadata.xmpAttributeUtf8
import com.fotoxplorr.core.metadata.XmpPacket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * EXIF fields for the room's camera card. All optional: many images (esp. screenshots, edited
 * copies, or anything that already had EXIF stripped by this app's own clean-share export)
 * won't carry camera data — which is why those fields are drawn with a dash rather than a card
 * that has to invent a headline when the file says nothing.
 *
 * Numeric fields are kept numeric here and formatted in [DetailFormatting] rather than being
 * pre-stringified at extraction time, so the presentation can be changed without touching
 * the reader and so the formatting is unit-testable on its own.
 */
data class ImageExifDetails(
    val make: String? = null,
    val model: String? = null,
    val lensModel: String? = null,
    val focalLengthMm: Double? = null,
    /** The 35 mm-equivalent focal length, when the file records one -- what the exposure strip shows. */
    val focalLength35mm: Int? = null,
    val aperture: Double? = null,
    val iso: String? = null,
    val shutterSpeed: String? = null,
    val exposureBiasEv: Double? = null,
    /** Raw EXIF flash bitfield; bit 0 set means the flash fired. */
    val flash: Int? = null,
    /**
     * The embedded GPS fix, if the file carries one. Both are null together — a latitude
     * without a longitude is not half a location, it is no location, and letting them vary
     * independently would invite a caller to draw a pin on one axis of nonsense.
     */
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** Human-readable colour space, when the file names one. */
    val colorSpace: String? = null,
    /**
     * The embedded colour profile's name ("sRGB IEC61966-2.1", "Display P3"), from a bounds-only
     * decode -- no pixels, so this is not a decode path in TRAPS 34's sense. Null when the
     * platform cannot name it.
     */
    val colorProfile: String? = null,
    /**
     * The photographer-grade fields this file already carries — caption, creator, copyright,
     * rating, keywords — read from the SAME [ExifInterface] this whole block is built from
     * rather than a second file open.
     */
    val professionalMetadata: CurrentMetadata = CurrentMetadata.EMPTY,
    /** Whether this file's XMP declares the Ultra HDR gain-map namespace (P0-16) -- see
     *  [DetailFormatting.dynamicRangeBadge]. */
    val hasHdrGainMap: Boolean = false,
    /**
     * Whether this file's EXIF was actually read. False means the open or the parse failed --
     * [PlaceBlock] must never show the "set a location by hand" picker in that case (P0-02): a
     * read failure is not evidence the file has no GPS tag, only that this attempt couldn't see
     * it. A video (no EXIF to read) counts as ok: there is nothing this field would be warning
     * about.
     */
    val readOk: Boolean = true,
    /**
     * True only for [PENDING]: the read has not finished, so NOTHING in this value is evidence of
     * anything -- not "no GPS", not "no camera". [PlaceBlock] draws neither "Add a place" nor
     * "No location yet" while this is set (the P0-02 failure through the loading state, where a
     * geotagged file could be hand-placed over its real fix in the window before the read lands).
     * False for every value a reader produces, which is also the default so a caller that builds
     * one by hand (a preview, a test) is unaffected.
     */
    val pending: Boolean = false,
    /** The EXIF orientation tag, 1..8; 0 when the file has none. */
    val orientation: Int = 0,
    /**
     * The photo's size as it is DISPLAYED (upright, after [orientation]), in pixels, from the
     * file's own bounds rather than MediaStore's stored WIDTH/HEIGHT; 0 when it could not be
     * read. The flier's start rectangle is fitted from this -- see [PlaceMorph.uprightSize].
     */
    val displayWidth: Int = 0,
    val displayHeight: Int = 0,
) {
    companion object {
        /** The state before the first read finishes. See [pending]. */
        val PENDING = ImageExifDetails(pending = true)
    }
}

suspend fun readImageExifDetails(context: Context, asset: MediaAsset): ImageExifDetails {
    if (asset.isVideo) return ImageExifDetails(readOk = true)
    return withContext(Dispatchers.IO) {
        val requested = context.uriForLocationRead(asset.contentUri)
        val opened = openForLocationRead(requested, asset.contentUri) { uri ->
            context.contentResolver.openInputStream(uri)
        }
        val details = if (opened == null) {
            ImageExifDetails(readOk = false)
        } else {
            val (input, _) = opened
            runCatching { input.use { exifDetailsFrom(ExifInterface(it)) } }
                .map { it.copy(readOk = true) }
                .getOrDefault(ImageExifDetails(readOk = false))
        }
        // A second, bounds-only pass for the colour profile's name and the stored size.
        // Separate from the EXIF read because ExifInterface does not see ICC data, and tolerant
        // of failure because a file whose profile cannot be named is still a file whose EXIF
        // was read fine.
        val bounds = readImageBounds(context, asset)
        val upright = if (PlaceMorph.decoderAppliesOrientation(asset.mimeType)) {
            PlaceMorph.uprightSize(bounds.width, bounds.height, details.orientation)
        } else {
            PlaceMorph.uprightSize(bounds.width, bounds.height, 0)
        }
        details.copy(
            colorProfile = bounds.colorProfile,
            displayWidth = upright?.width?.roundToInt() ?: 0,
            displayHeight = upright?.height?.roundToInt() ?: 0,
        )
    }
}

/** What a bounds-only decode says: the stored pixel size (0 when unreadable) and the profile name. */
private class ImageBounds(val width: Int, val height: Int, val colorProfile: String?)

/**
 * The file's stored size and the name of its colour profile, from a bounds-only decode ([BitmapFactory] with
 * `inJustDecodeBounds`, which reads headers and allocates no pixels -- TRAPS 34 is about pixels).
 * "sRGB IEC61966-2.1" is what the platform calls sRGB, which is exactly the reference's row.
 */
private fun readImageBounds(context: Context, asset: MediaAsset): ImageBounds {
    val none = ImageBounds(0, 0, null)
    return runCatching {
        context.contentResolver.openInputStream(asset.contentUri)?.use { stream ->
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(stream, null, options)
            ImageBounds(
                width = options.outWidth.coerceAtLeast(0),
                height = options.outHeight.coerceAtLeast(0),
                colorProfile = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    options.outColorSpace?.name?.takeIf(String::isNotBlank)
                } else {
                    null
                },
            )
        }
    }.getOrNull() ?: none
}

@Suppress("DEPRECATION") // TAG_ISO_SPEED_RATINGS is the pre-EXIF-2.3 fallback for older files.
internal fun exifDetailsFrom(exif: ExifInterface): ImageExifDetails {
    val iso = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
        ?: exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)
    val shutter = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, Double.NaN)
        .takeUnless(Double::isNaN)
    // getLatLong() returns null unless BOTH the coordinate and its hemisphere ref are present
    // and well-formed, which is exactly the guarantee ImageExifDetails.latitude documents —
    // so the pair is destructured from one call rather than read as two independent tags.
    // An exact 0.0, 0.0 is "no fix" written as a number (see PlaceMorph.isNullIsland), so it is
    // dropped here and every consumer of latitude/longitude agrees the photo has no place.
    val latLong = exif.latLong?.takeUnless { PlaceMorph.isNullIsland(it[0], it[1]) }
    // xmpAttributeUtf8(), not getAttribute(TAG_XMP): see that function's own doc for why
    // getAttribute decodes a real (non-entity-escaped) UTF-8 packet with the wrong charset.
    // Parsed once and reused below for both the professional-metadata card and the P0-16 HDR
    // gain-map check, rather than parsing the same packet twice.
    val xmp = exif.xmpAttributeUtf8()?.let { XmpPacket.parse(it) }
    return ImageExifDetails(
        make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()?.takeIf(String::isNotEmpty),
        model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()?.takeIf(String::isNotEmpty),
        lensModel = exif.getAttribute(ExifInterface.TAG_LENS_MODEL)?.trim()?.takeIf(String::isNotEmpty),
        focalLengthMm = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, Double.NaN)
            .takeUnless(Double::isNaN),
        focalLength35mm = exif.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0).takeIf { it > 0 },
        aperture = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, Double.NaN)
            .takeUnless(Double::isNaN),
        iso = iso,
        shutterSpeed = shutter?.let(::formatShutterSpeed),
        exposureBiasEv = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_BIAS_VALUE, Double.NaN)
            .takeUnless(Double::isNaN),
        flash = exif.getAttributeInt(ExifInterface.TAG_FLASH, -1).takeIf { it >= 0 },
        latitude = latLong?.get(0),
        longitude = latLong?.get(1),
        orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0).takeIf { it in 1..8 } ?: 0,
        colorSpace = colorSpaceName(exif.getAttributeInt(ExifInterface.TAG_COLOR_SPACE, -1)),
        professionalMetadata = currentMetadataFrom(
            xmp = xmp,
            exifImageDescription = exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION),
            exifArtist = exif.getAttribute(ExifInterface.TAG_ARTIST),
            exifCopyright = exif.getAttribute(ExifInterface.TAG_COPYRIGHT),
        ),
        hasHdrGainMap = xmp?.hasNamespace(XmpPacket.HDR_GAIN_MAP_NS) == true,
    )
}

/**
 * EXIF stores colour space as a number: 1 is sRGB and 0xFFFF ("uncalibrated") is what almost
 * everything else writes, most often for Display P3. Anything else is unspecified, and is
 * reported as nothing rather than guessed at — the room shows a dash in that case.
 */
internal fun colorSpaceName(raw: Int): String? = when (raw) {
    1 -> "sRGB"
    0xFFFF -> "Uncalibrated"
    else -> null
}

internal fun formatShutterSpeed(seconds: Double): String = when {
    seconds <= 0.0 || seconds.isNaN() -> "—"
    seconds >= 1.0 -> "%.1f s".format(java.util.Locale.US, seconds)
    else -> "1/${(1.0 / seconds).roundToInt()} s"
}
