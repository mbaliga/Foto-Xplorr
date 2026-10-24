package com.fotoxplorr.app.aquarium

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** A source of numbers in [0, 1). Injected so a test run is reproducible. */
fun interface Rng {
    fun next(): Float
}

/** SplitMix64: tiny, fast, and the same sequence on every JVM and every phone. */
class SeededRng(seed: Long) : Rng {
    private var state = seed

    override fun next(): Float {
        state += 0x9E3779B97F4A7C15uL.toLong()
        var z = state
        z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9uL.toLong()
        z = (z xor (z ushr 27)) * 0x94D049BB133111EBuL.toLong()
        z = z xor (z ushr 31)
        return (z ushr 40).toInt() / 16_777_216f
    }
}

/** What a finger on the tank means. */
enum class TouchKind { TAP, DISTURB }

/** A press held longer than this is a disturbance, not a tap. */
const val TOUCH_HOLD_MS = 250L

/**
 * A short, still press drops food (TAP); holding, or dragging past [slopPx], scares nearby fish
 * (DISTURB), so a food tap never also frightens them. Both limits are inclusive on the TAP side; a
 * non-finite travel counts as still.
 */
fun classifyTouch(durationMs: Long, travelPx: Float, slopPx: Float): TouchKind {
    val travelled = if (travelPx.isFinite()) travelPx else 0f
    return if (durationMs > TOUCH_HOLD_MS || travelled > slopPx) TouchKind.DISTURB else TouchKind.TAP
}

/** One food pellet in a fixed pool; [active] false means the slot is free. */
class FoodPellet {
    var active = false
        internal set
    var x = 0f
        internal set
    var y = 0f
        internal set
    internal var baseX = 0f
    internal var age = 0f
    internal var rest = 0f
}

/** The part of the tank a creature steers against: its width, the touch, and the food. */
internal class TankWorld {
    var aspect = TankSimulation.DEFAULT_ASPECT
    var touched = false
    var touchX = 0f
    var touchY = 0f
    val food = Array(TankSimulation.MAX_FOOD) { FoodPellet() }
    var eaten = 0
}

/**
 * The rules that move one creature for one step, in tank units (height 1.0, y down). Each rule adds
 * to the creature's desired velocity; the creature then eases toward it, so turns are arcs, never
 * snaps. Rules in priority order: wander (always), flee a touch (sets panic), seek food (only when
 * not frightened), keep off the walls (always strongest near them). The creature uses exactly one
 * [Rng] sample per step, whatever else is going on, so a touch or a pellet never shifts the random
 * stream and a run with and without it stay comparable.
 *
 * No allocation: state lives in the [Creature] and the [TankWorld]. The numbers are starting values,
 * not tuned on a device.
 */
internal object Steering {
    /** Calm top speed as a multiple of the creature's cruise speed. */
    const val MAX_SPEED_FACTOR = 1.3f

    /** Never slower than this multiple of cruise: nothing stalls. */
    const val MIN_SPEED_FACTOR = 0.3f

    /** Extra speed at full panic, as a multiple of the calm speed. */
    const val PANIC_BOOST = 1.6f

    /** Panic lost per second once the touch is gone. */
    const val PANIC_DECAY = 1.5f

    /** Below this panic a creature is calm enough to go for food. */
    const val PANIC_FORGETS_FOOD = 0.15f

    const val SEEK_RADIUS = 0.5f
    const val FOOD_GAIN = 1.4f
    const val FLEE_GAIN = 2.5f
    const val WALL_MARGIN = 0.12f
    const val WALL_GAIN = 3f
    const val WALL_GAIN_WHILE_SEEKING = 0.75f

    /** How fast velocity eases to the desired one, per second. */
    const val TURN_RESPONSE = 3.5f

    /** Heading drift, radians per second at most. */
    const val WANDER_TURN = 6f

    /** Swimming mostly sideways: the vertical part of the wander direction is scaled by this. */
    const val VERTICAL_BIAS = 0.45f

    const val WIGGLE_DECAY = 1.2f
    const val EAT_REACH = 0.04f

    private const val WALL_TURN = 6f
    private const val PITCH_RESPONSE = 6f
    private const val TWO_PI = (2.0 * PI).toFloat()

    fun step(c: Creature, world: TankWorld, dt: Float, rng: Rng) {
        val jitter = rng.next() * 2f - 1f
        c.wanderAngle = wrap(c.wanderAngle + jitter * WANDER_TURN * dt)
        c.panic = max(0f, c.panic - PANIC_DECAY * dt)
        c.wiggle = max(0f, c.wiggle - WIGGLE_DECAY * dt)
        c.dvx = 0f
        c.dvy = 0f

        if (world.touched) flee(c, world)
        val target = if (c.panic < PANIC_FORGETS_FOOD) nearestFood(c, world) else null

        val wander = if (target != null) 0.3f else 1f
        c.dvx += cos(c.wanderAngle) * c.cruise * wander
        c.dvy += sin(c.wanderAngle) * VERTICAL_BIAS * c.cruise * wander
        if (target != null) seek(c, target, world)
        avoidWalls(c, world, dt, if (target != null) WALL_GAIN_WHILE_SEEKING else WALL_GAIN)

        val boost = 1f + PANIC_BOOST * c.panic
        c.dvx *= boost
        c.dvy *= boost
        move(c, world, dt)
    }

    /** Keeps [c] inside the tank for a given width; also used when the tank is resized. */
    fun clampInside(c: Creature, aspect: Float) {
        val minX = min(c.halfW, aspect / 2f)
        val minY = min(c.halfH, 0.5f)
        if (c.x < minX) { c.x = minX; if (c.vx < 0f) c.vx = 0f }
        if (c.x > aspect - minX) { c.x = aspect - minX; if (c.vx > 0f) c.vx = 0f }
        if (c.y < minY) { c.y = minY; if (c.vy < 0f) c.vy = 0f }
        if (c.y > 1f - minY) { c.y = 1f - minY; if (c.vy > 0f) c.vy = 0f }
    }

    private fun flee(c: Creature, world: TankWorld) {
        val dx = c.x - world.touchX
        val dy = c.y - world.touchY
        val d = hypot(dx, dy)
        if (d >= TankSimulation.DISTURB_RADIUS) return
        c.panic = max(c.panic, 1f - 0.5f * d / TankSimulation.DISTURB_RADIUS)
        val awayX: Float
        val awayY: Float
        if (d > 1e-4f) {
            awayX = dx / d
            awayY = dy / d
        } else {
            awayX = c.facing.toFloat()
            awayY = 0f
        }
        c.dvx += awayX * c.cruise * FLEE_GAIN
        c.dvy += awayY * c.cruise * FLEE_GAIN
    }

    private fun nearestFood(c: Creature, world: TankWorld): FoodPellet? {
        var best: FoodPellet? = null
        var bestD = SEEK_RADIUS
        for (p in world.food) {
            if (!p.active) continue
            val d = hypot(p.x - c.x, p.y - c.y)
            if (d <= bestD) {
                bestD = d
                best = p
            }
        }
        return best
    }

    private fun seek(c: Creature, p: FoodPellet, world: TankWorld) {
        val dx = p.x - c.x
        val dy = p.y - c.y
        val d = hypot(dx, dy)
        if (d <= EAT_REACH + 0.6f * max(c.halfW, c.halfH)) {
            p.active = false
            world.eaten++
            c.wiggle = 1f
            return
        }
        c.dvx += dx / d * c.cruise * FOOD_GAIN
        c.dvy += dy / d * c.cruise * FOOD_GAIN
    }

    private fun avoidWalls(c: Creature, world: TankWorld, dt: Float, gain: Float) {
        val minX = min(c.halfW, world.aspect / 2f)
        val minY = min(c.halfH, 0.5f)
        val px = ramp((c.x - minX) / WALL_MARGIN) - ramp((world.aspect - minX - c.x) / WALL_MARGIN)
        val py = ramp((c.y - minY) / WALL_MARGIN) - ramp((1f - minY - c.y) / WALL_MARGIN)
        c.dvx += px * c.cruise * gain
        c.dvy += py * c.cruise * gain
        if (hypot(px, py) > 0.05f) {
            // Swing the wander heading inward too, so wandering stops pulling toward the wall.
            val diff = wrap(atan2(py, px) - c.wanderAngle)
            c.wanderAngle = wrap(c.wanderAngle + diff * min(1f, WALL_TURN * dt))
        }
    }

    /** 0 when [t] (distance to a wall over the margin) is 1 or more, rising to 1 at the wall. */
    private fun ramp(t: Float): Float = if (t >= 1f) 0f else 1f - max(t, 0f)

    private fun move(c: Creature, world: TankWorld, dt: Float) {
        val ease = min(1f, TURN_RESPONSE * dt)
        c.vx += (c.dvx - c.vx) * ease
        c.vy += (c.dvy - c.vy) * ease

        val calmCap = c.cruise * MAX_SPEED_FACTOR
        val cap = calmCap * (1f + PANIC_BOOST * c.panic)
        val floor = c.cruise * MIN_SPEED_FACTOR
        val speed = hypot(c.vx, c.vy)
        if (speed > cap) {
            c.vx *= cap / speed
            c.vy *= cap / speed
        } else if (speed < floor) {
            // Crawling or reversing through zero: carry on in the way it wants to go.
            val dl = hypot(c.dvx, c.dvy)
            val ux = if (dl > 1e-6f) c.dvx / dl else cos(c.wanderAngle)
            val uy = if (dl > 1e-6f) c.dvy / dl else sin(c.wanderAngle) * VERTICAL_BIAS
            c.vx = ux * floor
            c.vy = uy * floor
        }

        c.x += c.vx * dt
        c.y += c.vy * dt
        clampInside(c, world.aspect)
        if (!(c.x.isFinite() && c.y.isFinite() && c.vx.isFinite() && c.vy.isFinite())) c.reset(world.aspect)

        c.facing = SwimMesh.nextFacing(c.facing, c.vx, calmCap)
        c.flipX = SwimMesh.nextFlip(c.flipX, c.facing, dt)
        val now = hypot(c.vx, c.vy)
        val pace = min(now / c.cruise, 2.5f)
        val rate = min(0.5f + 0.5f * pace + c.wiggle, 3f)
        c.phase = (c.phase + TWO_PI * c.params.swim.waveHz * rate * dt) % TWO_PI
        val pitch = SwimMesh.pitchFor(c.facing, c.vx, c.vy)
        c.pitch += (pitch - c.pitch) * min(1f, PITCH_RESPONSE * dt)
    }

    private fun wrap(a: Float): Float {
        var r = a % TWO_PI
        if (r > PI.toFloat()) r -= TWO_PI else if (r < -PI.toFloat()) r += TWO_PI
        return r
    }
}
