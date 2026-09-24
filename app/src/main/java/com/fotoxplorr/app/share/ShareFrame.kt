package com.fotoxplorr.app.share

/**
 * How a shared photo is dressed on its way out of the app.
 *
 * Foto Xplorr's own visual language, applied at the one moment a photo leaves for somewhere the
 * app does not control (owner, 2026-08-15: *"make the share options nicer, Fotoz-branded, like
 * adding a frame that makes it look like a polaroid, or the postage stamp we have already
 * designed"*). The stamp is not a new invention here -- it is the same motif the map and the
 * calendar views use, so a shared photo reads as having come from this app specifically.
 *
 * A frame is applied to a **copy**, never to the original. The whole share pipeline goes through
 * [SharePreparer], which writes into the cache directory and hands out FileProvider URIs; nothing
 * in this package opens a library file for writing.
 */
enum class ShareFrame(val label: String, val description: String) {
    /** The photo exactly as it is. */
    NONE(
        label = "No frame",
        description = "The photo on its own",
    ),

    /**
     * Instant-film proportions: a white surround with a deep bottom lip, which is the whole
     * visual signature of the format -- an even border on all four sides reads as a plain matte,
     * not as a Polaroid.
     */
    POLAROID(
        label = "Polaroid",
        description = "White border with a deep lower edge, room for a caption",
    ),

    /**
     * The postage stamp: perforated edge, a thin inner rule, and room for a seal. Shares the
     * motif with the map's stamp pins and the calendar's day tiles.
     */
    STAMP(
        label = "Postage stamp",
        description = "Perforated edge, with your seal if you have set one",
    ),
}

/**
 * Everything the share pipeline needs to know, gathered before the system share sheet opens.
 *
 * A single value rather than a pile of booleans threaded through call sites, because these
 * options are decided together in one sheet and applied together in one pass.
 *
 * @param stripMetadata remove GPS, camera and timestamp EXIF from the shared copy. Defaults to
 *   **true** on owner direction: the safe thing should be what happens when nobody thinks about
 *   it, and the advanced sheet is where someone deliberately turns it off.
 * @param watermark draw the Foto Xplorr mark in the corner. Defaults to **false** (Phase 1 owner
 *   decision 3, 24 Sep 2026: the watermark is off by default for everyone, superseding the
 *   2026-08-21 free-tier-mark decision this field's default used to encode). This field alone
 *   never decided whether the mark actually gets drawn — [resolveWatermark] and [resolvedFor] are
 *   the real decision, and as of this owner decision they return `false` unconditionally,
 *   regardless of Pro status, until a monetization model is chosen.
 * @param caption drawn in the Polaroid's lower lip. Ignored by other frames.
 * @param seal the user's own short signature drawn on a stamp. Ignored by other frames.
 */
data class ShareOptions(
    val frame: ShareFrame = ShareFrame.NONE,
    val stripMetadata: Boolean = true,
    val watermark: Boolean = false,
    val caption: String? = null,
    val seal: String? = null,
) {
    /**
     * True when the photo's pixels have to be re-rendered rather than copied.
     *
     * Worth asking, because a plain copy is dramatically cheaper: it streams bytes and never
     * decodes a bitmap at all. Only a frame or a watermark forces a decode-draw-encode cycle.
     *
     * Reads [watermark] as-is, NOT resolved against Pro status — call this on the result of
     * [resolvedFor], not on a raw, just-constructed [ShareOptions], or a Pro share with no frame
     * chosen will be judged as needing a render it does not need and pay for a decode-draw-encode
     * cycle to draw nothing.
     */
    val requiresRender: Boolean
        get() = frame != ShareFrame.NONE || watermark

    /**
     * Whether the mark should actually be drawn.
     *
     * Always `false` (Phase 1 owner decision 3, 24 Sep 2026): the watermark is off by default for
     * everyone, and nothing gates on Pro status until the owner chooses a monetization model. This
     * still takes [isPro] and is still the one function every render path calls — deliberately,
     * so the [com.fotoxplorr.app.pro.ProEntitlement] seam this function sits on stays wired
     * exactly as before and reactivating the mark later (if a model is chosen) is a one-line
     * change here, not a hunt through every caller that currently asks this question. Before this
     * decision the rule was `!isPro` (free tier marked, Pro unmarked); [watermark] was never
     * consulted then either, for the same reason it is not now — see
     * [com.fotoxplorr.app.share.SharePreparer]'s class doc for why this field alone was never the
     * bypass-proof source of truth.
     */
    @Suppress("UNUSED_PARAMETER")
    fun resolveWatermark(isPro: Boolean): Boolean = false

    /**
     * These options as they should actually be rendered, with [watermark] resolved against
     * [isPro].
     *
     * The one call every real render path makes before touching a pixel
     * ([com.fotoxplorr.app.share.SharePreparer.prepare], and the sheet's own live preview):
     * resolving once, up front, means [requiresRender] downstream is asking about the SAME
     * options that end up drawn rather than the raw, pre-entitlement request.
     */
    fun resolvedFor(isPro: Boolean): ShareOptions = copy(watermark = resolveWatermark(isPro))
}
