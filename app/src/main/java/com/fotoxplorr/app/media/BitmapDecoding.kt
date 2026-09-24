package com.fotoxplorr.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * P0-03: the one place in this app that turns a content [Uri] into pixels. Every prior call site
 * (`editor/EditorScreen.kt`, `share/SharePreparer.kt`, `share/ShareOptionsSheet.kt`,
 * `viewer/PhotoDetailRoom.kt`, `lift/LiftOverlay.kt`, `recognition/RecognitionIndexer.kt`,
 * `ai/SimilarityIndexer.kt`, `spatial/SpatialOpenGlScene.kt`, `experience/PhotoWallRenderer.kt`)
 * had its own `BitmapFactory` call that ignored EXIF orientation entirely (sideways photos in the
 * editor and on export) and downscaled with a power-of-two `inSampleSize` that lands *below* the
 * requested target rather than at it.
 */
data class DecodeLimits(val maxLongEdge: Int, val maxPixels: Long)

data class DecodedBitmap(
    val bitmap: Bitmap,
    /** The upright source's own dimensions -- after accounting for a 90/270 EXIF swap, so this is
     * directly comparable to [bitmap]'s own width/height when deciding whether [downscaled] is
     * true, and to report "the original was this big" in a UI message. */
    val sourceWidth: Int,
    val sourceHeight: Int,
    val downscaled: Boolean,
)

/**
 * Decodes [uri] upright (EXIF orientation baked into the pixels -- callers write `Orientation = 1`
 * whenever they write EXIF afterward) and bounded to [limits], never upscaled. Returns null if the
 * source can't be decoded at all (a corrupt or unreadable file).
 *
 * **API 28+:** [ImageDecoder], `ALLOCATOR_SOFTWARE`, `setTargetSize` for an exact target.
 * [ImageDecoder] (via Skia's `SkAndroidCodec`) already applies EXIF orientation for the formats it
 * decodes directly -- JPEG, PNG, WebP, HEIF/HEIC, GIF, BMP, which is every format this app's own
 * editor/share/preview paths hand it -- so this path does **not** rotate again; see
 * [BitmapDecodingImageDecoderExifTest] for this session's own empirical confirmation of that,
 * against this exact Robolectric/AGP/SDK setup, and Decisions in
 * `docs/handoff/PHASE-0-PROGRESS.md` for the sourcing. (Android's *raw* decoder path, a different
 * codec Skia doesn't own, does not apply orientation -- out of scope here, since none of the nine
 * call sites this task replaces decode RAW/DNG.)
 *
 * **Fallback** (API 26-27, or any [ImageDecoder] exception): a [BitmapFactory] bounds pass (via a
 * stream, never `readBytes()`), the largest power-of-two `inSampleSize` whose result is still ≥
 * the target, [Bitmap.createScaledBitmap] to the exact target, then a [Matrix] applies the EXIF
 * orientation read from a second stream -- [BitmapFactory] has never applied it.
 */
suspend fun decodeUpright(context: Context, uri: Uri, limits: DecodeLimits): DecodedBitmap? =
    withContext(Dispatchers.IO) {
        val orientation = readOrientation(context, uri)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { decodeWithImageDecoder(context, uri, limits) }.getOrNull()?.let { return@withContext it }
        }
        decodeWithBitmapFactory(context, uri, limits, orientation)
    }

private fun readOrientation(context: Context, uri: Uri): Int =
    context.contentResolver.openInputStream(uri)?.use { stream ->
        ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } ?: ExifInterface.ORIENTATION_NORMAL

@RequiresApi(Build.VERSION_CODES.P)
private fun decodeWithImageDecoder(context: Context, uri: Uri, limits: DecodeLimits): DecodedBitmap? {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    var sourceW = 0
    var sourceH = 0
    var target = 0 to 0
    val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        sourceW = info.size.width
        sourceH = info.size.height
        target = targetSize(sourceW, sourceH, limits)
        decoder.setTargetSize(target.first, target.second)
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.isMutableRequired = false
    } ?: return null
    return DecodedBitmap(
        bitmap = bitmap,
        sourceWidth = sourceW,
        sourceHeight = sourceH,
        downscaled = target.first < sourceW || target.second < sourceH,
    )
}

private fun decodeWithBitmapFactory(
    context: Context,
    uri: Uri,
    limits: DecodeLimits,
    orientation: Int,
): DecodedBitmap? {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
    val rawW = bounds.outWidth
    val rawH = bounds.outHeight
    if (rawW <= 0 || rawH <= 0) return null

    val ops = orientationTransform(orientation)
    // targetSize works in *upright* terms -- the caller's limits describe what they want to see,
    // not the raw, possibly-sideways encoded grid.
    val uprightRawW = if (ops.swapsDimensions) rawH else rawW
    val uprightRawH = if (ops.swapsDimensions) rawW else rawH
    val (targetUprightW, targetUprightH) = targetSize(uprightRawW, uprightRawH, limits)
    // ...translated back to the raw (pre-rotation) grid BitmapFactory itself decodes.
    val targetRawW = if (ops.swapsDimensions) targetUprightH else targetUprightW
    val targetRawH = if (ops.swapsDimensions) targetUprightW else targetUprightH

    val decodeOptions = BitmapFactory.Options().apply {
        inSampleSize = sampleSizeFor(rawW, rawH, targetRawW, targetRawH)
    }
    val sampled = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOptions) }
        ?: return null
    val scaled = if (sampled.width == targetRawW && sampled.height == targetRawH) {
        sampled
    } else {
        Bitmap.createScaledBitmap(sampled, targetRawW, targetRawH, true).also {
            if (it !== sampled) sampled.recycle()
        }
    }
    val upright = applyOrientation(scaled, ops)
    return DecodedBitmap(
        bitmap = upright,
        sourceWidth = uprightRawW,
        sourceHeight = uprightRawH,
        downscaled = targetUprightW < uprightRawW || targetUprightH < uprightRawH,
    )
}

private fun applyOrientation(bitmap: Bitmap, ops: OrientationOps): Bitmap {
    if (ops.rotationDegrees == 0 && !ops.flipHorizontal) return bitmap
    val matrix = Matrix()
    if (ops.rotationDegrees != 0) matrix.setRotate(ops.rotationDegrees.toFloat())
    if (ops.flipHorizontal) matrix.postScale(-1f, 1f)
    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    if (rotated !== bitmap) bitmap.recycle()
    return rotated
}

/**
 * The exact size to decode at: preserves the source's aspect ratio, keeps the long edge within
 * [DecodeLimits.maxLongEdge] and the pixel count within [DecodeLimits.maxPixels], and never
 * upscales past the source's own size. Both dimensions are `[Int]`, computed from a single scale
 * factor so aspect ratio is exact up to integer rounding.
 */
internal fun targetSize(srcW: Int, srcH: Int, limits: DecodeLimits): Pair<Int, Int> {
    if (srcW <= 0 || srcH <= 0) return srcW.coerceAtLeast(0) to srcH.coerceAtLeast(0)
    val longEdge = max(srcW, srcH)
    val scaleByEdge = if (longEdge > limits.maxLongEdge) limits.maxLongEdge.toDouble() / longEdge else 1.0
    val srcPixels = srcW.toLong() * srcH.toLong()
    val scaleByPixels = if (srcPixels > limits.maxPixels) {
        sqrt(limits.maxPixels.toDouble() / srcPixels.toDouble())
    } else {
        1.0
    }
    val scale = min(1.0, min(scaleByEdge, scaleByPixels))
    val targetW = max(1, (srcW * scale).roundToInt())
    val targetH = max(1, (srcH * scale).roundToInt())
    return targetW to targetH
}

/**
 * The largest power-of-two `inSampleSize` whose decoded result is still ≥ ([targetW], [targetH])
 * on both axes -- so the exact-size [Bitmap.createScaledBitmap] pass that follows only ever scales
 * *down*. The classic Android "Loading Large Bitmaps Efficiently" algorithm, factored out and unit
 * tested on its own since it was the source of the "lands below the target" defect: nothing
 * upstream of this function was previously translating the *chosen* `inSampleSize`'s result back
 * up to the actual requested target size at all.
 */
internal fun sampleSizeFor(srcW: Int, srcH: Int, targetW: Int, targetH: Int): Int {
    var sampleSize = 1
    if (targetW <= 0 || targetH <= 0) return sampleSize
    while (srcW / (sampleSize * 2) >= targetW && srcH / (sampleSize * 2) >= targetH) {
        sampleSize *= 2
    }
    return sampleSize
}

/** A [rotationDegrees] (0/90/180/270) clockwise rotation, applied first, then an optional
 * [flipHorizontal] -- together these two steps express all 8 EXIF orientation values. */
internal data class OrientationOps(val rotationDegrees: Int, val flipHorizontal: Boolean) {
    val swapsDimensions: Boolean get() = rotationDegrees == 90 || rotationDegrees == 270
}

/**
 * Maps an EXIF `Orientation` tag value to the rotate-then-flip pair that makes the pixels upright.
 * All 8 defined values; anything else (including [ExifInterface.ORIENTATION_UNDEFINED]) is treated
 * as already-upright, matching [ExifInterface]'s own default.
 */
internal fun orientationTransform(orientation: Int): OrientationOps = when (orientation) {
    ExifInterface.ORIENTATION_NORMAL -> OrientationOps(0, false)
    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> OrientationOps(0, true)
    ExifInterface.ORIENTATION_ROTATE_180 -> OrientationOps(180, false)
    ExifInterface.ORIENTATION_FLIP_VERTICAL -> OrientationOps(180, true)
    ExifInterface.ORIENTATION_TRANSPOSE -> OrientationOps(90, true)
    ExifInterface.ORIENTATION_ROTATE_90 -> OrientationOps(90, false)
    ExifInterface.ORIENTATION_TRANSVERSE -> OrientationOps(270, true)
    ExifInterface.ORIENTATION_ROTATE_270 -> OrientationOps(270, false)
    else -> OrientationOps(0, false)
}
