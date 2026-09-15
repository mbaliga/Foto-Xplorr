package com.fotoxplorr.app.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.gallery.MAX_SLIDESHOW_INTERVAL_SECONDS
import com.fotoxplorr.app.gallery.MIN_SLIDESHOW_INTERVAL_SECONDS
import com.fotoxplorr.app.ui.RoomEyebrow
import com.fotoxplorr.app.ui.RoomRow
import com.fotoxplorr.app.ui.RoomRule
import com.fotoxplorr.app.ui.RoomStepper
import com.fotoxplorr.app.ui.RoomStyle
import com.fotoxplorr.app.ui.RoomToggle

/**
 * The viewer's TOP room: everything about *looking at a photo*, put where you are while looking at
 * one (owner, 2026-08-14: *"make the settings room at the top"*).
 *
 * Grouped into what is on screen, how a slideshow runs, and what plays by itself, because the room
 * now carries enough to need grouping (owner, 2026-08-18: the top and bottom rooms should carry
 * *"more items, like the left room"*). Several of these preferences already existed and were
 * simply never reachable from anywhere — keep-awake, shuffle, autoplay and looping animations were
 * all stored and honoured but had no control anywhere in the app.
 *
 * Deliberately still not the app's whole settings surface. Everything here changes what the screen
 * the user is on does; anything that does not belong to the act of viewing lives in the gallery's
 * settings room, and duplicating a preference in two places is how two settings screens end up
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
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black)
            // The shell insets a top room by its band at the BOTTOM (that is where the parked card
            // sits), so the system bar to clear here is the navigation bar, not the status bar --
            // the opposite of what a top-anchored surface usually wants.
            .navigationBarsPadding()
            // Scrollable because the room is now taller than the 70% of screen height the shell
            // gives a vertical room on a short phone. Without this the last group would simply be
            // unreachable, which is a worse failure than a scrollbar.
            .verticalScroll(rememberScrollState())
            .padding(
                start = RoomStyle.GutterStart,
                end = RoomStyle.GutterEnd,
                top = 28.dp,
                bottom = 20.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        RoomEyebrow("ON SCREEN")

        RoomRow(
            label = "Filmstrip",
            caption = "The strip of neighbouring photos along the bottom.",
            onClick = { onSetShowFilmstrip(!showFilmstrip) },
        ) { RoomToggle(showFilmstrip, onSetShowFilmstrip) }

        RoomRow(
            label = "Blur sensitive photos",
            caption = "Applies in the grid. A photo you have opened deliberately is never blurred.",
            onClick = { onSetBlurSensitive(!blurSensitive) },
        ) { RoomToggle(blurSensitive, onSetBlurSensitive) }

        RoomRow(
            label = "Keep the screen awake",
            caption = "While a photo is open. Costs battery.",
            onClick = { onSetKeepScreenOn(!keepScreenOn) },
        ) { RoomToggle(keepScreenOn, onSetKeepScreenOn) }

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
            caption = "Play in a random order instead of the order you are browsing in.",
            onClick = { onSetSlideshowShuffle(!slideshowShuffle) },
        ) { RoomToggle(slideshowShuffle, onSetSlideshowShuffle) }

        RoomRule(Modifier.padding(vertical = 14.dp))
        RoomEyebrow("PLAYS BY ITSELF")

        RoomRow(
            label = "Loop animations",
            caption = "GIFs and animated images play rather than showing one frame.",
            onClick = { onSetLoopAnimations(!loopAnimations) },
        ) { RoomToggle(loopAnimations, onSetLoopAnimations) }

        RoomRow(
            label = "Autoplay videos",
            caption = "Start a video as soon as it opens, without waiting for play.",
            onClick = { onSetAutoplayVideos(!autoplayVideos) },
        ) { RoomToggle(autoplayVideos, onSetAutoplayVideos) }

        RoomRule(Modifier.padding(vertical = 14.dp))
        RoomEyebrow("GESTURES")

        Text(
            text = "Pinch to zoom. Two fingers to turn. Swipe to move between photos. " +
                "Drag up from the bottom edge for this photo's details, in from the right for " +
                "what you can do with it.",
            color = RoomStyle.InkFaint,
            style = RoomStyle.Caption,
            modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
        )
    }
}
