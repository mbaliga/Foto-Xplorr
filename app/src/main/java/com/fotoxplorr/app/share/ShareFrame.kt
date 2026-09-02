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
 * @param watermark draw the Foto Xplorr mark in the corner. Defaults to **true** (owner,
 *   2026-08-21): the free tier's whole shape is that a share carries a small mark unless the
 *   sharer has unlocked Pro, so "on" has to be what happens when nobody has unlocked anything and
 *   nobody touched the switch — the same reasoning that makes [stripMetadata] default true. This
 *   field alone does not decide whether the mark actually gets drawn, though: a non-Pro sharer
 *   cannot set it false at all (the sheet shows the switch locked on), and a Pro one never draws
 *   it regardless of what it holds. [resolveWatermark] and [resolvedFor] are the real decision.
 * @param caption drawn in the Polaroid's lower lip. Ignored by other frames.
 * @param seal the user's own short signature drawn on a stamp. Ignored by other frames.
 */
data class ShareOptions(
    val frame: ShareFrame = ShareFrame.NONE,
    val stripMetadata: Boolean = true,
    val watermark: Boolean = true,
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
     * Whether the mark should actually be drawn, once the sharer's Pro status is known.
     *
     * Deliberately `!isPro` and NOT `watermark && !isPro`: [watermark] is not consulted at all.
     * Pro removes the mark unconditionally, and everyone else gets it unconditionally — there is
     * no per-share choice on either side, because a Pro account should never have a stray switch
     * that can put the mark back by accident, and a non-Pro account cannot buy its way out of the
     * mark by constructing a [ShareOptions] with `watermark = false` (a bypass this class exists
     * specifically to close — see [com.fotoxplorr.app.share.SharePreparer]'s class doc). The
     * [watermark] field survives on the data class only so the sheet has a checked/unchecked
     * state to render; it is not itself a way to opt out of the free tier's mark. See
     * [com.fotoxplorr.app.share.ShareOptionsSheet], where the switch is shown locked rather than
     * wired to let a non-Pro account flip it off.
     */
    fun resolveWatermark(isPro: Boolean): Boolean = !isPro

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
