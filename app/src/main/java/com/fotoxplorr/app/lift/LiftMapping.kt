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
    if (bitmapWidth <= 0 || bitmapHeight <= 0 || containerSize.width <= 0f || containerSize.height <= 0f) return null
    val rect = fittedImageRect(containerSize, bitmapWidth, bitmapHeight)
    if (rect.width <= 0f || rect.height <= 0f || !rect.contains(point)) return null
    val fx = ((point.x - rect.left) / rect.width).coerceIn(0f, 1f)
    val fy = ((point.y - rect.top) / rect.height).coerceIn(0f, 1f)
    return PixelPoint(
        x = (fx * bitmapWidth).toInt().coerceIn(0, bitmapWidth - 1),
        y = (fy * bitmapHeight).toInt().coerceIn(0, bitmapHeight - 1),
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

    /** The photo (or its working copies) did not fit in memory. */
    data object OutOfMemory : LiftFailure

    /** The file could not be opened or decoded at all. */
    data object Unreadable : LiftFailure

    /** Anything else; [detail] is the exception message when there was one. */
    data class Failed(val detail: String?) : LiftFailure
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
