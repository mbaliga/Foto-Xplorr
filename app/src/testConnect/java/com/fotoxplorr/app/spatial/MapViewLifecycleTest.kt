package com.fotoxplorr.app.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The map's lifecycle gate. The crash it answers (a GL frame after the renderer's worker pool was
 * shut down) needs a real renderer to reproduce, so what is pinned here is the contract that prevents
 * it: detach before destroy, destroy once, nothing after it.
 */
class MapViewLifecycleTest {

    private val calls = ArrayList<String>()

    private fun gate() = MapViewLifecycle(
        start = { calls += "start" },
        resume = { calls += "resume" },
        pause = { calls += "pause" },
        stop = { calls += "stop" },
        detach = { calls += "detach" },
        destroy = { calls += "destroy" },
    )

    @Test
    fun `the view is detached before it is destroyed and never after`() {
        val g = gate()
        g.onStart(); g.onResume()
        g.onDestroy()
        assertEquals(listOf("start", "resume", "pause", "stop", "detach", "destroy"), calls)
    }

    @Test
    fun `a second destroy from the other path does nothing`() {
        // The composable's disposal and the activity's own onDestroy both used to destroy the view.
        val g = gate()
        g.onStart(); g.onResume()
        g.onDestroy()
        calls.clear()
        g.onDestroy()
        g.onPause(); g.onStop(); g.onStart(); g.onResume()
        assertTrue("nothing after destroy: $calls", calls.isEmpty())
        assertTrue(g.destroyed)
    }

    @Test
    fun `start and resume repeat without double calls`() {
        val g = gate()
        g.onStart(); g.onStart(); g.onResume(); g.onResume()
        assertEquals(listOf("start", "resume"), calls)
    }

    @Test
    fun `going to the background and back runs the pairs in order`() {
        val g = gate()
        g.onStart(); g.onResume()
        g.onPause(); g.onStop()
        g.onStart(); g.onResume()
        assertEquals(listOf("start", "resume", "pause", "stop", "start", "resume"), calls)
    }

    @Test
    fun `a pause or stop that was never started is not sent`() {
        val g = gate()
        g.onPause(); g.onStop()
        assertTrue(calls.isEmpty())
        g.onDestroy()
        assertEquals(listOf("detach", "destroy"), calls)
    }

    @Test
    fun `resume starts first when only resume arrives`() {
        val g = gate()
        g.onResume()
        assertEquals(listOf("start", "resume"), calls)
    }
}
