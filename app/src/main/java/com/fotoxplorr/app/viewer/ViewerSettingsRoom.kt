package com.fotoxplorr.app.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.gallery.MAX_SLIDESHOW_INTERVAL_SECONDS
import com.fotoxplorr.app.gallery.MIN_SLIDESHOW_INTERVAL_SECONDS
import com.fotoxplorr.app.gallery.TransparencyBackground
import com.fotoxplorr.app.gallery.ViewerPinchMode
import com.fotoxplorr.app.ui.RoomEyebrow
import com.fotoxplorr.app.ui.RoomGeography
import com.fotoxplorr.app.ui.RoomRow
import com.fotoxplorr.app.ui.RoomRule
import com.fotoxplorr.app.ui.RoomStepper
import com.fotoxplorr.app.ui.RoomStyle
import com.fotoxplorr.app.ui.RoomToggle
import com.fotoxplorr.app.ui.clearSystemBarsFor
import com.fotoxplorr.app.ui.HelpCopy

/**
 * The viewer's RIGHT room, the view options: everything about *looking at a photo*, put where you
 * are while looking at one. The right edge is where [RoomGeography] puts how a thing is shown, in
 * the gallery (columns, sort, what to include) as much as here, because the effect of a view option
 * is what you want to watch. It was the top room (owner, 2026-08-14: *"make the settings room at
 * the top"*), then the bottom one while the viewer's geography differed from the gallery's; the
 * owner has since asked for one geography, and app-wide Settings is now a pane, not a room.
 *
 * Grouped into what is on screen, how a slideshow runs, and what plays by itself, because the room
 * now carries enough to need grouping (owner, 2026-08-18: the top and bottom rooms should carry
 * *"more items, like the left room"*). Several of these preferences already existed and were
 * simply never reachable from anywhere — keep-awake, shuffle, autoplay and looping animations were
 * all stored and honoured but had no control anywhere in the app.
 *
 * Deliberately still not the app's whole settings surface. Everything here changes what the screen
 * the user is on does; anything that does not belong to the act of viewing lives in the Settings
 * pane, and duplicating a preference in two places is how two settings screens end up
 * disagreeing about it.
 *
 * Styled from [RoomStyle], the left rail's language, rather than Material's — same type, same
 * gutters, and a drawn toggle instead of a `Switch` whose elevation and ripple belong to a
 * different design system (owner: the top and bottom rooms should be *"restyled to match"*).
 */
@Composable
fun ViewerSettingsRoom(
    slideshowIntervalSeconds: Int,
    blurSensitive: Boolean,
    showFilmstrip: Boolean,
    keepScreenOn: Boolean,
    slideshowShuffle: Boolean,
    loopAnimations: Boolean,
    autoplayVideos: Boolean,
    onSetSlideshowInterval: (Int) -> Unit,
    onSetBlurSensitive: (Boolean) -> Unit,
    onSetShowFilmstrip: (Boolean) -> Unit,
    onSetKeepScreenOn: (Boolean) -> Unit,
    onSetSlideshowShuffle: (Boolean) -> Unit,
    onSetLoopAnimations: (Boolean) -> Unit,
    onSetAutoplayVideos: (Boolean) -> Unit,
    /** What a pinch means in this screen. See [ViewerPinchMode]'s own doc. */
    pinchMode: ViewerPinchMode = ViewerPinchMode.OPTICAL_ZOOM,
    onSetPinchMode: (ViewerPinchMode) -> Unit = {},
    /** What is drawn behind a see-through photo. See [TransparencyBackground]. */
    transparencyBackground: TransparencyBackground = TransparencyBackground.CHECKERBOARD,
    onSetTransparencyBackground: (TransparencyBackground) -> Unit = {},
    /**
     * The "Colour vision" group (`vision/ColourVisionRoomSection`), drawn after the on-screen rows
     * and bringing its own rule and heading. Null leaves the room without it.
     */
    colourVisionSection: (@Composable ColumnScope.() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(Color.Black)
            // The shell insets a right room by its band at the LEFT (that is where the parked
            // card's sliver sits). The room runs the full height of the window, so it clears the
            // status bar at its head and the navigation bar at its foot.
            .clearSystemBarsFor(RoomGeography.ViewOptions)
            // Scrollable because the room is taller than a short phone's screen. Without this the
            // last group would simply be unreachable, which is a worse failure than a scrollbar.
            .verticalScroll(rememberScrollState())
            .padding(
                start = RoomStyle.GutterStart,
                end = RoomStyle.GutterEnd,
                top = 28.dp,
                // The navigation bar is cleared above, outside the scroll; this is only the room's
                // own breathing space under the last row, the same as the gallery's.
                bottom = 28.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        RoomEyebrow("ON SCREEN")

        RoomRow(
            label = "Filmstrip",
            caption = HelpCopy.Caption.FILMSTRIP,
            onClick = { onSetShowFilmstrip(!showFilmstrip) },
        ) { RoomToggle(showFilmstrip, onSetShowFilmstrip) }

        RoomRow(
            label = "Blur sensitive photos",
            caption = HelpCopy.Caption.BLUR_GRID_ONLY,
            onClick = { onSetBlurSensitive(!blurSensitive) },
        ) { RoomToggle(blurSensitive, onSetBlurSensitive) }

        RoomRow(
            label = "Keep the screen awake",
            caption = HelpCopy.Caption.KEEP_SCREEN_ON,
            onClick = { onSetKeepScreenOn(!keepScreenOn) },
        ) { RoomToggle(keepScreenOn, onSetKeepScreenOn) }

        // The chrome button for this only exists while a see-through photo is on view, so the
        // choice has to be reachable here too.
        RoomRow(
            label = "Transparent photos",
            caption = "What shows behind see-through parts.",
            onClick = { onSetTransparencyBackground(transparencyBackground.next()) },
        ) {
            Text(
                text = transparencyBackground.title(),
                color = RoomStyle.Ink,
                style = RoomStyle.Row,
                modifier = Modifier.testTag(TRANSPARENCY_ROW_VALUE_TAG),
            )
        }

        colourVisionSection?.invoke(this)

        RoomRule(Modifier.padding(vertical = 14.dp))
        RoomEyebrow("SLIDESHOW")

        RoomRow(label = "Seconds per photo") {
            RoomStepper(
                value = "$slideshowIntervalSeconds",
                onDecrease = { onSetSlideshowInterval(slideshowIntervalSeconds - 1) },
                onIncrease = { onSetSlideshowInterval(slideshowIntervalSeconds + 1) },
                canDecrease = slideshowIntervalSeconds > MIN_SLIDESHOW_INTERVAL_SECONDS,
                canIncrease = slideshowIntervalSeconds < MAX_SLIDESHOW_INTERVAL_SECONDS,
            )
        }

        RoomRow(
            label = "Shuffle",
            caption = HelpCopy.Caption.SHUFFLE,
            onClick = { onSetSlideshowShuffle(!slideshowShuffle) },
        ) { RoomToggle(slideshowShuffle, onSetSlideshowShuffle) }

        RoomRule(Modifier.padding(vertical = 14.dp))
        RoomEyebrow("PLAYS BY ITSELF")

        RoomRow(
            label = "Loop animations",
            caption = HelpCopy.Caption.LOOP_ANIMATIONS,
            onClick = { onSetLoopAnimations(!loopAnimations) },
        ) { RoomToggle(loopAnimations, onSetLoopAnimations) }

        RoomRow(
            label = "Autoplay videos",
            caption = HelpCopy.Caption.AUTOPLAY,
            onClick = { onSetAutoplayVideos(!autoplayVideos) },
        ) { RoomToggle(autoplayVideos, onSetAutoplayVideos) }

        RoomRule(Modifier.padding(vertical = 14.dp))
        RoomEyebrow("GESTURES")

        RoomRow(
            label = "Pinch opens panels",
            caption = if (pinchMode == ViewerPinchMode.PANEL_SHORTCUTS) {
                HelpCopy.Caption.PINCH_ON
            } else {
                HelpCopy.Caption.PINCH_OFF
            },
            onClick = {
                onSetPinchMode(
                    if (pinchMode == ViewerPinchMode.PANEL_SHORTCUTS) {
                        ViewerPinchMode.OPTICAL_ZOOM
                    } else {
                        ViewerPinchMode.PANEL_SHORTCUTS
                    },
                )
            },
        ) {
            RoomToggle(
                checked = pinchMode == ViewerPinchMode.PANEL_SHORTCUTS,
                onCheckedChange = {
                    onSetPinchMode(if (it) ViewerPinchMode.PANEL_SHORTCUTS else ViewerPinchMode.OPTICAL_ZOOM)
                },
            )
        }

        // The one geography ([RoomGeography]): details are the PULL-DOWN room, actions the
        // pull-UP one (a little for the quick-action strip, further for all of them), and view
        // options (this room) come in from the right.
        Text(
            text = if (pinchMode == ViewerPinchMode.PANEL_SHORTCUTS) {
                HelpCopy.Note.GESTURES_PINCH_ON
            } else {
                HelpCopy.Note.GESTURES_PINCH_OFF
            },
            color = RoomStyle.InkFaint,
            style = RoomStyle.Caption,
            modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
        )
    }
}

/** Test tag on the current choice shown in the view options' transparent-photos row. */
internal const val TRANSPARENCY_ROW_VALUE_TAG = "viewer-transparency-row-value"
