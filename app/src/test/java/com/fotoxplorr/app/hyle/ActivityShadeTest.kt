package com.fotoxplorr.app.hyle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shade's arithmetic and its state machine.
 *
 * [shadeHeight] is the number that must be right twice: it is what the shade draws itself at, and
 * it is what the host reserves from the spatial shell's top-edge gesture. If those two ever
 * disagree, either the shade cannot be pulled or the settings room cannot be opened — and neither
 * failure looks like a height bug when you meet it.
 */
class ActivityShadeTest {

    private fun activity(id: String, done: Int = 0, total: Int = 0, error: String? = null) =
        BackgroundActivity(id, ActivityKind.SCANNING, done, total, error)

    // ---- height ----

    @Test
    fun `nothing running takes no space at all`() {
        // Not "a thin strip that is usually empty": a shade with nothing to say must cost the
        // photographs zero pixels, in every state.
        ShadeState.entries.forEach { state ->
            assertEquals("$state", 0, shadeHeight(state, 0))
        }
    }

    @Test
    fun `collapsed is the same sliver however many jobs are running`() {
        // The slivers divide the width, they do not stack. Growing here would mean the quietest
        // state got taller the busier the app was, which is backwards.
        assertEquals(COLLAPSED_HEIGHT, shadeHeight(ShadeState.COLLAPSED, 1))
        assertEquals(COLLAPSED_HEIGHT, shadeHeight(ShadeState.COLLAPSED, 5))
        assertEquals(COLLAPSED_HEIGHT, shadeHeight(ShadeState.COLLAPSED, 50))
    }

    @Test
    fun `the notification state grows one row per job`() {
        assertEquals(NOTIFICATION_ROW, shadeHeight(ShadeState.NOTIFICATION, 1))
        assertEquals(NOTIFICATION_ROW * 2, shadeHeight(ShadeState.NOTIFICATION, 2))
        assertEquals(NOTIFICATION_ROW * 3, shadeHeight(ShadeState.NOTIFICATION, 3))
    }

    @Test
    fun `many jobs stop growing the shade and get an overflow line instead`() {
        // Otherwise eight simultaneous jobs would take 400dp of a 900dp screen.
        val capped = NOTIFICATION_ROW * MAX_LISTED_ROWS + OVERFLOW_ROW
        assertEquals(capped, shadeHeight(ShadeState.NOTIFICATION, MAX_LISTED_ROWS + 1))
        assertEquals(capped, shadeHeight(ShadeState.NOTIFICATION, 20))
    }

    @Test
    fun `expanded gives the first job a hero and lists the rest`() {
        assertEquals(EXPANDED_HERO, shadeHeight(ShadeState.EXPANDED, 1))
        assertEquals(EXPANDED_HERO + NOTIFICATION_ROW, shadeHeight(ShadeState.EXPANDED, 2))
        assertEquals(EXPANDED_HERO + NOTIFICATION_ROW * 2, shadeHeight(ShadeState.EXPANDED, 3))
    }

    @Test
    fun `every state is taller than the one below it`() {
        // The three states are a progression. One of them being shorter than a quieter one would
        // make the drag feel like it went the wrong way.
        for (count in 1..6) {
            val collapsed = shadeHeight(ShadeState.COLLAPSED, count)
            val notification = shadeHeight(ShadeState.NOTIFICATION, count)
            val expanded = shadeHeight(ShadeState.EXPANDED, count)
            assertTrue("$count jobs: $collapsed !< $notification", collapsed < notification)
            assertTrue("$count jobs: $notification !< $expanded", notification < expanded)
        }
    }

    @Test
    fun `a negative count is treated as nothing running`() {
        // Cannot happen from the app, but a height function that returned a negative would take
        // the shell's reserved strip negative with it.
        ShadeState.entries.forEach { assertEquals(0, shadeHeight(it, -1)) }
    }

    // ---- the drag ----

    @Test
    fun `a drag shorter than the step changes nothing`() {
        assertEquals(ShadeState.COLLAPSED, shadeAfterDrag(ShadeState.COLLAPSED, 1f))
        assertEquals(ShadeState.NOTIFICATION, shadeAfterDrag(ShadeState.NOTIFICATION, -3f))
    }

    @Test
    fun `dragging down opens one step at a time`() {
        // One step per drag, deliberately: a long pull must not skip the notification state, which
        // is the state most pulls are actually reaching for.
        assertEquals(ShadeState.NOTIFICATION, shadeAfterDrag(ShadeState.COLLAPSED, 100f))
        assertEquals(ShadeState.EXPANDED, shadeAfterDrag(ShadeState.NOTIFICATION, 100f))
    }

    @Test
    fun `dragging up closes one step at a time`() {
        assertEquals(ShadeState.NOTIFICATION, shadeAfterDrag(ShadeState.EXPANDED, -100f))
        assertEquals(ShadeState.COLLAPSED, shadeAfterDrag(ShadeState.NOTIFICATION, -100f))
    }

    @Test
    fun `the ends do not wrap around`() {
        // Pulling up on a collapsed shade must not send it to expanded.
        assertEquals(ShadeState.COLLAPSED, shadeAfterDrag(ShadeState.COLLAPSED, -500f))
        assertEquals(ShadeState.EXPANDED, shadeAfterDrag(ShadeState.EXPANDED, 500f))
    }

    // ---- one activity's own numbers ----

    @Test
    fun `progress is null until the total is known`() {
        // A scan that has not counted its files yet genuinely does not know how far along it is.
        // Null means "run the indeterminate sweep"; zero would mean "stuck at the start".
        assertNull(activity("a", done = 12, total = 0).fraction)
        assertEquals(0.5f, activity("a", done = 50, total = 100).fraction!!, 1e-5f)
    }

    @Test
    fun `progress cannot exceed one`() {
        // The scanner can report more processed than discovered when new files land mid-pass.
        assertEquals(1f, activity("a", done = 200, total = 100).fraction!!, 1e-5f)
    }

    @Test
    fun `a failed job is never finished and never complete`() {
        val failed = activity("a", done = 10, total = 10, error = "Could not read the library")
        assertTrue("a job that failed has not finished", !failed.isFinished)
    }

    @Test
    fun `a job with no total is not finished`() {
        assertTrue(!activity("a", done = 0, total = 0).isFinished)
    }

    // ---- the readout ----

    @Test
    fun `counts are grouped in thousands`() {
        assertEquals("4,822", grouped(4822))
        assertEquals("12,366", grouped(12366))
        assertEquals("999", grouped(999))
        assertEquals("1,000", grouped(1000))
        assertEquals("1,234,567", grouped(1234567))
        assertEquals("0", grouped(0))
    }

    @Test
    fun `a negative count reads as zero rather than as a stray minus`() {
        assertEquals("0", grouped(-5))
    }
}
