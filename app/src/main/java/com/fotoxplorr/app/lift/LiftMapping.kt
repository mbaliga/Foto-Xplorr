package com.fotoxplorr.app.lift

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.viewer.fittedImageRect
import com.fotoxplorr.core.formats.MediaFormat
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** A pixel position inside a decoded bitmap. */
data class PixelPoint(val x: Int, val y: Int)

/**
 * Where a touch on the screen lands on the DECODED bitmap, or null when it lands in the letterbox
 * (or the inputs are degenerate).
 *
 * Takes the bitmap's own width and height, never [MediaAsset.width]/[MediaAsset.height]. The
 * overlay segments and draws the EXIF-upright decode, while the asset's dimensions are whatever
 * MediaStore stored -- which for a portrait JPEG can be the sensor's un-rotated grid. Fitting the
 * wrong aspect ratio into the container shifts the image rect, so a tap on the real photo can
 * fall "outside" it and dismiss the overlay (the tap that looks like it did nothing), and a tap
 * that does land inside seeds the wrong pixel. The bitmap in hand is the only source of truth for
 * what is on screen, so it is the only thing to map through.
 */
fun imagePointToBitmapPixel(point: Offset, containerSize: Size, bitmapWidth: Int, bitmapHeight: Int): PixelPoint? {
    val norm = imagePointToNormalized(point, containerSize, bitmapWidth, bitmapHeight, clamp = false) ?: return null
    return PixelPoint(
        x = (norm.x * bitmapWidth).toInt().coerceIn(0, bitmapWidth - 1),
        y = (norm.y * bitmapHeight).toInt().coerceIn(0, bitmapHeight - 1),
    )
}

/**
 * [imagePointToBitmapPixel] without the quantisation: a position as a fraction (0..1) of the
 * image's width and height, which is the resolution-independent form [SelectionOp] strokes are
 * stored in. With [clamp] false a point in the letterbox returns null; with it true the point is
 * pulled onto the nearest edge of the image, which is what a stroke that wanders off the photo
 * wants (the scribble keeps going, it just stops at the border).
 */
fun imagePointToNormalized(
    point: Offset,
    containerSize: Size,
    bitmapWidth: Int,
    bitmapHeight: Int,
    clamp: Boolean,
): NormPoint? {
    if (bitmapWidth <= 0 || bitmapHeight <= 0 || containerSize.width <= 0f || containerSize.height <= 0f) return null
    val rect = fittedImageRect(containerSize, bitmapWidth, bitmapHeight)
    if (rect.width <= 0f || rect.height <= 0f) return null
    if (!clamp && !rect.contains(point)) return null
    return NormPoint(
        x = ((point.x - rect.left) / rect.width).coerceIn(0f, 1f),
        y = ((point.y - rect.top) / rect.height).coerceIn(0f, 1f),
    )
}

/**
 * Whether lift is offered for [asset] at all: a still raster photo the platform can decode.
 *
 * Not video; not SVG (a vector has no pixels to separate and rasterising it would defeat the
 * point); not RAW (no decoder, see [MediaFormat.Raw]); not an animated image ([isAnimated] comes
 * from the caller because the asset alone cannot tell a still GIF from an animated one); and
 * nothing the classifier already says the platform cannot decode. Unrecognised formats are
 * allowed through -- the decode then fails into a clear "couldn't open this photo" state rather
 * than hiding a feature that might have worked.
 */
fun canLiftSubject(asset: MediaAsset, isAnimated: Boolean = false): Boolean {
    if (asset.isVideo || isAnimated) return false
    return when (val format = asset.format) {
        MediaFormat.Svg, MediaFormat.Video -> false
        is MediaFormat.Raw -> false
        else -> format.isLikelyDecodable
    }
}

/** Why a lift did not produce a cutout, in the terms the person needs to act on. */
sealed interface LiftFailure {
    /** The solve ran but selected nothing: the marks sat on no region the solver could grow. */
    data object NoSubject : LiftFailure

    /**
     * The solve selected nearly the whole frame and nothing told it where the subject ends: a
     * warning, not a dead end (a close-up can really fill the frame), so the cut-out stays usable.
     */
    data object TooMuch : LiftFailure

    /** The photo (or its working copies) did not fit in memory. */
    data object OutOfMemory : LiftFailure

    /** The file could not be opened or decoded at all. */
    data object Unreadable : LiftFailure

    /** Anything else; [detail] is the exception message when there was one. */
    data class Failed(val detail: String?) : LiftFailure
}

/** A selection covering more than this fraction of the frame, with no background mark, is suspect. */
const val TOO_MUCH_COVERAGE = 0.85f

/**
 * Whether a finished solve is a failure worth telling the person about: [LiftFailure.NoSubject]
 * when there is a foreground mark but nothing was selected, [LiftFailure.TooMuch] when [mask]
 * covers more than [TOO_MUCH_COVERAGE] of the frame and the person has said nothing about what the
 * subject is NOT; null otherwise. "Said something" is a background hint or any painted correction:
 * past that point the person is steering and a large selection is their call.
 */
fun solveFailureFor(mask: SubjectSegmentation.Mask, session: SelectionSession): LiftFailure? {
    if (!session.hasForeground) return null
    if (SubjectSegmentation.boundingBox(mask) == null) return LiftFailure.NoSubject
    val steered = session.ops.any { (it is SelectionOp.Hint && !it.foreground) || it is SelectionOp.Paint }
    return if (!steered && coverage(mask) > TOO_MUCH_COVERAGE) LiftFailure.TooMuch else null
}

/** The fraction (0..1) of [mask]'s pixels that are more selected than not. */
fun coverage(mask: SubjectSegmentation.Mask): Float {
    if (mask.alpha.isEmpty()) return 0f
    var covered = 0
    for (a in mask.alpha) if (a >= 0.5f) covered++
    return covered.toFloat() / mask.alpha.size
}

/**
 * Sort a thrown [Throwable] into a [LiftFailure]. The OutOfMemoryError may arrive wrapped (a
 * decoder rethrowing it as the cause of its own exception), so the whole cause chain is checked
 * before anything is called a generic error.
 */
fun classifyLiftFailure(throwable: Throwable): LiftFailure {
    val chain = generateSequence(throwable) { it.cause }.take(MAX_CAUSE_DEPTH).toList()
    return when {
        chain.any { it is OutOfMemoryError } -> LiftFailure.OutOfMemory
        chain.any { it is IOException || it is SecurityException } -> LiftFailure.Unreadable
        else -> LiftFailure.Failed(throwable.message)
    }
}

/** The sentence shown for [this] failure. */
fun LiftFailure.userMessage(): String = when (this) {
    LiftFailure.NoSubject -> "Couldn't find a clear subject there"
    LiftFailure.TooMuch -> "That selected nearly everything"
    LiftFailure.OutOfMemory -> "This photo is too large to lift on this device"
    LiftFailure.Unreadable -> "Couldn't open this photo for lifting"
    is LiftFailure.Failed -> "Something went wrong while lifting this photo"
}

/**
 * [runCatching] that lets coroutine cancellation through. The plain version catches the
 * [CancellationException] a cancelled suspend call throws and turns it into a "failure", so a
 * decode abandoned because the person left the screen would carry on and write state into a
 * composition that no longer exists.
 */
inline fun <T> catchingResult(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (c: CancellationException) {
    throw c
} catch (t: Throwable) {
    Result.failure(t)
}

private const val MAX_CAUSE_DEPTH = 8
