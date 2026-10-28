package com.fotoxplorr.app.chroma

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The three planes of a YUV_420_888 picture, copied out of the camera's buffers so the buffer can
 * go back to the camera at once. [uvPixelStride] is 1 for planar chroma and 2 for interleaved
 * (NV21-like); the chroma planes are half the width and height.
 */
internal class YuvPlanes(
    val width: Int,
    val height: Int,
    val y: ByteArray,
    val yRowStride: Int,
    val u: ByteArray,
    val v: ByteArray,
    val uvRowStride: Int,
    val uvPixelStride: Int,
)

/**
 * YUV to RGB, and the preview's look at the middle of the view.
 *
 * The conversion is the full-range BT.601 (JFIF) one, which is what the camera2 API documents for
 * YUV_420_888 from the camera. The result is still the phone's own processed picture: its tone curve
 * is in it, so the sRGB codes here are only approximately sRGB. That is the biggest unverified
 * assumption of the whole tool (the physics wants linear light), and the Android seam cannot fix
 * it; a raw capture with a known curve could.
 */
internal object YuvFrames {
    /** Every pixel of [p] as a [ChromaFrame]. */
    fun toFrame(p: YuvPlanes): ChromaFrame {
        val out = IntArray(p.width * p.height)
        for (row in 0 until p.height) {
            for (col in 0 until p.width) out[row * p.width + col] = argbAt(p, col, row)
        }
        return ChromaFrame(p.width, p.height, out)
    }

    /**
     * The share of the middle of the picture ([region], sampled on a grid of about [grid] pixels a
     * side) whose brightest channel is at or above [PatchSampler.CLIP_CODE]: the same rule the
     * measurement uses, so the preview warns about exactly what the measurement would refuse.
     */
    fun clippedShare(p: YuvPlanes, region: PatchRegion = PatchRegion.CENTRE, grid: Int = 48): Double {
        val x0 = (region.left * p.width).toInt().coerceIn(0, p.width - 1)
        val x1 = ceil(region.right * p.width).toInt().coerceIn(x0 + 1, p.width)
        val y0 = (region.top * p.height).toInt().coerceIn(0, p.height - 1)
        val y1 = ceil(region.bottom * p.height).toInt().coerceIn(y0 + 1, p.height)
        val sx = max(1, (x1 - x0) / grid)
        val sy = max(1, (y1 - y0) / grid)
        var seen = 0
        var clipped = 0
        var row = y0
        while (row < y1) {
            var col = x0
            while (col < x1) {
                val px = argbAt(p, col, row)
                val top = max((px shr 16) and 0xFF, max((px shr 8) and 0xFF, px and 0xFF))
                seen++
                if (top >= PatchSampler.CLIP_CODE) clipped++
                col += sx
            }
            row += sy
        }
        return if (seen == 0) 0.0 else clipped.toDouble() / seen
    }

    private fun argbAt(p: YuvPlanes, col: Int, row: Int): Int {
        val y = p.y[row * p.yRowStride + col].toInt() and 0xFF
        val ci = (row shr 1) * p.uvRowStride + (col shr 1) * p.uvPixelStride
        val u = (p.u[ci].toInt() and 0xFF) - 128
        val v = (p.v[ci].toInt() and 0xFF) - 128
        val r = clamp(y + 1.402 * v)
        val g = clamp(y - 0.344136 * u - 0.714136 * v)
        val b = clamp(y + 1.772 * u)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun clamp(x: Double): Int = x.roundToInt().coerceIn(0, 255)
}

/**
 * Chooses the size of the measuring shot from the sizes the camera offers.
 *
 * It wants the preview's shape (so the middle of the preview is the middle of the shot) and about
 * [TARGET_PIXELS] pixels: enough to have thousands of pixels in the middle patch, small enough to
 * convert in a few milliseconds.
 */
internal object CaptureSize {
    const val TARGET_PIXELS = 300_000

    class Size(val width: Int, val height: Int) {
        val pixels: Long get() = width.toLong() * height
    }

    /** The preview size the camera offers that is largest within [maxPixels], or the smallest one if none is that small. */
    fun pickPreview(offered: List<Size>, maxPixels: Long = 1_400_000L): Size? =
        offered.filter { it.pixels <= maxPixels }.maxByOrNull { it.pixels } ?: offered.minByOrNull { it.pixels }

    /** The size nearest in shape to [preview], then nearest in pixel count to [TARGET_PIXELS]. */
    fun pickCapture(offered: List<Size>, preview: Size): Size? {
        val aspect = preview.width.toDouble() / preview.height
        return offered.minByOrNull { s ->
            val shape = kotlin.math.abs(s.width.toDouble() / s.height - aspect) / aspect
            val weight = kotlin.math.abs(kotlin.math.ln(s.pixels.toDouble() / TARGET_PIXELS))
            // A different shape costs far more than a different size: the middle must line up.
            shape * 20.0 + weight
        }
    }
}

/**
 * The colour of a picture taken WITHOUT the flash: the middle patch as the camera saw it, under the
 * room's light. It is not the surface's own colour (the room's colour is still in it), so the screen
 * says so every time it shows one. Used only when the phone has no flash.
 */
internal object AmbientOnly {
    fun read(frame: ChromaFrame, region: PatchRegion = PatchRegion.CENTRE): Answer<ColourReport> =
        when (val patch = PatchSampler.sample(frame, region)) {
            is Answer.Refused -> patch
            is Answer.Value -> Answer.Value(ColourReport.of(ColourSpace.argbOf(patch.value.linear)))
        }
}
