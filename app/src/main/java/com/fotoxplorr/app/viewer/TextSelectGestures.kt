package com.fotoxplorr.app.viewer

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope

/**
 * The one-finger gestures of the Select text mode, as a detector that knows nothing about text:
 * it reports a tap, or a stroke (start, every move with the whole path so far, end or cancel), in
 * the pointer-input node's own pixels. [LiveTextOverlay] turns those into selections.
 *
 * Why this is written by hand rather than composed from `detectTapGestures` and
 * `detectDragGestures`: the viewer already arbitrates pinch, pan and page in ONE loop because
 * stacked detectors race for the same down (see [ViewerScreen]'s own comment on that). This node
 * lives inside the zoom layer, a child of the viewer's detectors, so it sees every touch first,
 * and it has to be exact about three things the stock detectors cannot be:
 *
 *  - It CLAIMS the down (consumes it) so the viewer's own tap, double-tap and long-press detector,
 *    which only looks at unconsumed downs, stays out. That is what turns off the tap-to-hide-chrome,
 *    double-tap zoom and long-press Lift for as long as the mode is on.
 *  - It does NOT consume moves. A consumed move makes the viewer's pinch loop stand down for the
 *    rest of the touch, and a pinch begun after a stroke would then never zoom. The viewer's
 *    one-finger arbitration is switched off in this mode instead (see
 *    [ViewerChromePolicy.oneFingerGesturesAreViewers]), so a lone finger does nothing there, and
 *    a second finger reaches the pinch untouched.
 *  - A second finger ends this detector's part at once ([onCancel] if a stroke was under way,
 *    nothing otherwise) and the rest of that touch is the viewer's pinch.
 *
 * A press that never moves past touch slop is a tap however long it is held: in this mode a long
 * press is not a Lift (the mode is left first), and treating a held press as a tap is the least
 * surprising thing it can be.
 *
 * @param scale the viewer's current zoom, read live on every event, never captured. The node's
 *  pixels are the zoom layer's own (unscaled), so touch slop, a raw screen distance, has to be
 *  divided by it like every other distance in the mode ([textSelectSlop]).
 * @param onTap a press and release without moving past touch slop, at its first position.
 * @param onStart the finger passed touch slop; [Offset] is where it first went down.
 * @param onMove the stroke moved; the whole path so far, first position included.
 * @param onEnd the finger lifted at the end of a stroke; the whole path.
 * @param onCancel a stroke was abandoned (a second finger, or something else took the touch).
 */
internal suspend fun PointerInputScope.detectTextSelectGestures(
    scale: () -> Float,
    onTap: (Offset) -> Unit,
    onStart: (Offset) -> Unit,
    onMove: (List<Offset>) -> Unit,
    onEnd: (List<Offset>) -> Unit,
    onCancel: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        val path = ArrayList<Offset>()
        path.add(down.position)
        var drawing = false
        while (true) {
            val event = awaitPointerEvent()
            val slop = textSelectSlop(viewConfiguration.touchSlop, scale())
            // A second finger: this is a pinch, and it is the viewer's.
            if (event.changes.any { it.id != down.id }) {
                if (drawing) onCancel()
                return@awaitEachGesture
            }
            val change = event.changes.firstOrNull { it.id == down.id }
            if (change == null || change.isConsumed) {
                if (drawing) onCancel()
                return@awaitEachGesture
            }
            if (!change.pressed) {
                change.consume()
                if (drawing) {
                    path.add(change.position)
                    onEnd(path.toList())
                } else {
                    onTap(down.position)
                }
                return@awaitEachGesture
            }
            if (change.position != path.last()) path.add(change.position)
            if (!drawing && (change.position - down.position).getDistance() > slop) {
                drawing = true
                onStart(down.position)
            }
            if (drawing) onMove(path.toList())
        }
    }
}

/**
 * Touch slop in the overlay's own pixels. The overlay sits inside the zoom layer, so a screen
 * distance of [touchSlop] is [touchSlop] / zoom of its pixels: left undivided, at 6x the finger
 * would have to travel six times as far on screen before a stroke began, and a short drag over a
 * word or two would be read as a tap.
 */
internal fun textSelectSlop(touchSlop: Float, scale: Float): Float =
    touchSlop / scale.coerceAtLeast(TextSelectTouch.MIN_ZOOM)
