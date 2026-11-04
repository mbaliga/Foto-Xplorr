package com.fotoxplorr.app.viewer.colour

import android.content.Context

/** Which pro overlays are on. All off by default: a photo being looked at carries nothing extra. */
data class ProOverlayChoice(
    val info: Boolean = false,
    val histogram: Boolean = false,
    val clipping: Boolean = false,
    val peaking: Boolean = false,
) {
    /** Whether anything needs the small analysis copy of the photo decoded. */
    val needsPixels: Boolean get() = histogram || clipping || peaking

    val anyOn: Boolean get() = info || needsPixels

    fun with(overlay: ProOverlay, on: Boolean): ProOverlayChoice = when (overlay) {
        ProOverlay.INFO -> copy(info = on)
        ProOverlay.HISTOGRAM -> copy(histogram = on)
        ProOverlay.CLIPPING -> copy(clipping = on)
        ProOverlay.PEAKING -> copy(peaking = on)
    }

    fun has(overlay: ProOverlay): Boolean = when (overlay) {
        ProOverlay.INFO -> info
        ProOverlay.HISTOGRAM -> histogram
        ProOverlay.CLIPPING -> clipping
        ProOverlay.PEAKING -> peaking
    }
}

/** The four overlays, with their label and one-sentence caption (the feature's own copy). */
enum class ProOverlay(val label: String, val caption: String) {
    INFO("Photo info", "Shows the colour, size and exposure over the photo."),
    HISTOGRAM("Histogram", "Shows where the tones sit, from dark to light."),
    CLIPPING("Clipping", "Marks blown highlights in red and crushed shadows in blue."),
    PEAKING("Focus peaking", "Marks the sharpest edges in yellow."),
}

/**
 * The choice, remembered between photos and launches in the app's own preferences (a viewer-local
 * convenience: it is a way of looking, not part of the library). Every write is a single key.
 */
class ProOverlayStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("viewer_pro_overlay", Context.MODE_PRIVATE)

    fun load(): ProOverlayChoice = ProOverlayChoice(
        info = prefs.getBoolean(ProOverlay.INFO.name, false),
        histogram = prefs.getBoolean(ProOverlay.HISTOGRAM.name, false),
        clipping = prefs.getBoolean(ProOverlay.CLIPPING.name, false),
        peaking = prefs.getBoolean(ProOverlay.PEAKING.name, false),
    )

    fun set(overlay: ProOverlay, on: Boolean) {
        prefs.edit().putBoolean(overlay.name, on).apply()
    }
}
