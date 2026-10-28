package com.fotoxplorr.app.lift

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Random
import java.util.concurrent.CancellationException

/**
 * The scribble solver, checked against scenes whose right answer is known by construction -- the
 * same discipline [SubjectSegmentationTest] holds the colour fill to. A photograph only has
 * opinions; a synthetic one has an exact answer, so "the region grew to the edge" is a number.
 */
class GeodesicSegmenterTest {

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    private class Scene(val width: Int, val height: Int, val pixels: IntArray, val truth: BooleanArray)

    /** [inside] decides the true subject; colours come from [fg]/[bg]; every channel gets +-[noise]. */
    private fun scene(
        width: Int,
        height: Int,
        noise: Int,
        seed: Long = 1L,
        inside: (Int, Int) -> Boolean,
        fg: (Int, Int) -> Int,
        bg: (Int, Int) -> Int,
    ): Scene {
        val random = Random(seed)
        val truth = BooleanArray(width * height)
        val pixels = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val isSubject = inside(x, y)
            truth[index] = isSubject
            val base = if (isSubject) fg(x, y) else bg(x, y)
            fun jitter() = if (noise > 0) random.nextInt(2 * noise + 1) - noise else 0
            argb(((base shr 16) and 0xFF) + jitter(), ((base shr 8) and 0xFF) + jitter(), (base and 0xFF) + jitter())
        }
        return Scene(width, height, pixels, truth)
    }

    private fun disc(width: Int, height: Int, cx: Int, cy: Int, r: Int): BooleanArray =
        BooleanArray(width * height) { val x = it % width; val y = it / width; (x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r }

    private fun none(width: Int, height: Int) = BooleanArray(width * height)

    private fun union(a: BooleanArray, b: BooleanArray) = BooleanArray(a.size) { a[it] || b[it] }

    private fun iou(a: BooleanArray, b: BooleanArray): Double {
        var both = 0
        var either = 0
        for (i in a.indices) {
            if (a[i] && b[i]) both++
            if (a[i] || b[i]) either++
        }
        return if (either == 0) 1.0 else both.toDouble() / either
    }

    private fun solve(s: Scene, fg: BooleanArray, bg: BooleanArray, params: GeodesicSegmenter.Params = GeodesicSegmenter.Params()) =
        GeodesicSegmenter.solveRegion(s.pixels, s.width, s.height, fg, bg, params)

    private val redOnBlue = scene(
        128, 96, noise = 0,
        inside = { x, y -> x in 40..80 && y in 25..65 },
        fg = { _, _ -> argb(220, 30, 30) },
        bg = { _, _ -> argb(30, 40, 200) },
    )

    // ---- one tap is enough for a clean subject ----

    @Test
    fun `a single tap lifts a red square off a blue field`() {
        val region = solve(redOnBlue, disc(128, 96, 60, 45, 3), none(128, 96))

        assertTrue("the square must be found almost exactly", iou(region, redOnBlue.truth) > 0.97)
        assertTrue("centre of the square", region[45 * 128 + 60])
        assertFalse("far corner of the field", region[2 * 128 + 2])
        assertFalse("just outside the square", region[45 * 128 + 90])
    }

    @Test
    fun `sensor noise does not make the region leak or fray`() {
        val noisy = scene(
            128, 96, noise = 8, seed = 7,
            inside = { x, y -> x in 40..80 && y in 25..65 },
            fg = { _, _ -> argb(220, 30, 30) },
            bg = { _, _ -> argb(30, 40, 200) },
        )
        val region = solve(noisy, disc(128, 96, 60, 45, 3), none(128, 96))
        assertTrue("noisy square, iou=${iou(region, noisy.truth)}", iou(region, noisy.truth) > 0.95)
    }

    @Test
    fun `a smooth gradient background does not flood into the subject`() {
        val s = scene(
            128, 96, noise = 6, seed = 3,
            inside = { x, y -> (x - 64) * (x - 64) + (y - 48) * (y - 48) <= 28 * 28 },
            fg = { _, _ -> argb(200, 60, 40) },
            bg = { x, y -> argb(60 + x, 90 + y / 2, 120) },
        )
        val region = solve(s, disc(128, 96, 64, 48, 3), none(128, 96))
        assertTrue("disc on a gradient, iou=${iou(region, s.truth)}", iou(region, s.truth) > 0.93)
    }

    @Test
    fun `a subject touching the frame keeps its border pixels`() {
        val s = scene(
            128, 96, noise = 4, seed = 4,
            inside = { x, y -> x >= 50 && y >= 40 },
            fg = { _, _ -> argb(30, 160, 60) },
            bg = { _, _ -> argb(200, 200, 220) },
        )
        val region = solve(s, disc(128, 96, 90, 70, 3), none(128, 96))
        assertTrue("the bottom-right frame corner is part of the subject", region[95 * 128 + 127])
        assertTrue("iou=${iou(region, s.truth)}", iou(region, s.truth) > 0.95)
    }

    // ---- scribbles partition at the colour edge ----

    @Test
    fun `a subject scribble and a background scribble split the image at the colour edge`() {
        val s = scene(
            128, 96, noise = 0,
            inside = { x, _ -> x < 64 },
            fg = { _, _ -> argb(180, 50, 50) },
            bg = { _, _ -> argb(50, 50, 180) },
        )
        val region = solve(s, disc(128, 96, 20, 48, 3), disc(128, 96, 110, 48, 3))
        assertTrue(iou(region, s.truth) > 0.97)
        assertTrue(region[10 * 128 + 5])
        assertFalse(region[10 * 128 + 120])
    }

    @Test
    fun `a two-colour subject is completed by a second scribble`() {
        val s = scene(
            128, 96, noise = 4, seed = 2,
            inside = { x, y -> (x - 64) * (x - 64) + (y - 48) * (y - 48) <= 30 * 30 },
            fg = { x, _ -> if (x < 64) argb(200, 60, 40) else argb(230, 200, 60) },
            bg = { x, y -> argb(60 + x, 90 + y / 2, 120) },
        )
        val bothHalves = union(disc(128, 96, 50, 48, 3), disc(128, 96, 80, 48, 3))
        val region = solve(s, bothHalves, none(128, 96))
        assertTrue("iou=${iou(region, s.truth)}", iou(region, s.truth) > 0.95)
    }

    @Test
    fun `a background scribble pulls a leak back off a neighbour that shares the subject's colour`() {
        // The subject (x 20..60) touches a same-coloured strip (x 61..): with nothing to tell them
        // apart, one tap takes both.
        val s = scene(
            128, 96, noise = 0,
            inside = { x, y -> x in 20..60 && y in 30..70 },
            fg = { _, _ -> argb(200, 50, 50) },
            bg = { x, y -> if (x > 60 && y in 30..70) argb(200, 50, 50) else argb(40, 40, 160) },
        )
        val tap = disc(128, 96, 40, 50, 3)
        val leaked = solve(s, tap, none(128, 96))
        assertTrue("the tap alone takes the neighbour too", leaked[50 * 128 + 90])

        val fixed = solve(s, tap, disc(128, 96, 90, 50, 3))
        assertFalse("a background scribble on the neighbour removes it", fixed[50 * 128 + 90])
        assertTrue("and the subject stays", fixed[50 * 128 + 40])
        assertTrue(iou(fixed, s.truth) > iou(leaked, s.truth))
    }

    // ---- never a flood ----

    /** A red disc on a blue field with a blue "button" in it: marking the button teaches the model the frame's own colour. */
    private fun discWithBlueButton(): Pair<Scene, BooleanArray> {
        val button = disc(128, 96, 52, 48, 4)
        val s = scene(
            128, 96, noise = 3, seed = 5,
            inside = { x, y -> (x - 64) * (x - 64) + (y - 48) * (y - 48) <= 28 * 28 },
            fg = { x, y -> if (button[y * 128 + x]) argb(30, 40, 200) else argb(220, 30, 30) },
            bg = { _, _ -> argb(30, 40, 200) },
        )
        return s to button
    }

    @Test
    fun `a scribble that touches the frame's own colour does not select the whole photo`() {
        val (s, button) = discWithBlueButton()
        // Before the fix no frame pixel was a background mark here, so every pixel was "closer to
        // the subject": 100% coverage.
        val region = solve(s, union(disc(128, 96, 70, 48, 3), button), none(128, 96))
        assertTrue("coverage=${region.count { it }}", region.count { it } < s.width * s.height / 2)
        assertFalse("frame corner", region[0])
        assertFalse("opposite frame corner", region[95 * 128 + 127])
        assertTrue("iou=${iou(region, s.truth)}", iou(region, s.truth) > 0.9)
    }

    @Test
    fun `a scribble over every frame colour still finds a background`() {
        val s = redOnBlue
        val everything = BooleanArray(s.width * s.height) { true }
        val frame = BooleanArray(s.width * s.height) {
            val x = it % s.width
            val y = it / s.width
            x == 0 || y == 0 || x == s.width - 1 || y == s.height - 1
        }
        val region = solve(s, union(disc(128, 96, 60, 45, 3), frame), none(128, 96))
        assertTrue("marks stay in", frame.indices.all { !frame[it] || region[it] })
        assertTrue("something inside the frame is left out", region.any { !it })
        // Every pixel marked: nothing can be background, and nothing is invented.
        assertTrue(solve(s, everything, none(128, 96)).all { it })
    }

    @Test
    fun `a scribble that covers the frame's colour but not all of it keeps the least similar part as background`() {
        // The left half of the frame is red like the subject's scribble; the right is blue and was
        // marked only by one stray pixel. The blue part is the least like the marks, so it votes.
        val s = scene(
            128, 96, noise = 0,
            inside = { x, y -> x in 30..60 && y in 30..66 },
            fg = { _, _ -> argb(220, 30, 30) },
            bg = { x, _ -> if (x < 64) argb(220, 30, 30) else argb(30, 40, 200) },
        )
        val stray = BooleanArray(128 * 96).also { it[10 * 128 + 100] = true } // one blue pixel in the scribble
        val marks = union(disc(128, 96, 45, 48, 3), stray)
        val region = solve(s, marks, none(128, 96))
        assertFalse("the blue frame side is background", region[48 * 128 + 120])
        assertTrue("the scribbled subject stays", region[48 * 128 + 45])
    }

    // ---- soft edges ----

    @Test
    fun `a soft edge is cut near the middle of its ramp`() {
        // Red to blue over 10px centred on x=64: no pixel pair differs much, but the colour model
        // and the accumulated edge cost together still put the cut in the ramp, not past it.
        val ramp = 10
        val colour = { x: Int, _: Int ->
            val t = ((x - 59f) / ramp).coerceIn(0f, 1f)
            argb((220 - 190 * t).toInt(), (30 + 10 * t).toInt(), (30 + 170 * t).toInt())
        }
        val s = scene(128, 96, noise = 4, seed = 8, inside = { x, _ -> x < 64 }, fg = colour, bg = colour)
        val region = solve(s, disc(128, 96, 20, 48, 3), none(128, 96))
        var cut = 0
        while (cut < s.width && region[48 * 128 + cut]) cut++
        assertTrue("cut at x=$cut", cut in 60..68)
    }

    // ---- guarantees ----

    @Test
    fun `no foreground mark means an empty region`() {
        val region = solve(redOnBlue, none(128, 96), disc(128, 96, 5, 5, 2))
        assertTrue(region.none { it })
    }

    @Test
    fun `the foreground marks are always inside the result`() {
        val marks = disc(128, 96, 60, 45, 3)
        val region = solve(redOnBlue, marks, none(128, 96))
        for (i in marks.indices) if (marks[i]) assertTrue(region[i])
    }

    @Test
    fun `a uniform image gives the frame a vote too, so it is never simply everything`() {
        // Nothing tells subject from background here, so there is no right answer; the contract is
        // that the marks stay in and the frame is not silently declared subject.
        val s = scene(40, 30, 0, inside = { _, _ -> true }, fg = { _, _ -> argb(9, 9, 9) }, bg = { _, _ -> argb(9, 9, 9) })
        val marks = disc(40, 30, 20, 15, 2)
        val region = solve(s, marks, none(40, 30))
        for (i in marks.indices) if (marks[i]) assertTrue(region[i])
        assertFalse("the frame corner is not the subject", region[0])
    }

    @Test
    fun `the solve is deterministic`() {
        val a = solve(redOnBlue, disc(128, 96, 60, 45, 3), none(128, 96))
        val b = solve(redOnBlue, disc(128, 96, 60, 45, 3), none(128, 96))
        assertTrue(a.contentEquals(b))
    }

    @Test
    fun `solve returns a feathered mask whose soft edge does not move the shape`() {
        val mask = GeodesicSegmenter.solve(
            redOnBlue.pixels, 128, 96, disc(128, 96, 60, 45, 3), none(128, 96),
            GeodesicSegmenter.Params(featherRadius = 2),
        )
        assertEquals(128, mask.width)
        assertTrue("some fractional alpha at the cut", mask.alpha.any { it > 0.05f && it < 0.95f })
        assertEquals(1f, mask.alpha[45 * 128 + 60], 0.0001f)
        assertEquals(0f, mask.alpha[2 * 128 + 2], 0.0001f)
    }

    @Test
    fun `a superseded solve stops instead of finishing`() {
        try {
            GeodesicSegmenter.solveRegion(
                redOnBlue.pixels, 128, 96, disc(128, 96, 60, 45, 3), none(128, 96),
                shouldStop = { true },
            )
            fail("a solve told to stop must throw CancellationException")
        } catch (expected: CancellationException) {
            // expected
        }
    }

    @Test
    fun `a half-megapixel solve finishes well inside an interactive budget`() {
        val big = scene(
            512, 384, noise = 6, seed = 9,
            inside = { x, y -> (x - 256) * (x - 256) + (y - 190) * (y - 190) <= 110 * 110 },
            fg = { _, _ -> argb(200, 40, 40) },
            bg = { x, _ -> argb(40 + x / 8, 80, 160) },
        )
        val started = System.nanoTime()
        val region = solve(big, disc(512, 384, 256, 190, 6), none(512, 384))
        val millis = (System.nanoTime() - started) / 1_000_000
        assertTrue("iou=${iou(region, big.truth)}", iou(region, big.truth) > 0.97)
        // Generous on purpose (a loaded CI machine): the point is "seconds, not minutes", a
        // regression from O(N log N) to anything quadratic.
        assertTrue("took ${millis}ms", millis < 8_000)
    }

    // ---- clean-up helpers ----

    @Test
    fun `components not touching a mark are dropped`() {
        val w = 10
        val h = 1
        val region = booleanArrayOf(true, true, false, false, true, true, true, false, false, true)
        val marks = BooleanArray(10).also { it[5] = true }
        val kept = GeodesicSegmenter.keepComponentsTouching(region, marks, w, h)
        assertEquals(listOf(4, 5, 6), kept.indices.filter { kept[it] })
    }

    @Test
    fun `small enclosed holes are filled but a hole holding a protected pixel stays open`() {
        val w = 20
        val h = 20
        fun ring(): BooleanArray = BooleanArray(w * h) { val x = it % w; val y = it / w; x in 4..15 && y in 4..15 }
        val region = ring()
        region[10 * w + 10] = false // a pinhole
        region[6 * w + 6] = false // a second pinhole, deliberately protected
        val protected = BooleanArray(w * h).also { it[6 * w + 6] = true }

        GeodesicSegmenter.fillSmallHoles(region, protected, w, h, maxArea = 8)

        assertTrue("unprotected pinhole is filled", region[10 * w + 10])
        assertFalse("the protected hole is left alone", region[6 * w + 6])
    }

    @Test
    fun `a gap open to the frame is not a hole`() {
        val w = 10
        val h = 10
        val region = BooleanArray(w * h) { (it % w) in 3..6 && (it / w) in 0..6 }
        region[0 * w + 4] = false // notch on the top edge: connected to the outside
        GeodesicSegmenter.fillSmallHoles(region, BooleanArray(w * h), w, h, maxArea = 50)
        assertFalse(region[0 * w + 4])
    }
}
