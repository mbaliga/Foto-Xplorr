package com.fotoxplorr.app.gallery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerType
import kotlinx.coroutines.delay
import android.os.SystemClock

/**
 * The Android seam of [HoverPreview]: reports the tile a MOUSE pointer rests on. Touch never
 * hovers, so a finger is ignored and the long-press peek stays the touch path. Unverified on a
 * device (no mouse has run it); the decisions it makes are in [HoverPreview] and are tested.
 */
@Composable
internal fun Modifier.hoverPreview(
    id: Long,
    onShown: (Long?) -> Unit,
    onScrubX: ((x: Float, width: Float) -> Unit)? = null,
    /** Monotonic, the same clock `delay` runs on; a wall clock can step and stall the wait. */
    clock: () -> Long = SystemClock::uptimeMillis,
): Modifier {
    var state: HoverPreview by remember(id) { mutableStateOf(HoverPreview.Idle) }
    val currentOnShown by rememberUpdatedState(onShown)
    // The wait runs only while Pending; leaving or pressing changes the state, which cancels it.
    val pending = state as? HoverPreview.Pending
    if (pending != null) {
        LaunchedEffect(pending) {
            val shown = pending.awaitShown(clock) { delay(it) }
            state = shown
            currentOnShown(shown.shownId())
        }
    }
    return this.pointerInput(id) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull() ?: continue
                if (change.type != PointerType.Mouse) continue
                when (event.type) {
                    PointerEventType.Enter, PointerEventType.Move -> {
                        state = state.onEnter(id, clock())
                        onScrubX?.invoke(change.position.x, size.width.toFloat())
                    }
                    PointerEventType.Exit, PointerEventType.Press, PointerEventType.Scroll -> {
                        state = state.onDismiss()
                        onShown(null)
                    }
                    else -> Unit
                }
            }
        }
    }
}

/** The Compose key Quick Look answers to, or null for one it leaves alone. */
internal fun quickLookKeyFor(key: Key): QuickLookKey? = when (key) {
    Key.Spacebar -> QuickLookKey.SPACE
    Key.Escape -> QuickLookKey.ESCAPE
    Key.DirectionLeft -> QuickLookKey.LEFT
    Key.DirectionRight -> QuickLookKey.RIGHT
    Key.DirectionUp -> QuickLookKey.UP
    Key.DirectionDown -> QuickLookKey.DOWN
    Key.Enter -> QuickLookKey.ENTER
    else -> null
}
