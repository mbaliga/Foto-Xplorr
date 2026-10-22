package com.fotoxplorr.app.aquarium

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class TankSimulationTest {

    private val dt = 1f / 60f
    private val params = CreatureParams.forAspect(1.5f)

    private fun TankSimulation.run(seconds: Float, step: Float = dt) {
        repeat((seconds / step).toInt()) { step(step) }
    }

    private fun TankSimulation.snapshot(): List<Float> =
        creatures.flatMap { listOf(it.x, it.y, it.vx, it.vy, it.phase, it.flipX) }

    private fun TankSimulation.assertInside(label: String = "") {
        for (c in creatures) {
            val eps = 1e-4f
            assertTrue("$label x ${c.x} halfW ${c.halfW}", c.x - c.halfW >= -eps || c.halfW > aspect / 2f)
            assertTrue("$label x ${c.x}", c.x + c.halfW <= aspect + eps || c.halfW > aspect / 2f)
            assertTrue("$label y ${c.y}", c.y - c.halfH >= -eps || c.halfH > 0.5f)
            assertTrue("$label y ${c.y}", c.y + c.halfH <= 1f + eps || c.halfH > 0.5f)
            assertTrue(c.x.isFinite() && c.y.isFinite() && c.vx.isFinite() && c.vy.isFinite())
        }
    }

    private fun simWith(n: Int, seed: Long = 5L, aspect: Float = 1.8f): TankSimulation {
        val sim = TankSimulation(seed, aspect)
        repeat(n) { sim.addCreature("c$it", CreatureParams.forAspect(0.6f + it * 0.25f, headLeft = it % 2 == 0)) }
        return sim
    }

    // ---- bounds and determinism ----

    @Test
    fun `creatures never leave the tank over a long seeded run with touches and food`() {
        val sim = simWith(TankSimulation.MAX_CREATURES)
        assertEquals(12, sim.creatures.size)
        var t = 0
        repeat(3000) {
            if (it % 200 == 0) sim.dropFood(0.2f + (it % 7) * 0.2f, 0.1f)
            if (it % 500 in 100..160) sim.disturb(0.9f, 0.5f) else sim.endDisturbance()
            sim.step(dt)
            if (++t % 50 == 0) sim.assertInside("step $it")
        }
        sim.assertInside("end")
    }

    @Test
    fun `the creature count is bounded at twelve`() {
        val sim = simWith(12)
        assertNull(sim.addCreature("one too many", params))
        assertEquals(12, sim.creatures.size)
        assertTrue(sim.removeCreature("c3"))
        assertEquals(11, sim.creatures.size)
        assertNotNull(sim.addCreature("again", params))
        assertFalse(sim.removeCreature("missing"))
    }

    @Test
    fun `the same seed gives bit identical runs and another seed does not`() {
        val a = simWith(8, seed = 99L)
        val b = simWith(8, seed = 99L)
        val c = simWith(8, seed = 100L)
        for (sim in listOf(a, b, c)) {
            sim.dropFood(0.5f, 0.2f)
            sim.run(5f)
        }
        assertEquals(a.snapshot(), b.snapshot())
        assertTrue(a.snapshot() != c.snapshot())
    }

    @Test
    fun `creatures are kept ordered back to front`() {
        val sim = simWith(10)
        val zs = sim.creatures.map { it.z }
        assertEquals(zs.sorted(), zs)
        sim.run(2f)
        assertEquals(zs, sim.creatures.map { it.z })
    }

    @Test
    fun `a stalled frame is clamped and bad dt values do nothing`() {
        val a = simWith(3)
        val b = simWith(3)
        a.step(10f)
        b.step(TankSimulation.MAX_DT)
        assertEquals(a.snapshot(), b.snapshot())

        val before = a.snapshot()
        a.step(Float.NaN)
        a.step(-1f)
        a.step(0f)
        a.step(Float.POSITIVE_INFINITY)
        assertEquals(before, a.snapshot())
    }

    @Test
    fun `a zero or non finite sized tank is safe`() {
        for (aspect in listOf(0f, -3f, Float.NaN, Float.POSITIVE_INFINITY, 1e-9f, 1e9f)) {
            val sim = simWith(5, aspect = aspect)
            assertTrue(sim.aspect.isFinite() && sim.aspect > 0f)
            sim.dropFood(0.3f, 0.3f)
            sim.disturb(0.1f, 0.1f)
            sim.run(3f)
            sim.assertInside("aspect $aspect")
        }
    }

    @Test
    fun `shrinking the tank pulls everyone back inside`() {
        val sim = simWith(8, aspect = 2.4f)
        sim.run(3f)
        sim.setAspect(0.5f)
        sim.step(dt)
        sim.assertInside("after resize")
        sim.run(2f)
        sim.assertInside("after resize and settle")
    }

    // ---- steering ----

    private fun lone(x: Float, y: Float, vx: Float = 0f, vy: Float = 0f, seed: Long = 3L, aspect: Float = 1.8f): Pair<TankSimulation, Creature> {
        val sim = TankSimulation(seed, aspect)
        val c = sim.addCreature("solo", params, x = x, y = y, z = 0.5f)!!
        c.vx = vx
        c.vy = vy
        c.wanderAngle = 0f
        return sim to c
    }

    @Test
    fun `a creature heading for the right wall turns around`() {
        val (sim, c) = lone(x = 1.8f - 0.17f, y = 0.5f, vx = 0.12f)
        sim.run(0.5f)
        assertTrue("vx ${c.vx}", c.vx < 0f)
        sim.assertInside()
    }

    @Test
    fun `floor and surface turn a creature back too`() {
        val (sim, c) = lone(x = 0.9f, y = 1f - c0Half(), vy = 0.12f)
        sim.run(0.5f)
        assertTrue("vy ${c.vy}", c.vy < 0f)
        val (sim2, c2) = lone(x = 0.9f, y = c0Half(), vy = -0.12f)
        sim2.run(0.5f)
        assertTrue("vy ${c2.vy}", c2.vy > 0f)
    }

    private fun c0Half(): Float = params.height * Creature.depthScale(0.5f) / 2f + 0.01f

    @Test
    fun `a creature inside the touch radius flees and a distant one is unaffected`() {
        val tx = 0.8f
        val ty = 0.5f
        val (sim, c) = lone(x = 1.0f, y = 0.5f)
        val (quietSim, quiet) = lone(x = 1.0f, y = 0.5f)
        val before = hypot(c.x - tx, c.y - ty)
        sim.disturb(tx, ty)
        var maxPanic = 0f
        var maxSpeed = 0f
        var quietMax = 0f
        repeat(60) {
            sim.step(dt)
            quietSim.step(dt)
            maxPanic = maxOf(maxPanic, c.panic)
            maxSpeed = maxOf(maxSpeed, hypot(c.vx, c.vy))
            quietMax = maxOf(quietMax, hypot(quiet.vx, quiet.vy))
        }
        val after = hypot(c.x - tx, c.y - ty)
        assertTrue("before $before after $after", after > before + 0.05f)
        assertTrue(maxPanic >= 0.5f)
        assertTrue("fled at $maxSpeed, calm top $quietMax", maxSpeed > quietMax)

        // 1.5 radii away: identical to the run with no touch at all (same seed, same rng use).
        val farX = 0.4f + 1.5f * TankSimulation.DISTURB_RADIUS + 0.4f
        val (farSim, far) = lone(x = farX, y = 0.5f, aspect = 3f)
        val (calmSim, calm) = lone(x = farX, y = 0.5f, aspect = 3f)
        farSim.disturb(0.4f, 0.5f)
        farSim.run(1f)
        calmSim.run(1f)
        assertEquals(calm.x, far.x, 0f)
        assertEquals(calm.y, far.y, 0f)
        assertEquals(0f, far.panic, 0f)
    }

    @Test
    fun `panic fades once the touch ends`() {
        val (sim, c) = lone(x = 0.9f, y = 0.5f)
        sim.disturb(0.8f, 0.5f)
        sim.run(0.3f)
        assertTrue(c.panic > 0.3f)
        sim.endDisturbance()
        sim.run(2f)
        assertEquals(0f, c.panic, 1e-6f)
    }

    @Test
    fun `speed stays bounded and a creature never stalls`() {
        val sim = simWith(6)
        var moved = 0f
        val start = sim.creatures.map { it.x to it.y }
        repeat(600) {
            sim.step(dt)
            for (c in sim.creatures) {
                val cap = c.cruise * Steering.MAX_SPEED_FACTOR * (1f + Steering.PANIC_BOOST) + 1e-3f
                assertTrue(hypot(c.vx, c.vy) <= cap)
            }
        }
        sim.creatures.forEachIndexed { i, c -> moved += hypot(c.x - start[i].first, c.y - start[i].second) }
        assertTrue(moved / sim.creatures.size > 0.2f)
    }


    @Test
    fun `steering keeps creatures off the walls so the hard clamp is rarely needed`() {
        val sim = simWith(8, seed = 21L)
        var steps = 0
        var onWall = 0
        repeat(3000) {
            sim.step(dt)
            for (c in sim.creatures) {
                steps++
                val minX = minOf(c.halfW, sim.aspect / 2f)
                val minY = minOf(c.halfH, 0.5f)
                if (c.x <= minX + 1e-4f || c.x >= sim.aspect - minX - 1e-4f || c.y <= minY + 1e-4f || c.y >= 1f - minY - 1e-4f) onWall++
            }
        }
        assertTrue("$onWall of $steps creature-steps were pinned to a wall", onWall < steps / 20)
    }

    @Test
    fun `the pose handed to the mesh follows the creature and is always finite`() {
        val sim = simWith(6, seed = 4L)
        val pose = SwimPose()
        val mesh = FloatArray(SwimMesh.FLOATS)
        repeat(300) {
            sim.step(dt)
            for (c in sim.creatures) {
                c.writePose(pose, pxPerUnit = 800f, waveScale = sim.waveScale, offsetX = 10f, offsetY = 20f)
                assertEquals(10f + c.x * 800f, pose.centerX, 1e-3f)
                assertEquals(20f + c.y * 800f, pose.centerY, 1e-3f)
                assertEquals(c.drawWidth * 800f, pose.widthPx, 1e-3f)
                assertEquals(!c.params.headLeft, pose.headRight)
                SwimMesh.fill(mesh, pose, c.params.swim)
                assertTrue(mesh.all { it.isFinite() })
            }
        }
    }

    @Test
    fun `a drawing with its head on the left is mirrored once more`() {
        val sim = TankSimulation(3L, 1.8f)
        val right = sim.addCreature("r", CreatureParams.forAspect(1.5f, headLeft = false), x = 0.9f, y = 0.5f, z = 0.5f)!!
        val left = sim.addCreature("l", CreatureParams.forAspect(1.5f, headLeft = true), x = 0.9f, y = 0.5f, z = 0.5f)!!
        right.flipX = 1f
        left.flipX = 1f
        assertEquals(1f, right.displayFlip, 0f)
        assertEquals(-1f, left.displayFlip, 0f)
    }

    // ---- food ----

    @Test
    fun `a creature seeks a pellet in range and eats it`() {
        val (sim, c) = lone(x = 0.6f, y = 0.4f)
        sim.dropFood(1.0f, 0.45f)
        assertEquals(1, sim.activeFoodCount)
        val d0 = hypot(1.0f - c.x, 0.45f - c.y)
        sim.run(1f)
        val d1 = hypot(sim.food.first { it.active }.x - c.x, sim.food.first { it.active }.y - c.y)
        assertTrue("d0 $d0 d1 $d1", d1 < d0)
        sim.run(10f)
        assertEquals(0, sim.activeFoodCount)
        assertEquals(1, sim.foodEaten)
        assertTrue(c.wiggle >= 0f)
    }

    @Test
    fun `a pellet out of range is ignored and expires on the floor`() {
        val (sim, c) = lone(x = 0.2f, y = 0.3f, aspect = 6f)
        sim.dropFood(5.5f, 0.95f)
        sim.run(1f)
        val p = sim.food.first { it.active }
        assertTrue(hypot(p.x - c.x, p.y - c.y) > Steering.SEEK_RADIUS)
        assertEquals(0, sim.foodEaten)
        // Sinks to the floor, rests, then goes away.
        sim.run(TankSimulation.FOOD_FLOOR_SECONDS + 15f)
        assertEquals(0, sim.activeFoodCount)
    }

    @Test
    fun `pellets sink and the pool is bounded`() {
        val sim = TankSimulation(1L, 2f)
        assertTrue(sim.dropFood(1f, 0.1f))
        val y0 = sim.food.first { it.active }.y
        sim.run(1f)
        assertTrue(sim.food.first { it.active }.y > y0)
        repeat(TankSimulation.MAX_FOOD * 2) { sim.dropFood(0.5f, 0.1f) }
        assertEquals(TankSimulation.MAX_FOOD, sim.activeFoodCount)
        assertFalse(sim.dropFood(0.5f, 0.1f))
        assertFalse(sim.dropFood(Float.NaN, 0.1f))
    }

    @Test
    fun `a frightened creature does not go for food`() {
        val (sim, c) = lone(x = 0.9f, y = 0.5f)
        sim.dropFood(1.1f, 0.5f)
        sim.disturb(0.8f, 0.5f)
        sim.run(0.3f)
        assertEquals(0, sim.foodEaten)
        assertTrue(c.panic > 0f)
    }

    // ---- entering and speed scale ----

    @Test
    fun `a new creature can enter from the left edge`() {
        val sim = TankSimulation(2L, 1.8f)
        val c = sim.addCreature("n", params, enterFromLeft = true)!!
        assertEquals(c.halfW, c.x, 1e-5f)
        assertEquals(1, c.facing)
        assertTrue(c.vx > 0f)
    }

    @Test
    fun `a calmer profile moves less and waves less`() {
        val fast = simWith(4, seed = 8L)
        val slow = simWith(4, seed = 8L)
        slow.applyProfile(motionProfile(animatorDurationScale = 1f, calm = true))
        var fastPath = 0.0
        var slowPath = 0.0
        repeat(180) {
            val f = fast.snapshot()
            val sl = slow.snapshot()
            fast.step(dt)
            slow.step(dt)
            for ((i, c) in fast.creatures.withIndex()) fastPath += hypot(c.x - f[i * 6], c.y - f[i * 6 + 1])
            for ((i, c) in slow.creatures.withIndex()) slowPath += hypot(c.x - sl[i * 6], c.y - sl[i * 6 + 1])
        }
        assertEquals(MotionProfile.CALM.waveScale, slow.waveScale, 0f)
        assertEquals(MotionProfile.CALM.speedScale, slow.speedScale, 0f)
        assertTrue("slow $slowPath fast $fastPath", slowPath < fastPath * 0.5)
        assertTrue(slow.creatures.sumOf { it.phase.toDouble() } != fast.creatures.sumOf { it.phase.toDouble() })
    }

    // ---- touch ----

    @Test
    fun `a short still press is a tap and a hold or drag is a disturbance`() {
        assertEquals(TouchKind.TAP, classifyTouch(durationMs = 80, travelPx = 2f, slopPx = 8f))
        assertEquals(TouchKind.DISTURB, classifyTouch(durationMs = 400, travelPx = 0f, slopPx = 8f))
        assertEquals(TouchKind.DISTURB, classifyTouch(durationMs = 50, travelPx = 30f, slopPx = 8f))
        // Boundaries: the hold limit and the slop are inclusive on the tap side.
        assertEquals(TouchKind.TAP, classifyTouch(TOUCH_HOLD_MS, 8f, 8f))
        assertEquals(TouchKind.DISTURB, classifyTouch(TOUCH_HOLD_MS + 1, 0f, 8f))
        assertEquals(TouchKind.DISTURB, classifyTouch(0, 8.01f, 8f))
        assertEquals(TouchKind.TAP, classifyTouch(10, Float.NaN, 8f))
    }

    @Test
    fun `seeded rng is reproducible and stays in the unit interval`() {
        val a = SeededRng(42L)
        val b = SeededRng(42L)
        repeat(1000) {
            val v = a.next()
            assertEquals(v, b.next(), 0f)
            assertTrue(v >= 0f && v < 1f)
        }
        assertTrue(abs(SeededRng(1L).next() - SeededRng(2L).next()) > 0f)
        assertTrue(SeededRng(0L).next() in 0f..1f)
    }
}
