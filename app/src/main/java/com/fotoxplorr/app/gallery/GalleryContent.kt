@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.fotoxplorr.app.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.ui.RoomRow
import com.fotoxplorr.app.ui.RoomStyle
import com.fotoxplorr.app.media.MediaImage
import com.fotoxplorr.app.organize.LibraryState
import com.fotoxplorr.app.organize.MediaCollection
import com.fotoxplorr.app.spatial.LocalSpatialExperience
import com.fotoxplorr.app.spatial.PlacesScreen
import com.fotoxplorr.core.model.MediaId
import kotlin.math.exp
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot
import com.fotoxplorr.app.ui.HelpCopy

/**
 * Gutter between grid tiles. The owner's October 2026 root-grid screenshot (measured, see
 * `docs/handoff/DESIGN-FIDELITY-GALLERY.md`) draws three columns of 144 x 160dp tiles with a 4dp
 * black gutter between them and nothing at the screen edges, so this is 4dp -- it was a 1dp
 * hairline for the earlier "seamless mosaic" mockups -- and still no content padding.
 */
internal val GRID_GUTTER = 4.dp

/**
 * The everyday grid tile's shape: 144 wide by 160 tall on the same screenshot, i.e. a little
 * taller than square. The masonry layout passes each photo's own [MediaAsset.aspectRatio]
 * instead; see [MediaTile].
 */
internal const val GRID_TILE_ASPECT = 144f / 160f

/**
 * The pure-black canvas the mockups sit every grid on. Deliberately a literal black rather
 * than `colorScheme.background`: the mockups are black regardless of the theme's surface
 * tint, and OLED-black is the whole visual point of the design.
 */
val GRID_BACKGROUND: Color = Color.Black

/**
 * What a tile can ask its owner to do to ONE photo with no selection involved: the right-click
 * context menu (open, favourite, trash) and what a long press can be set to (favourite, share,
 * edit -- see [GridLongPress]). Deliberately the small subset of [GalleryActions] that makes sense
 * there -- everything a multi-select action bar already covers (move, copy, tag, export) stays
 * reachable only through selecting, so this is not a second, incomplete copy of that surface.
 */
enum class MediaContextAction { OPEN, TOGGLE_FAVORITE, MOVE_TO_TRASH, SHARE, EDIT }

/**
 * Wires the grid's mouse- and pinch-driven chrome to whatever owns it, across a boundary a
 * parameter cannot cross.
 *
 * The grid is reached two ways: directly from `GalleryScreen` for a drill-down route, and through
 * `DestinationBrowserScreen`'s `DestinationContent` for the root "Photos" grid -- the app's
 * most-used surface. Rather than thread new callbacks through both (and through the grid's own
 * tiles), this is provided the same way `LocalSpatialExperience` already is
 * (`com.fotoxplorr.app.spatial.SpatialComposition`): a value read via composition, so it reaches
 * every tile regardless of what sits between `GalleryScreen` and the tile in the tree.
 *
 * A mutable holder rather than putting the callbacks straight into a `compositionLocalOf` value:
 * `GalleryScreen` recomputes these lambdas on every recomposition (they close over the current
 * preferences and route), and re-providing a fresh VALUE on every recomposition would force this
 * bridge's entire subtree -- the 22k-tile grid -- to recompose along with it, which is exactly
 * the kind of per-frame cost the gallery cannot afford. Providing one [remember]ed instance and
 * mutating its fields instead means the grid reads the latest callback without ever being told
 * the bridge itself changed.
 */
@Stable
class GridChromeBridge {
    /** Called with this gesture frame's multiplicative scale change; see `pinchStep`. */
    internal var onZoom: (Float) -> Unit = {}

    /**
     * The gesture that was calling [onZoom] has finished: the fingers came up, or the wheel has
     * been still for a moment. The owner persists the rung and drops the unspent motion here, not
     * on every step.
     */
    internal var onZoomEnd: () -> Unit = {}

    /** One rung out (+1) or in (-1) without a gesture: the screen reader's zoom actions. */
    internal var onZoomStep: (Int) -> Unit = {}
    internal var onContextAction: (MediaAsset, MediaContextAction) -> Unit = { _, _ -> }

    /**
     * How many tiles sit across one row of the grid on screen right now. Written by the grid after
     * it has measured its width (the stamp view's count depends on it), read by the arrow keys to
     * step a row at a time. Not observable: nothing draws from it.
     */
    internal var columnsAcross: Int = 1

    /**
     * What a hold on a tile does, already adjusted for the route (see
     * [LongPressResolver.effectiveFor]). Set by `GalleryScreen` on every recomposition and read by a
     * tile when the hold completes -- so changing the setting needs no parameter through the grids
     * and cannot leave a tile acting on a stale value. `mutableStateOf` only because a tile's
     * accessibility long-click label reads it; the gesture itself reads the current value, not a
     * subscription. The photo picker never sets it: its grid is always in selection, where a hold
     * is a tap whatever this says.
     */
    var gridLongPress: GridLongPress by mutableStateOf(GridLongPress.PEEK)
        internal set

    /**
     * Which tile the keyboard's arrow keys currently sit on, if any -- unlike the callbacks
     * above, this one IS `mutableStateOf`, because a tile reads it reactively to draw its own
     * focus ring. That still costs nothing extra per keystroke beyond what it should: every tile
     * shares this one `State`, so moving the cursor recomposes exactly the tiles currently on
     * screen (the previous ring's tile turns off, the new one turns on) once per keypress -- not
     * once per frame, and not the other ~21,999 tiles that are not currently composed at all.
     */
    var keyboardFocusedId: MediaId? by mutableStateOf(null)
        internal set
}

val LocalGridChromeBridge = staticCompositionLocalOf { GridChromeBridge() }

/** A pinch has to move the fingers at least this many px apart before its spread is trusted as a
 * zoom signal rather than as two fingers landing near-simultaneously at the same point. */
private const val MIN_PINCH_SPREAD_PX = 8f

/** How much wheel travel (in px, Android's raw `scrollDelta` unit) equals one "unit" of pinch on
 * the same log scale `pinchStep` consumes -- picked so three or four notches of a typical mouse
 * wheel cross [com.fotoxplorr.app.adaptive.PINCH_STEP_THRESHOLD] once, matching how many notches a
 * deliberate density change takes on a touchpad's two-finger pinch. */
private const val SCROLL_ZOOM_SENSITIVITY = 0.02f

/** A wheel has no "fingers up": this long without a notch is the end of its gesture. */
private const val SCROLL_ZOOM_SETTLE_MILLIS = 250L

/**
 * Multi-touch pinch and Ctrl+scroll, both folded into [bridge]'s `onZoom` (and `onZoomEnd` when the
 * gesture is over), and nothing else.
 *
 * Attached to the ONE grid, so it works at every rung and in both directions: there is no rung
 * that draws something else without it.
 *
 * Hand-rolled rather than `detectTransformGestures` -- the same call `ViewerScreen` already
 * makes for its own gesture, and for the same reason (see its "Zoom, rotate, pan and page"
 * doc): the stock detector consumes every pointer's position change on every frame, ONE finger
 * included, which would eat the touch stream this grid's own vertical scroll and each tile's
 * hand-rolled tap/long-press detector both need. This one reads the pointer count itself and
 * only ever calls `consume()` on a frame with two or more fingers down, or on a Ctrl-held wheel
 * tick -- an un-modified scroll, a one-finger drag, and every tap or long-press pass through
 * completely untouched.
 */
internal fun Modifier.gridZoomGestures(bridge: GridChromeBridge): Modifier = pointerInput(Unit) {
    coroutineScope {
        val gestureScope = this
        // The previous frame's average finger-to-centroid distance. Reset to null whenever fewer
        // than two fingers are down, so the frame that brings the SECOND finger down never reports a
        // "zoom" against a one-finger baseline that was never a spread measurement at all.
        var previousSpread: Float? = null
        // Whether this pinch has reported any zoom yet, so lifting a finger after a plain two-finger
        // touch (which reported nothing) does not announce an end with no beginning.
        var pinching = false
        var wheelSettle: Job? = null
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Scroll) {
                    val change = event.changes.firstOrNull()
                    if (change != null && event.keyboardModifiers.isCtrlPressed) {
                        change.consume()
                        val scrollY = change.scrollDelta.y
                        // A wheel tick is a discrete unit, not a distance -- there is no "previous
                        // spread" to compare it against, so it maps straight onto the rungs' own
                        // log scale instead of being folded through a spread ratio like a pinch is.
                        if (scrollY != 0f) {
                            bridge.onZoom(exp(-scrollY * SCROLL_ZOOM_SENSITIVITY))
                            wheelSettle?.cancel()
                            wheelSettle = gestureScope.launch {
                                delay(SCROLL_ZOOM_SETTLE_MILLIS)
                                bridge.onZoomEnd()
                            }
                        }
                    }
                    continue
                }
                val pressed = event.changes.filter { it.pressed }
                if (pressed.size < 2) {
                    previousSpread = null
                    if (pinching) {
                        pinching = false
                        bridge.onZoomEnd()
                    }
                    continue
                }
                val centroidX = pressed.sumOf { it.position.x.toDouble() }.toFloat() / pressed.size
                val centroidY = pressed.sumOf { it.position.y.toDouble() }.toFloat() / pressed.size
                val spread = pressed.sumOf {
                    hypot((it.position.x - centroidX).toDouble(), (it.position.y - centroidY).toDouble())
                }.toFloat() / pressed.size
                val previous = previousSpread
                if (previous != null && previous > MIN_PINCH_SPREAD_PX) {
                    pressed.forEach { it.consume() }
                    pinching = true
                    bridge.onZoom(spread / previous)
                }
                previousSpread = spread
            }
        }
    }
}

/**
 * The plain grid the photo picker uses, and the masonry layout of the main gallery. The everyday
 * gallery grid -- every column count and the stamp view -- is [ZoomGrid]; this is what is left of
 * the older single grid, in either of two tile shapes.
 *
 * `fitToTile = true` (the default, and what the picker draws) is a fixed [LazyVerticalGrid], every
 * tile the [GRID_TILE_ASPECT] shape, cropped to fill it.
 *
 * `fitToTile = false` switches to a masonry layout: [LazyVerticalStaggeredGrid], each tile's
 * own height set by its [MediaAsset.aspectRatio] rather than forced to one shape. That field is
 * read straight off the asset's stored MediaStore width/height -- no bitmap is opened and no pixel
 * is measured to learn it -- so a masonry pass costs this grid nothing beyond the one division
 * `aspectRatio` already does, and it is exactly as lazy as the other grid: only the tiles
 * actually on screen are composed or measured.
 *
 * Masonry zooms like the rest (the same pinch detector, columns 1..6) but never shows stamps or
 * headers: there is no full-width item in a staggered grid to hang a month header on.
 *
 * The one thing masonry mode does NOT carry over is [gridState] -- a [LazyGridState] cannot
 * drive a [LazyVerticalStaggeredGrid], which needs its own `LazyStaggeredGridState`, and that
 * state is created and owned right here rather than threaded in. The one visible
 * consequence: `GalleryScreen`'s edge timeline scrubber, which reads and drives that same
 * externally-hoisted `LazyGridState`, cannot track a masonry-mode scroll -- it is hidden while
 * masonry is active rather than left on screen pointing at a position that will not move.
 */
@Composable
fun MediaGridScreen(
    assets: List<MediaAsset>,
    columns: Int,
    favoriteIds: Set<MediaId>,
    sensitiveIds: Set<MediaId>,
    blurSensitive: Boolean,
    selectedIds: Set<MediaId>,
    emptyMessage: String,
    /**
     * Whether the selection chrome is up. Defaults to the old rule -- something is picked -- so
     * the grids that do not participate in selection keep working unchanged.
     */
    selectionActive: Boolean = selectedIds.isNotEmpty(),
    onOpen: (MediaAsset) -> Unit,
    onToggleSelection: (MediaId) -> Unit,
    gridState: LazyGridState = rememberLazyGridState(),
    // Media-behaviour preferences. Defaulted so the several other call sites that render a grid
    // (albums, collections, search results) keep working without each having to thread them.
    fitToTile: Boolean = true,
    loopAnimations: Boolean = false,
    animatedIds: Set<MediaId> = emptySet(),
) {
    if (assets.isEmpty()) {
        GalleryMessage(emptyMessage)
        return
    }
    // The peeked photo, if any. Hoisted here rather than held per tile so only one can ever be
    // open, and so the overlay draws above the whole grid instead of inside one cell's bounds.
    var peeked by remember { mutableStateOf<MediaAsset?>(null) }
    val zoomBridge = LocalGridChromeBridge.current
    SideEffect { zoomBridge.columnsAcross = columns }

    Box(Modifier.fillMaxSize().gridZoomGestures(zoomBridge)) {
        if (fitToTile) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = gridState,
                modifier = Modifier.fillMaxSize().background(GRID_BACKGROUND),
                verticalArrangement = Arrangement.spacedBy(GRID_GUTTER),
                horizontalArrangement = Arrangement.spacedBy(GRID_GUTTER),
            ) {
                items(assets, key = { it.id.value }) { asset ->
                    MediaTile(
                        asset = asset,
                        favorite = asset.id in favoriteIds,
                        sensitive = asset.id in sensitiveIds,
                        blurSensitive = blurSensitive,
                        selected = asset.id in selectedIds,
                        selectionActive = selectionActive,
                        fitToTile = true,
                        loopAnimations = loopAnimations,
                        animated = asset.id in animatedIds,
                        onOpen = { onOpen(asset) },
                        onToggleSelection = { onToggleSelection(asset.id) },
                        onPeek = { peeked = asset },
                        onPeekEnd = { peeked = null },
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(88.dp)) }
            }
        } else {
            // Not `gridState`: see this function's own doc for why a masonry grid keeps its
            // scroll state to itself rather than sharing the square grid's.
            val staggeredState = rememberLazyStaggeredGridState()
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(columns),
                state = staggeredState,
                modifier = Modifier.fillMaxSize().background(GRID_BACKGROUND),
                verticalItemSpacing = GRID_GUTTER,
                horizontalArrangement = Arrangement.spacedBy(GRID_GUTTER),
            ) {
                items(assets, key = { it.id.value }) { asset ->
                    MediaTile(
                        asset = asset,
                        favorite = asset.id in favoriteIds,
                        sensitive = asset.id in sensitiveIds,
                        blurSensitive = blurSensitive,
                        selected = asset.id in selectedIds,
                        selectionActive = selectionActive,
                        // Always Crop, never Fit: the TILE already carries the real aspect ratio
                        // (below), so there is no letterboxing left for Fit to avoid -- Crop just
                        // fills a shape that already matches the image.
                        fitToTile = true,
                        tileAspectRatio = asset.aspectRatio,
                        loopAnimations = loopAnimations,
                        animated = asset.id in animatedIds,
                        onOpen = { onOpen(asset) },
                        onToggleSelection = { onToggleSelection(asset.id) },
                        onPeek = { peeked = asset },
                        onPeekEnd = { peeked = null },
                    )
                }
                item(span = StaggeredGridItemSpan.FullLine) { Spacer(Modifier.height(88.dp)) }
            }
        }

        peeked?.let { asset -> MediaPeek(asset = asset, loopAnimations = loopAnimations, animated = asset.id in animatedIds) }
    }
}

/**
 * The peek: the photo, large, for exactly as long as the finger is held on it.
 *
 * Held, not opened (owner, 2026-08-18: *"the quick preview must disappear when the long press is
 * released"*). It is a glance at a thumbnail too small to read, so the gesture that asks for it is
 * the gesture that holds it open, and letting go puts it back. That is why it carries no buttons:
 * a control you cannot reach without releasing -- which closes the thing the control is on -- is
 * not a control.
 *
 * Drawn in the composition rather than in a `Dialog`, which matters here. A dialog is a new
 * window, and raising one mid-gesture cancels the pointer stream on the window underneath -- the
 * hold would end the instant the preview appeared, so the preview would flash and vanish.
 *
 * Long press used to be how a selection started. That entry point moves to the gallery's own
 * actions room ("Select photos"), and the "Long press does" setting ([GridLongPress]) restores
 * long-press-to-select exactly as it was for anyone who prefers it -- or makes the hold favourite,
 * share or edit instead.
 */
@Composable
internal fun MediaPeek(asset: MediaAsset, loopAnimations: Boolean, animated: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(20f)
            // Swallows pointer events so nothing behind the peek can be scrolled or tapped by
            // the same finger that is holding it open.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
            .background(Color.Black.copy(alpha = 0.92f))
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MediaImage(
            asset = asset,
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            contentScale = ContentScale.Fit,
            animate = loopAnimations && animated,
        )
        Text(
            asset.displayName,
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            "Release to go back",
            color = Color.White.copy(alpha = 0.35f),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * What a finger does to a photo tile: a tap opens it (or toggles it while a selection runs), a hold
 * does whatever the "Long press does" setting says, and a screen reader gets the same two actions.
 * Shared by [MediaTile] and the slimmer stamp tile so the two cannot drift apart.
 */
@Composable
internal fun Modifier.tileGestures(
    asset: MediaAsset,
    selectionActive: Boolean,
    onOpen: () -> Unit,
    onToggleSelection: () -> Unit,
    onPeek: () -> Unit,
    onPeekEnd: () -> Unit,
): Modifier {
    val chromeBridge = LocalGridChromeBridge.current

    // The handlers below outlive any one composition: pointerInput(chromeBridge) starts once per
    // tile, so everything they call is read through these. Capturing the parameters directly froze
    // whichever onOpen the gesture block last started with -- and that closes over the grid's
    // asset list, so after a sort or filter change a tap could page the viewer through the old
    // order (TRAPS #50). Keying on the selection state instead would restart the detector on every
    // toggle, mid-gesture.
    val currentOnOpen by rememberUpdatedState(onOpen)
    val currentOnToggleSelection by rememberUpdatedState(onToggleSelection)
    val currentOnPeek by rememberUpdatedState(onPeek)
    val currentOnPeekEnd by rememberUpdatedState(onPeekEnd)
    val currentSelectionActive by rememberUpdatedState(selectionActive)
    val currentAsset by rememberUpdatedState(asset)
    val currentHaptic by rememberUpdatedState(LocalHapticFeedback.current)
    val longClick = LongPressResolver.longClickSemantics(chromeBridge.gridLongPress, selectionActive)

    return this
        // Click and long-click for a screen reader, whose double-tap and double-tap-and-hold
        // never reach the pointer handler below as a hold. A long press cannot be performed
        // as a held preview there, so LongPressResolver.longClickSemantics turns the preview
        // into Select; Nothing offers no long-click at all. Announced as one tile: the photo's
        // name from MediaImage plus any favourite / sensitive / selected marks.
        .semantics(mergeDescendants = true) {
            onClick(label = LongPressResolver.tapLabel(selectionActive)) {
                if (selectionActive) currentOnToggleSelection() else currentOnOpen()
                true
            }
            if (longClick != null) {
                onLongClick(label = longClick.label) {
                    when (val outcome = longClick.outcome) {
                        LongPressOutcome.ToggleSelection -> currentOnToggleSelection()
                        is LongPressOutcome.Run -> chromeBridge.onContextAction(currentAsset, outcome.action)
                        LongPressOutcome.Peek, LongPressOutcome.None -> Unit
                    }
                    true
                }
            }
        }
        // Hand-rolled rather than combinedClickable, because the peek needs the one thing
        // that API does not report: the RELEASE. onLongClick tells you the hold began and
        // nothing tells you it ended, so a peek built on it can only be a modal that outlives
        // the gesture -- which is exactly what this replaces.
        //
        // detectTapGestures gives all three: onPress suspends until the finger lifts or the
        // gesture is cancelled, onLongPress fires at the timeout, onTap only when it did not.
        .pointerInput(chromeBridge) {
            detectTapGestures(
                onPress = {
                    // Returns on release AND on cancellation -- so a peek cannot be stranded
                    // on screen by the grid scrolling out from under the finger.
                    tryAwaitRelease()
                    currentOnPeekEnd()
                },
                // What a hold does is the "Long press does" setting, read from the bridge at
                // the moment it completes; LongPressResolver owns the rules (including that a
                // running selection is extended, whatever the setting).
                onLongPress = {
                    val outcome = LongPressResolver.resolve(chromeBridge.gridLongPress, currentSelectionActive)
                    if (outcome != LongPressOutcome.None) {
                        currentHaptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                    when (outcome) {
                        LongPressOutcome.Peek -> currentOnPeek()
                        LongPressOutcome.ToggleSelection -> currentOnToggleSelection()
                        is LongPressOutcome.Run -> chromeBridge.onContextAction(currentAsset, outcome.action)
                        LongPressOutcome.None -> Unit
                    }
                },
                onTap = { if (currentSelectionActive) currentOnToggleSelection() else currentOnOpen() },
            )
        }
}

@Composable
internal fun MediaTile(
    asset: MediaAsset,
    favorite: Boolean,
    sensitive: Boolean,
    blurSensitive: Boolean,
    selected: Boolean,
    selectionActive: Boolean,
    fitToTile: Boolean,
    loopAnimations: Boolean,
    animated: Boolean,
    onOpen: () -> Unit,
    onToggleSelection: () -> Unit,
    onPeek: () -> Unit,
    onPeekEnd: () -> Unit,
    /**
     * The tile's own shape. [GRID_TILE_ASPECT] (144 x 160, from the owner's root-grid
     * screenshot) in the everyday grids, and the photo's real [MediaAsset.aspectRatio] when the
     * caller is the masonry layout -- see [MediaGridScreen]'s `fitToTile` branch.
     */
    tileAspectRatio: Float = GRID_TILE_ASPECT,
) {
    // Mouse-only: the panel this opens on a right-click, if anyone upstream wired
    // GridChromeBridge.onContextAction. Per-tile state rather than something hoisted to the
    // grid, because a `DropdownMenu` anchors to the composable that shows it, and threading an
    // "which of 22,000 tiles has its menu open" id back up would cost every OTHER tile a
    // recomposition on every click for a menu it will never draw.
    var contextMenuVisible by remember { mutableStateOf(false) }
    val chromeBridge = LocalGridChromeBridge.current

    Box(
        modifier = Modifier
            .aspectRatio(tileAspectRatio)
            // Black rather than surfaceVariant: an unloaded tile should read as a gap in the
            // mosaic, not as a light grey block punched through it.
            .background(GRID_BACKGROUND)
            .tileGestures(
                asset = asset,
                selectionActive = selectionActive,
                onOpen = onOpen,
                onToggleSelection = onToggleSelection,
                onPeek = onPeek,
                onPeekEnd = onPeekEnd,
            )
            // A second, independent pointerInput block rather than folding this into the one
            // above: `detectTapGestures` only ever sees the PRIMARY button, so a right-click
            // free-rides through it as an ordinary tap on any device that reports one at all.
            // This one reads the raw button state itself and consumes ONLY a secondary-button
            // press, leaving every left click, long-press and drag exactly as it was.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                            event.changes.forEach { it.consume() }
                            contextMenuVisible = true
                        }
                    }
                }
            },
    ) {
        MediaImage(
            asset = asset,
            modifier = Modifier
                .fillMaxSize()
                .then(if (sensitive && blurSensitive) Modifier.blur(22.dp) else Modifier),
            // Crop fills the tile and trims any excess off; Fit letterboxes so the whole frame
            // and its true proportions survive within a shape that was already forced. The
            // masonry layout gives Crop nothing left to trim -- the tile's own shape (above) IS
            // the photo's aspect ratio -- so this switch matters only in the square grid.
            contentScale = if (fitToTile) ContentScale.Crop else ContentScale.Fit,
            animate = loopAnimations && animated,
        )
        if (contextMenuVisible) {
            DropdownMenu(expanded = true, onDismissRequest = { contextMenuVisible = false }) {
                DropdownMenuItem(
                    text = { Text("Open") },
                    leadingIcon = { Icon(Icons.Outlined.OpenInNew, null) },
                    onClick = {
                        contextMenuVisible = false
                        chromeBridge.onContextAction(asset, MediaContextAction.OPEN)
                    },
                )
                DropdownMenuItem(
                    text = { Text(if (favorite) "Remove from favourites" else "Add to favourites") },
                    leadingIcon = {
                        Icon(if (favorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder, null)
                    },
                    onClick = {
                        contextMenuVisible = false
                        chromeBridge.onContextAction(asset, MediaContextAction.TOGGLE_FAVORITE)
                    },
                )
                DropdownMenuItem(
                    text = { Text("Move to trash") },
                    leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) },
                    onClick = {
                        contextMenuVisible = false
                        chromeBridge.onContextAction(asset, MediaContextAction.MOVE_TO_TRASH)
                    },
                )
            }
        }
        if (sensitive && blurSensitive) {
            Text(
                "Sensitive",
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.82f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (asset.isVideo) {
            Text(
                formatDuration(asset.durationMillis),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.68f))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (favorite) {
            Text(
                "★",
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        if (selected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.34f)),
            )
            Text(
                "✓",
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
        // The keyboard's arrow-key cursor -- a plain ring rather than the selection tint above,
        // because the two are deliberately different ideas: this tile is not picked, it is just
        // where Enter and Delete would currently act. Conflating the two would mean every arrow
        // press opened the full selection chrome (the export/move/copy bar, the trash corner)
        // over one photo nobody asked to select yet.
        if (chromeBridge.keyboardFocusedId == asset.id) {
            Box(Modifier.fillMaxSize().border(2.dp, Color.White))
        }
    }
}

@Composable
fun AlbumsScreen(
    assets: List<MediaAsset>,
    collections: List<MediaCollection>,
    archivedIds: Set<MediaId>,
    sensitiveIds: Set<MediaId>,
    lockedFolders: Set<String>,
    unlockedFolders: Set<String>,
    hideSensitive: Boolean,
    showVideos: Boolean,
    query: String,
    onOpenAlbum: (AlbumSummary) -> Unit,
    onOpenCollection: (MediaCollection) -> Unit,
) {
    // Device folders keep their own locked-folder handling below (a locked album still shows its
    // name and count, just no cover), so archived/locked/sensitive items stay in `available` for
    // that grouping to see -- but the Collections cover lookup below must use the fully filtered
    // browsableAssets list, or a collection can leak a cover thumbnail that shouldn't be shown.
    val available = assets.filter { asset ->
        !asset.isTrashed && asset.id !in archivedIds && (showVideos || !asset.isVideo)
    }
    val browsable = browsableAssets(assets, archivedIds, sensitiveIds, lockedFolders, unlockedFolders, hideSensitive)
        .filter { showVideos || !it.isVideo }
    val albums = buildAlbumSummaries(available, query)
    val normalized = query.trim().lowercase()
    val matchingCollections = collections.filter {
        normalized.isEmpty() || it.name.lowercase().contains(normalized)
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PANE_CONTENT_PADDING,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (matchingCollections.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionHeading("Collections", "Virtual albums that never move your files")
            }
            items(matchingCollections, key = { "collection:${it.id}" }) { collection ->
                val cover = collection.mediaIds.firstNotNullOfOrNull { id -> browsable.firstOrNull { it.id == id } }
                AlbumCard(
                    name = collection.name,
                    count = collection.mediaIds.size,
                    cover = cover,
                    locked = false,
                    collection = true,
                    onClick = { onOpenCollection(collection) },
                )
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            SectionHeading("Device folders", "Media remains in its original folder")
        }
        items(albums, key = { "album:${it.key}" }) { album ->
            val locked = album.key in lockedFolders && album.key !in unlockedFolders
            if (locked) {
                // A locked folder shows no photos at all, so there is nothing to stack -- and a
                // fanned stack of blank rectangles would imply content it is deliberately hiding.
                AlbumCard(
                    name = album.name,
                    count = album.count,
                    cover = null,
                    locked = true,
                    collection = false,
                    onClick = { onOpenAlbum(album) },
                )
            } else {
                AlbumStack(
                    covers = album.covers,
                    label = album.name,
                    count = album.count,
                    stackKey = album.key,
                    modifier = Modifier.combinedClickable(
                        onClick = { onOpenAlbum(album) },
                        onLongClick = { onOpenAlbum(album) },
                    ),
                )
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(88.dp)) }
    }
}

@Composable
private fun AlbumCard(
    name: String,
    count: Int,
    cover: MediaAsset?,
    locked: Boolean,
    collection: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onClick),
        colors = paneCardColors(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.16f)
                .background(RoomStyle.Surface),
            contentAlignment = Alignment.Center,
        ) {
            when {
                cover != null -> MediaImage(
                    asset = cover,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                locked -> Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(36.dp))
                collection -> Icon(Icons.Outlined.Collections, contentDescription = null, modifier = Modifier.size(36.dp))
                else -> Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(36.dp))
            }
        }
        Column(Modifier.padding(12.dp)) {
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            Text(
                if (locked) "Private folder" else "$count items",
                style = MaterialTheme.typography.bodySmall,
                color = RoomStyle.InkFaint,
            )
        }
    }
}

@Composable
fun DiscoverScreen(
    summaries: List<SmartAlbumSummary>,
    onOpen: (SmartAlbumSummary) -> Unit,
) {
    val spatial = LocalSpatialExperience.current
    var showingPlaces by remember { mutableStateOf(false) }

    if (showingPlaces && spatial != null) {
        Column(modifier = Modifier.fillMaxSize()) {
            RoomRow(
                label = "Back to Discover",
                onClick = { showingPlaces = false },
                modifier = Modifier.padding(horizontal = RoomStyle.GutterStart),
            )
            PlacesScreen(
                assets = spatial.assets,
                indexInput = spatial.indexInput,
                geoState = spatial.geoState,
                onIndexLocations = spatial.onIndexLocations,
                onOpenAsset = spatial.onOpenAsset,
            )
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(160.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PANE_CONTENT_PADDING,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (spatial != null) {
            item(key = "places") {
                Card(
                    modifier = Modifier.combinedClickable(
                        onClick = { showingPlaces = true },
                        onLongClick = { showingPlaces = true },
                    ),
                    colors = paneCardColors(),
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth().aspectRatio(1.35f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.Map, contentDescription = null, modifier = Modifier.size(40.dp))
                        if (spatial.geoState.locatedCount > 0) {
                            Text(
                                spatial.geoState.locatedCount.toString(),
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(8.dp)
                                    .background(Color.Black.copy(alpha = 0.68f))
                                    .padding(horizontal = 7.dp, vertical = 3.dp),
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Places", style = MaterialTheme.typography.titleSmall)
                        Text(
                            HelpCopy.Hint.PLACES_CARD,
                            style = MaterialTheme.typography.bodySmall,
                            color = RoomStyle.InkFaint,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        items(summaries, key = { it.album.name }) { summary ->
            Card(
                modifier = Modifier.combinedClickable(
                    onClick = { onOpen(summary) },
                    onLongClick = { onOpen(summary) },
                ),
                colors = paneCardColors(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.35f)
                        .background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    summary.cover?.let {
                        MediaImage(it, Modifier.fillMaxSize(), ContentScale.Crop)
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.18f)))
                    } ?: Icon(Icons.Outlined.Collections, contentDescription = null, modifier = Modifier.size(34.dp))
                    Text(
                        summary.count.toString(),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .background(Color.Black.copy(alpha = 0.68f))
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(summary.title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        summary.subtitle,
                        color = RoomStyle.InkFaint,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(88.dp)) }
    }
}

@Composable
fun LibraryScreen(
    library: LibraryState,
    privateAlbumCount: Int,
    trashCount: Int,
    onOpenCollection: (MediaCollection) -> Unit,
    onOpenTag: (String) -> Unit,
    onOpenArchive: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenPrivateFolders: () -> Unit,
    onOpenSettings: () -> Unit,
    onExportMetadata: () -> Unit,
    onImportMetadata: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PANE_CONTENT_PADDING,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SectionHeading("Organise", "Local metadata; original media is unchanged") }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LibraryActionCard(
                    modifier = Modifier.weight(1f),
                    title = "Archive",
                    subtitle = "${library.archivedIds.size} items",
                    icon = Icons.Outlined.Archive,
                    onClick = onOpenArchive,
                )
                LibraryActionCard(
                    modifier = Modifier.weight(1f),
                    title = "Trash",
                    subtitle = "$trashCount items",
                    icon = Icons.Outlined.DeleteOutline,
                    onClick = onOpenTrash,
                )
            }
        }
        item {
            LibraryActionCard(
                modifier = Modifier.fillMaxWidth(),
                title = "Private folders",
                subtitle = HelpCopy.protectedCount(privateAlbumCount),
                icon = Icons.Outlined.Lock,
                onClick = onOpenPrivateFolders,
            )
        }

        item { SectionHeading("Collections", "${library.collections.size} virtual albums") }
        if (library.collections.isEmpty()) {
            // Not "use the + button": that FAB only exists on the Albums screen, not here --
            // this screen has none, so the old copy pointed at a control the reader could not
            // see.
            item {
                Text(
                    "Create your first collection from Albums.",
                    color = RoomStyle.InkFaint,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            items(library.collections, key = { it.id }) { collection ->
                Card(
                    onClick = { onOpenCollection(collection) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = paneCardColors(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Outlined.Collections, null)
                        Column(Modifier.weight(1f)) {
                            Text(collection.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${collection.mediaIds.size} items",
                                color = RoomStyle.InkFaint,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }

        item { SectionHeading("Tags", "Searchable labels stored only in Fotoz") }
        item {
            if (library.allTags.isEmpty()) {
                Text(
                    HelpCopy.Hint.TAG_FIRST,
                    color = RoomStyle.InkFaint,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(library.allTags, key = { it }) { tag ->
                        AssistChip(
                            onClick = { onOpenTag(tag) },
                            label = { Text("#$tag") },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = RoomStyle.Surface,
                                labelColor = RoomStyle.Ink,
                            ),
                            border = AssistChipDefaults.assistChipBorder(
                                enabled = true,
                                borderColor = Color.White.copy(alpha = 0.18f),
                            ),
                        )
                    }
                }
            }
        }

        item { SectionHeading("Metadata backup", "Collections, tags and archive state") }
        item {
            // Rows in the rooms' own language rather than Material text buttons.
            Column {
                RoomRow(
                    label = "Export",
                    onClick = onExportMetadata,
                    trailing = { Icon(Icons.Outlined.UploadFile, contentDescription = null, tint = RoomStyle.InkMuted) },
                )
                RoomRow(
                    label = "Import",
                    onClick = onImportMetadata,
                    trailing = { Icon(Icons.Outlined.Collections, contentDescription = null, tint = RoomStyle.InkMuted) },
                )
            }
        }
        item {
            LibraryActionCard(
                modifier = Modifier.fillMaxWidth(),
                title = "Settings",
                subtitle = "Appearance, timeline, privacy and slideshow",
                icon = Icons.Outlined.Settings,
                onClick = onOpenSettings,
            )
        }
        item { Spacer(Modifier.height(88.dp)) }
    }
}

@Composable
private fun LibraryActionCard(
    modifier: Modifier,
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Card(modifier = modifier, onClick = onClick, colors = paneCardColors()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = null)
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, color = RoomStyle.InkFaint, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SectionHeading(title: String, subtitle: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, color = RoomStyle.Ink, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, color = RoomStyle.InkFaint, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * The cards on the destination panes: raised off the pane's black, with white ink. Material's
 * defaults would hand them `surfaceVariant` and its dark `onSurfaceVariant`, which is a light grey
 * slab in light theme sitting on a literal black ground.
 */
@Composable
private fun paneCardColors() = CardDefaults.cardColors(
    containerColor = RoomStyle.Surface,
    contentColor = RoomStyle.Ink,
)

/** The pane screens' padding: the rooms' own 20dp gutters, so every pane lines up with Settings. */
private val PANE_CONTENT_PADDING = androidx.compose.foundation.layout.PaddingValues(
    horizontal = RoomStyle.GutterStart,
    vertical = 12.dp,
)

@Composable
internal fun GalleryMessage(message: String) {
    // Drawn on [GRID_BACKGROUND], which is a literal black regardless of theme, so the text
    // colour has to be explicit too -- inheriting onSurface would render this dark-on-black
    // and effectively invisible whenever the app is in light theme.
    Box(
        Modifier.fillMaxSize().background(GRID_BACKGROUND),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            message,
            modifier = Modifier.padding(32.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White.copy(alpha = 0.55f),
        )
    }
}

private fun formatDuration(durationMillis: Long): String {
    if (durationMillis <= 0L) return "Video"
    val totalSeconds = durationMillis / 1_000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%d:%02d".format(minutes, seconds)
}
