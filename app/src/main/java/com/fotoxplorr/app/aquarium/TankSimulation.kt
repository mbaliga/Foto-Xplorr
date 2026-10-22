package com.fotoxplorr.app.aquarium

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * One swimmer. Mutable on purpose: the simulation updates the same few floats every frame and the
 * drawing code reads them, so a frame allocates nothing. Only [TankSimulation] and [Steering] write.
 *
 * [z] is depth, 0 (far) to 1 (near), fixed for the creature's life; it sets how big and how fast the
 * creature looks ([depthScale]). Creatures are kept sorted by [z], far ones first, so drawing in list
 * order is already back to front.
 */
class Creature internal constructor(val key: String, val params: CreatureParams, z: Float) {
    val z: Float = if (z.isFinite()) z.coerceIn(0f, 1f) else 0.5f
    private val scale = depthScale(this.z)

    /** Sprite size in the tank, at this creature's depth. */
    val drawWidth: Float = params.width * scale
    val drawHeight: Float = params.height * scale
    val halfW: Float = drawWidth / 2f
    val halfH: Float = drawHeight / 2f

    /** Calm cruise speed in tank heights per second at this depth. */
    val cruise: Float = params.speed * scale

    var x = 0f
        internal set
    var y = 0f
        internal set
    var vx = 0f
        internal set
    var vy = 0f
        internal set

    /** Swim-wave phase in radians, 0..2 pi. */
    var phase = 0f
        internal set

    /** Direction of travel, +1 right or -1 left. */
    var facing = 1
        internal set

    /** Mirror factor easing between +1 and -1 as the creature turns round. */
    var flipX = 1f
        internal set

    /** Smoothed tilt in radians, positive nose-down for a creature facing right. */
    var pitch = 0f
        internal set

    /** 0..1, set by a touch nearby; speeds the creature up and fades. */
    var panic = 0f
        internal set

    /** 0..1, a short extra flutter after eating. */
    var wiggle = 0f
        internal set

    internal var wanderAngle = 0f
    internal var dvx = 0f
    internal var dvy = 0f

    /** The mirror factor to hand [SwimPose.flipX]: a drawing whose head is on the left is mirrored once more. */
    val displayFlip: Float get() = if (params.headLeft) -flipX else flipX

    /**
     * Fills [pose] for this frame. [pxPerUnit] converts tank units to pixels (the canvas height);
     * ([offsetX], [offsetY]) is where the tank's top left sits on the canvas; [waveScale] comes from
     * the motion profile. Writes only floats, so it can run every frame.
     */
    fun writePose(pose: SwimPose, pxPerUnit: Float, waveScale: Float, offsetX: Float = 0f, offsetY: Float = 0f) {
        pose.widthPx = drawWidth * pxPerUnit
        pose.heightPx = drawHeight * pxPerUnit
        pose.centerX = offsetX + x * pxPerUnit
        pose.centerY = offsetY + y * pxPerUnit
        pose.phase = phase
        pose.waveScale = waveScale
        pose.flipX = displayFlip
        pose.pitch = pitch
        pose.headRight = !params.headLeft
    }

    internal fun reset(aspect: Float) {
        x = aspect / 2f
        y = 0.5f
        vx = 0f
        vy = 0f
        phase = 0f
        flipX = facing.toFloat()
        pitch = 0f
        panic = 0f
        wiggle = 0f
    }

    companion object {
        /** Far creatures draw at 0.6, near ones at 1.2; a non-finite depth is mid-tank. */
        fun depthScale(z: Float): Float = 0.6f + 0.6f * (if (z.isFinite()) z.coerceIn(0f, 1f) else 0.5f)
    }
}

/**
 * The aquarium's world: up to [MAX_CREATURES] creatures wandering a tank 1.0 tall and [aspect]
 * wide, a touch that scares them, and food that sinks and gets eaten. Pure Kotlin and seeded, so a
 * test run is reproducible; nothing here knows about Android.
 *
 * [step] clamps its time step, so a stalled frame (a 500 ms hitch, the app returning from the
 * background) advances at most [MAX_DT]; the result for a given sequence of steps is deterministic,
 * not independent of the step size. The tank forgets everything on a new instance: positions are
 * re-randomised on open, and only the creatures' own records are stored.
 */
class TankSimulation(seed: Long = 1L, aspect: Float = DEFAULT_ASPECT) {
    private val rng: Rng = SeededRng(seed)
    private val world = TankWorld()
    private val list = ArrayList<Creature>(MAX_CREATURES)

    /** Back to front. Do not modify. */
    val creatures: List<Creature> get() = list

    /** The fixed pool of pellets; those with [FoodPellet.active] are in the water. */
    val food: Array<FoodPellet> get() = world.food

    /** Tank width, tank heights. Always finite and inside [MIN_ASPECT]..[MAX_ASPECT]. */
    val aspect: Float get() = world.aspect

    /** Time scale for [step] from the motion profile; 0 holds everything still. */
    var speedScale = 1f
        set(value) { field = if (value.isFinite()) value.coerceIn(0f, 2f) else 1f }

    /** Handed to [Creature.writePose]; not used by the simulation itself. */
    var waveScale = 1f
        set(value) { field = if (value.isFinite()) value.coerceIn(0f, 1.5f) else 1f }

    /** Pellets eaten since this simulation began. */
    val foodEaten: Int get() = world.eaten

    val activeFoodCount: Int get() = world.food.count { it.active }

    init {
        setAspect(aspect)
    }

    fun applyProfile(profile: MotionProfile) {
        speedScale = profile.speedScale
        waveScale = profile.waveScale
    }

    /**
     * Resizes the tank. A non-finite or non-positive width becomes [DEFAULT_ASPECT], and anything
     * else is clamped to the supported range. Every creature and pellet is pulled back inside at once.
     */
    fun setAspect(aspect: Float) {
        world.aspect = if (aspect.isFinite() && aspect > 0f) aspect.coerceIn(MIN_ASPECT, MAX_ASPECT) else DEFAULT_ASPECT
        for (c in list) Steering.clampInside(c, world.aspect)
        for (p in world.food) if (p.active) {
            p.baseX = p.baseX.coerceIn(0f, world.aspect)
            p.x = p.x.coerceIn(0f, world.aspect)
        }
    }

    /**
     * Puts a creature in the tank, or returns null when it already holds [MAX_CREATURES]. [x], [y]
     * and [z] place it exactly (for tests); left null they are drawn from the seeded stream, and
     * [enterFromLeft] starts it at the left edge heading right. The same four random samples are used
     * either way, so placing one creature does not change what the next one gets.
     */
    fun addCreature(
        key: String,
        params: CreatureParams,
        x: Float? = null,
        y: Float? = null,
        z: Float? = null,
        enterFromLeft: Boolean = false,
    ): Creature? {
        if (list.size >= MAX_CREATURES) return null
        val rz = rng.next()
        val rx = rng.next()
        val ry = rng.next()
        val ra = rng.next()
        val c = Creature(key, params, z ?: rz)
        val minX = minOf(c.halfW, world.aspect / 2f)
        val minY = minOf(c.halfH, 0.5f)
        c.x = x ?: if (enterFromLeft) minX else minX + rx * (world.aspect - 2f * minX)
        c.y = y ?: (minY + ry * (1f - 2f * minY))
        c.wanderAngle = if (enterFromLeft) 0f else (ra * 2f - 1f) * PI.toFloat()
        c.facing = if (enterFromLeft || cos(c.wanderAngle) >= 0f) 1 else -1
        c.flipX = c.facing.toFloat()
        c.vx = c.facing * c.cruise
        c.phase = ra * (2.0 * PI).toFloat()
        Steering.clampInside(c, world.aspect)
        var at = list.size
        while (at > 0 && list[at - 1].z > c.z) at--
        list.add(at, c)
        return c
    }

    /** Takes every creature with [key] out of the tank. */
    fun removeCreature(key: String): Boolean = list.removeAll { it.key == key }

    /** Drops a pellet at ([x], [y]); false when the pool is full or the spot is not a number. */
    fun dropFood(x: Float, y: Float): Boolean {
        if (!x.isFinite() || !y.isFinite()) return false
        val p = world.food.firstOrNull { !it.active } ?: return false
        p.active = true
        p.baseX = x.coerceIn(0f, world.aspect)
        p.x = p.baseX
        p.y = y.coerceIn(0f, FOOD_FLOOR_Y)
        p.age = 0f
        p.rest = 0f
        return true
    }

    /** A finger is down at ([x], [y]): nearby creatures flee until [endDisturbance]. */
    fun disturb(x: Float, y: Float) {
        if (!x.isFinite() || !y.isFinite()) return
        world.touched = true
        world.touchX = x
        world.touchY = y
    }

    fun endDisturbance() {
        world.touched = false
    }

    /**
     * Advances the tank by [dt] seconds scaled by [speedScale]. A negative, zero, NaN or infinite
     * [dt] does nothing; a large one counts as [MAX_DT].
     */
    fun step(dt: Float) {
        val d = (if (dt.isFinite() && dt > 0f) minOf(dt, MAX_DT) else 0f) * speedScale
        if (!(d > 0f)) return
        stepFood(d)
        for (i in list.indices) Steering.step(list[i], world, d, rng)
    }

    private fun stepFood(dt: Float) {
        for (p in world.food) {
            if (!p.active) continue
            p.age += dt
            if (p.y < FOOD_FLOOR_Y) {
                p.y = minOf(FOOD_FLOOR_Y, p.y + FOOD_SINK * dt)
            } else {
                p.rest += dt
                if (p.rest >= FOOD_FLOOR_SECONDS) {
                    p.active = false
                    continue
                }
            }
            p.x = (p.baseX + FOOD_SWAY * sin(p.age * 2f + p.baseX * 7f)).coerceIn(0f, world.aspect)
        }
    }

    companion object {
        const val MAX_CREATURES = 12
        const val MAX_FOOD = 10

        /** The longest step the simulation takes in one go. */
        const val MAX_DT = 0.05f

        const val DEFAULT_ASPECT = 1.5f
        const val MIN_ASPECT = 0.25f
        const val MAX_ASPECT = 4f

        /** How close a touch must be to scare a creature, tank heights. */
        const val DISTURB_RADIUS = 0.25f

        /** Pellets sink this many tank heights per second. */
        const val FOOD_SINK = 0.08f
        const val FOOD_SWAY = 0.008f

        /** Where a pellet comes to rest. */
        const val FOOD_FLOOR_Y = 0.98f

        /** A pellet on the floor goes after this long. */
        const val FOOD_FLOOR_SECONDS = 8f
    }
}
