package com.fotoxplorr.app.viewer.sphere

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

/**
 * Renders the view a camera at the centre of the sphere sees, on the CPU: for each output pixel a ray
 * is cast, turned into longitude and latitude, mapped into the equirectangular picture ([SphereMeta]
 * says which part of the sphere it covers) and sampled bilinearly. Pure over ARGB ints so the geometry
 * is checked on the JVM; the viewer renders a SMALL frame (a few hundred pixels wide) so this is cheap,
 * and the cost on a real phone is a device check.
 *
 * Pixels the picture does not cover (outside a partial sphere) are transparent black.
 */
object SphereProjection {

    /**
     * The picture coordinates (u, v in 0..1 over the picture, u wrapping for a round sphere) the ray
     * through output pixel ([x], [y]) of a [w] x [h] frame hits, or null when it falls outside the data.
     * Written to [out] (2 floats) to avoid an allocation per pixel.
     */
    fun sample(look: Look, meta: SphereMeta, w: Int, h: Int, x: Float, y: Float, out: FloatArray): Boolean {
        val f = (h / 2f) / tan(Math.toRadians(look.fov / 2.0)).toFloat()
        val dx = x - w / 2f
        val dy = h / 2f - y
        val p = Math.toRadians(look.pitch.toDouble())
        val yw = Math.toRadians(look.yaw.toDouble())
        val cp = cos(p).toFloat(); val sp = sin(p).toFloat()
        val cy = cos(yw).toFloat(); val sy = sin(yw).toFloat()
        val y1 = dy * cp + f * sp
        val z1 = -dy * sp + f * cp
        val x2 = dx * cy + z1 * sy
        val z2 = -dx * sy + z1 * cy
        var lon = Math.toDegrees(atan2(x2, z2).toDouble()).toFloat()
        val lat = Math.toDegrees(atan2(y1, hypot(x2, z2)).toDouble()).toFloat()
        if (meta.wrapsHorizontally) {
            while (lon < meta.lonMin) lon += 360f
            while (lon >= meta.lonMin + 360f) lon -= 360f
        } else if (lon < meta.lonMin || lon > meta.lonMax) {
            return false
        }
        if (lat > meta.latMax || lat < meta.latMin) return false
        out[0] = (lon - meta.lonMin) / meta.lonSpan
        out[1] = (meta.latMax - lat) / meta.latSpan
        return true
    }

    /** The frame the camera [look] sees from [src] (a [srcW] x [srcH] picture described by [meta]). */
    fun render(src: IntArray, srcW: Int, srcH: Int, meta: SphereMeta, look: Look, w: Int, h: Int): IntArray {
        require(src.size >= srcW * srcH) { "pixels smaller than ${srcW}x$srcH" }
        val out = IntArray(w * h)
        if (srcW < 1 || srcH < 1 || w < 1 || h < 1) return out
        val uv = FloatArray(2)
        for (j in 0 until h) for (i in 0 until w) {
            if (sample(look, meta, w, h, i + 0.5f, j + 0.5f, uv)) {
                out[j * w + i] = bilinear(src, srcW, srcH, uv[0] * srcW - 0.5f, uv[1] * srcH - 0.5f, meta.wrapsHorizontally)
            }
        }
        return out
    }

    internal fun bilinear(src: IntArray, w: Int, h: Int, fx: Float, fy: Float, wrapX: Boolean): Int {
        val x0 = floor(fx).toInt()
        val y0 = floor(fy).toInt()
        val tx = fx - x0
        val ty = fy - y0
        fun px(x: Int, y: Int): Int {
            val xx = if (wrapX) ((x % w) + w) % w else x.coerceIn(0, w - 1)
            return src[y.coerceIn(0, h - 1) * w + xx]
        }
        val a = px(x0, y0); val b = px(x0 + 1, y0); val c = px(x0, y0 + 1); val d = px(x0 + 1, y0 + 1)
        var result = 0
        for (shift in intArrayOf(24, 16, 8, 0)) {
            val top = ((a ushr shift) and 0xFF) * (1 - tx) + ((b ushr shift) and 0xFF) * tx
            val bot = ((c ushr shift) and 0xFF) * (1 - tx) + ((d ushr shift) and 0xFF) * tx
            val v = (top * (1 - ty) + bot * ty + 0.5f).toInt().coerceIn(0, 255)
            result = result or (v shl shift)
        }
        return result
    }
}
