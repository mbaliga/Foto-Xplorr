package com.fotoxplorr.app.animate

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.InfiniteAnimationPolicy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlin.coroutines.coroutineContext
import com.fotoxplorr.app.ui.RoomStyle

/**
 * A looping preview, played in place by a frame loop: the pictures are made once into bitmaps, and a
 * clock ([PreviewClock]) says which one is up. It plays the preview's own timing for ever, whatever
 * the saved file's repeat setting will be.
 *
 * Frame loop: `withFrameNanos`, asked through the `InfiniteAnimationPolicy` when the context has one (a test clock does, and
 * then stops a loop it is not driving so the test can go idle; the app has none), inside `repeatOnLifecycle(RESUMED)`, as the aquarium's, so it runs only
 * while this screen is resumed and stops the moment the app is paused or stopped. When it starts again
 * the first step is zero, and one long step is held to [PreviewClock.MAX_STEP_MS], so a pause is never
 * one big jump. [startPaused] (the system's "remove animations" setting) opens it paused, and it
 * then moves only after Play. A paused player says which frame it is on to a screen reader; a playing
 * one says nothing new each frame.
 *
 * The bitmaps are left to the collector rather than recycled: a recycled bitmap still drawn for one
 * more frame is a crash, and 24 small pictures are about 4 MB. Nothing here has run on a device.
 */
@Composable
internal fun FramePlayer(set: PreviewSet, startPaused: Boolean, modifier: Modifier = Modifier) {
    val images = remember(set) {
        set.frames.map { Bitmap.createBitmap(it.argb, set.width, set.height, Bitmap.Config.ARGB_8888).asImageBitmap() }
    }
    val delays = remember(set) { set.delaysMs }
    var playing by remember(set) { mutableStateOf(!startPaused) }
    var elapsed by remember(set) { mutableLongStateOf(0L) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(set, playing, lifecycle) {
        if (!playing) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var last = 0L
            while (true) {
                nextFrameNanos { now ->
                    if (last != 0L) elapsed = PreviewClock.advance(elapsed, now - last)
                    last = now
                }
            }
        }
    }

    val index = PreviewClock.frameAt(delays, elapsed)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Image(
            bitmap = images[index],
            contentDescription = AnimateScreenCopy.PREVIEW_DESCRIPTION,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = MAX_HEIGHT)
                .background(Color.Black)
                .testTag(ANIM_PLAYER_TAG)
                .semantics { if (!playing) stateDescription = AnimateScreenCopy.previewFrame(index + 1, images.size) },
            contentScale = ContentScale.Fit,
        )
        AnButton(if (playing) AnimateScreenCopy.PREVIEW_PAUSE else AnimateScreenCopy.PREVIEW_PLAY, { playing = !playing })
        if (startPaused) {
            Text(AnimateScreenCopy.PREVIEW_MOTION_OFF, style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkFaint)
        }
    }
}

private val MAX_HEIGHT = 260.dp

/** The next frame's time, through the context's [InfiniteAnimationPolicy] if it has one: the same as Compose's own infinite animations. */
private suspend fun <R> nextFrameNanos(onFrame: (Long) -> R): R {
    val policy = coroutineContext[InfiniteAnimationPolicy]
    return if (policy == null) withFrameNanos(onFrame) else policy.onInfiniteOperation { withFrameNanos(onFrame) }
}
