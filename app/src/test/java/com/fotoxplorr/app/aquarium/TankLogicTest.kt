package com.fotoxplorr.app.aquarium

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TankLogicTest {

    private fun record(id: String = CreatureId.generate(), aspect: Int = 2) =
        StoredCreature(id, 1L, 100 * aspect, 100, false)

    @Test
    fun `animations turned off open the tank paused, and nothing else does`() {
        assertTrue(TankLogic.startsPaused(0f))
        assertFalse(TankLogic.startsPaused(1f))
        assertFalse(TankLogic.startsPaused(0.5f))
        assertFalse(TankLogic.startsPaused(10f))
        assertFalse(TankLogic.startsPaused(Float.NaN))
    }

    @Test
    fun `the paused start also asks for calm motion`() {
        val p = motionProfile(0f)
        assertTrue(p.startPaused)
        assertTrue(p.speedScale < 1f)
        assertTrue(p.waveScale < 1f)
    }

    @Test
    fun `the first frame and a stalled clock step by nothing`() {
        assertEquals(0f, TankLogic.frameSeconds(0L, 5_000_000L), 0f)
        assertEquals(0f, TankLogic.frameSeconds(5_000_000L, 5_000_000L), 0f)
        assertEquals(0f, TankLogic.frameSeconds(6_000_000L, 5_000_000L), 0f)
    }

    @Test
    fun `a frame is its length in seconds, never more than the simulation's limit`() {
        assertEquals(0.016f, TankLogic.frameSeconds(1_000_000_000L, 1_016_000_000L), 1e-6f)
        assertEquals(TankSimulation.MAX_DT, TankLogic.frameSeconds(1L, 5_000_000_000L), 0f)
    }

    @Test
    fun `the tank aspect is width over height and zero without an area`() {
        assertEquals(0.5f, TankLogic.aspectOf(540, 1080), 1e-6f)
        assertEquals(0f, TankLogic.aspectOf(0, 100), 0f)
        assertEquals(0f, TankLogic.aspectOf(100, 0), 0f)
    }

    @Test
    fun `an empty size leaves the simulation's tank usable`() {
        val sim = TankSimulation()
        sim.setAspect(TankLogic.aspectOf(0, 0))
        assertTrue(sim.aspect > 0f)
    }

    @Test
    fun `reconcile adds what is wanted and removes what is not`() {
        val sim = TankSimulation(seed = 3)
        val a = record()
        val b = record()
        assertEquals(2, TankLogic.reconcile(sim, listOf(a, b), enterFromLeft = false))
        assertEquals(setOf(a.id, b.id), sim.creatures.map { it.key }.toSet())
        val c = record()
        assertEquals(1, TankLogic.reconcile(sim, listOf(b, c), enterFromLeft = true))
        assertEquals(setOf(b.id, c.id), sim.creatures.map { it.key }.toSet())
    }

    @Test
    fun `a creature already in the tank keeps its place`() {
        val sim = TankSimulation(seed = 3)
        val a = record()
        TankLogic.reconcile(sim, listOf(a), enterFromLeft = false)
        val before = sim.creatures.single().let { it.x to it.y }
        repeat(5) { sim.step(0.016f) }
        val moved = sim.creatures.single().let { it.x to it.y }
        TankLogic.reconcile(sim, listOf(a), enterFromLeft = false)
        assertEquals(moved, sim.creatures.single().let { it.x to it.y })
        assertTrue(before != moved)
    }

    @Test
    fun `a new creature enters from the left when asked`() {
        val sim = TankSimulation(seed = 3)
        val a = record()
        TankLogic.reconcile(sim, listOf(a), enterFromLeft = true)
        val c = sim.creatures.single()
        assertTrue(c.x <= c.halfW + 1e-4f)
        assertEquals(1, c.facing)
    }

    @Test
    fun `reconcile never goes past the tank's limit and does not throw`() {
        val sim = TankSimulation(seed = 3)
        val many = List(TankSimulation.MAX_CREATURES + 5) { record() }
        assertEquals(TankSimulation.MAX_CREATURES, TankLogic.reconcile(sim, many, enterFromLeft = false))
        assertEquals(TankSimulation.MAX_CREATURES, sim.creatures.size)
    }

    @Test
    fun `reconcile with nothing wanted empties the tank`() {
        val sim = TankSimulation(seed = 3)
        TankLogic.reconcile(sim, listOf(record(), record()), enterFromLeft = false)
        TankLogic.reconcile(sim, emptyList(), enterFromLeft = false)
        assertTrue(sim.creatures.isEmpty())
    }

    @Test
    fun `feed places spread across the top and stay inside the tank`() {
        val xs = (0 until 14).map { TankLogic.feedX(it, 1.5f) }
        assertEquals(7, xs.toSet().size)
        assertTrue(xs.all { it > 0f && it < 1.5f })
        assertEquals(xs[0], xs[7], 0f)
        assertTrue(TankLogic.feedX(-1, 1.5f) in 0f..1.5f)
        assertTrue(TankLogic.feedX(3, Float.NaN) > 0f)
        val sim = TankSimulation()
        assertTrue(sim.dropFood(TankLogic.feedX(0, sim.aspect), TankLogic.FEED_Y))
    }

    @Test
    fun `swim needs ink, room and no save under way`() {
        assertTrue(canSwim(sketchEmpty = false, tankFull = false, busy = false))
        assertFalse(canSwim(true, false, false))
        assertFalse(canSwim(false, true, false))
        assertFalse(canSwim(false, false, true))
    }

    @Test
    fun `the line under the board says the most pressing thing`() {
        assertEquals(AquariumCopy.WORKING, swimNote(busy = true, tankFull = true, strokesFull = true, message = "m"))
        assertEquals(AquariumCopy.TANK_FULL, swimNote(false, true, true, "m"))
        assertEquals(AquariumCopy.STROKES_FULL, swimNote(false, false, true, "m"))
        assertEquals("m", swimNote(false, false, false, "m"))
        assertEquals(null, swimNote(false, false, false, null))
    }

    @Test
    fun `the tank and the board read their counts`() {
        assertEquals("Aquarium, empty", AquariumCopy.tank(0))
        assertEquals("Aquarium, 1 creature", AquariumCopy.tank(1))
        assertEquals("Aquarium, 5 creatures", AquariumCopy.tank(5))
        assertEquals("Drawing board, empty", AquariumCopy.board(0))
        assertEquals("Drawing board, 1 stroke", AquariumCopy.board(1))
        assertEquals("Drawing board, 3 strokes", AquariumCopy.board(3))
    }
}
