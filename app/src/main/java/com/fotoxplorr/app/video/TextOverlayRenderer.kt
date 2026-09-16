package com.fotoxplorr.app.video

import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextOverlay as Media3TextOverlay
import androidx.media3.effect.TextureOverlay

/**
 * Builds the single Media3 texture overlay a burned-in [TextOverlay] renders as. Kept separate
 * from [VideoExporter] itself: the anchor-point arithmetic below is the only part of "add a text
 * caption" that is worth reading in isolation from the rest of the export pipeline.
 */
internal fun buildTextOverlay(overlay: TextOverlay): TextureOverlay {
    val spanned = SpannableString(overlay.text).apply {
        setSpan(ForegroundColorSpan(overlay.colorArgb), 0, overlay.text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    val (backgroundAnchorY, overlayAnchorY) = when (overlay.position) {
        TextOverlayPosition.TOP -> TOP_BACKGROUND_ANCHOR_Y to TOP_OVERLAY_ANCHOR_Y
        TextOverlayPosition.CENTER -> 0f to 0f
        TextOverlayPosition.BOTTOM -> BOTTOM_BACKGROUND_ANCHOR_Y to BOTTOM_OVERLAY_ANCHOR_Y
    }
    val settings = StaticOverlaySettings.Builder()
        .setBackgroundFrameAnchor(0f, backgroundAnchorY)
        .setOverlayFrameAnchor(0f, overlayAnchorY)
        .setScale(overlay.sizeScale, overlay.sizeScale)
        .build()
    return Media3TextOverlay.createStaticTextOverlay(spanned, settings)
}

// Normalized device coordinates: +y is up, so "near the top of frame" is a positive y.
private const val TOP_BACKGROUND_ANCHOR_Y = 0.85f
private const val TOP_OVERLAY_ANCHOR_Y = 1f
private const val BOTTOM_BACKGROUND_ANCHOR_Y = -0.85f
private const val BOTTOM_OVERLAY_ANCHOR_Y = -1f
