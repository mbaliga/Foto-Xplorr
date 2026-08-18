package com.fotoxplorr.app.viewer

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.fotoxplorr.app.ui.RoomEyebrow
import com.fotoxplorr.app.ui.RoomStyle
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.exifinterface.media.ExifInterface
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val PANEL_BACKGROUND = Color(0xFF0B0B0B)
private val CARD_BACKGROUND = Color(0xFF151515)
private val CARD_HEADER_BACKGROUND = Color(0xFF1D1D1D)
private val PRIMARY_TEXT = Color(0xFFF2F2F2)
private val SECONDARY_TEXT = Color(0xFF8A8A8A)
private val MUTED_TEXT = Color(0xFF6A6A6A)

/**
 * The viewer's **top room**: what this photo is, and where it was taken.
 *
 * This was `ImageDetailScreen`, a full-screen Material `Scaffold` with a centred app bar and a
 * close button, reached by tapping a "Details" text button. That was a screen, and the
 * constellation does not have screens — `docs/fonebrew-navigation.md` is explicit that a modal
 * window floating over a room is two contradictory ideas of where you are. It is a room now:
 * pulled down from the top of the viewer, with the photo still alive on the parked card behind
 * it, and no chrome of its own because the card *is* the way back.
 *
 * The order is the reference video's: filename, date, the camera card, the place plate the
 * photo flies into, the caption affordance, then the file's own facts. Related photos stay at
 * the bottom, below everything the room exists to say.
 *
 * The caption row is still an affordance without storage -- Foto Xplorr has no caption
 * field in its data model, and inventing one was out of scope here -- so tapping it does
 * nothing yet. It is drawn because the mockup draws it; it is not claimed to persist.
 *
 * @param exif read by the viewer rather than here. The shell composes a room only once it is
 *   at least slightly open, so a room that read its own EXIF would start that read on the first
 *   pixel of the pull and show an empty card for the rest of it — the facts have to be in hand
 *   before the gesture begins, not fetched because it did.
 * @param reveal how open the room is, 0..1. Read on the draw pass so a drag animates the
 *   arrival without recomposing the room's content on every frame.
 */
@Composable
fun PhotoDetailRoom(
    asset: MediaAsset,
    exif: ImageExifDetails,
    reveal: () -> Float,
    modifier: Modifier = Modifier,
    /** Marks this photo carries in your library, as opposed to facts about the file itself. */
    isFavorite: Boolean = false,
    isSensitive: Boolean = false,
) {
    // The shell deliberately consumes no insets — it says so in its own KDoc, because doing so
    // would lift its drag-sensitive edges off the physical screen edge. So a room pads for the
    // status bar itself, exactly as the rail and settings rooms already do.
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PANEL_BACKGROUND)
            // The room is at the BOTTOM edge now, so the shell parks the card upward and insets
            // this room at its top. The system bar to clear is therefore the navigation bar.
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(top = 16.dp, bottom = 24.dp),
    ) {
        RoomHeader(asset = asset, reveal = reveal)
        StatusBlock(
            asset = asset,
            isFavorite = isFavorite,
            isSensitive = isSensitive,
            reveal = reveal,
        )
        Box(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            ExifCard(asset = asset, exif = exif)
        }
        Text(
            text = "Add a Caption",
            color = RoomStyle.InkFaint,
            style = RoomStyle.Row,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = false) {}
                .padding(horizontal = 20.dp, vertical = 18.dp),
        )
        InformationBlock(asset = asset, exif = exif, reveal = reveal)
        // The place plate goes LAST, because it is the thing this room exists to show. It used
        // to sit third of six, above a 3-column grid of up to thirty related photos -- so the
        // bottom half of the room was a gallery, and for an un-geotagged file (most of a real
        // library) the plate collapsed to a single line and the gallery took the screen (owner,
        // 2026-08-14: "the details screen is showing the gallery view at the bottom half instead
        // of the map"). "Jump to another photo" is not this room's job any more either: the
        // filmstrip that used to close this Column lives over the photo itself now, where it was
        // before -- see ViewerScreen (owner, second round: "The filmstrip has to appear not in
        // the details view but in the view where the photo is selected").
        PlaceBlock(asset = asset, exif = exif, reveal = reveal)
    }
}

/** Filename, date line and the local-storage glyph — the room's first block. */
@Composable
private fun RoomHeader(asset: MediaAsset, reveal: () -> Float) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = PlaceMorph.textAlpha(reveal()) }
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = asset.displayName.substringBeforeLast('.'),
                color = PRIMARY_TEXT,
                style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            // Foto Xplorr has no cloud-backup subsystem, so this reports the one backup
            // state it can actually establish: whether the file is still present locally in
            // MediaStore (on device) or has been moved to the system trash (not backed up
            // anywhere by this app). It is not a claim about any cloud service.
            Icon(
                imageVector = if (asset.isTrashed) Icons.Outlined.CloudOff else Icons.Outlined.CloudDone,
                contentDescription = if (asset.isTrashed) "In trash" else "Stored on this device",
                tint = SECONDARY_TEXT,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = DetailFormatting.dateLine(asset.dateTakenMillis),
            color = SECONDARY_TEXT,
            style = TextStyle(fontSize = 14.sp),
        )
    }
}

/**
 * The place plate, or an honest absence.
 *
 * Most libraries are mostly un-geotagged — screenshots, downloads, anything shared through a
 * messenger that strips EXIF, and every file this app's own clean-share export has been through.
 * A plate drawn at 0°,0° for those would be a confident lie, so the block says the fix is
 * missing instead, and says it quietly.
 */
/**
 * What this photo is to *you*, as distinct from what the file is.
 *
 * Everything else in this room is read off the file — its size, its camera, its coordinates. This
 * block is the library's own knowledge: whether you marked it, whether you hid it, and where it
 * actually lives on the device. Worth its own group because "is this one of my favourites?" is a
 * question the room could not previously answer at all, even though the viewer already knew.
 */
@Composable
private fun StatusBlock(
    asset: MediaAsset,
    isFavorite: Boolean,
    isSensitive: Boolean,
    reveal: () -> Float,
) {
    val marks = buildList {
        if (isFavorite) add("Favourite")
        if (isSensitive) add("Sensitive")
        if (asset.isTrashed) add("In the trash")
        if (asset.isVideo) add("Video")
    }
    val album = asset.bucketName?.takeIf(String::isNotBlank)
    // Nothing to say and no album to name means no empty heading: a section that renders as a
    // lone eyebrow over blank space is the same mistake as the bare warning triangle was.
    if (marks.isEmpty() && album == null) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = PlaceMorph.textAlpha(reveal()) }
            .padding(horizontal = 20.dp, vertical = 4.dp),
    ) {
        RoomEyebrow("IN YOUR LIBRARY")
        if (marks.isNotEmpty()) {
            Text(
                text = marks.joinToString(" · "),
                color = RoomStyle.Ink,
                style = RoomStyle.Row,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        album?.let {
            Text(
                text = it,
                color = RoomStyle.InkMuted,
                style = RoomStyle.Caption,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

@Composable
private fun PlaceBlock(asset: MediaAsset, exif: ImageExifDetails, reveal: () -> Float) {
    val latitude = exif.latitude
    val longitude = exif.longitude
    Box(Modifier.padding(horizontal = 20.dp)) {
        if (latitude != null && longitude != null) {
            PlacePlate(
                asset = asset,
                latitude = latitude,
                longitude = longitude,
                reveal = reveal,
            )
        } else {
            Column(
                modifier = Modifier
                    .graphicsLayer { alpha = PlaceMorph.textAlpha(reveal()) }
                    .padding(vertical = 6.dp),
            ) {
                RoomEyebrow("PLACE")
                Text(
                    text = "No location in this file",
                    color = RoomStyle.InkMuted,
                    style = RoomStyle.Row,
                    modifier = Modifier.padding(top = 6.dp),
                )
                // Says why, rather than leaving a bare negative. Photos saved from messaging apps
                // and websites have their GPS stripped before they ever reach the device, which
                // is the reason for most of the empty ones in a real library -- and without that
                // sentence the line reads as the app having failed to find something.
                Text(
                    text = "Photos saved from messaging apps and websites usually have their " +
                        "location removed before they arrive.",
                    color = RoomStyle.InkFaint,
                    style = RoomStyle.Caption,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/**
 * The file's own facts, in the shape of the owner's second reference screenshot: kind, size,
 * where it lives, when it changed, its pixel dimensions and — only when the file actually
 * records one — its colour space.
 */
@Composable
private fun InformationBlock(asset: MediaAsset, exif: ImageExifDetails, reveal: () -> Float) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = PlaceMorph.textAlpha(reveal()) }
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        // Section headings across every room are one component now, so the detail room, the
        // viewer's settings room and the left rail no longer each pick their own size and weight
        // (owner, 2026-08-18: the top and bottom rooms should be "restyled to match").
        RoomEyebrow("INFORMATION", Modifier.padding(bottom = 3.dp))
        InformationRow("Kind", DetailFormatting.formatBadge(asset.mimeType) ?: asset.mimeType)
        InformationRow("Size", DetailFormatting.byteLine(asset.sizeBytes))
        InformationRow("Dimensions", "${asset.width} × ${asset.height}")
        if (asset.isVideo && asset.durationMillis > 0L) {
            InformationRow("Duration", DetailFormatting.durationLine(asset.durationMillis))
        }
        asset.bucketName?.takeIf(String::isNotBlank)?.let { InformationRow("Album", it) }
        asset.relativePath?.takeIf(String::isNotBlank)?.let { InformationRow("Where", it) }
        InformationRow("Modified", DetailFormatting.dateLine(asset.dateModifiedSeconds * 1_000L))
        exif.colorSpace?.let { InformationRow("Color space", it) }
    }
}

@Composable
private fun InformationRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = label,
            color = MUTED_TEXT,
            style = TextStyle(fontSize = 13.sp),
            modifier = Modifier.weight(0.36f),
        )
        Text(
            text = value,
            color = SECONDARY_TEXT,
            style = TextStyle(fontSize = 13.sp),
            modifier = Modifier.weight(0.64f),
        )
    }
}

@Composable
private fun ExifCard(asset: MediaAsset, exif: ImageExifDetails) {
    val deviceLine = listOfNotNull(exif.make, exif.model)
        .joinToString(" ").trim().takeIf(String::isNotEmpty)
    val lensLine = DetailFormatting.lensLine(exif.lensModel, exif.focalLengthMm, exif.aperture)
    val formatBadge = DetailFormatting.formatBadge(asset.mimeType)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(CARD_BACKGROUND),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CARD_HEADER_BACKGROUND)
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = deviceLine ?: "Unknown camera",
                color = PRIMARY_TEXT,
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            formatBadge?.let { OutlineBadge(it) }
            if (DetailFormatting.flashFired(exif.flash)) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(Color(0xFF2B2B2B)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Bolt,
                        contentDescription = "Flash fired",
                        tint = PRIMARY_TEXT,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }

        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            lensLine?.let {
                Text(it, color = SECONDARY_TEXT, style = TextStyle(fontSize = 14.sp))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = DetailFormatting.dimensionsLine(asset.width, asset.height, asset.sizeBytes),
                    color = SECONDARY_TEXT,
                    style = TextStyle(fontSize = 14.sp),
                    modifier = Modifier.weight(1f),
                )
                OutlineBadge(DetailFormatting.dynamicRangeBadge(asset.mimeType))
            }
        }

        val exposureFields = listOfNotNull(
            exif.iso?.let { "ISO $it" },
            exif.focalLengthMm?.takeIf { it > 0.0 }?.let { "${it.roundToInt()} mm" },
            exif.exposureBiasEv?.let { "${it.roundToInt()} ev" },
            exif.aperture?.takeIf { it > 0.0 }?.let { DetailFormatting.apertureText(it) },
            exif.shutterSpeed,
        )
        if (exposureFields.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                exposureFields.forEachIndexed { index, field ->
                    if (index > 0) {
                        Box(
                            Modifier
                                .width(1.dp)
                                .height(12.dp)
                                .background(Color(0xFF333333)),
                        )
                    }
                    Text(
                        text = field,
                        color = MUTED_TEXT,
                        style = TextStyle(fontSize = 12.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun OutlineBadge(text: String) {
    Text(
        text = text,
        color = SECONDARY_TEXT,
        style = TextStyle(fontSize = 11.sp, letterSpacing = 0.4.sp),
        modifier = Modifier
            .border(1.dp, Color(0xFF3A3A3A), RoundedCornerShape(4.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

/**
 * EXIF fields for the metadata card. All optional: many images (esp. screenshots, edited
 * copies, or anything that already had EXIF stripped by this app's own clean-share export)
 * won't carry camera data.
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
)

suspend fun readImageExifDetails(context: Context, asset: MediaAsset): ImageExifDetails {
    if (asset.isVideo) return ImageExifDetails()
    return withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(asset.contentUri)?.use { input ->
                exifDetailsFrom(ExifInterface(input))
            } ?: ImageExifDetails()
        }.getOrDefault(ImageExifDetails())
    }
}

@Suppress("DEPRECATION") // TAG_ISO_SPEED_RATINGS is the pre-EXIF-2.3 fallback for older files.
private fun exifDetailsFrom(exif: ExifInterface): ImageExifDetails {
    val iso = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
        ?: exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)
    val shutter = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, Double.NaN)
        .takeUnless(Double::isNaN)
    // getLatLong() returns null unless BOTH the coordinate and its hemisphere ref are present
    // and well-formed, which is exactly the guarantee ImageExifDetails.latitude documents —
    // so the pair is destructured from one call rather than read as two independent tags.
    val latLong = exif.latLong
    return ImageExifDetails(
        make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()?.takeIf(String::isNotEmpty),
        model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()?.takeIf(String::isNotEmpty),
        lensModel = exif.getAttribute(ExifInterface.TAG_LENS_MODEL)?.trim()?.takeIf(String::isNotEmpty),
        focalLengthMm = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, Double.NaN)
            .takeUnless(Double::isNaN),
        aperture = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, Double.NaN)
            .takeUnless(Double::isNaN),
        iso = iso,
        shutterSpeed = shutter?.let(::formatShutterSpeed),
        exposureBiasEv = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_BIAS_VALUE, Double.NaN)
            .takeUnless(Double::isNaN),
        flash = exif.getAttributeInt(ExifInterface.TAG_FLASH, -1).takeIf { it >= 0 },
        latitude = latLong?.get(0),
        longitude = latLong?.get(1),
        colorSpace = colorSpaceName(exif.getAttributeInt(ExifInterface.TAG_COLOR_SPACE, -1)),
    )
}

/**
 * EXIF stores colour space as a number: 1 is sRGB and 0xFFFF ("uncalibrated") is what almost
 * everything else writes, most often for Display P3. Anything else is unspecified, and is
 * reported as nothing rather than guessed at — the room omits the row entirely in that case.
 */
internal fun colorSpaceName(raw: Int): String? = when (raw) {
    1 -> "sRGB"
    0xFFFF -> "Uncalibrated"
    else -> null
}

internal fun formatShutterSpeed(seconds: Double): String = when {
    seconds <= 0.0 -> "—"
    seconds >= 1.0 -> "%.1f s".format(java.util.Locale.US, seconds)
    else -> "1/${(1.0 / seconds).roundToInt()} s"
}
