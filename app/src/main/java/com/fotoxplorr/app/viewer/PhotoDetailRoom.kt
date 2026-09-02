package com.fotoxplorr.app.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import com.fotoxplorr.app.hyle.HyleTextField
import com.fotoxplorr.app.ui.HyleGrotesk
import com.fotoxplorr.app.ui.RoomEyebrow
import com.fotoxplorr.app.ui.RoomStyle
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.exifinterface.media.ExifInterface
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaId
import com.fotoxplorr.app.media.MediaImage
import com.fotoxplorr.app.palette.PaletteExtractor
import com.fotoxplorr.app.palette.PaletteSwatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val PANEL_BACKGROUND = Color(0xFF0B0B0B)
// internal, not private: com.fotoxplorr.app.lens.LensCard's "Search inside this photo" card is
// visually a card of this same room (see its own KDoc), and re-declaring these five hex values a
// second time in that package would be exactly the drift RoomStyle's own KDoc warns about --
// "three different apps" -- for a card that is supposed to read as part of this one. They stay
// even though the camera card that also used them has gone: LensCard is the surface now.
internal val CARD_BACKGROUND = Color(0xFF151515)
internal val CARD_HEADER_BACKGROUND = Color(0xFF1D1D1D)
internal val PRIMARY_TEXT = Color(0xFFF2F2F2)
internal val SECONDARY_TEXT = Color(0xFF8A8A8A)
internal val MUTED_TEXT = Color(0xFF6A6A6A)

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
 * The order is the reference video's: filename, date, what the photo carries in your library,
 * the caption affordance, the file's own facts, and the place plate the photo flies into last.
 *
 * **Every fact is said once.** It was not: a camera card sat near the top repeating twelve rows'
 * worth of the labelled list further down — make, model, lens, focal length, aperture, format,
 * flash, dimensions, megapixels, file size, ISO, shutter and exposure bias — and repeated focal
 * length and aperture a second time *inside itself*, once on its lens line and again in the
 * exposure strip directly under that line. The
 * header's filename and date were printed again as rows. The album name appeared as a bare,
 * unlabelled line under "IN YOUR LIBRARY" as well as in the labelled "Album" row (owner,
 * 2026-09-01: *"make sure the details/info part of the photo does not have duplicate info (shows
 * multiple times)"* — the render that prompted it showed a lone, unexplained word "Camera" under
 * that heading, which is what an unlabelled value always eventually reads as).
 *
 * The card lost rather than the list, and that was forced, not preferred. The standing rule for
 * this room is that every label is present whether or not the file answers it (owner,
 * 2026-08-18: *"it's fine if the values are empty, but the headers do need to exist"*), so
 * trimming the duplicated rows out of [InformationBlock] instead would have left a screenshot —
 * no camera, no lens, no aperture, which is most of a real library — with no "Lens" label
 * anywhere and no way to tell a lens the file never recorded from a lens this app declines to
 * show. A card cannot state an absence; a labelled row exists in order to state one.
 *
 * So the facts divide by who states them best, and each is stated exactly once: [RoomHeader]
 * owns the filename, the capture date and the storage state, because it is the room's title;
 * [StatusBlock] owns the marks *you* put on the photo and nothing the file knows; [PlaceBlock]
 * owns the coordinates, which [PlacePlate] prints under the pin itself; and [InformationBlock]
 * owns every remaining file and capture fact, labelled, including the two that used to exist
 * only on the card — its format badge, which is "Kind", and its dynamic-range badge, which had
 * no row at all until the card's removal made one necessary.
 *
 * The caption row is real now. It was drawn-but-dead for as long as this room existed, because
 * the data model had nowhere to put a caption; `LibraryStore` has a caption field since the
 * auto-annotation work, so the row writes to it. A caption the annotator wrote says so, and
 * typing over it makes it yours -- see [CaptionBlock].
 *
 * @param exif read by the viewer rather than here. The shell composes a room only once it is
 *   at least slightly open, so a room that read its own EXIF would start that read on the first
 *   pixel of the pull and show a CAPTURE section of dashes for the rest of it — the facts have to
 *   be in hand before the gesture begins, not fetched because it did.
 * @param reveal how open the room is, 0..1. Read on the draw pass so a drag animates the
 *   arrival without recomposing the room's content on every frame.
 * @param recognizedText this photo's OCR text, flattened -- read by the viewer the same way
 *   [exif] is (see this KDoc's own note on [exif]), typically
 *   `recognition.textOf(asset.id)` off [com.fotoxplorr.app.recognition.RecognitionIndex].
 *   Defaulted to empty, not required, so a caller that has not been updated to pass it yet
 *   still compiles -- it simply gets the room's previous behaviour, no "Search inside this
 *   photo" card, exactly as before this feature existed. Blank hides the card entirely; see
 *   [com.fotoxplorr.app.lens.LensCard]'s own KDoc.
 * @param onSearchLibrary hands recognised text to [com.fotoxplorr.app.search] to search the
 *   user's own library. Same shape as [onSetLocation] below: null leaves that one action
 *   visibly disabled rather than wired to nothing. See
 *   [com.fotoxplorr.app.lens.LensCard]'s own KDoc.
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
    /** This photo's caption. May have been typed by a person or written by the annotator. */
    caption: String = "",
    /** True when [caption] is the annotator's sentence rather than someone's own words. */
    captionIsMachineWritten: Boolean = false,
    /** Null leaves the caption read-only, the same shape [onSetLocation] uses. */
    onSetCaption: ((String) -> Unit)? = null,
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
            // The activity is edge-to-edge, so the window no longer shrinks for the keyboard on
            // its own; without this the caption field, low in the room, was covered by the
            // keyboard that opened to type into it, caret and all.
            .imePadding()
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
        TextLensBlock(asset = asset, recognizedText = recognizedText, onSearchLibrary = onSearchLibrary)
        TagsBlock(tags = tags, autoTags = autoTags, onRemoveTag = onRemoveTag)
        CaptionBlock(
            photoId = asset.id,
            caption = caption,
            isMachineWritten = captionIsMachineWritten,
            onSetCaption = onSetCaption,
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
        PlaceBlock(
            asset = asset,
            exif = exif,
            reveal = reveal,
            manualLatitude = manualLatitude,
            manualLongitude = manualLongitude,
            onSetLocation = onSetLocation,
            onClearLocation = onClearLocation,
        )
    }
}

/**
 * Filename, date line and the local-storage glyph — the room's first block, and now the only
 * place any of those three facts is stated.
 *
 * [InformationBlock] used to carry "Name" and "Taken" as rows too, a few hundred pixels below a
 * header that had just said both in larger type. The header keeps them because it is the room's
 * *title*: it is what the reader lands on, and a title restated as a field is not a field, it is
 * an echo. The extension that `substringBeforeLast` drops from the title is not lost with the
 * "Name" row — it survives, said better, as the "Kind" row's "HEIF".
 *
 * The storage glyph is the single statement of the trashed/on-device state for the same reason in
 * reverse: it is the only one of the two that speaks in *both* directions. [StatusBlock] used to
 * add "In the trash" beside it, a mark that by construction can only ever appear when the answer
 * is yes.
 */
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
 * The "Search inside this photo" card -- see [com.fotoxplorr.app.lens.LensCard] for everything
 * it actually does; this wrapper only supplies the room's own outer padding and hands through
 * what the room already has in hand rather than fetching anything itself.
 *
 * No [PlaceMorph.textAlpha]-driven fade-in here, on purpose: unlike the plain text blocks around
 * it, this is a CARD, not a line of the room's own prose, and a card arrives rather than fades.
 * It is the only card left in this room now that the camera card has gone, so the precedent it
 * used to borrow from that one is written down here instead.
 *
 * It survives the de-duplication pass untouched because it duplicates nothing: the words inside a
 * photo are not recorded anywhere else in the room, and unlike the camera card it is absent
 * entirely when there is nothing to say rather than headlining a shrug.
 */
@Composable
private fun TextLensBlock(
    asset: MediaAsset,
    recognizedText: String,
    onSearchLibrary: ((String) -> Unit)?,
) {
    if (recognizedText.isBlank()) return
    Box(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
        com.fotoxplorr.app.lens.LensCard(
            asset = asset,
            recognizedText = recognizedText,
            onSearchLibrary = onSearchLibrary,
        )
    }
}

/**
 * Every tag on this photo, with the auto-applied ones drawn as what they are.
 *
 * One list, not two. A person thinks of these as "this photo's tags" regardless of who typed
 * them, and splitting them into "Yours" and "Suggested" sections would make the reader do
 * bookkeeping the app already did. The provenance still shows — an auto tag is outlined rather
 * than filled, and carries no delete affordance of its own beyond the same one a typed tag has —
 * because a person who wants to know which words they chose has to be able to tell.
 *
 * Renders nothing when there are no tags. The precedent is [TextLensBlock] directly above and
 * [StatusBlock]'s own reasoning: an empty section header is a promise the room does not keep.
 */
@Composable
private fun TagsBlock(tags: Set<String>, autoTags: Set<String>, onRemoveTag: ((String) -> Unit)?) {
    if (tags.isEmpty()) return
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RoomEyebrow("TAGS")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Sorted, with the person's own words first. Set iteration order is an accident of
            // insertion, and a list of chips that reshuffles between two openings of the same
            // photo reads as a bug even when nothing changed.
            val ordered = tags.sortedWith(compareBy({ it in autoTags }, { it.lowercase() }))
            ordered.forEach { tag ->
                TagChip(
                    tag = tag,
                    isAuto = tag in autoTags,
                    onRemove = onRemoveTag?.let { remove -> { remove(tag) } },
                )
            }
        }
        if (autoTags.isNotEmpty()) {
            Text(
                text = "Outlined tags were suggested by on-device recognition. Removing one " +
                    "removes it for good — it will not be suggested again.",
                color = MUTED_TEXT,
                style = RoomStyle.Caption,
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
 * The caption row — a real one now.
 *
 * It was a `clickable(enabled = false)` placeholder reading "Add a Caption" for as long as this
 * room has existed, which is worse than absent: it advertised a feature and then did nothing when
 * tapped.
 *
 * The field holds its own draft and commits on focus loss rather than on every keystroke. Writing
 * per character would mean a database write per letter typed, and — because a machine caption is
 * cleared the moment a person edits — would also make the first keystroke silently discard the
 * annotator's sentence before anyone had decided to replace it.
 *
 * Two more commit paths back that one up, because focus loss alone is not guaranteed to fire:
 * leaving the room or the viewer with the field still focused detaches the node, and Compose does
 * not promise a final focus event to a node on its way out. So the draft is also flushed when the
 * block leaves composition or the photo underneath it changes, and on the keyboard's Done. Without
 * those, a caption typed and then dismissed with Back was simply gone, with nothing to say so.
 *
 * @param photoId which photo the draft belongs to. The draft is keyed on this AND the stored
 *   caption: keyed on the caption string alone, two consecutive photos with no caption share the
 *   key "", the remember never resets, and a half-typed draft rides across a page swipe and is
 *   committed to the wrong photo.
 */
@Composable
private fun CaptionBlock(
    photoId: MediaId,
    caption: String,
    isMachineWritten: Boolean,
    onSetCaption: ((String) -> Unit)?,
) {
    if (onSetCaption == null && caption.isBlank()) return

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RoomEyebrow("CAPTION")

        if (onSetCaption == null) {
            Text(text = caption, color = PRIMARY_TEXT, style = RoomStyle.Caption)
        } else {
            // key(photoId): a change of photo DISPOSES this whole editable block and creates a
            // fresh one, rather than recomposing it in place. That is what makes the dispose-time
            // flush below commit to the right photo: an in-place recomposition would have reset
            // the draft to the new photo's caption before the old effect's onDispose ran, so the
            // old photo's unsaved draft compared equal to "nothing typed" and was dropped.
            key(photoId) {
                var draft by remember(caption) { mutableStateOf(caption) }
                // rememberUpdatedState so the flush reads the LATEST draft, and the commit lambda
                // this block last saw -- which, inside the disposed key, is still the old photo's.
                val latestDraft by rememberUpdatedState(draft)
                val latestStored by rememberUpdatedState(caption)
                val latestCommit by rememberUpdatedState(onSetCaption)
                DisposableEffect(Unit) {
                    onDispose {
                        if (latestDraft != latestStored) latestCommit(latestDraft)
                    }
                }
                HyleTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = "Say something about this photo",
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focus ->
                            if (!focus.isFocused && draft != caption) onSetCaption(draft)
                        },
                )
            }
        }

        if (caption.isNotBlank() && isMachineWritten) {
            Text(
                text = "Written by on-device recognition. Type over it and it becomes yours.",
                color = MUTED_TEXT,
                style = RoomStyle.Caption,
            )
        }
    }
}

/**
 * What this photo is to *you*, as distinct from what the file is.
 *
 * Everything else in this room is read off the file — its size, its camera, its coordinates. This
 * block is the library's own knowledge: whether you marked it and whether you hid it. Worth its
 * own group because "is this one of my favourites?" is a question the room could not previously
 * answer at all, even though the viewer already knew.
 *
 * It used to print the album name here as well, as a bare line under the eyebrow with nothing
 * beside it to say what it was — in the render that started the de-duplication pass it read as a
 * mysterious lone word "Camera" under "IN YOUR LIBRARY", while the same value sat a few blocks
 * down as a labelled "Album" row. The labelled row keeps it. "In the trash" went with it: the
 * header's cloud glyph already states that, and states the other half of it too (see
 * [RoomHeader]), which a mark that can only appear when the answer is yes never could.
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
    }
    // No marks means no empty heading: a section that renders as a lone eyebrow over blank space
    // is the same mistake as the bare warning triangle was.
    if (marks.isEmpty()) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = PlaceMorph.textAlpha(reveal()) }
            .padding(horizontal = 20.dp, vertical = 4.dp),
    ) {
        RoomEyebrow("IN YOUR LIBRARY")
        Text(
            text = marks.joinToString(" · "),
            color = RoomStyle.Ink,
            style = RoomStyle.Row,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * The place plate, or an honest absence — and the room's only statement of where this photo was
 * taken.
 *
 * Most libraries are mostly un-geotagged — screenshots, downloads, anything shared through a
 * messenger that strips EXIF, and every file this app's own clean-share export has been through.
 * A plate drawn at 0°,0° for those would be a confident lie, so the block says the fix is
 * missing instead, and says it quietly.
 *
 * That is why [InformationBlock] no longer carries "Latitude" and "Longitude" rows. They were the
 * one pair of labels the de-duplication pass could delete without losing the label's job:
 * [PlacePlate] prints the coordinate under the pin itself, and every branch below already states
 * the absence more loudly than a dash would — the picker draws it as a map still searching and
 * offers to fix it, and the read-only branch names the usual cause in a sentence.
 *
 * (This KDoc used to sit orphaned above [StatusBlock], describing a block two functions away. It
 * belongs here.)
 */
@Composable
private fun PlaceBlock(
    asset: MediaAsset,
    exif: ImageExifDetails,
    reveal: () -> Float,
    manualLatitude: Double?,
    manualLongitude: Double?,
    onSetLocation: ((Double, Double) -> Unit)?,
    onClearLocation: (() -> Unit)?,
) {
    // A hand-placed location stands in for an absent embedded one. It never overrides a real GPS
    // tag: what the camera recorded is a fact about the photograph, and the picker is for photos
    // that have no such fact.
    val latitude = exif.latitude ?: manualLatitude
    val longitude = exif.longitude ?: manualLongitude
    Box(Modifier.padding(horizontal = 20.dp)) {
        if (latitude != null && longitude != null && exif.latitude != null) {
            PlacePlate(
                asset = asset,
                latitude = latitude,
                longitude = longitude,
                reveal = reveal,
            )
        } else if (onSetLocation != null) {
            Box(Modifier.graphicsLayer { alpha = PlaceMorph.textAlpha(reveal()) }) {
                com.fotoxplorr.app.spatial.LocationPicker(
                    latitude = latitude,
                    longitude = longitude,
                    onSet = onSetLocation,
                    onClear = { onClearLocation?.invoke() },
                )
            }
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
 * The file's own facts — and, since the camera card went, *every* one of them.
 *
 * The shape is the owner's second reference screenshot: kind, size, where it lives, when it
 * changed, its pixel dimensions, its colour space. It is also the room's only surface that can
 * say "this file does not record that", which is why the de-duplication pass kept this block
 * whole and deleted the card that repeated a dozen of its rows rather than the other way round —
 * see [PhotoDetailRoom]'s own KDoc for that argument in full.
 *
 * Two consequences of the card's removal live here. "Dynamic range" is a new row: it was the
 * card's second badge and had no row at all, so deleting the card without adding it would have
 * quietly lost the one fact the card carried alone. "Latitude" and "Longitude", by contrast, are
 * gone: [PlacePlate] prints the coordinate under the pin in the block below, and [PlaceBlock]
 * spells the absence out in words when there is no fix, so both the value and the empty case
 * already have a home — and a home that says more than two columns of decimals could.
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
        // Every header is present whether or not the file answers it (owner, 2026-08-18: *"much
        // of the information that the photo needs is not shown -- it's fine if the values are
        // empty, but the headers do need to exist"*). Rows used to be dropped when their value
        // was null, so the room silently changed shape from photo to photo and there was no way
        // to tell "this file has no lens recorded" from "this app does not show lenses".
        RoomEyebrow("FILE", Modifier.padding(bottom = 3.dp))
        // No "Name" row: the room's title is the filename, and RoomHeader's KDoc says why the
        // title wins. The extension the title drops is what "Kind" is.
        InformationRow("Kind", DetailFormatting.formatBadge(asset.mimeType) ?: asset.mimeType)
        // Never null, and deliberately unconditional: "STANDARD" is a real answer here, not a
        // missing one — see DetailFormatting.dynamicRangeBadge on what this can honestly claim.
        InformationRow("Dynamic range", DetailFormatting.dynamicRangeBadge(asset.mimeType))
        InformationRow("Size", DetailFormatting.byteLine(asset.sizeBytes))
        InformationRow("Dimensions", "${asset.width} × ${asset.height}")
        InformationRow("Megapixels", megapixels(asset.width, asset.height))
        InformationRow("Aspect", aspectRatioLabel(asset.width, asset.height))
        if (asset.isVideo) {
            InformationRow(
                "Duration",
                asset.durationMillis.takeIf { it > 0L }?.let(DetailFormatting::durationLine),
            )
        }
        // Album and Where look like the same fact on a phone whose album name is the last segment
        // of its folder path, and they are not: Album is the grouping the library files this photo
        // under and the one the Albums room opens, Where is the folder it physically sits in. Both
        // stay for that reason -- unlike the album line StatusBlock used to print, which was this
        // row's own value said a second time with no label on it.
        InformationRow("Album", asset.bucketName?.takeIf(String::isNotBlank))
        InformationRow("Where", asset.relativePath?.takeIf(String::isNotBlank))
        // No "Taken" row either: the header's date line is this value, in larger type, at the top.
        InformationRow("Modified", DetailFormatting.dateLine(asset.dateModifiedSeconds * 1_000L))
        InformationRow("Colour space", exif.colorSpace)
        // Not a video: MediaImage never decodes a video's frames here, and readImagePalette
        // returns nothing for one anyway — so the row is only worth adding for a still photo.
        if (!asset.isVideo) PaletteRow(asset)

        RoomEyebrow("CAPTURE", Modifier.padding(top = 16.dp, bottom = 3.dp))
        InformationRow("Camera", listOfNotNull(exif.make, exif.model).joinToString(" ").takeIf(String::isNotBlank))
        InformationRow("Lens", exif.lensModel)
        InformationRow("Focal length", exif.focalLengthMm?.takeIf { it > 0.0 }?.let { "${it.roundToInt()} mm" })
        InformationRow("Aperture", exif.aperture?.takeIf { it > 0.0 }?.let(DetailFormatting::apertureText))
        InformationRow("Shutter", exif.shutterSpeed)
        InformationRow("ISO", exif.iso)
        InformationRow("Exposure bias", exif.exposureBiasEv?.let { "${it.roundToInt()} ev" })
        InformationRow("Flash", exif.flash?.let { if (DetailFormatting.flashFired(it)) "Fired" else "Did not fire" })
        // Latitude and Longitude used to close this list. PlaceBlock, directly below, is their
        // single home now -- as a plate with the coordinate on it, or as a sentence saying there
        // is none. See this block's KDoc.
    }
}

/**
 * One label and its value, or a dash where the file had nothing to say.
 *
 * The dash is the point: an absent row tells the reader nothing, while a dashed one tells them
 * this photo does not record a lens — which for a screenshot or a download is the actual answer.
 */
@Composable
private fun InformationRow(label: String, value: String?) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = label,
            color = MUTED_TEXT,
            style = TextStyle(fontSize = 13.sp),
            modifier = Modifier.weight(0.36f),
        )
        Text(
            text = value?.takeIf(String::isNotBlank) ?: "—",
            color = if (value.isNullOrBlank()) MUTED_TEXT else SECONDARY_TEXT,
            style = TextStyle(fontSize = 13.sp),
            modifier = Modifier.weight(0.64f),
        )
    }
}

/**
 * The colour palette, drawn as the segmented bar the owner asked for: one strip, each colour's
 * width its own share of the photo, hex value printed underneath.
 *
 * Laid out like [InformationRow] (same label column, same weights) rather than as its own
 * free-standing block, so it reads as one more fact about the file — which is what it is — instead
 * of a separate feature bolted onto the bottom of the list.
 *
 * Loads asynchronously and starts empty (the dash every other absent row already uses): unlike
 * every other row in this block, which is read straight off [ImageExifDetails] the caller already
 * has in hand, a palette needs the actual pixels decoded first, which [readImagePalette] does off
 * the main thread.
 */
@Composable
private fun PaletteRow(asset: MediaAsset) {
    val context = LocalContext.current
    var swatches by remember(asset.id) { mutableStateOf<List<PaletteSwatch>>(emptyList()) }
    LaunchedEffect(asset.id, asset.contentUriString) {
        swatches = readImagePalette(context, asset)
    }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = "Palette",
            color = MUTED_TEXT,
            style = TextStyle(fontFamily = HyleGrotesk, fontSize = 13.sp),
            modifier = Modifier.weight(0.36f).padding(top = 2.dp),
        )
        if (swatches.isEmpty()) {
            Text(
                text = "—",
                color = MUTED_TEXT,
                style = TextStyle(fontFamily = HyleGrotesk, fontSize = 13.sp),
                modifier = Modifier.weight(0.64f),
            )
        } else {
            Column(modifier = Modifier.weight(0.64f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(14.dp)
                        .clip(RoundedCornerShape(4.dp)),
                ) {
                    swatches.forEach { swatch ->
                        Box(
                            modifier = Modifier
                                // Row.weight requires a positive value; the quantiser only ever
                                // emits a proportion above zero (an empty bucket is never kept),
                                // but the floor guards this draw call against that invariant ever
                                // being loosened upstream without this composable breaking too.
                                .weight(swatch.proportion.coerceAtLeast(MIN_SEGMENT_WEIGHT))
                                .fillMaxHeight()
                                .background(Color(swatch.argb)),
                        )
                    }
                }
                Text(
                    text = swatches.joinToString("   ") { it.hex },
                    color = SECONDARY_TEXT,
                    style = TextStyle(fontFamily = HyleGrotesk, fontSize = 11.sp, letterSpacing = 0.2.sp),
                )
            }
        }
    }
}

private const val MIN_SEGMENT_WEIGHT = 0.001f

/**
 * The photo's dominant colours, as a segmented-bar-ready list. Empty for a video, or when the
 * file cannot be decoded at all (corrupt, or a format `BitmapFactory` does not understand).
 *
 * The photo is decoded at a small fraction of its real size FIRST — see [decodeSampledBitmap] —
 * and only then handed to [PaletteExtractor]. Running a quantiser meant to produce five swatches
 * over a 48-megapixel original would decode tens of megabytes of pixels, spend real CPU time
 * bucketing all of them, and arrive at the exact same five colours a 100-pixel-wide thumbnail
 * already carries — the swatches a viewer perceives "from across the room" do not change with
 * resolution, so paying full-resolution cost for them is pure waste.
 */
internal suspend fun readImagePalette(
    context: Context,
    asset: MediaAsset,
    maxColors: Int = PaletteExtractor.DEFAULT_MAX_COLORS,
): List<PaletteSwatch> {
    if (asset.isVideo) return emptyList()
    return withContext(Dispatchers.IO) {
        runCatching {
            val bitmap = decodeSampledBitmap(context, asset) ?: return@runCatching emptyList()
            try {
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
 * Decode [asset] with its longest edge no greater than [PALETTE_SAMPLE_DIMENSION].
 *
 * Bytes are read into memory once and decoded from that byte array twice — first with
 * `inJustDecodeBounds` to learn the real size without allocating any pixels, then for real with
 * `inSampleSize` set — rather than opening the content stream twice. Re-opening a `content://`
 * stream a second time is not guaranteed cheap: for a cloud-backed provider it can mean a second
 * network fetch of the original file, which is exactly the cost this function exists to avoid.
 * `inSampleSize` only takes powers of two, so this lands at or below the target rather than
 * exactly on it — decoding above budget and scaling down afterwards would mean holding the
 * oversized bitmap first, which is the allocation this whole function is written to avoid.
 */
private fun decodeSampledBitmap(context: Context, asset: MediaAsset): Bitmap? {
    val bytes = context.contentResolver.openInputStream(asset.contentUri)?.use { it.readBytes() }
        ?: return null

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val longestEdge = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)

    var sample = 1
    while (longestEdge / sample > PALETTE_SAMPLE_DIMENSION) sample *= 2

    return BitmapFactory.decodeByteArray(
        bytes, 0, bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample },
    )
}

/**
 * The target longest edge for palette sampling. A segmented bar only ever shows up to
 * [PaletteExtractor.DEFAULT_MAX_COLORS] swatches, and 100px on the long edge is already tens of
 * thousands of sample pixels for the quantiser to work with — far more than five colours need to
 * be measured accurately, and small enough that decoding it costs milliseconds, not seconds.
 */
private const val PALETTE_SAMPLE_DIMENSION = 100

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

/**
 * EXIF fields for the room's CAPTURE section. All optional: many images (esp. screenshots, edited
 * copies, or anything that already had EXIF stripped by this app's own clean-share export)
 * won't carry camera data — which is why those fields are rows with labels and a dash rather than
 * a card that has to invent a headline when the file says nothing.
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
