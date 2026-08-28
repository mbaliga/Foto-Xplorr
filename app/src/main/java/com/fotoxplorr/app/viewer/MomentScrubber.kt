package com.fotoxplorr.app.viewer

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.moments.VideoMoment
import java.util.Locale
import kotlin.math.roundToLong

/**
 * The video timeline: a track that is grey everywhere except the stretches around key moments,
 * which are drawn in a highlight colour, with a white line at the current position.
 *
 * The interesting part is entirely in the three pure functions below -- [momentFraction],
 * [highlightSegments] and [formatTimecode] -- which have no Android imports and are exercised
 * directly by `MomentScrubberGeometryTest` on the JVM, the same split [LiveTextOverlay] uses for
 * its own screen-mapping arithmetic ([fittedImageRect], [toScreenRect], [blockAt]) and for the
 * identical reason: this is the part of a scrubber that goes subtly wrong (an off-by-one at a
 * duration of zero, two adjacent markers drawing as one pixel-wide seam) and the part worth
 * pinning down rather than eyeballing on a device this environment does not have.
 */

/**
 * One highlighted stretch of the track, as fractions of the whole duration (`0f` at the first
 * frame, `1f` at the last).
 *
 * Fractions rather than a millisecond range: the composable that draws this only ever multiplies
 * these by a pixel width, and handing it something already in that unit means it never has to see
 * [Long] millisecond arithmetic or the duration a second time.
 */
internal data class TrackSegment(val startFraction: Float, val endFraction: Float)

/**
 * Where one instant sits on the track, as a fraction of the whole video.
 *
 * Guards a division that genuinely does hit zero in normal use: `durationMs` is 0 for every
 * polling tick before `VideoView`'s `onPrepared` fires (see [VideoPlayer]), and `positionMs / 0`
 * would be an `Infinity` or a `NaN` the moment it is multiplied into a pixel offset a few lines of
 * caller code later -- returning `0f` here means the marker sits quietly at the left edge for
 * that first instant instead of the draw call failing somewhere downstream, at a call site that
 * has no idea a duration of zero was ever involved.
 *
 * Clamped, not merely divided: a `positionMs` past `durationMs` (VideoView occasionally reports a
 * position a few milliseconds beyond the length it itself reported once decoding reaches the very
 * last frame) is pinned to `1f` rather than allowed to draw the marker past the end of its own
 * track.
 */
internal fun momentFraction(positionMs: Long, durationMs: Long): Float {
    if (durationMs <= 0L) return 0f
    return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
}

/**
 * The highlighted stretches to paint on the track: a [halfWidthMs] window on either side of every
 * moment in [moments], clamped to the video's own bounds and MERGED wherever two windows touch or
 * overlap.
 *
 * Merging is not a cosmetic nicety. Two AUTO moments a detector placed close together -- a burst
 * of motion spanning several of its sampling windows is a genuinely common case, not a pathology
 * -- would otherwise draw as two rounded segments pressed edge to edge, and at the pixel widths a
 * phone's scrubber actually has, that renders as a single hairline seam bisecting what reads as
 * one highlight: indistinguishable from a rendering bug. The merge is done in millisecond space,
 * before the fraction conversion, so "do these two windows touch" is exact integer comparison
 * rather than comparing two floats that each came from an independent division.
 *
 * @param halfWidthMs how far a highlight extends on EITHER side of a moment's exact position, in
 *   the source video's own milliseconds -- not a fraction of the duration, because a fixed
 *   fractional width would make the highlight around a moment in a ten-second clip a tiny sliver
 *   compared to the same fraction of an eight-minute one, when what "near this moment" means to a
 *   person watching either video is the same handful of real seconds either way.
 */
internal fun highlightSegments(
    moments: List<VideoMoment>,
    durationMs: Long,
    halfWidthMs: Long,
): List<TrackSegment> {
    if (durationMs <= 0L || moments.isEmpty()) return emptyList()

    val windows = moments
        .map { moment ->
            // Clamped BEFORE windowing, not after: a moment recorded past the video's own length
            // (the indexer ran against a slightly different duration reading than this playback
            // session has, for instance) still produces a valid window pinned to the last instant
            // of the track, rather than a window that starts beyond `durationMs` and is silently
            // dropped by the merge below for having no valid position to sort on.
            val clamped = moment.positionMs.coerceIn(0L, durationMs)
            (clamped - halfWidthMs).coerceAtLeast(0L) to (clamped + halfWidthMs).coerceAtMost(durationMs)
        }
        .sortedBy { it.first }

    val merged = mutableListOf<Pair<Long, Long>>()
    for (window in windows) {
        val open = merged.lastOrNull()
        if (open != null && window.first <= open.second) {
            // `<=`, not `<`: two windows that meet EXACTLY at a shared boundary must merge too --
            // see the class doc above. `<` would leave a one-value gap that draws as a grey
            // hairline between two segments that are actually touching.
            merged[merged.lastIndex] = open.first to maxOf(open.second, window.second)
        } else {
            merged += window
        }
    }

    return merged.map { (start, end) ->
        TrackSegment(
            startFraction = start.toFloat() / durationMs.toFloat(),
            endFraction = end.toFloat() / durationMs.toFloat(),
        )
    }
}

/**
 * "0:04" -- minutes:seconds with no leading zero on the minutes, matching the reference exactly
 * ("0:04 / 0:20", not "00:04").
 *
 * Deliberately NOT [DetailFormatting.durationLine]: that formatter's contract is to print the
 * string "Unknown" for a duration of zero or less, which is the right answer on an EXIF info card
 * and the wrong one here -- a video that has just started genuinely IS at "0:00", and this chrome
 * would print "Unknown" for an instant at the start of every single playback if it reused that
 * formatter for the live position readout.
 *
 * Negative input clamps to zero rather than printing a sign: the only way this chrome ever calls
 * this with a negative number is a not-yet-prepared player reporting a position of `-1`, and
 * "0:00" for "not known yet" is a far less alarming thing to flash on screen than "-0:01".
 */
internal fun formatTimecode(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1_000L
    return "%d:%02d".format(Locale.US, totalSeconds / 60L, totalSeconds % 60L)
}

/**
 * The scrubber itself: a thin rounded track, the highlighted stretches from [highlightSegments]
 * drawn over it, and a white line at the current position -- draggable, and tappable anywhere to
 * jump there directly.
 *
 * The touch target ([TRACK_TOUCH_HEIGHT]) is taller than the drawn bar ([TRACK_VISUAL_HEIGHT]):
 * the visible track matches the reference's slim proportions, but a hit box that thin would make
 * this nearly unusable with a fingertip. This mismatch is exactly what the two separate constants
 * are for.
 *
 * @param onSeek called continuously while dragging (so the player can seek live, the way a scrub
 *   is meant to preview) and once on a plain tap. Does not itself move [positionMs] -- that is the
 *   caller's job, driven by whatever `VideoView.seekTo` + polling does with the request -- which
 *   is why this composable tracks its OWN transient drag position (see the comment on `dragMs`
 *   below) rather than assuming [positionMs] updates synchronously with the call.
 */
@Composable
fun MomentScrubber(
    positionMs: Long,
    durationMs: Long,
    moments: List<VideoMoment>,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    // While a finger is down, the marker follows the TOUCH rather than [positionMs]: the caller's
    // position comes from polling roughly every 150ms (see VideoPlayer's own KDoc), so without
    // this the thumb would visibly lag a live drag by up to that long, snapping forward in small
    // jumps instead of tracking the finger continuously. Reset to null the instant the gesture
    // ends, handing control back to the authoritative value the caller reports.
    var dragMs by remember { mutableStateOf<Long?>(null) }
    val shownPositionMs = dragMs ?: positionMs

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(TRACK_TOUCH_HEIGHT)
            .pointerInput(durationMs) {
                // A duration of 0 means the player has not reported a real length yet (see
                // momentFraction's own KDoc on why that happens). Seeking against an unknown
                // length would send onSeek a position computed from a division that is about to
                // be wrong the moment the real duration arrives, so scrubbing is simply not wired
                // up until there is a real length to scrub against -- the alternative is a seek
                // that is silently mis-scaled for the first fraction of a second of every playback.
                if (durationMs <= 0L) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    // Consumed unconditionally, tap or drag: this touch belongs to the scrubber,
                    // and leaving it unconsumed would let ViewerScreen's own outer tap detector
                    // also see it once it bubbles up -- exactly the failure LiftOverlay's KDoc
                    // documents avoiding for the same reason ("a tap that lands here is consumed
                    // here and never reaches the outer chrome-toggle"). Without this, a tap meant
                    // only to jump the playhead would ALSO flip chromeVisible off, hiding the
                    // control the user just touched.
                    down.consume()
                    // Read once per gesture rather than once per pointerInput instance: reading it
                    // here means a resize between two gestures (a rotation) is picked up, at the
                    // small cost of not tracking a resize that happens mid-drag, which is not a
                    // real scenario on a phone.
                    val trackWidth = size.width.toFloat()

                    fun seekToX(x: Float) {
                        if (trackWidth <= 0f) return
                        val fraction = (x / trackWidth).coerceIn(0f, 1f)
                        val ms = (fraction * durationMs).roundToLong()
                        dragMs = ms
                        onSeek(ms)
                    }

                    seekToX(down.position.x)
                    drag(down.id) { change ->
                        seekToX(change.position.x)
                        change.consume()
                    }
                    dragMs = null
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(TRACK_VISUAL_HEIGHT)
                .clip(RoundedCornerShape(percent = 50))
                .drawBehind {
                    drawRect(color = TRACK_GREY)

                    highlightSegments(moments, durationMs, MOMENT_HIGHLIGHT_HALF_WIDTH_MS).forEach { segment ->
                        val left = segment.startFraction * size.width
                        val right = segment.endFraction * size.width
                        drawRect(
                            color = TRACK_HIGHLIGHT,
                            topLeft = Offset(left, 0f),
                            size = Size(right - left, size.height),
                        )
                    }

                    val markerWidthPx = MARKER_WIDTH.toPx()
                    val markerX = (momentFraction(shownPositionMs, durationMs) * size.width - markerWidthPx / 2f)
                        .coerceIn(0f, (size.width - markerWidthPx).coerceAtLeast(0f))
                    drawRect(
                        color = Color.White,
                        topLeft = Offset(markerX, 0f),
                        size = Size(markerWidthPx, size.height),
                    )
                },
        )
    }
}

private val TRACK_TOUCH_HEIGHT = 28.dp
private val TRACK_VISUAL_HEIGHT = 4.dp
private val MARKER_WIDTH = 3.dp

/** See [highlightSegments]'s own KDoc for why this is a fixed millisecond width, not a fraction. */
private const val MOMENT_HIGHLIGHT_HALF_WIDTH_MS = 1_200L

private val TRACK_GREY = Color.White.copy(alpha = 0.28f)

/**
 * A warm pink/salmon, matching the owner's reference screenshot as closely as this environment
 * lets a colour be matched -- there is no source image to sample a hex from here, only the
 * description "warm pink/salmon", so this is a considered judgement call rather than a measured
 * value. Named once and used in exactly one place, so retuning it against the real reference is a
 * one-line change.
 */
private val TRACK_HIGHLIGHT = Color(0xFFFF7A6E)
