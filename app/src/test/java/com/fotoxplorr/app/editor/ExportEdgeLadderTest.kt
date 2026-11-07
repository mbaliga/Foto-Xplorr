package com.fotoxplorr.app.editor

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The export size policy: the first attempt is the user's cap or the fixed decode ceiling (never a
 * memory-derived number), and a smaller rung is tried only after an actual [OutOfMemoryError].
 */
class ExportEdgeLadderTest {

    @Test
    fun `Original renders at the fixed ceiling regardless of the device`() {
        assertEquals(8192, ExportOptions().renderEdge())
        assertEquals(8192, ExportOptions(maxEdge = MaxEdgePreset.ORIGINAL).renderEdge())
        assertEquals(64_000_000L, MAX_EXPORT_PIXELS)
    }

    @Test
    fun `a user cap is the first rung, as chosen`() {
        assertEquals(4096, ExportOptions(maxEdge = MaxEdgePreset.LARGE).renderEdge())
        assertEquals(2048, ExportOptions(maxEdge = MaxEdgePreset.MEDIUM).renderEdge())
        assertEquals(1024, ExportOptions(maxEdge = MaxEdgePreset.SMALL).renderEdge())
    }

    @Test
    fun `the ladder halves from the request down to a floor`() {
        assertEquals(listOf(8192, 4096, 2048, 1536), exportEdgeLadder(8192))
        assertEquals(listOf(4096, 2048, 1536), exportEdgeLadder(4096))
        assertEquals(listOf(2048, 1536), exportEdgeLadder(2048))
    }

    @Test
    fun `a request at or below the floor is a single rung and the ladder is never empty`() {
        assertEquals(listOf(1536), exportEdgeLadder(1536))
        assertEquals(listOf(1024), exportEdgeLadder(1024))
        assertEquals(listOf(100), exportEdgeLadder(100))
    }

    @Test
    fun `rungs strictly decrease`() {
        for (requested in listOf(8192, 6000, 4096, 3000, 1600, 1537)) {
            val ladder = exportEdgeLadder(requested)
            assertEquals(requested, ladder.first())
            assertEquals(ladder.sortedDescending(), ladder)
            assertEquals(ladder.distinct(), ladder)
        }
    }

    @Test
    fun `a first-try success never steps down`() = runBlocking {
        val tried = mutableListOf<Int>()
        val outcome = renderDownTheLadder(exportEdgeLadder(8192)) { edge -> tried += edge; "ok@$edge" }!!
        assertEquals(listOf(8192), tried)
        assertEquals("ok@8192", outcome.value)
        assertFalse(outcome.steppedDown)
    }

    @Test
    fun `an OutOfMemoryError steps down one rung at a time until one fits`() = runBlocking {
        val tried = mutableListOf<Int>()
        val outcome = renderDownTheLadder(exportEdgeLadder(8192)) { edge ->
            tried += edge
            if (edge > 2048) throw OutOfMemoryError("test") else "ok@$edge"
        }!!
        assertEquals(listOf(8192, 4096, 2048), tried)
        assertEquals(2048, outcome.edge)
        assertTrue(outcome.steppedDown)
    }

    @Test
    fun `running out of memory on every rung gives null`() = runBlocking {
        val tried = mutableListOf<Int>()
        val outcome = renderDownTheLadder<String>(exportEdgeLadder(4096)) { edge ->
            tried += edge
            throw OutOfMemoryError("test")
        }
        assertNull(outcome)
        assertEquals(listOf(4096, 2048, 1536), tried)
    }

    @Test
    fun `an unreadable photo stops the search, a smaller size would not help`() = runBlocking {
        val tried = mutableListOf<Int>()
        val outcome = renderDownTheLadder<String>(exportEdgeLadder(8192)) { edge -> tried += edge; null }
        assertNull(outcome)
        assertEquals(listOf(8192), tried)
    }

    @Test
    fun `a failure that is not an OutOfMemoryError is not swallowed`() {
        val tried = mutableListOf<Int>()
        try {
            runBlocking {
                renderDownTheLadder<String>(exportEdgeLadder(8192)) { edge ->
                    tried += edge
                    throw IllegalStateException("boom")
                }
            }
            throw AssertionError("expected the exception to propagate")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }
        assertEquals(listOf(8192), tried)
    }

    @Test
    fun `an overwrite uses a single rung so an OutOfMemoryError writes nothing`() = runBlocking {
        val outcome = renderDownTheLadder<String>(listOf(MAX_EXPORT_EDGE)) { throw OutOfMemoryError("test") }
        assertNull(outcome)
    }
}
