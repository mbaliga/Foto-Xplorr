package com.fotoxplorr.app.gallery

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.R
import com.fotoxplorr.app.hyle.PeekChrome
import com.fotoxplorr.app.hyle.PeekFrameShape
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaImage
import com.fotoxplorr.app.sources.gallery.SourceActionGate
import kotlin.math.sqrt

/**
 * What the quick preview does when the finger that opened it lifts (owner, 2026-11-19: *"if close
 * on release, then don't show the chrome. If it stays open, then the chrome is needed."*).
 *
 * [CLOSE_ON_RELEASE] is the default and what the preview has always done: [MediaPeek], held, no
 * chrome. [STAYS_OPEN] opens [PeekWindow] instead, which outlives the hold and carries the chrome
 * the owner drew: three actions on a tab in one corner and a close mark in the other.
 *
 * Only the NAME is persisted (`peek_mode` in [GalleryPreferences]); [label] is the Settings chip.
 */
enum class PeekMode(val label: String) {
    CLOSE_ON_RELEASE(PeekCopy.CLOSES_ON_RELEASE),
    STAYS_OPEN(PeekCopy.STAYS_OPEN),
}

/** The three actions on the window's tab: the selection bar's three, for the one previewed photo. */
enum class PeekAction {
    EXPORT_ZIP,
    MOVE_TO_FOLDER,
    COPY_TO_FOLDER,
}

/** The quick preview's words. One short line each. */
internal object PeekCopy {
    const val CLOSES_ON_RELEASE = "Closes when you let go"
    const val STAYS_OPEN = "Stays open"
    const val RELEASE_HINT = "Release to go back"
    const val CLOSE = "Close preview"

    // The selection bar's own words for the same three glyphs, so the two read as one control.
    const val EXPORT_ZIP = "Export as zip"
    const val MOVE_TO_FOLDER = "Move to folder"
    const val COPY_TO_FOLDER = "Copy to folder"
}

/**
 * The glyphs the window's tab shows, in slot order. The same rule as the selection bar: nothing in
 * the Trash, and no move while the photo is a source file ([movable] is
 * `SourceActionGate.writesAllowed`), in which case copy takes the second slot and the third stays
 * empty -- the tab keeps the reference's size rather than shrinking around two glyphs.
 */
internal fun peekActionSlots(movable: Boolean, inTrash: Boolean): List<PeekAction> = when {
    inTrash -> emptyList()
    movable -> listOf(PeekAction.EXPORT_ZIP, PeekAction.MOVE_TO_FOLDER, PeekAction.COPY_TO_FOLDER)
    else -> listOf(PeekAction.EXPORT_ZIP, PeekAction.COPY_TO_FOLDER)
}

/** [peekActionSlots] for [asset]: move only where `SourceActionGate` allows writes, as on the bar. */
internal fun peekActionsFor(asset: MediaAsset, inTrash: Boolean): List<PeekAction> =
    peekActionSlots(movable = SourceActionGate.writesAllowed(listOf(asset)), inTrash = inTrash)

/**
 * Runs [action] on [asset] through the screen's [GalleryActions] -- the `SourceActionGate`-wrapped
 * set, so the gate still has the last word -- exactly as the selection bar runs it on a selection.
 */
internal fun runPeekAction(action: PeekAction, asset: MediaAsset, actions: GalleryActions) {
    val one = listOf(asset)
    when (action) {
        PeekAction.EXPORT_ZIP -> actions.onExportZip(one)
        PeekAction.MOVE_TO_FOLDER -> actions.onMoveToFolder(one)
        PeekAction.COPY_TO_FOLDER -> actions.onCopyToFolder(one)
    }
}

/**
 * The window's side: the shorter side of the space it opens in, less [PEEK_WINDOW_MARGIN] all
 * round, so on a 440 dp phone it is the reference's 392 dp. Capped at [PEEK_WINDOW_MAX] on a
 * tablet, where a full-height square would dwarf the chrome's fixed-fraction glyphs for no gain.
 */
internal fun peekWindowSide(width: Dp, height: Dp): Dp =
    (minOf(width, height) - PEEK_WINDOW_MARGIN * 2).coerceIn(PEEK_WINDOW_MIN, PEEK_WINDOW_MAX)

internal val PEEK_WINDOW_MARGIN = 24.dp
internal val PEEK_WINDOW_MIN = 160.dp
internal val PEEK_WINDOW_MAX = 520.dp

/** How much the grid behind the window is darkened. The reference shows no backdrop. */
internal const val PEEK_BACKDROP_ALPHA = 0.6f

/**
 * The quick preview that stays open: the photo, cropped to fill a square card, in the chrome of the
 * owner's reference (2026-11-19) -- see [PeekChrome] for the outline and where every number came
 * from. The tab in the top-left corner holds the selection bar's three actions for this one photo;
 * the tab in the bottom-right corner closes it.
 *
 * Like [MediaPeek] it is drawn in the composition, not in a `Dialog`: it appears while the finger
 * that asked for it is still down, and a new window would cancel that hold. Unlike it, letting go
 * leaves it up. It closes on the ✕, on Back, on a tap outside the card, and after any of its three
 * actions. A tap on the photo itself does nothing: opening is the tile's tap, and a preview that
 * opened the viewer when brushed would be a second, accidental way in.
 *
 * Every tap on it is consumed here: the backdrop covers the whole screen, so nothing under it --
 * the tile, the grid's pinch, the floating controls -- sees a touch while it is up. The finger that
 * opened it is the exception and is harmless: its stream stays with the tile it went down on, and
 * the tile's release after a hold is never a tap.
 *
 * @param blurred draw the photo blurred, as the grid does a sensitive one (the look views' rule).
 * @param actions the slots to show, from [peekActionSlots].
 */
@Composable
internal fun PeekWindow(
    asset: MediaAsset,
    blurred: Boolean,
    actions: List<PeekAction>,
    animate: Boolean,
    onAction: (PeekAction) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Registered when the window appears, so after every handler the screen already has: Back
    // closes the window before it does anything else.
    BackHandler(onBack = onClose)
    // The chrome is measured on a left-to-right reference (tab top left); it does not mirror.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(modifier.fillMaxSize()) {
            val side = peekWindowSide(maxWidth, maxHeight)
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag(PEEK_BACKDROP_TAG)
                    .background(Color.Black.copy(alpha = PEEK_BACKDROP_ALPHA))
                    // No semantics of its own: the ✕ and Back are the screen reader's ways out.
                    .pointerInput(Unit) { detectTapGestures(onTap = { onClose() }) },
            )
            PeekCard(
                asset = asset,
                blurred = blurred,
                actions = actions,
                animate = animate,
                side = side,
                onAction = { action ->
                    onAction(action)
                    onClose()
                },
                onClose = onClose,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/** The card at [side]: photo, chrome, glyphs. Separate so a render test can draw it alone. */
@Composable
internal fun PeekCard(
    asset: MediaAsset,
    blurred: Boolean,
    actions: List<PeekAction>,
    animate: Boolean,
    side: Dp,
    onAction: (PeekAction) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    photo: @Composable (Modifier) -> Unit = { m ->
        MediaImage(
            asset = asset,
            modifier = m.then(if (blurred) Modifier.blur(22.dp) else Modifier),
            contentScale = ContentScale.Crop,
            animate = animate,
        )
    },
) {
    val w = side.value
    Box(
        modifier
            .size(side)
            .testTag(PEEK_WINDOW_TAG)
            // Taps on the photo stop here: they neither close the window nor reach the backdrop.
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        // The photo runs under the frame to half its width, so the frame's outer edge anti-aliases
        // against black and the window's inner edge against the photo, with no seam between.
        val inset = (PeekChrome.FRAME * w / 2f).dp
        photo(
            Modifier
                .padding(inset)
                .fillMaxSize()
                .clip(RoundedCornerShape(((PeekChrome.OUTER_RADIUS - PeekChrome.FRAME / 2f) * w).dp)),
        )
        Box(Modifier.fillMaxSize().testTag(PEEK_CHROME_TAG).background(Color.Black, PeekFrameShape))
        actions.forEachIndexed { slot, action ->
            val (icon, label) = when (action) {
                PeekAction.EXPORT_ZIP -> R.drawable.ic_action_zip to PeekCopy.EXPORT_ZIP
                PeekAction.MOVE_TO_FOLDER -> R.drawable.ic_action_move to PeekCopy.MOVE_TO_FOLDER
                PeekAction.COPY_TO_FOLDER -> R.drawable.ic_action_copy to PeekCopy.COPY_TO_FOLDER
            }
            TabGlyph(
                icon = icon,
                description = label,
                glyph = (PeekChrome.SLOT_BOX * w).dp,
                modifier = Modifier.centredAt(PeekChrome.SLOT_X[slot] * w, PeekChrome.SLOT_Y * w),
                onClick = { onAction(action) },
            )
        }
        CloseMark(
            w = w,
            onClick = onClose,
            modifier = Modifier.centredAt(PeekChrome.CLOSE_X * w, PeekChrome.CLOSE_Y * w),
        )
    }
}

/** A [MIN_TAB_TARGET] box placed so its centre is at ([x], [y]) dp in the card. */
private fun Modifier.centredAt(x: Float, y: Float): Modifier =
    offset(x = (x - MIN_TAB_TARGET / 2f).dp, y = (y - MIN_TAB_TARGET / 2f).dp)

/**
 * The ✕: two strokes at 45° with square ends, drawn rather than taken from an icon set (none of the
 * drawables is a plain cross, and the reference's butt ends are not what a vector icon has). Its
 * 48 dp target is centred on the mark, so it reaches a little over the photo and past the card.
 */
@Composable
private fun CloseMark(w: Float, onClick: () -> Unit, modifier: Modifier) {
    Box(
        modifier
            .size(MIN_TAB_TARGET.dp)
            .testTag(PEEK_CLOSE_TAG)
            .clickable(onClickLabel = PeekCopy.CLOSE, onClick = onClick)
            .clearAndSetSemantics { contentDescription = PeekCopy.CLOSE },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val half = PeekChrome.CLOSE_ARM * w / 2f / sqrt(2f) * density
            val c = center
            val stroke = PeekChrome.CLOSE_STROKE * w * density
            drawLine(Color.White, Offset(c.x - half, c.y - half), Offset(c.x + half, c.y + half), stroke, StrokeCap.Butt)
            drawLine(Color.White, Offset(c.x - half, c.y + half), Offset(c.x + half, c.y - half), stroke, StrokeCap.Butt)
        }
    }
}

/**
 * One white glyph on a black tab, [glyph] dp, centred in a [MIN_TAB_TARGET] target -- the
 * selection bar's glyph and the preview window's, so both tabs read as one family. A bare `Icon`
 * in a clickable box rather than an `IconButton`: the slots sit closer than 48 dp on both tabs, so
 * neighbouring targets overlap (the later one wins where they do), which a Row cannot express.
 */
@Composable
internal fun TabGlyph(
    @DrawableRes icon: Int,
    description: String,
    glyph: Dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .size(MIN_TAB_TARGET.dp)
            .clickable(onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = description,
            tint = Color.White,
            modifier = Modifier.size(glyph),
        )
    }
}

/** Smallest touch target a tab control gets, whatever is drawn. */
internal const val MIN_TAB_TARGET = 48f

internal const val PEEK_WINDOW_TAG = "peek_window"
internal const val PEEK_CHROME_TAG = "peek_chrome"
internal const val PEEK_CLOSE_TAG = "peek_close"
internal const val PEEK_BACKDROP_TAG = "peek_backdrop"
