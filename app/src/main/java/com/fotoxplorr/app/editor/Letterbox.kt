package com.fotoxplorr.app.editor

import androidx.compose.ui.geometry.Rect
import kotlin.math.min

/**
 * Where a `ContentScale.Fit` image of [contentWidth]x[contentHeight] is actually drawn inside a
 * container of [containerWidth]x[containerHeight] — the same letterbox math the Heal tool and the
 * crop overlay both need to turn a finger's raw pixel position into a point on the bitmap's own
 * pixels, kept in one place so the two tools cannot silently disagree about it.
 *
 * `androidx.compose.ui.geometry.Rect` is a pure Kotlin multiplatform value type (no Android
 * framework dependency), which is what makes this testable on the plain JVM like every other pure
 * function in this package, with no Robolectric needed.
 */
fun letterboxRect(containerWidth: Float, containerHeight: Float, contentWidth: Float, contentHeight: Float): Rect {
    if (containerWidth <= 0f || containerHeight <= 0f || contentWidth <= 0f || contentHeight <= 0f) {
        return Rect(0f, 0f, containerWidth.coerceAtLeast(0f), containerHeight.coerceAtLeast(0f))
    }
    val scale = min(containerWidth / contentWidth, containerHeight / contentHeight)
    val drawnWidth = contentWidth * scale
    val drawnHeight = contentHeight * scale
    val left = (containerWidth - drawnWidth) / 2f
    val top = (containerHeight - drawnHeight) / 2f
    return Rect(left, top, left + drawnWidth, top + drawnHeight)
}
