@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.fotoxplorr.app.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.util.lerp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.adaptive.PINCH_STEP_THRESHOLD
import com.fotoxplorr.app.gallery.ViewerPinchMode
import com.fotoxplorr.app.lift.LiftOverlay
import com.fotoxplorr.app.lift.canLiftSubject
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaImage
import com.fotoxplorr.app.palette.PaletteMemo
import com.fotoxplorr.app.palette.PaletteSwatch
import com.fotoxplorr.app.spatial.ExpandedPhotoMap
import com.fotoxplorr.app.spatial.GeoIndexState
import com.fotoxplorr.app.spatial.MapFocus
import com.fotoxplorr.core.model.MediaId
import dev.aarso.cellshell.ParkStyle
import dev.aarso.cellshell.RoomEdge
import dev.aarso.cellshell.SpatialShell
import dev.aarso.cellshell.rememberSpatialController
import kotlin.math.ln
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The full-screen viewer, as a spatial shell with three rooms around it: what this photo is (and
 * where it was taken) at the TOP, in [PhotoDetailRoom], pulled DOWN over the photo; the nine photo
 * actions at the right; and settings at the bottom -- with the photo itself still alive on the
 * parked card behind whichever one is open. The details room replaces two separate surfaces that
 * used to say overlapping things about the same file: a bottom `MetadataPanel` behind an "Info"
 * button, and a full-screen Material details screen behind a "Details" button. Two buttons, two
 * layouts and two half-answers about one photo is exactly the drift `docs/fonebrew-navigation.md`
 * describes; the room is the single answer.
 *
 * **This screen deviates from the app's one geography, on purpose.** The gallery's rule is TOP =
 * settings, BOTTOM = what-this-is (owner, 2026-08-18: *"the model needs to remain the same"*, see
 * `GalleryScreen`), and the viewer followed it until the owner's design-fidelity reference put the
 * photo's details at the top: *pull the photo down* for what it is. The viewer now does that, and
 * settings take the bottom edge; the gallery's rooms are untouched. Recorded in
 * `docs/handoff/DESIGN-FIDELITY-VIEWER.md` as a documented deviation rather than quietly left to
 * drift.
 *
 * The left edge has no room, so the shell refuses drags from it and draws no peek there.
 */
@Composable
fun ViewerScreen(
    asset: MediaAsset,
    position: Int,
    total: Int,
    isFavorite: Boolean,
    isSensitive: Boolean,
    hasPrevious: Boolean,
    hasNext: Boolean,
    canMoveToTrash: Boolean,
    slideshowActive: Boolean,
    slideshowIntervalSeconds: Int,
    onToggleSlideshow: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleSensitive: () -> Unit,
    onShare: () -> Unit,
    onEdit: () -> Unit,
    onOpenWith: () -> Unit,
    onMoveToTrash: () -> Unit,
    /** "Share with options…" -- see [ViewerActionsRoom]'s own doc. Null hides the row. */
    onShareClean: (() -> Unit)? = null,
    /** Renames this photo/video, previously reachable only from the gallery's own selection. */
    onRename: (() -> Unit)? = null,
    /** See [com.fotoxplorr.app.media.MediaAsset.isExternal]. */
    isExternal: Boolean = false,
    /** Video's own Save As — re-encodes [asset] to H.264/AAC/MP4. Null (the default) leaves the
     *  action hidden; see [ViewerActionsRoom]'s own doc. Meaningless for a still photo, so a
     *  caller need not check [MediaAsset.isVideo] itself before wiring this in. */
    isConvertingToMp4: Boolean = false,
    onConvertToMp4: (() -> Unit)? = null,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    /** Blur-sensitive preference, surfaced in the top room so it is adjustable where it bites. */
    blurSensitive: Boolean = true,
    /** Hold the screen awake while this screen is up. */
    keepScreenOn: Boolean = false,
    /** Whether the filmstrip appears along the bottom with the rest of the chrome. */
    showFilmstrip: Boolean = true,
    /**
     * What a pinch means on this screen. [ViewerPinchMode.OPTICAL_ZOOM] (the default, and this
     * screen's original behaviour) zooms the photo, unchanged for anyone who never opens this
     * setting. [ViewerPinchMode.PANEL_SHORTCUTS] repurposes the SAME gesture entirely: pinch-in
     * opens [PhotoDetailRoom] (the TOP room, pulled down), pinch-out opens [ViewerActionsRoom]
     * (the right room), and neither also zooms -- see [ViewerPinchMode]'s own doc for why the two
     * are mutually exclusive within one gesture.
     */
    pinchMode: ViewerPinchMode = ViewerPinchMode.OPTICAL_ZOOM,
    onSetPinchMode: (ViewerPinchMode) -> Unit = {},
    /** A location the user placed by hand, for a photo whose file carries no GPS tag. */
    manualLatitude: Double? = null,
    manualLongitude: Double? = null,
    onSetLocation: ((Double, Double) -> Unit)? = null,
    onClearLocation: (() -> Unit)? = null,
    /** Embeds the hand-placed location into the file's own EXIF -- see
     *  [com.fotoxplorr.app.spatial.LocationPicker]'s own doc. Null hides the action. */
    onWriteLocationIntoFile: (() -> Unit)? = null,
    /** Play a slideshow in a random order rather than the browsing order. */
    slideshowShuffle: Boolean = false,
    /**
     * Text the offline recognition pass read out of THIS photo, positioned.
     *
     * Defaulted to empty so every existing call site keeps working and a photo with no recognised
     * text simply has no text layer, rather than an invisible one intercepting taps.
     */
    liveTextBlocks: List<com.fotoxplorr.app.recognition.TextBlock> = emptyList(),
    /**
     * Search the library for text read off this photo, from the details room's Search pill.
     *
     * Leaving the viewer is the caller's job, not this screen's: the pill hands over a string and
     * says nothing about where the results should appear, so a caller that wants search results
     * on some other surface is not fighting a close() this screen decided to do on its behalf.
     * Null leaves the pill visibly disabled -- see [com.fotoxplorr.app.lens.LensCard].
     */
    onSearchLibrary: ((String) -> Unit)? = null,
    /** This photo's stored palette, for the details room's swatches. See [PhotoDetailRoom]. */
    palette: List<PaletteSwatch> = emptyList(),
    /**
     * Every indexed photo's dominant colour, for the expanded map's dots
     * (`RecognitionIndex.dominantColoursByMedia`). Merged with [PaletteMemo] as the map opens.
     */
    dotColors: Map<MediaId, Int> = emptyMap(),
    /** This photo's tags and caption, for the details room. See [PhotoDetailRoom]. */
    tags: Set<String> = emptySet(),
    autoTags: Set<String> = emptySet(),
    onRemoveTag: ((String) -> Unit)? = null,
    caption: String = "",
    captionIsMachineWritten: Boolean = false,
    onSetCaption: ((String) -> Unit)? = null,
    /**
     * Editors for this photo's own FILE-embedded metadata — its current value is read off
     * [PhotoDetailRoom]'s own `exif` parameter, already loaded by this screen just below, not
     * passed in again here. See [PhotoDetailRoom].
     */
    onSetRating: ((Int) -> Unit)? = null,
    onSetCreator: ((String) -> Unit)? = null,
    onSetCopyright: ((String) -> Unit)? = null,
    onEmbedKeywords: (() -> Unit)? = null,
    /**
     * Bumped by the caller every time a metadata write actually lands on this asset's file, so
     * the EXIF/XMP re-read below picks it up. [exif] is otherwise only reloaded when [asset]
     * itself changes — a rating tapped or a creator typed would otherwise sit on screen showing
     * its OLD value until the viewer moved to a different photo and back, since nothing else here
     * observes a write this screen did not itself perform.
     */
    metadataRevision: Int = 0,
    /** Let GIFs and animated images play. */
    loopAnimations: Boolean = false,
    /**
     * Whether [asset] itself actually animates (P0-14's [com.fotoxplorr.app.formats.AnimationIndex],
     * sniffed from real bytes) -- independent of [loopAnimations], which is a grid-density
     * preference about decoding many tiles at once and has no equivalent cost here: the viewer
     * shows exactly one photo at a time, so a genuinely animated photo the user opened full-screen
     * plays regardless of that setting, the same way opening a GIF in most photo viewers does.
     */
    animated: Boolean = false,
    /** Start videos without waiting for a tap on play. On by default (owner, #1): a video that
     *  opens and waits is indistinguishable from one that is broken. */
    autoplayVideos: Boolean = true,
    onSetSlideshowInterval: (Int) -> Unit = {},
    onSetBlurSensitive: (Boolean) -> Unit = {},
    onSetShowFilmstrip: (Boolean) -> Unit = {},
    onSetKeepScreenOn: (Boolean) -> Unit = {},
    onSetSlideshowShuffle: (Boolean) -> Unit = {},
    onSetLoopAnimations: (Boolean) -> Unit = {},
    onSetAutoplayVideos: (Boolean) -> Unit = {},
    /**
     * The assets being paged through, feeding the filmstrip in the bottom room. Empty is a safe
     * fallback -- the strip hides itself below two items.
     */
    relatedAssets: List<MediaAsset> = emptyList(),
    /** Jump straight to an asset in [relatedAssets], from the filmstrip. */
    onSelectAsset: (MediaAsset) -> Unit = {},
    /**
     * False for an ad-hoc asset opened from outside this app's own library (P0-18's
     * `ExternalViewerActivity`, for a file this app never scanned and must never write anything
     * about to any store -- catalogue, geo, recognition or favourites). Hides or disables
     * favourite, sensitive, trash, tags, captions, location set/clear, edit, convert-to-MP4 and
     * moments export; Share (through `SharePreparer`, which works on any readable URI) and "Open
     * with" both stay, since neither depends on the asset being catalogued.
     */
    catalogueActions: Boolean = true,
    /** Adds a tag typed into the details room. Null leaves the "Add Tags…" line inert. */
    onAddTag: ((String) -> Unit)? = null,
    /**
     * The library's geo index and the library itself, for the map the details room's plate grows
     * into: every located photo becomes a dot on it. Empty by default, so a caller with no library
     * (the external viewer) gets a map with only this photo on it.
     */
    geoState: GeoIndexState = GeoIndexState(),
    libraryAssets: List<MediaAsset> = emptyList(),
    /** Test seam for the plate's map backdrop; null is the flavour's own. See [PhotoDetailRoom]. */
    plateMapBackdrop: (@Composable BoxScope.(settled: Boolean) -> Unit)? = null,
) {
    // Chrome starts HIDDEN for a photo and stays hidden across a swipe -- and starts SHOWN the
    // first time a video opens.
    //
    // It used to default to visible AND be keyed on asset.id, so it reset to visible on every
    // photo change -- immersive was unreachable for more than one photo at a time. Not keyed on
    // the asset any more: a preference to see the position counter is a preference about the
    // viewer, not about a file (owner, 2026-08-14: "Immersive! Immersive! Immersive!").
    //
    // The exception is the first video (owner, #1, "Fotoz is not playing videos"): a video that
    // opens into bare black with no visible transport gave no sign that it could be played, so
    // the first one this viewer shows brings the chrome up. After that it is the person's to hide.
    var chromeVisible by remember { mutableStateOf(ViewerChromePolicy.initialChromeVisible(asset.isVideo)) }
    var videoChromeRevealed by remember { mutableStateOf(asset.isVideo) }
    LaunchedEffect(asset.id) {
        if (ViewerChromePolicy.revealChromeForVideo(asset.isVideo, videoChromeRevealed)) {
            chromeVisible = true
            videoChromeRevealed = true
        }
    }
    // True while THIS asset is a video that is playing or about to, as reported by VideoPlayer
    // (which always reports false again when it leaves). Keyed on the asset so a value from the
    // clip just paged away from can never hold the slideshow on a photo.
    var videoPlaying by remember(asset.id) { mutableStateOf(false) }

    var scale by remember(asset.id) { mutableFloatStateOf(1f) }
    var offsetX by remember(asset.id) { mutableFloatStateOf(0f) }
    var offsetY by remember(asset.id) { mutableFloatStateOf(0f) }
    var rotation by remember(asset.id) { mutableFloatStateOf(0f) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    // Lifting a subject: long-press the photo, or "Lift subject" in the actions room. Keyed on
    // asset.id like every other per-photo gesture state here, so paging to the next photo cannot
    // leave a stale "picking a subject" overlay armed over a photo the user never long-pressed.
    var liftActive by remember(asset.id) { mutableStateOf(false) }
    // Whether a subject can be lifted from THIS asset at all (not a video, SVG, RAW or an
    // animated image): gates both the long press and the actions-room row, so neither offers an
    // editor that could only report that it cannot open the photo.
    val animatedImage = ViewerChromePolicy.isAnimatedImage(animated, asset.mimeType)
    val canLift = remember(asset, animatedImage) { canLiftSubject(asset, animatedImage) }
    // The tap detector below is keyed on the asset alone and captures what it reads when it
    // starts, so anything that can change under it without the asset changing -- the animated flag
    // once the sniff lands, the recognised text once OCR finishes -- is read through these.
    val liftable by rememberUpdatedState(canLift)
    val recognisedBlockCount by rememberUpdatedState(liveTextBlocks.size)
    val shell = rememberSpatialController()
    // Picture-in-picture shrinks the whole window to a small video: nothing drawn over the photo
    // (the position chip, the filmstrip) belongs in it. VideoPlayer hides its own transport the
    // same way; the host's chrome has to ask for itself because it is not inside that composition.
    val isInPip by rememberIsInPictureInPicture()
    // While a lift owns the screen the rooms are withdrawn (null slots): the editor consumes every
    // touch that lands on it, but the shell claims edge drags on an earlier pass than any child
    // sees, so without this an edge pull would open a room under the editor. Held until the shell
    // is home so a room that was just closed to start the lift slides away instead of vanishing.
    val roomsWithdrawn by remember(shell, asset.id) {
        derivedStateOf { ViewerChromePolicy.roomsWithdrawn(lifting = liftActive, atHome = shell.atHome) }
    }
    // True while the user is moving the filmstrip (a finger on it, or its fling). Pauses a playing
    // video and keeps the next one from starting until the strip settles -- see VideoPlayer's
    // scrubSuspended. Reset by the strip itself when it leaves composition mid-gesture.
    var scrubbing by remember { mutableStateOf(false) }
    // The filmstrip's one rule, in one place, and NOT a function of the current asset: see
    // ViewerChromePolicy.filmstripShown for why a strip gated on "is this a video" cancels the
    // drag that scrubbed onto the video. The same flag sizes the video transport's bottom inset
    // below, so the two can never disagree about whether the strip is there.
    val filmstripShown = ViewerChromePolicy.filmstripShown(
        chromeVisible = chromeVisible,
        inPictureInPicture = isInPip,
        showFilmstripPreference = showFilmstrip,
        rollSize = relatedAssets.size,
        lifting = liftActive,
    )

    // ── The shrink-to-map flight (owner, #19) ─────────────────────────────────────────────────
    //
    // Pulling the details room down shrinks the photo and lands it as the pin on the map in the
    // room's place block -- a shrink-to-appear, explicitly not a tilt of the pane. Three parts:
    //
    // 1. The TOP room parks the home card with a flat SLIDE; every other room keeps the SWIVEL.
    //    Chosen here, app-side, from the shell's own progress: ParkStyle is an ordinary parameter
    //    the shell reads each composition, and the swivel angle is multiplied by `lift`, which is
    //    ~0 at the instant this flips, so there is no visible pop. A derived flag, so this screen
    //    recomposes when the room starts and stops being pulled, not on every drag frame. The
    //    flier follows the card's rectangle and a tilted card has none.
    // 2. One PhotoToPinFlight above the shell, a pure function of the shell's vertical progress,
    //    aimed at the pin tile's rectangle that the room reports (held in a plain holder).
    // 3. While the flier is up the room's pin waits beneath it instead of playing its own
    //    in-plate shrink. A zoomed or turned photo has no honest resting rectangle to lift off
    //    from, so it keeps the in-plate shrink (flightActive false) -- see ViewerChromePolicy.
    //
    // No map tile is fetched or warmed by any of this: the map behind the pin is still the
    // one-shot snapshot that SETTLE_DEBOUNCE_MILLIS gates, below.
    val topPulling by remember(shell) { derivedStateOf { shell.vProgress > 0.001f } }
    val photoAtRest by remember(asset.id) { derivedStateOf { ViewerChromePolicy.photoAtRest(scale, rotation) } }
    val flightActive = ViewerChromePolicy.flightActive(topPulling, photoAtRest, containerSize.width, containerSize.height)
    // Where the pin tile sits in the room, as the room reports it. A snapshot-backed holder read
    // only from draw/layer lambdas, so a late report repaints the flier without recomposing; a
    // fresh one per photo so one photo's position is never used for the next.
    val pinHolder = remember(asset.id) { PinRectHolder() }
    // Where the photo is drawn at rest, in the shell's coordinates. The home box fills the shell,
    // so this is the photo's Fit rectangle in that box. From the stored dimensions, like the
    // minimap and the text layer: an EXIF-rotated file whose stored size is the unrotated one
    // starts the flight from a rectangle of the wrong shape (a device check).
    val photoRect = remember(asset.width, asset.height, containerSize) {
        PlaceMorph.fitRect(
            containerSize.width.toFloat(), containerSize.height.toFloat(),
            asset.width.toFloat(), asset.height.toFloat(),
        )
    }
    val onPinPositioned = remember(pinHolder) { { rect: Rect -> pinHolder.rect = rect } }
    val density = LocalDensity.current

    // Read here rather than inside the room: the shell only composes a room once it is slightly
    // open, so reading it there would start the EXIF load on the first pixel of the pull and
    // leave the card empty for the rest of the gesture.
    val context = LocalContext.current
    var exif by remember(asset.id) { mutableStateOf(ImageExifDetails()) }
    LaunchedEffect(asset.id, metadataRevision) {
        exif = readImageExifDetails(context, asset)
    }

    // A room is not a back-stack entry, but Back is the gesture people reach for to leave one.
    // Disabled at home so the activity's own handler still closes the viewer.
    BackHandler(enabled = !shell.atHome) { shell.closeAll() }

    // Cleared on dispose, not just toggled off: leaving FLAG_KEEP_SCREEN_ON set behind a closed
    // viewer would hold the whole app awake for as long as it stayed in the foreground.
    val view = LocalView.current
    DisposableEffect(keepScreenOn, view) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    // The countdown restarts whenever any of its inputs changes, which is what lets a video play to
    // its end and THEN get a normal interval, rather than being cut off by a timer that started
    // when the clip did. See ViewerChromePolicy.slideshowMayAdvance.
    val roomOpen = shell.anyRoomVisible
    LaunchedEffect(asset.id, slideshowActive, slideshowIntervalSeconds, roomOpen, videoPlaying, liftActive) {
        if (ViewerChromePolicy.slideshowMayAdvance(slideshowActive, total, roomOpen, videoPlaying, liftActive)) {
            delay(slideshowIntervalSeconds.coerceAtLeast(2) * 1_000L)
            onNext()
        }
    }

    // "Settled": the details room fully open AND at rest, with no finger on it -- the only moment
    // PlacePlate lets the connect flavour fetch its one-shot map. The controller cannot say
    // whether a finger is down, so this waits a beat after the room reports open: a finger that
    // pulled the room to its stop and is still holding it will move again (and un-settle it)
    // long before the debounce lands, and a release that settles at full open lands just after.
    val detailsOpen = shell.openRoom == RoomEdge.TOP
    var detailsSettled by remember { mutableStateOf(false) }
    LaunchedEffect(detailsOpen) {
        detailsSettled = false
        if (detailsOpen) {
            delay(SETTLE_DEBOUNCE_MILLIS)
            detailsSettled = true
        }
    }

    // The expanded map: grows out of the plate's own rectangle (shared-element style) into the
    // whole screen, and Back shrinks it back. The origin is remembered per photo so a map opened
    // on one photo cannot survive a page swipe onto another.
    var mapOrigin by remember(asset.id) { mutableStateOf<Rect?>(null) }
    val mapExpansion = remember { Animatable(0f) }
    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }
    val scope = rememberCoroutineScope()
    val collapseMap: () -> Unit = {
        scope.launch {
            mapExpansion.animateTo(0f, EXPAND_SPEC)
            mapOrigin = null
        }
    }
    LaunchedEffect(mapOrigin) {
        if (mapOrigin != null) mapExpansion.animateTo(1f, EXPAND_SPEC) else mapExpansion.snapTo(0f)
    }
    BackHandler(enabled = mapOrigin != null) { collapseMap() }

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { overlayOrigin = it.positionInRoot() },
    ) {
    SpatialShell(
        controller = shell,
        accentColor = MaterialTheme.colorScheme.primary,
        // The rooms sit on the same black the photo does, so opening one reads as the surface
        // moving rather than as a different screen appearing behind it.
        scrimColor = Color.Black,
        cardColor = Color.Black,
        modifier = Modifier.fillMaxSize(),
        // Shrink AND swivel (owner, 2026-08-14). The card turns about the hinge edge that
        // stays on screen, so opening a room reads as a panel swinging away rather than a
        // rectangle sliding off -- the Magic Portal shape. The shrink is kept; the swivel is
        // added to it. EXCEPT for the top room (owner, #19: "not a pane tilt, rather a
        // shrink-to-appear-on-the-map effect"): there the card slides flat and the photo's
        // copy flies to the map pin. See the flight notes above.
        parkStyle = if (topPulling) ParkStyle.SLIDE else ParkStyle.SWIVEL,
        // TOP = what this photo is, pulled DOWN over it (owner's reference, 6.png). See this
        // screen's KDoc for the deviation from the gallery's geography this is.
        top = roomSlot(!roomsWithdrawn) {
            PhotoDetailRoom(
                asset = asset,
                exif = exif,
                isFavorite = isFavorite,
                isSensitive = isSensitive,
                manualLatitude = manualLatitude,
                manualLongitude = manualLongitude,
                onSetLocation = if (catalogueActions) onSetLocation else null,
                onClearLocation = if (catalogueActions) onClearLocation else null,
                onWriteLocationIntoFile = if (catalogueActions) onWriteLocationIntoFile else null,
                // Derived from the blocks this screen already receives rather than taken as a
                // second parameter: two sources for the same text is how they drift apart, and
                // the overlay and the details card must never disagree about what the photo says.
                recognizedText = liveTextBlocks.joinToString("\n") { it.text },
                onSearchLibrary = onSearchLibrary,
                palette = palette,
                tags = if (catalogueActions) tags else emptySet(),
                autoTags = if (catalogueActions) autoTags else emptySet(),
                onRemoveTag = if (catalogueActions) onRemoveTag else null,
                onAddTag = if (catalogueActions) onAddTag else null,
                caption = if (catalogueActions) caption else "",
                captionIsMachineWritten = captionIsMachineWritten,
                onSetCaption = if (catalogueActions) onSetCaption else null,
                onSetRating = onSetRating,
                onSetCreator = onSetCreator,
                onSetCopyright = onSetCopyright,
                onEmbedKeywords = onEmbedKeywords,
                settled = detailsSettled,
                flightActive = flightActive,
                onPinPositioned = onPinPositioned,
                onExpandMap = { from -> mapOrigin = from },
                plateMapBackdrop = plateMapBackdrop,
                // POSITIVE, and this is not cosmetic. A TOP room opens with vProgress running
                // POSITIVE (SpatialMotion's sign convention: +1 = TOP open, -1 = BOTTOM open),
                // while PlaceMorph.stagger clamps its input to 0..1 -- so feeding a negated
                // value, as the room needed when it was the BOTTOM room, would hold every
                // stagger at zero and render the plate and its text at alpha 0. The room would
                // be *invisible*, with nothing thrown and PlaceMorphTest still green.
                reveal = { shell.vProgress },
            )
        },
        right = roomSlot(!roomsWithdrawn) {
            ViewerActionsRoom(
                isFavorite = isFavorite,
                isSensitive = isSensitive,
                canMoveToTrash = canMoveToTrash,
                slideshowActive = slideshowActive,
                // Every action closes the room on its way out. Leaving it open would mean the
                // result of the action -- a favourite mark, a trash confirmation -- landing
                // behind the panel that triggered it.
                onToggleSlideshow = { shell.closeAll(); onToggleSlideshow() },
                onToggleFavorite = onToggleFavorite,
                onToggleSensitive = onToggleSensitive,
                onShare = { shell.closeAll(); onShare() },
                onShareClean = onShareClean?.let { clean -> { shell.closeAll(); clean() } },
                onEdit = { shell.closeAll(); onEdit() },
                onOpenWith = { shell.closeAll(); onOpenWith() },
                onRename = onRename?.let { rename -> { shell.closeAll(); rename() } },
                onMoveToTrash = { shell.closeAll(); onMoveToTrash() },
                isVideo = asset.isVideo,
                isConvertingToMp4 = isConvertingToMp4,
                onConvertToMp4 = onConvertToMp4?.let { convert -> { shell.closeAll(); convert() } },
                catalogueActions = catalogueActions,
                isExternal = isExternal,
                // Not behind catalogueActions: lifting reads only the photo's URI and writes a
                // sticker file only when asked, so it is as valid on a photo opened from another
                // app. Closing the room first hands the screen to the editor cleanly.
                onLiftSubject = if (canLift) ({ shell.closeAll(); liftActive = true }) else null,
            )
        },
        // BOTTOM = settings, pulled UP from the bottom edge. It was the top room until the
        // details took that edge -- see this screen's KDoc.
        bottom = roomSlot(!roomsWithdrawn) {
            ViewerSettingsRoom(
                slideshowIntervalSeconds = slideshowIntervalSeconds,
                blurSensitive = blurSensitive,
                showFilmstrip = showFilmstrip,
                keepScreenOn = keepScreenOn,
                slideshowShuffle = slideshowShuffle,
                loopAnimations = loopAnimations,
                autoplayVideos = autoplayVideos,
                onSetSlideshowInterval = onSetSlideshowInterval,
                onSetBlurSensitive = onSetBlurSensitive,
                onSetShowFilmstrip = onSetShowFilmstrip,
                onSetKeepScreenOn = onSetKeepScreenOn,
                onSetSlideshowShuffle = onSetSlideshowShuffle,
                onSetLoopAnimations = onSetLoopAnimations,
                onSetAutoplayVideos = onSetAutoplayVideos,
                pinchMode = pinchMode,
                onSetPinchMode = onSetPinchMode,
            )
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onSizeChanged { containerSize = it }
                // ONE gesture handler, not three stacked ones.
                //
                // This screen used to layer detectHorizontalDragGestures, detectTapGestures and
                // transformable as three separate pointerInput nodes. `transformable` was last,
                // so it was innermost and saw events first; its detector computes pan from the
                // CENTROID, which for a single finger is that finger, so a one-finger drag was
                // read as a pan and consumed before the swipe detector ever ran. Swipe-to-page
                // was therefore unreachable code. And because pan was discarded at scale 1, the
                // photo did not move either -- which is why the screen read as having no
                // gestures at all rather than as having a broken one.
                //
                // Arbitrating zoom, rotate, pan and page in a single loop is the only way to
                // decide between them with the whole picture: pointer count and current scale
                // both matter, and neither is visible to a detector that has already consumed.
                // Keyed on pinchMode too, not just asset.id: this is a suspend coroutine that
                // runs for as long as its key set is stable, so a mode flip mid-viewing has to
                // restart it or the closure below would keep arbitrating gestures by whichever
                // mode was in force when the viewer first opened.
                .pointerInput(asset.id, pinchMode) {
                    detectViewerGestures(
                        scaleProvider = { scale },
                        pinchMode = pinchMode,
                        onTransform = { zoomChange, panChange, rotationChange ->
                            val next = (scale * zoomChange).coerceIn(MIN_SCALE, MAX_SCALE)
                            rotation += rotationChange
                            if (next <= 1.001f && rotation == 0f) {
                                offsetX = 0f
                                offsetY = 0f
                            } else {
                                offsetX += panChange.x
                                offsetY += panChange.y
                            }
                            scale = next
                        },
                        onPage = { forward ->
                            if (forward && hasNext) onNext() else if (!forward && hasPrevious) onPrevious()
                        },
                        // PANEL_SHORTCUTS only -- see ViewerPinchMode's own doc. Reuses the SAME
                        // rooms the shell's own edge-drag gestures already open (ViewerSettingsRoom's
                        // own "GESTURES" caption: "Drag the photo down for its details, in from
                        // the right for what you can do with it"), so this is a second way IN to
                        // a surface that already exists, not a new one. Pinch IN = the details,
                        // which are the TOP room now.
                        onPinch = { direction ->
                            when (direction) {
                                PinchDirection.IN -> shell.open(RoomEdge.TOP)
                                PinchDirection.OUT -> shell.open(RoomEdge.RIGHT)
                            }
                        },
                        onGestureEnd = {
                            // Free rotation while the fingers are down, squared up on release:
                            // a photo resting at 7 degrees reads as a bug, not as a choice.
                            rotation = snapRotation(rotation)
                            if (scale <= 1.001f) {
                                offsetX = 0f
                                offsetY = 0f
                            }
                        },
                    )
                }
                .pointerInput(asset.id) {
                    detectTapGestures(
                        onTap = { chromeVisible = !chromeVisible },
                        onDoubleTap = {
                            if (!asset.isVideo) {
                                if (scale > 1.05f || rotation != 0f) {
                                    scale = 1f
                                    rotation = 0f
                                    offsetX = 0f
                                    offsetY = 0f
                                } else {
                                    scale = DOUBLE_TAP_SCALE
                                }
                            }
                        },
                        // Long-press enters lift mode. Added as a THIRD callback on this SAME
                        // detectTapGestures call, deliberately not as a second, separate
                        // `pointerInput { detectTapGestures(...) }` block: two independent
                        // detectTapGestures detectors racing to consume the same down is exactly
                        // the "several stacked detectors" failure this file's header comment
                        // already describes fixing once for zoom/pan/page vs tap. One detector
                        // with three callbacks has no such race -- detectTapGestures arbitrates
                        // tap vs double-tap vs long-press internally, against its own single
                        // stream, and this screen never has to reason about ordering between two
                        // detectTapGestures instances at all.
                        //
                        // Guarded off whenever LiveTextOverlay is up (chrome visible AND this
                        // photo has recognised text): that layer is a later sibling inside the
                        // same photo Box below, so it is hit-tested first and already consumes
                        // every tap and long-press within its bounds -- bare photo included, per
                        // its own "a tap on bare photo clears [selection]" behaviour. Without
                        // this guard a long press over live text would not reliably do EITHER
                        // thing, a dead zone rather than a conflict. The guard now sits behind
                        // ViewerChromePolicy.longPressStartsLift, which also refuses anything
                        // that cannot be lifted (video, SVG, RAW, animated). Lift stays reachable
                        // for a photo with text through the actions room's "Lift subject" row,
                        // which is why the dead zone is acceptable rather than a loss of the
                        // feature; hiding the chrome brings the long press back as well.
                        onLongPress = {
                            if (ViewerChromePolicy.longPressStartsLift(
                                    liftable, chromeVisible, recognisedBlockCount,
                                )
                            ) {
                                liftActive = true
                            }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            if (asset.isVideo) {
                // key(asset.id): one VideoPlayer instance per video, not one reused across the
                // roll. VideoPlayer hands its VideoView to AndroidView's factory, and AndroidView
                // calls that factory exactly once for the life of the node -- so without this,
                // swiping from one clip to the next left the OLD, stopped VideoView on screen
                // while the new one it had built for the next clip was never attached: a black
                // or frozen frame, and a play button that flips to pause with nothing playing.
                key(asset.id) {
                    VideoPlayer(
                        asset = asset,
                        modifier = Modifier.fillMaxSize(),
                        chromeVisible = chromeVisible,
                        autoplayVideos = autoplayVideos,
                        catalogueActions = catalogueActions,
                        // Offered next to Retry when playback fails; the same action the actions
                        // room's "Open with" row runs.
                        onOpenWith = onOpenWith,
                        onPlaybackActive = { videoPlaying = it },
                        // Sits the transport above the filmstrip band instead of under it.
                        bottomInset = ViewerChromePolicy.videoBottomInsetDp(filmstripShown).dp,
                        scrubSuspended = scrubbing,
                    )
                }
            } else {
                // The photo and its text layer share ONE transform box. Putting the overlay
                // inside the same graphicsLayer is what keeps the OCR boxes glued to the words
                // under pinch, drag and rotate without the overlay knowing anything about the
                // current gesture state -- the only arrangement that cannot drift out of
                // alignment as the gesture code changes. The lift editor is NOT in this box: it
                // draws its own stage and the decoded photo with its own viewport, so living
                // under the pinch/rotate transform would show it zoomed and turned for no
                // reason; it sits beside this box, below.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            rotationZ = rotation
                            translationX = offsetX
                            translationY = offsetY
                        },
                ) {
                    MediaImage(
                        asset = asset,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                        // A GIF plays even before AnimationIndex has sniffed it (P0-14's own
                        // stated fallback) -- almost every real GIF a user has IS animated, and a
                        // static one costs nothing extra through the animated decode path when
                        // only one image is ever on screen at a time, unlike a grid tile.
                        animate = animatedImage,
                    )

                    // Text found by the offline recognition pass, selectable in place. Only while
                    // the chrome is up: the viewer's whole premise is that a photo is unobstructed
                    // until asked otherwise, and tap targets over the picture are an obstruction
                    // even when they are invisible.
                    if (chromeVisible && liveTextBlocks.isNotEmpty()) {
                        LiveTextOverlay(
                            blocks = liveTextBlocks,
                            imageWidth = asset.width,
                            imageHeight = asset.height,
                        )
                    }
                }
            }

            // The lift editor, over the whole content area and OUTSIDE the zoom transform above. It
            // paints an opaque stage and the decoded photo itself, maps touches against that
            // bitmap's own size, and consumes every touch that lands on it, so the gestures on
            // this Box never see them. Renders nothing at all while `liftActive` is false -- see
            // LiftOverlay's own KDoc for why that, not an internal armed/disarmed flag, is what
            // keeps it from contending with them. Its Back handler registers after the shell's
            // (it composes inside the shell's content), and the most recent enabled one wins.
            LiftOverlay(
                asset = asset,
                active = liftActive,
                onDismiss = { liftActive = false },
                modifier = Modifier.fillMaxSize(),
            )

            // All that is left over the photo, and only when asked for: where you are in the
            // run. The nine actions are the right room, the facts are the top room, and the
            // settings are the bottom room -- none of them costs the photo a pixel until pulled.
            if (ViewerChromePolicy.chromeShown(chromeVisible, isInPip, liftActive)) {
                ViewerPositionChip(
                    asset = asset,
                    position = position,
                    total = total,
                    onClose = onClose,
                    modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding(),
                )
            }

            // Only meaningful once the user has actually zoomed in. Not gated on the chrome: a
            // navigator that only appears when the chrome does is a navigator nobody finds while
            // zoomed, and the owner's reference shows it over the bare photo. It is the one
            // thing drawn over the photo without asking, and only while the photo is zoomed.
            if (!asset.isVideo && !liftActive && scale > MINIMAP_SCALE_THRESHOLD) {
                ZoomMinimap(
                    asset = asset,
                    scale = { scale },
                    offsetX = { offsetX },
                    offsetY = { offsetY },
                    containerSize = containerSize,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .navigationBarsPadding()
                        .padding(start = MINIMAP_INSET_START, bottom = MINIMAP_INSET_BOTTOM),
                )
            }

            // Back on the photo itself, not in the details room (owner, second round,
            // 2026-08-14: "The filmstrip has to appear not in the details view but in the view
            // where the photo is selected"). Gated on the same chrome flag as everything else
            // drawn over the photo, for the same reason: immersive means immersive by default,
            // and the strip is unambiguously chrome.
            //
            // NOT gated on the type of the current asset (owner, #28: "When I scrub through the
            // filmstrip and stop on a video, the video player progress bar etc. replaces the
            // filmstrip so scrubbing through is no longer possible"). The strip is roll-level
            // navigation and it drives this screen live, so gating it on `asset.isVideo` removed
            // it from under the finger the instant the scrub landed on a video. The two used to
            // be kept apart because both anchor to this bottom edge; instead the video transport
            // now sits `bottomInset` above the strip's resting band (above), and dims while the
            // strip is being moved. Last child of this Box on purpose: it stays on top of the
            // player, so it is the first thing a touch near the bottom edge reaches.
            if (filmstripShown) {
                FilmstripScrubber(
                    assets = relatedAssets,
                    currentIndex = position - 1,
                    onSelect = onSelectAsset,
                    onScrubbingChanged = { scrubbing = it },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding(),
                )
            }
        }
    }

    // The photo, flying to the pin. Above the shell (it leaves the parked card and lands in the
    // room), beneath the map (which grows over everything once the pin is tapped). Pointer-free,
    // so the shell's return drag on the parked card still receives every touch. A video flies as
    // its poster frame: the flier draws the asset's thumbnail, never the live player surface,
    // which a layer cannot move reliably.
    if (flightActive) {
        PhotoToPinFlight(
            asset = asset,
            progress = { shell.vProgress },
            photoRect = photoRect,
            pinRect = { pinHolder.rect },
            viewportSize = containerSize,
            bandPx = PlaceMorph.SHELL_BAND_DP * density.density,
            modifier = Modifier.testTag(PHOTO_TO_PIN_FLIGHT_TAG),
            landedDim = PlaceMorph.landedDim(
                ViewerChromePolicy.placeKnown(exif.latitude, exif.longitude, manualLatitude, manualLongitude),
            ),
        )
    }

    // The map, grown out of the plate. Above the shell, so it can fill the screen the room's
    // bottom band would otherwise cut off; drawn only while there is an origin to grow from.
    mapOrigin?.let { from ->
        val latitude = exif.latitude ?: manualLatitude
        val longitude = exif.longitude ?: manualLongitude
        if (PlaceMorph.isDrawable(latitude, longitude)) {
            val accent = MaterialTheme.colorScheme.primary.toArgb()
            // The palette colours are read ONCE, as the map opens: PaletteMemo is a memo of what
            // this process has already decoded, never a reason to decode anything for a dot.
            val focus = remember(asset.id, latitude, longitude, accent, dotColors) {
                MapFocus(
                    assetId = asset.id,
                    latitude = latitude!!,
                    longitude = longitude!!,
                    // The index's stored colours, then what this process decoded on top: both
                    // already known, so no dot ever costs a decode.
                    dotColors = dotColors + PaletteMemo.dominantColours(),
                    accentArgb = accent,
                )
            }
            // One shape per whole dp of corner radius, made once: the layer lambda below runs
            // every frame of the growth and must not allocate a shape each time.
            val cornerShapes = remember { Array(PLATE_CORNER_DP + 1) { RoundedCornerShape(it.dp) } }
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // From the plate's rectangle to the whole screen, as one scale-and-slide
                        // about the top-left corner: at 0 the layer sits exactly over the plate
                        // (its rounded corners included); at 1 it is the screen.
                        val progress = mapExpansion.value.coerceIn(0f, 1f)
                        val fromLeft = from.left - overlayOrigin.x
                        val fromTop = from.top - overlayOrigin.y
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = lerp((from.width / size.width).coerceAtLeast(0.01f), 1f, progress)
                        scaleY = lerp((from.height / size.height).coerceAtLeast(0.01f), 1f, progress)
                        translationX = fromLeft * (1f - progress)
                        translationY = fromTop * (1f - progress)
                        shape = cornerShapes[(PLATE_CORNER_DP * (1f - progress)).roundToInt().coerceIn(0, PLATE_CORNER_DP)]
                        clip = true
                    },
            ) {
                ExpandedPhotoMap(
                    focus = focus,
                    assets = libraryAssets.ifEmpty { relatedAssets.ifEmpty { listOf(asset) } },
                    geoState = geoState,
                    onOpenAsset = { picked -> onSelectAsset(picked) },
                    onClose = collapseMap,
                )
            }
        }
    }
    }
}

/** Id of the photo-to-pin flier, for tests: present exactly while the flight is composed. */
internal const val PHOTO_TO_PIN_FLIGHT_TAG = "photoToPinFlight"

/**
 * The details room's pin tile rectangle in room coordinates, as last reported by the room.
 * Snapshot state so a late report repaints the flier (which reads it inside draw and layer
 * lambdas only), and never read during composition.
 */
private class PinRectHolder {
    var rect: Rect? by mutableStateOf(null)
}

/**
 * A room's slot for the shell: [content] while [available], otherwise null -- which the shell reads
 * as "this edge has no room", so it composes nothing for it and refuses drags from that edge.
 */
private fun roomSlot(available: Boolean, content: @Composable () -> Unit): (@Composable () -> Unit)? =
    if (available) content else null

/**
 * Zoom, rotate, pan and page, arbitrated in one pass -- or, whenever [pinchMode] is
 * [ViewerPinchMode.PANEL_SHORTCUTS], a two-or-more-finger pinch's net direction instead of zoom,
 * with rotate/pan/zoom left untouched by that gesture entirely. See [ViewerPinchMode]'s own doc
 * for why the two are mutually exclusive rather than both firing off one pinch.
 *
 * Compose ships `detectTransformGestures`, but it cannot express this screen's rule: a
 * **one-finger** drag at rest pages to the next photo, while the same drag once zoomed pans the
 * image, and two fingers always transform (or, in PANEL_SHORTCUTS, are tracked for [onPinch]
 * instead). That decision needs the pointer count and the current scale together, and any
 * detector that consumes first has already thrown the choice away.
 *
 * Nothing is consumed until the gesture passes touch slop, which is what leaves single taps and
 * double taps to the tap detector layered beside this one.
 *
 * @param scaleProvider read live rather than captured: the scale changes during the gesture this
 *   very function is driving, and a captured copy would decide "is it zoomed?" using a value from
 *   before the pinch.
 * @param onPage forward = true means "next photo".
 * @param onPinch PANEL_SHORTCUTS only -- called at most once per gesture, at its end, with the
 *   pinch's net direction. Never called for OPTICAL_ZOOM, and never called at all for a pinch too
 *   small to cross [resolvePinchDirection]'s own threshold.
 */
private suspend fun PointerInputScope.detectViewerGestures(
    scaleProvider: () -> Float,
    pinchMode: ViewerPinchMode,
    onTransform: (zoomChange: Float, panChange: Offset, rotationChange: Float) -> Unit,
    onPage: (forward: Boolean) -> Unit,
    onPinch: (PinchDirection) -> Unit,
    onGestureEnd: () -> Unit,
) {
    awaitEachGesture {
        var zoom = 1f
        var pan = Offset.Zero
        var rotation = 0f
        var pastSlop = false
        var maxPointers = 1
        var pagingTravel = 0f
        // Accumulated ln(zoomChange) across every frame this gesture has had 2+ fingers down --
        // PANEL_SHORTCUTS only, read once at the gesture's end via [resolvePinchDirection]. Log
        // scale for the same reason [com.fotoxplorr.app.adaptive.ZoomLadder.step] uses it: sums
        // exactly across many frames where multiplying the raw ratios would drift under float
        // rounding.
        var panelPinchLog = 0f
        val slop = viewConfiguration.touchSlop

        // requireUnconsumed = false, and the down is deliberately NOT consumed: the tap detector
        // beside this one has to see the same down or taps stop working entirely.
        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
            if (event.changes.any { it.isConsumed }) break
            val pointersNow = event.changes.count { it.pressed }
            maxPointers = maxOf(maxPointers, pointersNow)

            val zoomChange = event.calculateZoom()
            val rotationChange = event.calculateRotation()
            val panChange = event.calculatePan()

            if (!pastSlop) {
                zoom *= zoomChange
                rotation += rotationChange
                pan += panChange
                val centroidSize = event.calculateCentroidSize(useCurrent = false)
                val zoomMotion = abs(1f - zoom) * centroidSize
                val rotationMotion = abs(rotation * PI.toFloat() / 180f) * centroidSize
                if (zoomMotion > slop || rotationMotion > slop || pan.getDistance() > slop) {
                    pastSlop = true
                }
            }

            if (pastSlop) {
                val zoomed = scaleProvider() > 1.001f
                when {
                    // PANEL_SHORTCUTS + two or more fingers: track the pinch's net direction
                    // ONLY -- onTransform is not called, so this gesture never also zooms, pans
                    // or rotates the photo. Locked in by maxPointers, not the live count, so the
                    // tail of the gesture (fingers lifting one at a time) still counts as the
                    // same pinch it started as -- the same reason the page-vs-transform branch
                    // below locks on maxPointers rather than the current frame's count.
                    pinchMode == ViewerPinchMode.PANEL_SHORTCUTS && maxPointers >= 2 -> {
                        if (pointersNow >= 2 && zoomChange > 0f) panelPinchLog += ln(zoomChange)
                    }
                    // One finger, not zoomed: this is a page turn, and the image must not move
                    // with it -- the photo slides only when there is something to slide within.
                    maxPointers == 1 && !zoomed -> pagingTravel += panChange.x
                    else -> onTransform(zoomChange, panChange, rotationChange)
                }
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        } while (event.changes.any { it.pressed })

        if (pastSlop && maxPointers == 1 && abs(pagingTravel) >= SWIPE_THRESHOLD_PX) {
            // Drag left => the next photo comes in from the right.
            onPage(pagingTravel < 0f)
        }
        if (pinchMode == ViewerPinchMode.PANEL_SHORTCUTS && maxPointers >= 2) {
            resolvePinchDirection(panelPinchLog)?.let(onPinch)
        }
        onGestureEnd()
    }
}

/** Which panel a [ViewerPinchMode.PANEL_SHORTCUTS] pinch opens. */
internal enum class PinchDirection { IN, OUT }

/**
 * Resolves one whole PANEL_SHORTCUTS gesture's accumulated log-scale pinch into a direction, or
 * null if it never crossed [threshold] either way -- pure, so this judgement call can be checked
 * without a live gesture, the same split [com.fotoxplorr.app.adaptive.ZoomLadder.step] makes
 * between its own pure arithmetic and the pointer-input code that feeds it.
 *
 * Reuses [PINCH_STEP_THRESHOLD] rather than inventing a second "how much pinch counts as
 * deliberate, not twitchy" number for what is, underneath, the exact same judgement call the
 * gallery's own zoom ladder already makes -- see that constant's own doc for the reasoning.
 *
 * Negative accumulated log (fingers moved together, net, across the gesture) is
 * [PinchDirection.IN] -- the owner's own naming, and the plain-English one: pinching your fingers
 * together IS "pinch in". Positive (fingers spread apart) is [PinchDirection.OUT].
 */
internal fun resolvePinchDirection(
    accumulatedLogZoom: Float,
    threshold: Float = PINCH_STEP_THRESHOLD,
): PinchDirection? = when {
    accumulatedLogZoom <= -threshold -> PinchDirection.IN
    accumulatedLogZoom >= threshold -> PinchDirection.OUT
    else -> null
}

/**
 * Squares a free rotation up to the nearest quarter turn, normalised to 0/90/180/270.
 *
 * Pure so the wrap-around cases can be asserted: the interesting ones are negative angles and
 * anything past a full turn, where a naive round-to-90 leaves 350 degrees sitting at 360 rather
 * than at 0 and the photo then animates the long way round on the next gesture.
 */
internal fun snapRotation(degrees: Float): Float {
    val snapped = (Math.round(degrees / 90f) * 90).toFloat()
    val wrapped = snapped % 360f
    val positive = if (wrapped < 0f) wrapped + 360f else wrapped
    // `%` returns -0.0 for exact negative multiples of 360, and -0.0 is NOT equal to 0.0 once
    // boxed (Float.equals compares bit patterns), so an unnormalised result can silently fail a
    // set-membership or map-key check even though it draws identically. Adding zero canonicalises
    // it: -0.0 + 0.0 is +0.0.
    return positive + 0f
}

/**
 * The only thing that draws over the photo, and only when the chrome is asked for: which shot
 * this is, and the way out.
 *
 * What it replaces was a full-width 78%-opaque plate carrying a filename, a counter and nine
 * text buttons, present by default.
 */
@Composable
private fun ViewerPositionChip(
    asset: MediaAsset,
    position: Int,
    total: Int,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(
            onClick = onClose,
            // 48dp, not the 36dp this used to be clipped to: below the platform's own minimum
            // touch target, a close button in the corner of a full-bleed photo is exactly the
            // kind of tap that misses.
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                .size(48.dp),
        ) {
            Icon(Icons.Outlined.Close, contentDescription = "Close", tint = Color.White)
        }
        Text(
            text = "$position / $total",
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/**
 * The zoom navigator, to the owner's reference: bottom-left, a translucent light-blue rectangle
 * the shape of the whole image with NO border, and inside it a solid blue filled rectangle for the
 * part of the image the screen currently shows. [ZoomViewport.fractions] is the maths; it is
 * evaluated inside the draw lambda from the live `scale`/`offset` providers, so a pan re-draws the
 * viewport rectangle every frame with no recomposition and no lag behind the finger.
 *
 * The maths assumes [MediaImage] is laid out to fill the container (ContentScale.Fit, centred)
 * and that the pinch-zoom graphicsLayer scales/translates about that same centre -- true for how
 * this screen drives `scale`/`offsetX`/`offsetY` above.
 */
@Composable
internal fun ZoomMinimap(
    asset: MediaAsset,
    scale: () -> Float,
    offsetX: () -> Float,
    offsetY: () -> Float,
    containerSize: IntSize,
    modifier: Modifier = Modifier,
) {
    if (containerSize.width <= 0 || containerSize.height <= 0) return
    val (boxWidthDp, boxHeightDp) = ZoomViewport.boxSizeDp(asset.width, asset.height, MINIMAP_LONG_EDGE_DP)
    Box(
        modifier = modifier
            .size(width = boxWidthDp.dp, height = boxHeightDp.dp)
            .drawBehind {
                drawRect(MINIMAP_FIELD)
                // The inline form: four floats handed to the block, no object per frame.
                ZoomViewport.withFractions(
                    imageWidth = asset.width,
                    imageHeight = asset.height,
                    containerWidth = containerSize.width.toFloat(),
                    containerHeight = containerSize.height.toFloat(),
                    scale = scale(),
                    offsetX = offsetX(),
                    offsetY = offsetY(),
                ) { leftFraction, topFraction, rightFraction, bottomFraction ->
                    val minimumPx = 2.dp.toPx()
                    val left = (leftFraction * size.width).coerceAtMost(size.width - minimumPx)
                    val top = (topFraction * size.height).coerceAtMost(size.height - minimumPx)
                    val right = (rightFraction * size.width).coerceAtLeast(left + minimumPx)
                    val bottom = (bottomFraction * size.height).coerceAtLeast(top + minimumPx)
                    drawRect(
                        color = MINIMAP_VIEWPORT,
                        topLeft = Offset(left, top),
                        size = Size(right - left, bottom - top),
                    )
                }
            },
    )
}

// Fixed on purpose (reference design): the zoom navigator's blues are specified by the owner's
// reference, not the accent.
/** The navigator's field: light blue at about half strength, no border -- the reference's look. */
private val MINIMAP_FIELD = Color(0xFF9DB8FF).copy(alpha = 0.55f)
/** The visible viewport: solid blue, filled. */
private val MINIMAP_VIEWPORT = Color(0xFF5A8BFF)
private const val MINIMAP_LONG_EDGE_DP = 82f
private const val MINIMAP_SCALE_THRESHOLD = 1.05f
private val MINIMAP_INSET_START = 18.dp
private val MINIMAP_INSET_BOTTOM = 16.dp

/** How long the details room must sit fully open before it counts as settled. */
private const val SETTLE_DEBOUNCE_MILLIS = 160L

/** The map's growth out of the plate, and back. Eased, no overshoot -- a place you arrive at. */
private val EXPAND_SPEC = tween<Float>(durationMillis = 360, easing = FastOutSlowInEasing)

private const val SWIPE_THRESHOLD_PX = 180f

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 6f
private const val DOUBLE_TAP_SCALE = 2.5f
