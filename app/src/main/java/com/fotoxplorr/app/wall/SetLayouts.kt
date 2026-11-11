package com.fotoxplorr.app.wall

/** How a set of pictures is arranged on the wall. */
internal enum class SetKind(val key: String, val capacity: Int) {
    SINGLE("single", 1),

    /** One picture as big as the wall takes. */
    POSTER("poster", 1),
    PAIR("pair", 2),
    TRIO("trio", 3),
    GRID("grid", 6),
    ;

    companion object {
        fun fromKey(key: String): SetKind? = entries.firstOrNull { it.key == key }
    }
}

/**
 * How the pictures of a set lie: all upright, all lying down, or some of each. It decides the cell
 * each picture gets ([SetLayouts.cell]), so a set of landscape photos is spaced for landscape
 * frames and a mixed one is spaced so that neither shape can touch its neighbour.
 */
internal enum class SetOrientation {
    PORTRAIT,
    LANDSCAPE,
    MIXED,
    ;

    companion object {
        /** The orientation of a set whose pictures lie down where [landscape] says so. No pictures is upright. */
        fun of(landscape: List<Boolean>): SetOrientation = when {
            landscape.none { it } -> PORTRAIT
            landscape.all { it } -> LANDSCAPE
            else -> MIXED
        }
    }
}

/** A picture's place in a set: its centre as an offset from the set's centre, and its size, all in cm. */
internal data class PlacedBox(val dxCm: Float, val dyCm: Float, val wCm: Float, val hCm: Float)

/**
 * Where the pictures of a set go, as pure functions of the kind, the count and the size.
 * Offsets are from the set's own centre; the screen puts that centre on the wall.
 */
internal object SetLayouts {
    /** Clear space between the outer edges of neighbours (picture + mat + moulding). */
    const val GAP_CM = 6f

    /** In a trio the two flanking pictures are this much of the middle one. */
    const val FLANK_SCALE = 0.7f

    const val SET_MAX_HEIGHT_CM = 60f

    /** Share of the wall's width and height a set may use. */
    const val WALL_SHARE_W = 0.8f
    const val WALL_SHARE_H = 0.65f

    /**
     * The boxes for [count] pictures (clamped to the kind's capacity) at size [base], where each
     * picture's border takes [borderCm] more on every side, so the borders never overlap.
     * The first box is the first picture; a trio's first box is the large middle one.
     */
    fun boxes(kind: SetKind, count: Int, base: FrameSize, borderCm: Float): List<PlacedBox> {
        val n = count.coerceIn(1, kind.capacity)
        val pitchX = base.wCm + 2 * borderCm + GAP_CM
        val pitchY = base.hCm + 2 * borderCm + GAP_CM
        return when {
            kind == SetKind.TRIO && n == 3 -> {
                val fw = base.wCm * FLANK_SCALE
                val fh = base.hCm * FLANK_SCALE
                val side = (base.wCm + fw) / 2 + 2 * borderCm + GAP_CM
                listOf(
                    PlacedBox(0f, 0f, base.wCm, base.hCm),
                    PlacedBox(-side, 0f, fw, fh),
                    PlacedBox(side, 0f, fw, fh),
                )
            }
            kind == SetKind.GRID && n > 3 -> {
                val cols = 3
                val rows = (n + cols - 1) / cols
                List(n) { i ->
                    val row = i / cols
                    val inRow = if (row == rows - 1) n - row * cols else cols
                    val col = i % cols
                    PlacedBox((col - (inRow - 1) / 2f) * pitchX, (row - (rows - 1) / 2f) * pitchY, base.wCm, base.hCm)
                }
            }
            else -> List(n) { i -> PlacedBox((i - (n - 1) / 2f) * pitchX, 0f, base.wCm, base.hCm) }
        }
    }

    /** The outer size of a set (picture borders included), as (width, height) in cm. */
    fun extent(boxes: List<PlacedBox>, borderCm: Float): Pair<Float, Float> {
        if (boxes.isEmpty()) return 0f to 0f
        val left = boxes.minOf { it.dxCm - it.wCm / 2 } - borderCm
        val right = boxes.maxOf { it.dxCm + it.wCm / 2 } + borderCm
        val top = boxes.minOf { it.dyCm - it.hCm / 2 } - borderCm
        val bottom = boxes.maxOf { it.dyCm + it.hCm / 2 } + borderCm
        return (right - left) to (bottom - top)
    }

    /**
     * The size [boxes] is given for a [preset] (held in portrait) in a set that lies as [orientation]:
     * the preset itself, turned, or a square on its long side, which holds either shape.
     */
    fun cell(preset: FrameSize, orientation: SetOrientation): FrameSize = when (orientation) {
        SetOrientation.PORTRAIT -> preset
        SetOrientation.LANDSCAPE -> FrameSize(preset.label, preset.hCm, preset.wCm)
        SetOrientation.MIXED -> FrameSize(preset.label, preset.hCm, preset.hCm)
    }

    /**
     * The biggest standard size whose set fits [WALL_SHARE_W] x [WALL_SHARE_H] of a wall photo
     * [wallWidthCm] x [wallHeightCm]; the smallest if none does. Only a poster may be taller than
     * [SET_MAX_HEIGHT_CM], so a pair or a grid starts at a size that reads as a set. The size
     * returned is the preset in portrait; the set is spaced for [orientation] ([cell]).
     */
    fun biggestThatFits(
        kind: SetKind,
        count: Int,
        wallWidthCm: Float,
        wallHeightCm: Float,
        borderCm: Float,
        orientation: SetOrientation = SetOrientation.PORTRAIT,
    ): FrameSize {
        val sizes = FramePresets.all
            .filter { kind == SetKind.POSTER || it.hCm <= SET_MAX_HEIGHT_CM }
            .sortedBy { it.wCm * it.hCm }
        return sizes.lastOrNull { size ->
            val (w, h) = extent(boxes(kind, count, cell(size, orientation), borderCm), borderCm)
            w <= wallWidthCm * WALL_SHARE_W && h <= wallHeightCm * WALL_SHARE_H
        } ?: sizes.first()
    }
}
