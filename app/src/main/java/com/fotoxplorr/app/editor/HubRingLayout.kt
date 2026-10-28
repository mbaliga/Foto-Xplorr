package com.fotoxplorr.app.editor

/** How the hub is drawn: a [RING] of group buttons round the centre, or a plain grouped [LIST]. */
internal enum class HubLayout { RING, LIST }

/**
 * The two decisions of the hub's layout that are arithmetic, kept out of Compose so a test can
 * hold them.
 *
 * The ring only works where five 48 dp buttons fit side by side with their short labels, which
 * is a portrait phone at a normal text size. Everything else (landscape, a tablet, a narrow
 * window, a short window, a large font) gets the list: every group as a heading with its tools
 * under it, scrolling if it must. That is the "non-gesture, nothing hidden" layout, so it is also the fallback.
 *
 * Nothing here has been tried on a device; the thresholds are judgement, named so they can move.
 */
internal object HubRingLayout {

    /** Past this the ring's labels no longer fit a fifth of the width. */
    const val MAX_RING_FONT_SCALE = 1.3f

    /** A tablet or a wide window: the ring would be a small island in a large dark area. */
    const val MIN_LIST_WIDTH_DP = 600

    /** Under this a fifth of the width (less the gutters) is under 48 dp. */
    const val MIN_RING_WIDTH_DP = 300

    /**
     * Under this an upright window (split screen, a small phone with a tall bar) has no room for the
     * ring's pages and a photo worth looking at. A judgement, not measured on a device.
     */
    const val MIN_RING_HEIGHT_DP = 560

    /** How far the middle ring button sits below the two end ones. */
    const val ARC_DEPTH_DP = 14f

    fun kindFor(widthDp: Int, heightDp: Int, fontScale: Float): HubLayout = when {
        widthDp > heightDp -> HubLayout.LIST
        widthDp >= MIN_LIST_WIDTH_DP -> HubLayout.LIST
        widthDp < MIN_RING_WIDTH_DP -> HubLayout.LIST
        heightDp < MIN_RING_HEIGHT_DP -> HubLayout.LIST
        fontScale > MAX_RING_FONT_SCALE -> HubLayout.LIST
        else -> HubLayout.RING
    }

    /**
     * How far down each of [count] ring buttons sits, in the unit of [depth]: the two ends at 0 and
     * the middle at [depth], on a parabola, so the row curves like a smile under the centre and the
     * thumb's natural reach is the bottom of it. One button sits at 0.
     */
    fun arcOffsets(count: Int, depth: Float): List<Float> {
        if (count <= 0) return emptyList()
        if (count == 1) return listOf(0f)
        return List(count) { i ->
            val t = 2f * i / (count - 1) - 1f
            depth * (1f - t * t)
        }
    }
}
