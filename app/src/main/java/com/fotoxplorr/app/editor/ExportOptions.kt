package com.fotoxplorr.app.editor

/**
 * Long-edge caps the export controls themselves offer, distinct from [MAX_EXPORT_EDGE], the decode
 * ceiling this app imposes regardless of what the user asked for. This one is the user's own
 * choice of a smaller file — for sharing, for a slower connection, for a screen that will never
 * show more pixels than this — and [ORIGINAL] simply declines to add a cap of its own on top of
 * that ceiling.
 */
enum class MaxEdgePreset(val label: String, val edgePx: Int?) {
    ORIGINAL("Original", null),
    LARGE("4096", 4096),
    MEDIUM("2048", 2048),
    SMALL("1024", 1024),
}

/**
 * What the export controls let a user choose on top of the recipe itself: an explicit output
 * [format] (null keeps the source's own, via [outputFormatFor]), a JPEG/WebP [quality], and a
 * [maxEdge] cap. Deliberately separate from [EditRecipe]: none of this describes what the
 * PHOTOGRAPH looks like, only how this one export of it is encoded, so it is never persisted by
 * [RecipeStore] or [PresetStore] alongside the recipe.
 */
data class ExportOptions(
    val format: OutputFormat? = null,
    /** null = the resolved format's own default (see [OutputFormat.exportQuality]). Meaningful
     *  only for JPEG/WebP/HEIC — PNG's encoder is lossless and ignores it regardless. */
    val quality: Int? = null,
    val maxEdge: MaxEdgePreset = MaxEdgePreset.ORIGINAL,
) {
    /**
     * The quality [EditedCopyWriter] is actually called with: [quality] clamped to a range where
     * a re-encode is a genuine trade-off rather than visible blocking artefacts, or [format]'s own
     * default when the user has never touched the slider.
     */
    fun resolvedQuality(resolvedFormat: OutputFormat): Int =
        (quality ?: resolvedFormat.exportQuality()).coerceIn(MIN_QUALITY, MAX_QUALITY)

    companion object {
        /** Below this a JPEG/WebP/HEIC re-encode of an already-compressed camera photo visibly
         *  blocks — see [OutputFormat.exportQuality]'s own doc. */
        const val MIN_QUALITY = 50
        const val MAX_QUALITY = 100
    }
}

/**
 * The absolute ceiling on an export's longest edge: 8192 is 67 megapixels at 1:1 and covers every
 * phone camera in circulation, so there is never a reason to decode above it. It is NOT derived
 * from this device's memory class: an earlier version scaled it down from `memoryClass`, which
 * silently turned "Original" into roughly 2.8k px on a 256 MB heap. A device that genuinely cannot
 * hold the buffers finds that out from an actual [OutOfMemoryError] instead, and [exportEdgeLadder]
 * steps down from there.
 */
internal const val MAX_EXPORT_EDGE = 8192

/** The pixel-count budget alongside [MAX_EXPORT_EDGE] -- P0-03: a source can be under the edge
 * cap on both axes yet still exceed a sane pixel count on an unusual aspect ratio, so both limits
 * apply together, exactly like [com.fotoxplorr.app.media.DecodeLimits] everywhere else. */
internal const val MAX_EXPORT_PIXELS = 64_000_000L

/** The last rung of [exportEdgeLadder]: a crop or straighten needs enough real pixels to look
 *  sharp once exported, so the ladder never steps below this on its own. */
internal const val MIN_LADDER_EDGE = 1536

/** The long edge an export under these options is first attempted at: the user's own cap when
 *  they chose one, otherwise [MAX_EXPORT_EDGE]. Independent of the device's memory class. */
internal fun ExportOptions.renderEdge(): Int = maxEdge.edgePx ?: MAX_EXPORT_EDGE

/**
 * The long edges an export is attempted at, largest first: [requested], then halved until the next
 * halving would fall to [floor] or below, then [floor] itself. The next rung is only ever tried
 * after the previous one threw [OutOfMemoryError] (see [renderDownTheLadder]) — never up front.
 * A request at or below [floor] is a single rung, and the list is never empty.
 */
internal fun exportEdgeLadder(requested: Int, floor: Int = MIN_LADDER_EDGE): List<Int> {
    val rungs = mutableListOf(requested)
    var edge = requested / 2
    while (edge > floor) {
        rungs += edge
        edge /= 2
    }
    if (requested > floor) rungs += floor
    return rungs
}

/** [renderDownTheLadder]'s result: what [attempt][renderDownTheLadder] produced, at which edge,
 *  and whether that was lower than the first rung. */
internal class LadderOutcome<T : Any>(val value: T, val edge: Int, val steppedDown: Boolean)

/**
 * Runs [attempt] at each edge in [ladder] in turn, moving to the next ONLY when the attempt threw
 * [OutOfMemoryError]. An attempt that returns null (the photo could not be read or rendered for a
 * reason a smaller size would not fix) ends the search with null rather than burning every rung,
 * and any other throwable propagates untouched.
 *
 * Catching [OutOfMemoryError] is only reliable around the bitmap allocations themselves, so the
 * attempt should do nothing but decode and render and must not hold on to its bitmaps when it
 * throws: by the time the next rung runs they have to be unreachable. Returns null when every rung
 * ran out of memory.
 */
internal suspend fun <T : Any> renderDownTheLadder(
    ladder: List<Int>,
    attempt: suspend (edge: Int) -> T?,
): LadderOutcome<T>? {
    ladder.forEachIndexed { index, edge ->
        try {
            val value = attempt(edge) ?: return null
            return LadderOutcome(value, edge, steppedDown = index > 0)
        } catch (_: OutOfMemoryError) {
            // Fall through to the next, smaller rung.
        }
    }
    return null
}
