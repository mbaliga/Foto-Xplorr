package com.fotoxplorr.app.aquarium

/**
 * How lively the tank is allowed to be: a time scale for the whole simulation, a scale for the body
 * wave, and whether it should open paused. Decided by one pure function from the two things a person
 * can ask for, the system's "remove animations" setting and the tank's own calm switch.
 */
data class MotionProfile(
    /** Multiplies the simulation's time step: swimming, turning, sinking food. */
    val speedScale: Float,
    /** Multiplies the body wave; 0 is a rigid sprite. */
    val waveScale: Float,
    /** True when the tank should open paused and wait for a Play press. */
    val startPaused: Boolean,
) {
    companion object {
        val NORMAL = MotionProfile(speedScale = 1f, waveScale = 1f, startPaused = false)
        val CALM = MotionProfile(speedScale = 0.25f, waveScale = 0.3f, startPaused = false)
    }
}

/**
 * [animatorDurationScale] is Settings.Global ANIMATOR_DURATION_SCALE, read by the caller (1 when
 * unset); 0 means the person turned animations off, and the tank then opens paused and calm. [calm]
 * is the tank's own switch for the same slow motion without pausing. A non-finite scale is "unset".
 * Any other positive scale, such as the 0.5x or 10x of the developer options, changes nothing here:
 * those slow or speed up transitions, not an ambient scene.
 */
fun motionProfile(animatorDurationScale: Float, calm: Boolean = false): MotionProfile {
    val off = animatorDurationScale.isFinite() && animatorDurationScale <= 0f
    return when {
        off -> MotionProfile.CALM.copy(startPaused = true)
        calm -> MotionProfile.CALM
        else -> MotionProfile.NORMAL
    }
}
