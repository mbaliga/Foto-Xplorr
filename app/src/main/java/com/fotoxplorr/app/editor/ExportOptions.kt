package com.fotoxplorr.app.editor

/**
 * Long-edge caps the export controls themselves offer, distinct from [maxExportEdge] (in
 * `EditorScreen.kt`), which is a memory SAFETY ceiling this app imposes regardless of what the
 * user asked for. This one is the user's own choice of a smaller file — for sharing, for a slower
 * connection, for a screen that will never show more pixels than this — and [ORIGINAL] simply
 * declines to add a cap of its own on top of that safety ceiling.
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
