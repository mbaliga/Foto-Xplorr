package com.fotoxplorr.app.animate

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The planner's size model against the encoders it models, on synthetic scenes (grep
 * ANIM-ESTIMATE). Passing means: on these scenes the real size falls inside the stated range, and
 * for the photo-like ones the typical figure is within a factor of two. It does NOT mean the model is
 * right for real photographs or video, which have not been measured.
 */
class AnimEstimateCalibrationTest {

    private fun changed(seq: AnimSequence): Double {
        var diff = 0L
        var total = 0L
        for (i in 1 until seq.frames.size) {
            val a = seq.frames[i - 1].pixels
            val b = seq.frames[i].pixels
            for (p in a.indices) if (a[p] != b[p]) diff++
            total += a.size
        }
        return if (total == 0L) 0.0 else diff.toDouble() / total
    }

    private class Scene(val name: String, val seq: AnimSequence, val kind: ContentKind, val change: Double, val tightPhoto: Boolean)

    @Test
    fun `real sizes fall inside the stated range on the synthetic scenes`() {
        val w = 240
        val h = 180
        val n = 12
        val video = AnimSequence(
            w, h,
            List(n) { i -> AnimFrame(AnimTestKit.photoFrame(w, h, i, w / 6 + (w * 2 / 3) * i / (n - 1), h / 2, noise = 3, seed = 9), 100) },
            0,
        )
        val photo = AnimTestKit.photoSequence(w, h, n)
        val flat = AnimTestKit.flatSequence(w, h, n)
        val sticker = AnimTestKit.stickerSequence(w, h, n)
        val scenes = listOf(
            Scene("still camera", photo, ContentKind.PHOTO, changed(photo), true),
            Scene("noisy video", video, ContentKind.PHOTO, changed(video), true),
            Scene("flat graphics", flat, ContentKind.GRAPHICS, changed(flat), false),
            Scene("sticker", sticker, ContentKind.GRAPHICS, 1.0, false),
        )
        for (s in scenes) {
            for (format in AnimFormat.values()) {
                if (format == AnimFormat.APNG && s.name == "sticker") continue // APNG deltas exactly; the GIF-without-delta rule does not apply
                val actual = when (format) {
                    AnimFormat.GIF -> GifEncoder.encode(s.seq).size
                    AnimFormat.APNG -> ApngEncoder.encode(s.seq).size
                }.toLong()
                val est = AnimPlanner.estimate(format, s.kind, w, h, n, s.change)
                println("ANIM-ESTIMATE %-14s %-4s actual=%8d typical=%8d range=%d..%d ratio=%.2f".format(s.name, format, actual, est.typicalBytes, est.lowBytes, est.highBytes, est.typicalBytes.toDouble() / actual))
                assertTrue("${s.name} $format actual=$actual outside ${est.lowBytes}..${est.highBytes}", actual in est.lowBytes..est.highBytes)
                if (s.tightPhoto) {
                    val ratio = est.typicalBytes.toDouble() / actual
                    assertTrue("${s.name} $format typical/actual=$ratio", ratio in 0.5..2.0)
                }
            }
        }
    }
}
