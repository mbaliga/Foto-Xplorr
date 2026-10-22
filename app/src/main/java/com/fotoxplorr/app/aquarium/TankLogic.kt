package com.fotoxplorr.app.aquarium

import kotlin.math.min

/**
 * The decisions the tank screen makes that need no Android: whether to open paused, how long a
 * frame was, which creatures the simulation should hold. Pure, so each is a plain JVM test.
 */
internal object TankLogic {

    /**
     * True when the tank should open paused: the system's animator duration scale is 0 (the person
     * turned animations off). The caller reads the scale (Settings.Global ANIMATOR_DURATION_SCALE,
     * 1 when unset); a missing or non-finite value never pauses. The person can still press Play.
     */
    fun startsPaused(animatorDurationScale: Float): Boolean = motionProfile(animatorDurationScale).startPaused

    /**
     * Seconds between two frame times in nanoseconds, for [TankSimulation.step]. Zero for the first
     * frame after a start (so a long pause is not one big jump) and for a clock that did not move or
     * went backwards; never more than [TankSimulation.MAX_DT].
     */
    fun frameSeconds(lastNanos: Long, nowNanos: Long): Float {
        if (lastNanos <= 0L || nowNanos <= lastNanos) return 0f
        return min((nowNanos - lastNanos) / 1_000_000_000f, TankSimulation.MAX_DT)
    }

    /** Where the Feed button's food enters: a few tank heights down from the surface. */
    const val FEED_Y = 0.02f

    private const val FEED_SLOTS = 7

    /** Across the top in seven places, one per press, so repeated feeding spreads out. Always inside [0, aspect]. */
    fun feedX(slot: Int, aspect: Float): Float {
        val a = if (aspect.isFinite() && aspect > 0f) aspect else TankSimulation.DEFAULT_ASPECT
        return a * (Math.floorMod(slot, FEED_SLOTS) + 1) / (FEED_SLOTS + 1)
    }

    /** The tank's width over its height for a canvas of [widthPx] x [heightPx]; 0 when there is no area. */
    fun aspectOf(widthPx: Int, heightPx: Int): Float =
        if (widthPx > 0 && heightPx > 0) widthPx.toFloat() / heightPx else 0f

    /**
     * Makes the simulation hold exactly [wanted]: creatures that are no longer wanted are taken out,
     * new ones put in (swimming in from the left when [enterFromLeft], otherwise placed at random).
     * A creature already in the tank keeps its place. Returns how many were added.
     */
    fun reconcile(sim: TankSimulation, wanted: List<StoredCreature>, enterFromLeft: Boolean): Int {
        val wantedKeys = wanted.mapTo(HashSet()) { it.id }
        for (key in sim.creatures.map { it.key }) if (key !in wantedKeys) sim.removeCreature(key)
        val present = sim.creatures.mapTo(HashSet()) { it.key }
        var added = 0
        for (record in wanted) {
            if (record.id in present) continue
            if (sim.addCreature(record.id, record.params(), enterFromLeft = enterFromLeft) != null) added++
        }
        return added
    }
}
