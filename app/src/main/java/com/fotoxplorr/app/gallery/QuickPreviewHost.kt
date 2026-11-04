package com.fotoxplorr.app.gallery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerType
import kotlinx.coroutines.delay

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
): Modifier {
    var state: HoverPreview by remember(id) { mutableStateOf(HoverPreview.Idle) }
    val clock = remember { System::currentTimeMillis }
    // The wait runs only while Pending; leaving or pressing resets the state and cancels it.
    val pending = state as? HoverPreview.Pending
    if (pending != null) {
        LaunchedEffect(pending) {
            delay(state.remainingMs(clock()) ?: 0L)
            state = state.onTick(clock())
            onShown(state.shownId())
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

/** Space / arrows / Enter / Escape to [QuickLook]; returns true when the key was used. */
internal fun Modifier.quickLookKeys(
    state: () -> QuickLook,
    focused: () -> Int?,
    count: () -> Int,
    columns: () -> Int,
    onChange: (QuickLook, openViewer: Boolean) -> Unit,
): Modifier = onPreviewKeyEvent { e ->
    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    val k = when (e.key) {
        Key.Spacebar -> QuickLookKey.SPACE
        Key.Escape -> QuickLookKey.ESCAPE
        Key.DirectionLeft -> QuickLookKey.LEFT
        Key.DirectionRight -> QuickLookKey.RIGHT
        Key.DirectionUp -> QuickLookKey.UP
        Key.DirectionDown -> QuickLookKey.DOWN
        Key.Enter -> QuickLookKey.ENTER
        else -> return@onPreviewKeyEvent false
    }
    val r = state().onKey(k, focused(), count(), columns())
    if (r.consumed) onChange(r.state, r.openViewer)
    r.consumed
}
