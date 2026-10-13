package com.fotoxplorr.app.viewer

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.exifinterface.media.ExifInterface
import com.fotoxplorr.app.hyle.HyleTextField
import com.fotoxplorr.app.media.DecodeLimits
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.decodeUpright
import com.fotoxplorr.app.media.openForLocationRead
import com.fotoxplorr.app.media.uriForLocationRead
import com.fotoxplorr.app.metadata.CurrentMetadata
import com.fotoxplorr.app.metadata.currentMetadataFrom
import com.fotoxplorr.app.metadata.xmpAttributeUtf8
import com.fotoxplorr.app.palette.PaletteExtractor
import com.fotoxplorr.app.palette.PaletteMemo
import com.fotoxplorr.app.palette.PaletteSwatch
import com.fotoxplorr.app.ui.HyleGrotesk
import com.fotoxplorr.app.ui.RoomEyebrow
import com.fotoxplorr.app.ui.RoomStyle
import com.fotoxplorr.core.metadata.XmpPacket
import com.fotoxplorr.core.model.MediaId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

// ── The room's palette, every value sampled off the owner's reference (6.png) ─────────────────
private val ROOM_BACKGROUND = Color.Black
/** The slightly-raised panel the date line and the camera card sit on. */
private val PANEL_BACKGROUND = Color(0xFF09090A)
internal val CARD_BACKGROUND = Color(0xFF111113)
internal val CARD_HEADER_BACKGROUND = Color(0xFF1A1A1D)
private val CARD_DIVIDER = Color(0xFF18171A)
private val CARD_CHIP_BACKGROUND = Color(0xFF212023)
private val CARD_CHIP_OUTLINE = Color(0xFF19181B)
private val CARD_SEPARATOR = Color(0xFF1A191C)
internal val PRIMARY_TEXT = Color(0xFFE8E8E8)
private val TITLE_TEXT = Color.White
private val DATE_TEXT = Color(0xFFEBEBEB)
private val CARD_HEADER_TEXT = Color(0xFFE8E8E8)
private val CARD_CHIP_TEXT = Color(0xFFCAC8CD)
private val CARD_BODY_TEXT = Color(0xFF626165)
private val CARD_STRIP_TEXT = Color(0xFF5B5A5E)
private val CARD_GLYPH = Color(0xFF6A696E)
private val SECTION_TEXT = Color(0xFFDDDDDD)
private val ROW_LABEL = Color(0xFF949695)
private val ROW_VALUE = Color(0xFFDCDCDC)
private val HAIRLINE = Color(0xFF141414)
private val LINK_TEXT = Color(0xFF3F99F9)
private val PLACEHOLDER_TEXT = Color(0xFF474A4A)
internal val SECONDARY_TEXT = Color(0xFF8A8A8A)
internal val MUTED_TEXT = Color(0xFF6A6A6A)

/**
 * The viewer's **top room**: what this photo is, and where it was taken. Pulled DOWN over the
 * photo, which stays alive on the parked card behind it, and no chrome of its own because the
 * card *is* the way back.
 *
 * The order is the owner's reference (6.png), top to bottom: the filename and date; the camera
 * card; the map plate the photo flies into; the caption; "Information" with a Show More/Show Less
 * toggle; "Tags". Everything this app knows that the reference does not draw comes after that --
 * the OCR card, the rating/creator/copyright fields, the library marks -- so nothing a previous
 * round made reachable has been deleted, it has only moved below the reference's own content.
 *
 * **The camera card is back, and that is a documented reversal.** A de-duplication pass (owner,
 * 2026-09-01: *"make sure the details/info part of the photo does not have duplicate info"*)
 * removed it, on the argument that every one of its facts was also a labelled row further down and
 * the rows could state an absence where a card headlines a shrug. The owner's reference now puts
 * the card back, so the *card* owns the capture facts -- camera, format, flash, lens, focal length,
 * aperture, size, dimensions, dynamic range, ISO, exposure bias, shutter -- and states an absence
 * itself: a dash in the exposure strip, a dashed lens line, "Unknown camera" in the header. The
 * rows below no longer repeat any of them, which keeps the Sept 1 rule (*every fact is said once*)
 * intact; what changed is WHICH surface says each fact, not whether it is said twice. See
 * [CameraCardFacts] for the exact division.
 *
 * The coordinate is said once too, now as a labelled "Location" row under Information rather than
 * printed on the plate: the reference's plate carries no caption, and a row can state "none" where
 * a plate can only be absent.
 *
 * @param exif read by the viewer rather than here. The shell composes a room only once it is
 *   at least slightly open, so a room that read its own EXIF would start that read on the first
 *   pixel of the pull and show a card of dashes for the rest of it — the facts have to be in hand
 *   before the gesture begins, not fetched because it did.
 * @param reveal how open the room is, 0..1. Read on the draw pass so a drag animates the
 *   arrival without recomposing the room's content on every frame: the photo flies into its pin
 *   first, then each block of text slides up into place as it fades in ([PlaceMorph.textOffsetDp]).
 * @param settled true once the room is fully open and at rest with no finger on it -- the only
 *   moment [PlacePlate] may let the connect flavour fetch a map. See [PlacePlate].
 * @param onExpandMap a tap on the map plate, with the plate's bounds in root coordinates so the
 *   caller can grow the full-screen map out of that exact rectangle.
 * @param plateMapBackdrop what the plate draws under its pin once settled; null means the
 *   flavour's own backdrop. The render test passes a stand-in, since MapLibre cannot render on
 *   the JVM.
 * @param recognizedText this photo's OCR text, flattened -- read by the viewer the same way
 *   [exif] is. Blank hides the "Search inside this photo" card entirely.
 * @param onSearchLibrary hands recognised text to the library search. Null leaves that one action
 *   visibly disabled rather than wired to nothing.
 */
@Composable
fun PhotoDetailRoom(
    asset: MediaAsset,
    exif: ImageExifDetails,
    reveal: () -> Float,
    modifier: Modifier = Modifier,
    settled: Boolean = false,
    onExpandMap: ((Rect) -> Unit)? = null,
    plateMapBackdrop: (@Composable BoxScope.(settled: Boolean) -> Unit)? = null,
    /** Marks this photo carries in your library, as opposed to facts about the file itself. */
    isFavorite: Boolean = false,
    isSensitive: Boolean = false,
    /** A location the user placed by hand, for a photo whose file carries none. */
    manualLatitude: Double? = null,
    manualLongitude: Double? = null,
    /** Null leaves the room read-only, which is what the "no location" line used to be. */
    onSetLocation: ((Double, Double) -> Unit)? = null,
    onClearLocation: (() -> Unit)? = null,
    recognizedText: String = "",
    onSearchLibrary: ((String) -> Unit)? = null,
    /** Every tag on this photo, whoever or whatever put it there. */
    tags: Set<String> = emptySet(),
    /** The subset of [tags] the auto-tagger applied. Drawn differently, never separately. */
    autoTags: Set<String> = emptySet(),
    onRemoveTag: ((String) -> Unit)? = null,
    /** Adds a tag typed into the room's "Add Tags…" field. Null leaves the field out. */
    onAddTag: ((String) -> Unit)? = null,
    /** This photo's caption. May have been typed by a person or written by the annotator. */
    caption: String = "",
    /** True when [caption] is the annotator's sentence rather than someone's own words. */
    captionIsMachineWritten: Boolean = false,
    /** Null leaves the caption read-only, the same shape [onSetLocation] uses. */
    onSetCaption: ((String) -> Unit)? = null,
    /**
     * `0` clears the rating; `1..5` sets it. Null leaves the row read-only. The current value is
     * read off [exif]`.professionalMetadata`, not a separate parameter here — the same file this
     * whole room already reads for its camera facts is the one [com.fotoxplorr.app.metadata.MetadataWriter]
     * writes these fields into.
     */
    onSetRating: ((Int) -> Unit)? = null,
    onSetCreator: ((String) -> Unit)? = null,
    onSetCopyright: ((String) -> Unit)? = null,
    /** Writes this photo's [tags] into the file's own XMP keyword list. See [TagsBlock]. */
    onEmbedKeywords: (() -> Unit)? = null,
    /** Whether Information starts expanded. Collapsed by default: the reference's three rows. */
    informationInitiallyExpanded: Boolean = false,
) {
    // The shell deliberately consumes no insets — it says so in its own KDoc, because doing so
    // would lift its drag-sensitive edges off the physical screen edge. So a room pads for the
    // system bar itself. This is the TOP room: the shell insets it by the band at the BOTTOM
    // (where the parked card's top edge shows), so the bar to clear here is the status bar.
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ROOM_BACKGROUND)
            .statusBarsPadding()
            // The activity is edge-to-edge, so the window no longer shrinks for the keyboard on
            // its own; without this the caption field was covered by the keyboard that opened to
            // type into it, caret and all.
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(top = ROOM_TOP_PAD, bottom = 24.dp),
    ) {
        RoomHeader(asset = asset, reveal = reveal)
        Spacer(Modifier.height(TITLE_TO_PANEL))
        // The raised panel under the date line and the camera card: 2 dp in from each screen
        // edge in the reference, which is why it is not simply the room's own ground.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PANEL_INSET)
                .textArrival(reveal)
                .background(PANEL_BACKGROUND)
                .padding(top = PANEL_TOP_PAD, bottom = PANEL_BOTTOM_PAD),
        ) {
            Text(
                text = DetailFormatting.dateLine(asset.dateTakenMillis),
                color = DATE_TEXT,
                style = TextStyle(fontFamily = HyleGrotesk, fontSize = 16.sp),
                modifier = Modifier.padding(start = GUTTER - PANEL_INSET),
            )
            Spacer(Modifier.height(DATE_TO_CARD))
            CameraCard(
                facts = remember(asset, exif) { CameraCardFacts.from(asset, exif) },
                modifier = Modifier.padding(horizontal = GUTTER - PANEL_INSET),
            )
        }
        Spacer(Modifier.height(PANEL_TO_PLATE))
        PlaceBlock(
            asset = asset,
            exif = exif,
            reveal = reveal,
            settled = settled,
            manualLatitude = manualLatitude,
            manualLongitude = manualLongitude,
            onSetLocation = onSetLocation,
            onClearLocation = onClearLocation,
            onExpandMap = onExpandMap,
            plateMapBackdrop = plateMapBackdrop,
        )
        CaptionBlock(
            photoId = asset.id,
            caption = caption,
            isMachineWritten = captionIsMachineWritten,
            onSetCaption = onSetCaption,
            reveal = reveal,
        )
        InformationBlock(
            asset = asset,
            exif = exif,
            manualLatitude = manualLatitude,
            manualLongitude = manualLongitude,
            reveal = reveal,
            initiallyExpanded = informationInitiallyExpanded,
        )
        TagsBlock(
            photoId = asset.id,
            tags = tags,
            autoTags = autoTags,
            onRemoveTag = onRemoveTag,
            onAddTag = onAddTag,
            onEmbedKeywords = onEmbedKeywords,
            reveal = reveal,
        )
        // Everything below is this app's own, beyond the reference: kept, after the reference's
        // content, so nothing is lost and nothing displaces the layout the owner drew.
        StatusBlock(asset = asset, isFavorite = isFavorite, isSensitive = isSensitive, reveal = reveal)
        TextLensBlock(asset = asset, recognizedText = recognizedText, onSearchLibrary = onSearchLibrary, reveal = reveal)
        MetadataBlock(
            photoId = asset.id,
            metadata = exif.professionalMetadata,
            onSetRating = onSetRating,
            onSetCreator = onSetCreator,
            onSetCopyright = onSetCopyright,
            reveal = reveal,
        )
    }
}

/**
 * The slide-and-fade every text block arrives with as the room settles: alpha from
 * [PlaceMorph.textAlpha], a vertical slide from [PlaceMorph.textOffsetDp]. Draw-time only -- both
 * are read inside the layer lambda, so a drag re-draws the arrival without recomposing anything.
 */
private fun Modifier.textArrival(reveal: () -> Float): Modifier = graphicsLayer {
    val r = reveal()
    alpha = PlaceMorph.textAlpha(r)
    translationY = PlaceMorph.textOffsetDp(r) * density
}

/**
 * Filename and the local-storage glyph — the room's title. The date line sits on the panel just
 * below, in the reference's own arrangement.
 *
 * The storage glyph does NOT carry the trashed state on its own. An outline glyph swapped for
 * another outline glyph is too quiet for "this photo is queued for deletion" — so [StatusBlock]
 * still says it in words, and this says the half the glyph is actually for: whether the file is
 * still here.
 */
@Composable
private fun RoomHeader(asset: MediaAsset, reveal: () -> Float) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .textArrival(reveal)
            // Drawn a little lower than it is laid out: the reference's title sits 48 dp down
            // with the panel starting 2 dp under its descenders, which is tighter than the
            // title's own line box allows. An offset keeps the glyphs where the reference has
            // them without the box pushing the panel down.
            .offset(y = TITLE_NUDGE)
            .padding(horizontal = GUTTER),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = asset.displayName.substringBeforeLast('.'),
            color = TITLE_TEXT,
            style = TextStyle(fontFamily = HyleGrotesk, fontSize = 19.sp, fontWeight = FontWeight.Medium),
            // Two lines: this is the ONLY place the filename appears anywhere in the viewer, and
            // a name like "Screenshot_20250614-091233_com.google.android.youtube" ellipsised at
            // one line is deletion, not de-duplication.
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        // Foto Xplorr has no cloud-backup subsystem, so this reports the one backup state it can
        // actually establish: whether the file is still present locally in MediaStore (on device)
        // or has been moved to the system trash. It is not a claim about any cloud service.
        Icon(
            imageVector = if (asset.isTrashed) Icons.Outlined.CloudOff else Icons.Outlined.CloudDone,
            contentDescription = if (asset.isTrashed) "In trash" else "Stored on this device",
            tint = SECONDARY_TEXT,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * The facts the camera card states, resolved once per photo and pure, so the division of labour
 * the room's KDoc describes can be asserted: what the card says, the rows below must not.
 *
 * Every line is present whether or not the file answers it (owner, 2026-08-18: *"it's fine if
 * the values are empty, but the headers do need to exist"*): an unknown camera is "Unknown
 * camera", an unrecorded lens is a dash, an empty exposure cell is a dash. A card that only
 * appeared when the file had something to say would silently change the room's shape from photo
 * to photo, which is the thing that made the first card unreadable.
 *
 * @property exposure the five cells of the exposure strip, in the reference's order: ISO, focal
 *   length (35 mm equivalent when the file records one, the real focal length otherwise -- the
 *   reference strip says "24 mm" under a "13 mm" lens line, which is exactly that distinction),
 *   exposure bias, aperture, shutter.
 */
internal data class CameraCardFacts(
    val title: String,
    val formatBadge: String?,
    val flashFired: Boolean,
    val lensLine: String,
    val dimensionsLine: String,
    val dynamicRangeBadge: String?,
    val exposure: List<String>,
    /** False for a video: there is no exposure to speak of, and five dashes say less than nothing. */
    val showExposure: Boolean,
) {
    companion object {
        fun from(asset: MediaAsset, exif: ImageExifDetails): CameraCardFacts {
            val camera = listOfNotNull(exif.make, exif.model).joinToString(" ").trim()
            val lens = if (asset.isVideo) {
                asset.durationMillis.takeIf { it > 0L }?.let { "Video — ${DetailFormatting.durationLine(it)}" } ?: "Video"
            } else {
                DetailFormatting.lensLine(exif.lensModel, exif.focalLengthMm, exif.aperture) ?: DASH
            }
            val focal = exif.focalLength35mm?.takeIf { it > 0 }?.let { "$it mm" }
                ?: exif.focalLengthMm?.takeIf { it > 0.0 }?.let { "${it.roundToInt()} mm" }
            return CameraCardFacts(
                title = camera.ifEmpty { "Unknown camera" },
                formatBadge = DetailFormatting.formatBadge(asset.mimeType, asset.displayName),
                flashFired = DetailFormatting.flashFired(exif.flash),
                lensLine = lens,
                dimensionsLine = DetailFormatting.dimensionsLine(asset.width, asset.height, asset.sizeBytes),
                dynamicRangeBadge = DetailFormatting.dynamicRangeBadge(exif.hasHdrGainMap, asset.mimeType),
                exposure = listOf(
                    exif.iso?.takeIf(String::isNotBlank)?.let { "ISO $it" } ?: "ISO $DASH",
                    focal ?: DASH,
                    exif.exposureBiasEv?.let(DetailFormatting::exposureBiasText) ?: DASH,
                    exif.aperture?.takeIf { it > 0.0 }?.let(DetailFormatting::apertureText) ?: DASH,
                    exif.shutterSpeed?.takeIf(String::isNotBlank) ?: DASH,
                ),
                showExposure = !asset.isVideo,
            )
        }

        const val DASH = "—"
    }
}

/**
 * The camera card, drawn to the reference's own geometry: a 36 dp header band carrying the camera
 * name, the format chip and the flash glyph; a body with the lens line, the dimensions line and the
 * dynamic-range chip; a divider; and the five-cell exposure strip with thin separators.
 */
@Composable
private fun CameraCard(facts: CameraCardFacts, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CARD_CORNER))
            .background(CARD_BACKGROUND),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(CARD_HEADER_HEIGHT)
                .background(CARD_HEADER_BACKGROUND)
                .padding(horizontal = CARD_PAD),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = facts.title,
                color = CARD_HEADER_TEXT,
                style = TextStyle(fontFamily = HyleGrotesk, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            facts.formatBadge?.let { badge ->
                Text(
                    text = badge,
                    color = CARD_CHIP_TEXT,
                    style = TextStyle(
                        fontFamily = HyleGrotesk,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.6.sp,
                    ),
                    maxLines = 1,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(CARD_CHIP_BACKGROUND)
                        .padding(horizontal = 5.dp, vertical = 3.dp),
                )
            }
            FlashGlyph(lit = facts.flashFired, modifier = Modifier.padding(start = 8.dp))
        }
        Column(Modifier.fillMaxWidth().padding(start = CARD_PAD + 1.dp, end = CARD_PAD, top = CARD_BODY_TOP)) {
            Text(
                text = facts.lensLine,
                color = CARD_BODY_TEXT,
                style = TextStyle(fontFamily = HyleGrotesk, fontSize = 14.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = CARD_LINE_GAP),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = facts.dimensionsLine,
                    color = CARD_BODY_TEXT,
                    style = TextStyle(fontFamily = HyleGrotesk, fontSize = 14.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                facts.dynamicRangeBadge?.let { badge ->
                    Text(
                        text = badge,
                        color = CARD_BODY_TEXT,
                        style = TextStyle(
                            fontFamily = HyleGrotesk,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 1.sp,
                        ),
                        maxLines = 1,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .border(1.dp, CARD_CHIP_OUTLINE, CircleShape)
                            .padding(horizontal = 11.dp, vertical = 5.dp),
                    )
                }
            }
        }
        if (facts.showExposure) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = CARD_DIVIDER_TOP)
                    .height(CARD_DIVIDER_HEIGHT)
                    .background(CARD_DIVIDER),
            )
            ExposureStrip(cells = facts.exposure)
        } else {
            Spacer(Modifier.height(CARD_BODY_BOTTOM))
        }
    }
}

/**
 * The flash glyph: a thin ring with a bolt in it, lit white when the flash fired and in the card's
 * own grey when it did not. Drawn rather than imported so its weight matches the card's hairlines.
 */
@Composable
private fun FlashGlyph(lit: Boolean, modifier: Modifier = Modifier) {
    val ink = if (lit) CARD_CHIP_TEXT else CARD_GLYPH
    Box(
        modifier = modifier
            .size(FLASH_GLYPH_SIZE)
            .drawBehind {
                val stroke = 1.5.dp.toPx()
                drawCircle(color = ink, radius = size.minDimension / 2f - stroke / 2f, style = Stroke(stroke))
                val w = size.width
                val h = size.height
                val bolt = Path().apply {
                    moveTo(w * 0.56f, h * 0.22f)
                    lineTo(w * 0.36f, h * 0.54f)
                    lineTo(w * 0.50f, h * 0.54f)
                    lineTo(w * 0.44f, h * 0.78f)
                    lineTo(w * 0.64f, h * 0.46f)
                    lineTo(w * 0.50f, h * 0.46f)
                    close()
                }
                drawPath(bolt, ink)
            }
            .then(
                if (lit) Modifier else Modifier,
            ),
    )
}

/**
 * The five exposure cells with hairline separators between them. The column widths are the
 * reference's own (measured at 72 / 96.5 / 79 / 81 / 71.5 of a 400 dp card), the first cell
 * left-aligned, the last right-aligned, the middle three centred -- which is how it is laid out
 * there, and why a plain five-way split would not match it.
 */
@Composable
private fun ExposureStrip(cells: List<String>) {
    Row(
        // A hair above the strip's own centre: the reference's cells sit 1 dp higher than a
        // centred line box would put them.
        modifier = Modifier.fillMaxWidth().height(CARD_STRIP_HEIGHT).padding(bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cells.forEachIndexed { index, cell ->
            Box(
                modifier = Modifier.weight(EXPOSURE_WEIGHTS[index]).fillMaxHeight(),
                contentAlignment = when (index) {
                    0 -> Alignment.CenterStart
                    cells.lastIndex -> Alignment.CenterEnd
                    else -> Alignment.Center
                },
            ) {
                Text(
                    text = cell,
                    color = CARD_STRIP_TEXT,
                    style = TextStyle(fontFamily = HyleGrotesk, fontSize = 12.sp),
                    maxLines = 1,
                    modifier = Modifier.padding(
                        start = if (index == 0) CARD_PAD + 1.dp else 0.dp,
                        end = if (index == cells.lastIndex) CARD_PAD + 3.dp else 0.dp,
                    ),
                )
            }
            if (index < cells.lastIndex) {
                Box(Modifier.width(1.dp).height(CARD_SEPARATOR_HEIGHT).background(CARD_SEPARATOR))
            }
        }
    }
}

/**
 * The "Search inside this photo" card -- see [com.fotoxplorr.app.lens.LensCard] for everything
 * it actually does; this wrapper only supplies the room's own outer padding and hands through
 * what the room already has in hand rather than fetching anything itself. Absent entirely when
 * there is nothing to say rather than headlining a shrug.
 */
@Composable
private fun TextLensBlock(
    asset: MediaAsset,
    recognizedText: String,
    onSearchLibrary: ((String) -> Unit)?,
    reveal: () -> Float,
) {
    if (recognizedText.isBlank()) return
    Box(Modifier.textArrival(reveal).padding(horizontal = GUTTER, vertical = 10.dp)) {
        com.fotoxplorr.app.lens.LensCard(
            asset = asset,
            recognizedText = recognizedText,
            onSearchLibrary = onSearchLibrary,
        )
    }
}

/**
 * "Tags", then every tag on this photo with the auto-applied ones drawn as what they are, or the
 * reference's "Add Tags…" placeholder when there are none.
 *
 * One list, not two. A person thinks of these as "this photo's tags" regardless of who typed
 * them, and splitting them into "Yours" and "Suggested" sections would make the reader do
 * bookkeeping the app already did. The provenance still shows — an auto tag is outlined rather
 * than filled — because a person who wants to know which words they chose has to be able to tell.
 *
 * The header is always present (the reference draws it with nothing under it), and so is the way
 * to add a tag when [onAddTag] is wired: tapping the placeholder opens a field; a tag is added on
 * commit.
 */
@Composable
private fun TagsBlock(
    photoId: MediaId,
    tags: Set<String>,
    autoTags: Set<String>,
    onRemoveTag: ((String) -> Unit)?,
    onAddTag: ((String) -> Unit)?,
    onEmbedKeywords: (() -> Unit)?,
    reveal: () -> Float,
) {
    var adding by remember(photoId) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .textArrival(reveal)
            .padding(start = GUTTER + 2.dp, end = GUTTER + 2.dp, top = SECTION_TOP),
        verticalArrangement = Arrangement.spacedBy(TAGS_GAP),
    ) {
        SectionTitle("Tags")
        if (tags.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Sorted, with the person's own words first. Set iteration order is an accident
                // of insertion, and a list of chips that reshuffles between two openings of the
                // same photo reads as a bug even when nothing changed.
                val ordered = tags.sortedWith(compareBy({ it in autoTags }, { it.lowercase() }))
                ordered.forEach { tag ->
                    TagChip(
                        tag = tag,
                        isAuto = tag in autoTags,
                        onRemove = onRemoveTag?.let { remove -> { remove(tag) } },
                    )
                }
            }
        }
        if (onAddTag != null && adding) {
            CommitOnceField(
                fieldKey = photoId,
                placeholder = "Type a tag",
                onCommit = { typed ->
                    adding = false
                    val tag = typed.trim()
                    if (tag.isNotEmpty()) onAddTag(tag)
                },
            )
        } else {
            Text(
                text = if (tags.isEmpty()) "Add Tags…" else "Add a tag…",
                color = PLACEHOLDER_TEXT,
                style = TextStyle(fontFamily = HyleGrotesk, fontSize = 15.sp),
                modifier = Modifier
                    .then(
                        if (onAddTag != null) {
                            Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { adding = true }
                        } else {
                            Modifier
                        },
                    ),
            )
        }
        if (autoTags.isNotEmpty()) {
            Text(
                text = "Outlined tags were suggested by on-device recognition. Removing one " +
                    "removes it for good — it will not be suggested again.",
                color = MUTED_TEXT,
                style = RoomStyle.Caption,
            )
        }
        // Tags above live in this app's own library. This is the bridge to the file: a
        // photographer's tags, written into the file's own XMP keyword list, so they travel with
        // a copy or a share rather than staying legible only inside Foto Xplorr.
        if (onEmbedKeywords != null && tags.isNotEmpty()) {
            Text(
                text = "Embed these tags as keywords in the file →",
                color = SECONDARY_TEXT,
                style = RoomStyle.Caption,
                modifier = Modifier.clickable(onClick = onEmbedKeywords),
            )
        }
    }
}

@Composable
private fun TagChip(tag: String, isAuto: Boolean, onRemove: (() -> Unit)?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            // Filled for a tag someone typed, outlined for one the app guessed. Drawn here rather
            // than said in a legend for the same reason the room labels its rows: two kinds of
            // fact should not look identical.
            .then(
                if (isAuto) {
                    Modifier.border(1.dp, Color(0xFF3A3A3A), RoundedCornerShape(6.dp))
                } else {
                    Modifier.background(CARD_HEADER_BACKGROUND)
                },
            )
            .padding(start = 10.dp, end = if (onRemove != null) 4.dp else 10.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Text(
            text = tag,
            color = if (isAuto) SECONDARY_TEXT else PRIMARY_TEXT,
            style = RoomStyle.Caption,
        )
        if (onRemove != null) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "Remove tag $tag",
                tint = MUTED_TEXT,
                modifier = Modifier.size(16.dp).clickable(onClick = onRemove),
            )
        }
    }
}

/**
 * The caption: the reference's centred "Add a Caption" when there is none, the caption itself
 * when there is, and a field when either is tapped.
 *
 * The field holds its own draft and commits on focus loss rather than on every keystroke. Writing
 * per character would mean a database write per letter typed, and — because a machine caption is
 * cleared the moment a person edits — would also make the first keystroke silently discard the
 * annotator's sentence before anyone had decided to replace it. Two more commit paths back that
 * one up, because focus loss alone is not guaranteed to fire: leaving the room with the field
 * still focused detaches the node, so the draft is also flushed when the field leaves composition.
 *
 * @param photoId which photo the draft belongs to. Keyed on this AND the stored caption: keyed on
 *   the caption string alone, two consecutive photos with no caption share the key "", the
 *   remember never resets, and a half-typed draft rides across a page swipe onto the wrong photo.
 */
@Composable
private fun CaptionBlock(
    photoId: MediaId,
    caption: String,
    isMachineWritten: Boolean,
    onSetCaption: ((String) -> Unit)?,
    reveal: () -> Float,
) {
    if (onSetCaption == null && caption.isBlank()) return
    var editing by remember(photoId) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .textArrival(reveal)
            .padding(start = GUTTER, end = GUTTER, top = PLATE_TO_CAPTION),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (onSetCaption != null && editing) {
            EditableCommitField(
                fieldKey = photoId,
                value = caption,
                placeholder = "Say something about this photo",
                onCommit = { text ->
                    editing = false
                    if (text != caption) onSetCaption(text)
                },
                requestFocus = true,
            )
        } else {
            Text(
                text = caption.ifBlank { "Add a Caption" },
                color = TITLE_TEXT,
                textAlign = TextAlign.Center,
                style = TextStyle(
                    fontFamily = HyleGrotesk,
                    fontSize = if (caption.isBlank()) 17.sp else 16.sp,
                    fontWeight = FontWeight.Medium,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (onSetCaption != null) {
                            Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClickLabel = "Edit the caption",
                            ) { editing = true }
                        } else {
                            Modifier
                        },
                    ),
            )
        }
        if (caption.isNotBlank() && isMachineWritten) {
            Text(
                text = "Written by on-device recognition. Type over it and it becomes yours.",
                color = MUTED_TEXT,
                style = RoomStyle.Caption,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * A text field that holds its own draft and commits only when it stops being safe to keep
 * editing — the exact dance [CaptionBlock]'s own history explains in full: focus loss alone is
 * not a guaranteed final event, so the draft is also flushed when this block leaves composition
 * (room closed, photo swiped away with the field still focused) rather than only on blur.
 *
 * Shared by the caption and [MetadataBlock]'s creator and copyright rows: three independent copies
 * of logic this subtle (see the dispose-time flush below) are three independent places it could
 * quietly drift out of sync.
 *
 * @param fieldKey identifies which field on which photo this draft belongs to. Keying on [value]
 *   alone is wrong: two photos with the same blank field would share the key "", the `remember`
 *   would never reset across a page swipe, and a half-typed draft would ride onto the wrong photo.
 * @param requestFocus ask for focus on first composition -- for a field that appears because the
 *   user tapped the text it replaces, and would otherwise sit there waiting for a second tap.
 */
@Composable
private fun EditableCommitField(
    fieldKey: Any,
    value: String,
    placeholder: String,
    onCommit: ((String) -> Unit)?,
    requestFocus: Boolean = false,
) {
    if (onCommit == null) {
        if (value.isNotBlank()) Text(text = value, color = PRIMARY_TEXT, style = RoomStyle.Caption)
        return
    }
    key(fieldKey) {
        var draft by remember(value) { mutableStateOf(value) }
        val latestDraft by rememberUpdatedState(draft)
        val latestStored by rememberUpdatedState(value)
        val latestCommit by rememberUpdatedState(onCommit)
        val focusRequester = remember { FocusRequester() }
        DisposableEffect(Unit) {
            onDispose {
                if (latestDraft != latestStored) latestCommit(latestDraft)
            }
        }
        if (requestFocus) {
            LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
        }
        HyleTextField(
            value = draft,
            onValueChange = { draft = it },
            placeholder = placeholder,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged { focus ->
                    if (!focus.isFocused && draft != value) onCommit(draft)
                },
        )
    }
}

/**
 * A field for ONE new value -- a tag -- that commits exactly once, on focus loss or on leaving
 * composition, and starts empty. Differs from [EditableCommitField] in that there is no stored
 * value to compare against: anything typed is new.
 */
@Composable
private fun CommitOnceField(fieldKey: Any, placeholder: String, onCommit: (String) -> Unit) {
    key(fieldKey) {
        var draft by remember { mutableStateOf("") }
        var committed by remember { mutableStateOf(false) }
        val latestDraft by rememberUpdatedState(draft)
        val latestCommit by rememberUpdatedState(onCommit)
        val focusRequester = remember { FocusRequester() }
        fun commit() {
            if (committed) return
            committed = true
            latestCommit(latestDraft)
        }
        DisposableEffect(Unit) { onDispose { commit() } }
        LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
        HyleTextField(
            value = draft,
            onValueChange = { draft = it },
            placeholder = placeholder,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged { focus -> if (!focus.isFocused && draft.isNotEmpty()) commit() },
        )
    }
}

/**
 * The professional fields a photographer's own tools already read and write — star rating,
 * creator, copyright — as opposed to [StatusBlock], which is this app's own private knowledge
 * about a photo. These are written into the FILE itself (EXIF and XMP together; see
 * [com.fotoxplorr.app.metadata.MetadataWriter]) precisely so they show up unchanged in Lightroom,
 * Photo Mechanic, or any other tool the same file is later opened in.
 *
 * Renders nothing when every callback is null AND there is nothing to show — an absent feature
 * affordance is worse than no affordance.
 */
@Composable
private fun MetadataBlock(
    photoId: MediaId,
    metadata: CurrentMetadata,
    onSetRating: ((Int) -> Unit)?,
    onSetCreator: ((String) -> Unit)?,
    onSetCopyright: ((String) -> Unit)?,
    reveal: () -> Float,
) {
    val hasAnyField = onSetRating != null || onSetCreator != null || onSetCopyright != null ||
        metadata.rating != null || !metadata.creator.isNullOrBlank() || !metadata.copyright.isNullOrBlank()
    if (!hasAnyField) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .textArrival(reveal)
            .padding(start = GUTTER + 2.dp, end = GUTTER + 2.dp, top = SECTION_TOP),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionTitle("Metadata")
        if (onSetRating != null || metadata.rating != null) {
            RatingRow(rating = metadata.rating ?: 0, onSetRating = onSetRating)
        }
        if (onSetCreator != null || !metadata.creator.isNullOrBlank()) {
            Text(text = "Creator", color = ROW_LABEL, style = TextStyle(fontFamily = HyleGrotesk, fontSize = 14.sp))
            EditableCommitField(
                fieldKey = photoId,
                value = metadata.creator.orEmpty(),
                placeholder = "Photographer or studio name",
                onCommit = onSetCreator,
            )
        }
        if (onSetCopyright != null || !metadata.copyright.isNullOrBlank()) {
            Text(text = "Copyright", color = ROW_LABEL, style = TextStyle(fontFamily = HyleGrotesk, fontSize = 14.sp))
            EditableCommitField(
                fieldKey = photoId,
                value = metadata.copyright.orEmpty(),
                placeholder = "© 2026 your name",
                onCommit = onSetCopyright,
            )
        }
    }
}

/**
 * Five tappable stars. Tapping the star that already sets the current rating clears it back to
 * unrated rather than re-applying the same value — the standard rating-widget affordance, and the
 * only way back to "no rating" once any star has been tapped, since there is no sixth "clear" star.
 */
@Composable
private fun RatingRow(rating: Int, onSetRating: ((Int) -> Unit)?) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (star in 1..5) {
            val filled = star <= rating
            Icon(
                imageVector = if (filled) Icons.Filled.Star else Icons.Outlined.StarBorder,
                contentDescription = null,
                tint = if (filled) PRIMARY_TEXT else MUTED_TEXT,
                modifier = Modifier
                    .size(22.dp)
                    .then(
                        if (onSetRating != null) {
                            Modifier.clickable { onSetRating(if (star == rating) 0 else star) }
                        } else {
                            Modifier
                        },
                    ),
            )
        }
    }
}

/**
 * What this photo is to *you*, as distinct from what the file is: whether you marked it, whether
 * you hid it, whether it is queued for deletion. Stated in words, not left to the header's cloud
 * glyph -- an 18 dp outline carrying a consequential meaning is the bare-glyph mistake this project
 * has already made once. A mark that only appears when the answer is yes is the right shape for a
 * state that is only worth mentioning when it holds, so with no marks there is no empty heading.
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
        if (asset.isVideo) add("Video")
        if (asset.isTrashed) add("In the trash")
    }
    if (marks.isEmpty()) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .textArrival(reveal)
            .padding(start = GUTTER + 2.dp, end = GUTTER + 2.dp, top = SECTION_TOP),
    ) {
        SectionTitle("In your library")
        Text(
            text = marks.joinToString(" · "),
            color = ROW_VALUE,
            style = TextStyle(fontFamily = HyleGrotesk, fontSize = 15.sp),
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * The place plate, or an honest absence — and the room's only drawing of where this photo was
 * taken.
 *
 * Most libraries are mostly un-geotagged — screenshots, downloads, anything shared through a
 * messenger that strips EXIF. A plate drawn at 0°,0° for those would be a confident lie, so with
 * no location the plate's space is simply empty background (owner's words), with the one
 * explanatory sentence, and the picker when the caller allows a location to be placed by hand.
 */
@Composable
private fun PlaceBlock(
    asset: MediaAsset,
    exif: ImageExifDetails,
    reveal: () -> Float,
    settled: Boolean,
    manualLatitude: Double?,
    manualLongitude: Double?,
    onSetLocation: ((Double, Double) -> Unit)?,
    onClearLocation: (() -> Unit)?,
    onExpandMap: ((Rect) -> Unit)?,
    plateMapBackdrop: (@Composable BoxScope.(settled: Boolean) -> Unit)?,
) {
    // A hand-placed location stands in for an absent embedded one. It never overrides a real GPS
    // tag: what the camera recorded is a fact about the photograph, and the picker is for photos
    // that have no such fact.
    val embedded = PlaceMorph.isDrawable(exif.latitude, exif.longitude)
    val latitude = if (embedded) exif.latitude else manualLatitude
    val longitude = if (embedded) exif.longitude else manualLongitude
    Box(Modifier.padding(horizontal = GUTTER)) {
        when {
            PlaceMorph.isDrawable(latitude, longitude) -> {
                if (plateMapBackdrop != null) {
                    PlacePlate(
                        asset = asset,
                        latitude = latitude!!,
                        longitude = longitude!!,
                        reveal = reveal,
                        settled = settled,
                        onExpand = onExpandMap,
                        mapBackdrop = plateMapBackdrop,
                    )
                } else {
                    PlacePlate(
                        asset = asset,
                        latitude = latitude!!,
                        longitude = longitude!!,
                        reveal = reveal,
                        settled = settled,
                        onExpand = onExpandMap,
                    )
                }
            }
            !exif.readOk -> {
                // A failed read is not evidence the file has no GPS tag (P0-02) -- only that this
                // attempt couldn't see it. Never the picker here: offering to hand-place a
                // location would invite overwriting one that may already be in the file.
                Column(Modifier.textArrival(reveal).padding(vertical = 6.dp)) {
                    RoomEyebrow("PLACE")
                    Text(
                        text = "Couldn't read this photo's details",
                        color = RoomStyle.InkMuted,
                        style = RoomStyle.Row,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            onSetLocation != null -> {
                Box(Modifier.textArrival(reveal)) {
                    com.fotoxplorr.app.spatial.LocationPicker(
                        latitude = latitude,
                        longitude = longitude,
                        onSet = onSetLocation,
                        onClear = { onClearLocation?.invoke() },
                    )
                }
            }
            else -> {
                // The plate's area is just empty space in the background, with the one sentence
                // that says why -- photos saved from messaging apps and websites have their GPS
                // stripped before they ever reach the device, which is the reason for most of the
                // empty ones in a real library.
                Column(Modifier.textArrival(reveal).padding(vertical = 6.dp)) {
                    Text(
                        text = "No location in this file",
                        color = RoomStyle.InkMuted,
                        style = RoomStyle.Row,
                    )
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
}

/**
 * "Information": the reference's three rows (Modified, colour space, colour profile) with a
 * Show More / Show Less toggle; expanded, every remaining fact about the file that the camera
 * card does not already state -- aspect, a video's duration, album, folder, the coordinate, and
 * the palette. Labels are present whether or not the file answers them, with a dash where it
 * does not: an absent row tells the reader nothing, a dashed one tells them this photo does not
 * record that.
 *
 * The toggle's label names the ACTION, so it reads "Show More" while collapsed; the reference's
 * frame shows "Show Less" over the same three rows, which would make the button a status rather
 * than a control -- see the handoff doc for that one deliberate departure.
 */
@Composable
private fun InformationBlock(
    asset: MediaAsset,
    exif: ImageExifDetails,
    manualLatitude: Double?,
    manualLongitude: Double?,
    reveal: () -> Float,
    initiallyExpanded: Boolean,
) {
    var expanded by remember(asset.id) { mutableStateOf(initiallyExpanded) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .textArrival(reveal)
            .padding(start = GUTTER + 2.dp, end = GUTTER + 2.dp, top = CAPTION_TO_INFO),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = SECTION_TO_ROWS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionTitle("Information", modifier = Modifier.weight(1f))
            Text(
                text = if (expanded) "Show Less" else "Show More",
                color = LINK_TEXT,
                style = TextStyle(fontFamily = HyleGrotesk, fontSize = 14.sp),
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { expanded = !expanded },
            )
        }
        InformationRow("Modified", DetailFormatting.dateLine(asset.dateModifiedSeconds * 1_000L))
        InformationRow("Colour space", exif.colorSpace)
        InformationRow("Colour profile", exif.colorProfile)
        if (expanded) {
            InformationRow("Aspect", aspectRatioLabel(asset.width, asset.height))
            if (asset.isVideo) {
                InformationRow("Duration", asset.durationMillis.takeIf { it > 0L }?.let(DetailFormatting::durationLine))
            }
            // Album and Where look like the same fact on a phone whose album name is the last
            // segment of its folder path, and they are not: Album is the grouping the library
            // files this photo under, Where is the folder it physically sits in.
            InformationRow("Album", asset.bucketName?.takeIf(String::isNotBlank))
            InformationRow("Where", asset.relativePath?.takeIf(String::isNotBlank))
            val latitude = exif.latitude ?: manualLatitude
            val longitude = exif.longitude ?: manualLongitude
            InformationRow(
                "Location",
                if (PlaceMorph.isDrawable(latitude, longitude)) {
                    PlaceMorph.coordinateLine(latitude!!, longitude!!)
                } else {
                    null
                },
            )
            // Not a video: MediaImage never decodes a video's frames here, and readImagePalette
            // returns nothing for one anyway — so the row is only worth adding for a still photo.
            if (!asset.isVideo) PaletteRow(asset)
        }
    }
}

/** A section's title, in the reference's own weight and shade. */
@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = SECTION_TEXT,
        style = TextStyle(fontFamily = HyleGrotesk, fontSize = 16.sp, fontWeight = FontWeight.Bold),
        modifier = modifier,
    )
}

/**
 * One row of Information: a hairline on top, the label left in grey, the value right-aligned in
 * white, or a dash where the file had nothing to say. 27.5 dp tall, the reference's own pitch.
 */
@Composable
private fun InformationRow(label: String, value: String?) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(HAIRLINE_HEIGHT).background(HAIRLINE))
        Row(
            modifier = Modifier.fillMaxWidth().height(ROW_HEIGHT),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = label,
                color = ROW_LABEL,
                style = TextStyle(fontFamily = HyleGrotesk, fontSize = 13.sp),
                maxLines = 1,
            )
            Text(
                text = value?.takeIf(String::isNotBlank) ?: CameraCardFacts.DASH,
                color = if (value.isNullOrBlank()) ROW_LABEL else ROW_VALUE,
                style = TextStyle(fontFamily = HyleGrotesk, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * The colour palette, as one more row of Information: a segmented bar, each colour's width its own
 * share of the photo, hex values under it.
 *
 * Loads asynchronously and starts as the dash every other absent row uses: unlike every other row,
 * which is read straight off [ImageExifDetails], a palette needs the actual pixels decoded first,
 * which [readImagePalette] does off the main thread -- once per photo per process, because the
 * result is memoised in [PaletteMemo] (and that memo is what lets the expanded map colour this
 * photo's dot without decoding anything).
 */
@Composable
private fun PaletteRow(asset: MediaAsset) {
    val context = LocalContext.current
    var swatches by remember(asset.id) { mutableStateOf(PaletteMemo.get(asset.id).orEmpty()) }
    LaunchedEffect(asset.id, asset.contentUriString) {
        if (swatches.isEmpty()) {
            val read = readImagePalette(context, asset)
            PaletteMemo.put(asset.id, read)
            swatches = read
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(HAIRLINE_HEIGHT).background(HAIRLINE))
        Row(
            modifier = Modifier.fillMaxWidth().height(ROW_HEIGHT),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Palette",
                color = ROW_LABEL,
                style = TextStyle(fontFamily = HyleGrotesk, fontSize = 13.sp),
            )
            if (swatches.isEmpty()) {
                Text(
                    text = CameraCardFacts.DASH,
                    color = ROW_LABEL,
                    style = TextStyle(fontFamily = HyleGrotesk, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(12.dp)
                        .clip(RoundedCornerShape(4.dp)),
                ) {
                    swatches.forEach { swatch ->
                        Box(
                            modifier = Modifier
                                // Row.weight requires a positive value; the quantiser only ever
                                // emits a proportion above zero, but the floor guards this draw
                                // call against that invariant ever being loosened upstream.
                                .weight(swatch.proportion.coerceAtLeast(MIN_SEGMENT_WEIGHT))
                                .fillMaxHeight()
                                .background(Color(swatch.argb)),
                        )
                    }
                }
            }
        }
        if (swatches.isNotEmpty()) {
            Text(
                text = swatches.joinToString("   ") { it.hex },
                color = ROW_LABEL,
                style = TextStyle(fontFamily = HyleGrotesk, fontSize = 11.sp, letterSpacing = 0.2.sp),
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            )
        }
    }
}

private const val MIN_SEGMENT_WEIGHT = 0.001f

/**
 * The photo's dominant colours, as a segmented-bar-ready list. Empty for a video, or when the
 * file cannot be decoded at all (corrupt, or a format `BitmapFactory` does not understand).
 *
 * The photo is decoded at a small fraction of its real size FIRST — see [decodeUpright] — and
 * only then handed to [PaletteExtractor]. The swatches a viewer perceives "from across the room"
 * do not change with resolution, so paying full-resolution cost for them is pure waste.
 */
internal suspend fun readImagePalette(
    context: Context,
    asset: MediaAsset,
    maxColors: Int = PaletteExtractor.DEFAULT_MAX_COLORS,
): List<PaletteSwatch> {
    if (asset.isVideo) return emptyList()
    return withContext(Dispatchers.IO) {
        runCatching {
            val decoded = decodeUpright(context, asset.contentUri, PALETTE_DECODE_LIMITS)
                ?: return@runCatching emptyList()
            val bitmap = decoded.bitmap
            try {
                if (bitmap.width <= 0 || bitmap.height <= 0) return@runCatching emptyList()
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                PaletteExtractor.extract(pixels, maxColors)
            } finally {
                bitmap.recycle()
            }
        }.getOrDefault(emptyList())
    }
}

/**
 * The target longest edge for palette sampling. A segmented bar only ever shows up to
 * [PaletteExtractor.DEFAULT_MAX_COLORS] swatches, and 100px on the long edge is already tens of
 * thousands of sample pixels for the quantiser to work with.
 */
private const val PALETTE_SAMPLE_DIMENSION = 100
private val PALETTE_DECODE_LIMITS = DecodeLimits(
    maxLongEdge = PALETTE_SAMPLE_DIMENSION,
    maxPixels = PALETTE_SAMPLE_DIMENSION.toLong() * PALETTE_SAMPLE_DIMENSION,
)

/** `2.0 MP`, or null when the file never recorded its own size. */
internal fun megapixels(width: Int, height: Int): String? {
    if (width <= 0 || height <= 0) return null
    // Long multiplication: a 24-megapixel photo is 6000 x 4000, and Int overflows past 2.1 billion
    // — reachable by a panorama, where the count would silently go negative.
    val mp = width.toLong() * height / 1_000_000.0
    return "${(mp * 10).roundToInt() / 10.0} MP"
}

/**
 * `3 : 2`, reduced. Reduced by the greatest common divisor rather than matched against a table of
 * known ratios, so an odd crop reports its own shape instead of the nearest famous one.
 */
internal fun aspectRatioLabel(width: Int, height: Int): String? {
    if (width <= 0 || height <= 0) return null
    var a = width
    var b = height
    while (b != 0) {
        val t = b
        b = a % b
        a = t
    }
    val divisor = a.coerceAtLeast(1)
    val w = width / divisor
    val h = height / divisor
    // Past this the "ratio" is two large coprime numbers and says less than the pixel count did.
    return if (w > 50 || h > 50) null else "$w : $h"
}

// ── The room's measurements, every one off the reference (6.png at 1 px = 1 dp) ───────────────
private val GUTTER = 20.dp
private val ROOM_TOP_PAD = 38.dp
private val TITLE_NUDGE = 6.dp
private val TITLE_TO_PANEL = 5.dp
private val PANEL_INSET = 2.dp
private val PANEL_TOP_PAD = 8.dp
private val PANEL_BOTTOM_PAD = 8.dp
private val DATE_TO_CARD = 13.dp
private val PANEL_TO_PLATE = 10.dp
private val PLATE_TO_CAPTION = 18.dp
private val CAPTION_TO_INFO = 30.dp
private val SECTION_TO_ROWS = 6.dp
private val SECTION_TOP = 14.dp
private val TAGS_GAP = 7.dp
/** 26.5 dp of row under a 1 dp hairline: the reference's 27.5 dp pitch. */
private val ROW_HEIGHT = 26.5.dp
private val HAIRLINE_HEIGHT = 1.dp
private val CARD_CORNER = 10.dp
private val CARD_HEADER_HEIGHT = 36.dp
private val CARD_PAD = 6.dp
private val CARD_BODY_TOP = 7.dp
private val CARD_LINE_GAP = 6.dp
private val CARD_DIVIDER_TOP = 12.dp
private val CARD_DIVIDER_HEIGHT = 2.dp
private val CARD_STRIP_HEIGHT = 29.dp
private val CARD_SEPARATOR_HEIGHT = 12.dp
private val CARD_BODY_BOTTOM = 12.dp
private val FLASH_GLYPH_SIZE = 21.dp
/** Measured cell widths of the reference's exposure strip: 72 / 96.5 / 79 / 81 / 71.5 of 400. */
private val EXPOSURE_WEIGHTS = floatArrayOf(72f, 96.5f, 79f, 81f, 71.5f)

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
)

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
        // A second, bounds-only pass for the colour profile's name. Separate from the EXIF read
        // because ExifInterface does not see ICC data, and tolerant of failure because a file
        // whose profile cannot be named is still a file whose EXIF was read fine.
        details.copy(colorProfile = readColorProfileName(context, asset))
    }
}

/**
 * The name of the file's colour profile, from a bounds-only decode ([BitmapFactory] with
 * `inJustDecodeBounds`, which reads headers and allocates no pixels -- TRAPS 34 is about pixels).
 * "sRGB IEC61966-2.1" is what the platform calls sRGB, which is exactly the reference's row.
 */
private fun readColorProfileName(context: Context, asset: MediaAsset): String? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
    return runCatching {
        context.contentResolver.openInputStream(asset.contentUri)?.use { stream ->
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(stream, null, options)
            options.outColorSpace?.name?.takeIf(String::isNotBlank)
        }
    }.getOrNull()
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
