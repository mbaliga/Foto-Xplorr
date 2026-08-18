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
     */
    val requiresRender: Boolean
        get() = frame != ShareFrame.NONE || watermark
}
