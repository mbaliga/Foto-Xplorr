package com.fotoxplorr.app.animate

import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Synthetic scenes and the measuring sticks the animate tests share. Everything is seeded, so a
 * failure is the same failure on every machine. None of it is a real photograph: the "photo" scene
 * is smooth gradients plus noise plus moving shapes, which exercises the quantiser and the
 * delta coder but says nothing about real-world compression.
 */
object AnimTestKit {

    fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    /** A photo-like frame: diagonal colour gradient, seeded noise of +-[noise] levels, and a disc at ([cx], [cy]). */
    fun photoFrame(w: Int, h: Int, t: Int, cx: Int, cy: Int, noise: Int = 6, seed: Long = 1L): IntArray {
        val rnd = Random(seed + t * 7919L)
        val out = IntArray(w * h)
        val radius = minOf(w, h) / 5
        for (y in 0 until h) for (x in 0 until w) {
            var r = 40 + 170 * x / w
            var g = 60 + 120 * y / h
            var b = 200 - 120 * (x + y) / (w + h)
            val dx = x - cx
            val dy = y - cy
            if (dx * dx + dy * dy < radius * radius) {
                r = 230 - (dx * dx + dy * dy) * 60 / (radius * radius)
                g = 90
                b = 40 + (dy + radius) * 80 / (2 * radius)
            }
            if (noise > 0) {
                r += rnd.nextInt(2 * noise + 1) - noise
                g += rnd.nextInt(2 * noise + 1) - noise
                b += rnd.nextInt(2 * noise + 1) - noise
            }
            out[y * w + x] = argb(255, r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
        }
        return out
    }

    /** [n] frames of the photo scene with the disc sweeping left to right. The background noise is the same in every frame (a still camera on a tripod). */
    fun photoSequence(w: Int, h: Int, n: Int, delayMs: Int = 100, noise: Int = 6, plays: Int = 0): AnimSequence {
        val frames = List(n) { i ->
            val cx = (w / 6) + (w * 2 / 3) * i / maxOf(1, n - 1)
            // Same noise field every frame (t is 0); only the disc moves.
            AnimFrame(photoFrame(w, h, 0, cx, h / 2, noise), delayMs)
        }
        return AnimSequence(w, h, frames, plays)
    }

    /** Flat graphics: [colors] distinct flat colours, a bar sweeping across. Exactly representable in a palette. */
    fun flatSequence(w: Int, h: Int, n: Int, delayMs: Int = 80, plays: Int = 0): AnimSequence {
        val palette = intArrayOf(argb(255, 250, 250, 250), argb(255, 30, 60, 200), argb(255, 220, 40, 40), argb(255, 20, 160, 80))
        val frames = List(n) { i ->
            val px = IntArray(w * h) { palette[0] }
            val x0 = (w - w / 4) * i / maxOf(1, n - 1)
            for (y in h / 4 until h * 3 / 4) for (x in x0 until x0 + w / 4) px[y * w + x] = palette[1 + (i % 3)]
            for (x in 0 until w) px[(h - 3) * w + x] = palette[3]
            AnimFrame(px, delayMs)
        }
        return AnimSequence(w, h, frames, plays)
    }

    /** A sticker: a disc on a clear background, moving; alpha is only ever 0 or 255. */
    fun stickerSequence(w: Int, h: Int, n: Int, delayMs: Int = 100): AnimSequence {
        val frames = List(n) { i ->
            val px = IntArray(w * h)
            val cx = w / 4 + (w / 2) * i / maxOf(1, n - 1)
            val cy = h / 2
            val r = minOf(w, h) / 4
            for (y in 0 until h) for (x in 0 until w) {
                val dx = x - cx
                val dy = y - cy
                if (dx * dx + dy * dy < r * r) px[y * w + x] = argb(255, 200 - dx * 2, 80 + dy * 3, 120)
            }
            AnimFrame(px, delayMs)
        }
        return AnimSequence(w, h, frames, 0)
    }

    /** Mean squared error per channel over opaque pixels of [a] against [b] (alpha cut at 128 for both). Clear-vs-opaque counts as 255^2. */
    fun mse(a: IntArray, b: IntArray): Double {
        require(a.size == b.size)
        var sum = 0.0
        for (i in a.indices) {
            val ao = (a[i] ushr 24) >= 128
            val bo = (b[i] ushr 24) >= 128
            if (ao != bo) {
                sum += 255.0 * 255.0
            } else if (ao) {
                val dr = ((a[i] shr 16) and 255) - ((b[i] shr 16) and 255)
                val dg = ((a[i] shr 8) and 255) - ((b[i] shr 8) and 255)
                val db = (a[i] and 255) - (b[i] and 255)
                sum += (dr * dr + dg * dg + db * db) / 3.0
            }
        }
        return sum / a.size
    }

    fun psnr(a: IntArray, b: IntArray): Double {
        val m = mse(a, b)
        return if (m == 0.0) 99.0 else 10 * Math.log10(255.0 * 255.0 / m)
    }

    fun maxChannelError(a: IntArray, b: IntArray): Int {
        var m = 0
        for (i in a.indices) {
            m = maxOf(m, abs(((a[i] shr 16) and 255) - ((b[i] shr 16) and 255)), abs(((a[i] shr 8) and 255) - ((b[i] shr 8) and 255)), abs((a[i] and 255) - (b[i] and 255)))
        }
        return m
    }

    fun rms(a: IntArray, b: IntArray): Double = sqrt(mse(a, b))
}
