package com.fotoxplorr.app.animate

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How long and how big, on one host JVM (grep ANIM-MEASURE in the test output). The scenes are
 * synthetic (gradients, seeded noise, a moving disc, flat bars): they say how this code scales, not
 * how a real clip compresses, and a phone is slower by an unknown factor (a guess is 3-6x for
 * this kind of integer loop; nothing has run on a device). The asserts are loose ceilings so a
 * accidental quadratic shows up, not performance claims.
 */
class AnimMeasureTest {

    private fun median(runs: Int, block: () -> Unit): Double {
        block() // warm up the JIT
        val t = DoubleArray(runs)
        for (i in 0 until runs) {
            val s = System.nanoTime()
            block()
            t[i] = (System.nanoTime() - s) / 1e6
        }
        t.sort()
        return t[runs / 2]
    }

    /** Fresh sensor noise on every frame, like a real clip, with the disc moving. */
    private fun noisyVideo(w: Int, h: Int, n: Int): AnimSequence {
        val frames = List(n) { i ->
            val cx = w / 6 + (w * 2 / 3) * i / (n - 1)
            AnimFrame(AnimTestKit.photoFrame(w, h, i, cx, h / 2, noise = 3, seed = 9), 100)
        }
        return AnimSequence(w, h, frames, 0)
    }

    private fun line(label: String, ms: Double, bytes: Int, frames: Int) {
        println("ANIM-MEASURE %-46s %8.0f ms %9d bytes %7.1f KB/frame".format(label, ms, bytes, bytes / 1024.0 / frames))
    }

    @Test
    fun `thirty frames of 480 by 360`() {
        val w = 480
        val h = 360
        val n = 30
        val still = AnimTestKit.photoSequence(w, h, n)
        val video = noisyVideo(w, h, n)
        val flat = AnimTestKit.flatSequence(w, h, n)
        val sticker = AnimTestKit.stickerSequence(w, h, n)

        for ((name, seq) in listOf("still-camera photo" to still, "noisy video-like" to video, "flat graphics" to flat, "sticker (alpha)" to sticker)) {
            var gif = ByteArray(0)
            val gifMs = median(3) { gif = GifEncoder.encode(seq) }
            line("$name  GIF default", gifMs, gif.size, n)
            var gifNoDither = ByteArray(0)
            val ms0 = median(3) { gifNoDither = GifEncoder.encode(seq, GifOptions(ditherStrength = 0f)) }
            line("$name  GIF no dither", ms0, gifNoDither.size, n)
            var gifTol = ByteArray(0)
            val ms1 = median(3) { gifTol = GifEncoder.encode(seq, GifOptions(deltaTolerance = 6)) }
            line("$name  GIF tolerance 6", ms1, gifTol.size, n)
            var gifWhole = ByteArray(0)
            val ms2 = median(3) { gifWhole = GifEncoder.encode(seq, GifOptions(deltaFrames = false)) }
            line("$name  GIF whole frames", ms2, gifWhole.size, n)
            var apng = ByteArray(0)
            val ms3 = median(3) { apng = ApngEncoder.encode(seq) }
            line("$name  APNG level 6", ms3, apng.size, n)
            val dg = median(3) { GifReader.decodeAll(gif) }
            line("$name  GIF decode", dg, gif.size, n)
            val da = median(3) { ApngReader.decodeAll(apng) }
            line("$name  APNG decode", da, apng.size, n)
            assertTrue("GIF encode of 30 frames took $gifMs ms", gifMs < 20_000)
            assertTrue("APNG encode of 30 frames took $ms3 ms", ms3 < 20_000)
        }
    }

    @Test
    fun `how the encoders scale with frame count and pixel count`() {
        // Doubling the frames should about double the time; doubling the pixels the same. A quadratic would show here.
        fun gifMs(w: Int, h: Int, n: Int) = median(3) { GifEncoder.encode(noisyVideo(w, h, n).let { it }) }
        val base = gifMs(160, 120, 10)
        val moreFrames = gifMs(160, 120, 40)
        val morePixels = gifMs(320, 240, 10)
        println("ANIM-MEASURE scaling: 160x120x10=%.0f ms, x40 frames=%.0f ms, 320x240x10=%.0f ms".format(base, moreFrames, morePixels))
        assertTrue("4x frames took ${moreFrames / base}x", moreFrames / base < 12)
        assertTrue("4x pixels took ${morePixels / base}x", morePixels / base < 12)
    }
}
