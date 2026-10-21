package com.fotoxplorr.app.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaImage
import kotlin.math.max

/**
 * The photo you were looking at, shrinking and landing as the pin on the map in the details
 * room (owner: *"not a pane tilt, rather a shrink-to-appear-on-the-map effect"*).
 *
 * It is ONE copy of the photo, drawn above the shell, that follows the parked home card at the
 * start of the pull and is blended into the live pin tile by the end of it. It replaces the old
 * "flight", which was a second copy shrinking *inside* the plate -- a different effect that
 * never left the plate and, because the room slides 1:1 from above, mostly played while the
 * plate was still off screen.
 *
 * **A pure function of progress.** Every pixel of it is derived from `progress()` (the shell's
 * `vProgress`) by [PlaceMorph.flightFrame], read inside `graphicsLayer` / draw lambdas, so a
 * drag moves it without recomposing anything, a stationary finger holds it perfectly still, and
 * closing the room reverses it exactly. It has no state of its own and no animation of its own.
 *
 * **Pointer-free.** There is no `pointerInput` and no `clickable` anywhere in it, so the shell's
 * return drag on the parked card still receives every touch.
 *
 * **Privacy.** It draws only the photo. It fetches no map tile and pre-warms nothing: the map
 * behind the pin is still the one-shot, settle-gated snapshot [PlacePlate] documents.
 *
 * ## The integration contract (what `ViewerScreen` must supply)
 *
 * All rectangles are in ONE coordinate space: the **shell's** -- origin at the shell's top-left,
 * pixels. Place this overlay as a `fillMaxSize()` sibling drawn ABOVE the shell, inside the same
 * parent, so its origin IS the shell's. (If it ever sits in a differently-positioned parent,
 * subtract the offset between the two from `photoRect` before passing it.)
 *
 * - [progress]: the shell's `vProgress`, 0 = the photo, 1 = the details room fully open. Pass
 *   exactly what the room is given as `reveal`, so the two cannot disagree. POSITIVE for the top
 *   room; do not negate it.
 * - [photoRect]: where the photo is actually DRAWN at rest, in shell coordinates. Prefer the
 *   rectangle measured off the displayed image (`onGloballyPositioned` on it, translated to the
 *   shell's origin) over `PlaceMorph.fitRect(width, height, asset.width, asset.height)`: the
 *   stored dimensions can disagree with the decoded bitmap for an EXIF-rotated photo, and then
 *   the fit rect is the wrong shape. Must be the UNZOOMED rectangle, so reset zoom/pan before
 *   the pull or pass the rectangle the zoomed photo currently occupies. For a video pass the
 *   poster's rectangle: the flier draws the asset's thumbnail ([MediaImage] already produces
 *   one for a video), never the live player surface, which a layer cannot move reliably.
 * - [pinRect]: the pin tile's bounds in the ROOM's coordinates, exactly as
 *   `PhotoDetailRoom(onPinPositioned = ...)` reports them. Room coordinates are the room's own
 *   top-left, which is the shell's, so they need no conversion; the room sits at
 *   `y = -viewportHeight * (1 - progress)`, a rigid slide, which is why one measurement is
 *   enough and the flier derives the live position analytically ([PlaceMorph.pinRectAt]).
 *   A lambda so it can be a plain holder (not snapshot state) read at draw time. Return null
 *   until the first report; the flier draws nothing while it is null. **Clear the holder when
 *   the photo changes**, or a previous photo's position is used for one frame.
 * - [viewportSize]: the shell's size in px. [bandPx]: the grab band the shell leaves visible
 *   under the parked card, in px: `PlaceMorph.SHELL_BAND_DP * density` (cell-shell's `BAND_DP`).
 * - [landedDim]: `PlaceMorph.landedDim(placeKnown)` -- 0 for a photo with a place, the ghost
 *   dimming for one without, so the photo lands on [PlaceUnknownPlate]'s ghost pin in the same
 *   state that pin is drawn in.
 *
 * And on the other side of the same coin, the screen must:
 * 1. **Compose this only while `progress > 0.001`** (derive a Boolean from the state so the
 *    screen recomposes only when the flag flips, not every frame). It is cheap to draw but it
 *    holds a decoded image.
 * 2. **Pass `flightActive = true` to `PhotoDetailRoom` exactly when it composes this**, so the
 *    room's pin waits beneath the flier instead of playing a second hero shrink of its own. A
 *    `flightActive = true` with no flier on screen leaves the pin invisible until progress
 *    reaches [PlaceMorph.realPinVisible]'s threshold.
 * 3. **Park the home card with a flat slide while the top room is pulled** (cell-shell's
 *    `ParkStyle.SLIDE` -- `parkStyle = if (topPulling) SLIDE else SWIVEL`, chosen app-side,
 *    shared-libraries is another repo's submodule). The flier follows [PlaceMorph.parkedCardRect],
 *    which has no tilt term; under a swivel the card is not a rectangle and the flier would
 *    drift off it.
 * 4. **Draw the card's own photo underneath as usual.** The flier lifts off a card that keeps its
 *    photo (the card is the way back). Whether to dim that photo while the copy flies is the
 *    screen's call; nothing here depends on it.
 *
 * **Closing a scrolled room.** If the room was scrolled so the pin is no longer fully on screen
 * ([PlaceMorph.canFly] false) there is nowhere honest to land, so the flier draws nothing and the
 * room's pin fades in place instead (the room makes the same decision from the same numbers and
 * switches its pin to [PlaceMorph.PinMode.FADE]). No special case is needed in the screen.
 *
 * **Not verified on a device.** The geometry is pinned by `PlaceFlightGeometryTest`; the feel at
 * a real finger speed, the hand-off seam, and the match with a real shell are device checks.
 */
@Composable
fun PhotoToPinFlight(
    asset: MediaAsset,
    progress: () -> Float,
    photoRect: Rect?,
    pinRect: () -> Rect?,
    viewportSize: IntSize,
    bandPx: Float,
    modifier: Modifier = Modifier,
    landedDim: Float = 0f,
) {
    if (photoRect == null || photoRect.width <= 0f || photoRect.height <= 0f) return
    if (viewportSize.width <= 0 || viewportSize.height <= 0) return

    val density = LocalDensity.current
    val baseWidth = photoRect.width
    val baseHeight = photoRect.height
    val viewportWidth = viewportSize.width.toFloat()
    val viewportHeight = viewportSize.height.toFloat()
    // One path reused by every draw, rather than a fresh allocation per frame of a drag.
    val clip = remember { Path() }

    // Both lambdas below recompute the frame: it is a handful of float operations, and sharing
    // the result through a holder would depend on the order two phases run in.
    fun frameAt(pxPerDp: Float): PlaceMorph.FlightFrame? {
        val pin = pinRect() ?: return null
        return PlaceMorph.flightFrame(photoRect, pin, viewportWidth, viewportHeight, bandPx, progress(), pxPerDp)
    }

    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .size(
                    with(density) { baseWidth.toDp() },
                    with(density) { baseHeight.toDp() },
                )
                .graphicsLayer {
                    val frame = frameAt(this.density)
                    val placement = frame?.let { PlaceMorph.cropPlacement(baseWidth, baseHeight, it.photo) }
                    if (frame == null || placement == null || frame.alpha <= 0.001f) {
                        alpha = 0f
                        return@graphicsLayer
                    }
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = placement.scale
                    scaleY = placement.scale
                    translationX = placement.translationX
                    translationY = placement.translationY
                    alpha = frame.alpha
                }
                .drawWithContent {
                    val frame = frameAt(this.density) ?: return@drawWithContent
                    val placement = PlaceMorph.cropPlacement(baseWidth, baseHeight, frame.photo)
                        ?: return@drawWithContent
                    // This runs inside the scaled layer, so everything is in the photo's own
                    // pre-scale coordinates: screen sizes are divided by the layer's scale.
                    val scale = placement.scale
                    val frameInset = (frame.outer.width - frame.photo.width) / 2f / scale
                    val outerWindow = placement.window.inflate(frameInset)
                    val outerRadius = frame.outerCornerPx / scale
                    if (frameInset > 0.0001f && frame.frameAlpha > 0f) {
                        drawRoundRect(
                            color = Color.White.copy(alpha = frame.frameAlpha),
                            topLeft = outerWindow.topLeft,
                            size = outerWindow.size,
                            cornerRadius = CornerRadius(outerRadius),
                        )
                    }
                    clip.rewind()
                    clip.addRoundRect(RoundRect(placement.window, CornerRadius(max(0f, outerRadius - frameInset))))
                    clipPath(clip) { this@drawWithContent.drawContent() }
                    val darkness = landedDim * frame.flightT
                    if (darkness > 0f) {
                        drawRoundRect(
                            color = Color.Black.copy(alpha = darkness.coerceIn(0f, 1f)),
                            topLeft = outerWindow.topLeft,
                            size = outerWindow.size,
                            cornerRadius = CornerRadius(outerRadius),
                        )
                    }
                },
        ) {
            // The same request the room's pin makes, so the second copy is a cache hit and not a
            // second decode. Crop in a box of the photo's own aspect shows the whole photo; the
            // window drawn above is what narrows it to the pin's square.
            MediaImage(
                asset = asset,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}
