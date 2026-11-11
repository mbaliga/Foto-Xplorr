package com.fotoxplorr.app.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput

/**
 * An interactive crop box drawn over the preview image: 8 drag handles, drag-anywhere-inside to
 * move, a rule-of-thirds grid while a drag is in progress, and an aspect lock that constrains
 * every handle exactly the way [CropRect.dragged] defines. Replaces the preset-only crop tool —
 * the presets themselves still work (see [EditorScreen]'s `AspectPreset` row), they just now set
 * the SAME box this overlay lets you drag by hand afterwards.
 *
 * All the actual geometry is [CropInteraction]'s pure, unit-tested functions; this composable's
 * only job is turning a finger's pixel position into the normalised delta those functions expect,
 * and drawing the result.
 *
 * @param imageRect where the (letterboxed, `ContentScale.Fit`) preview image is actually drawn
 *   within this composable's own bounds — the crop box is drawn and hit-tested against THIS rect,
 *   not the full composable bounds, so it lines up with the pixels it will actually crop.
 * @param lockedAspect see [CropRect.dragged]'s identical parameter — already converted into
 *   normalised space by the caller (via [normalizedAspect]), or null for a free-form box.
 */
@Composable
fun CropOverlay(
    crop: CropRect,
    imageRect: Rect,
    lockedAspect: Float?,
    onCropChange: (CropRect) -> Unit,
    modifier: Modifier = Modifier,
) {
    var activeHandle by remember { mutableStateOf<CropHandle?>(null) }

    Canvas(
        modifier.pointerInput(imageRect, lockedAspect) {
            detectDragGestures(
                onDragStart = { start ->
                    activeHandle = hitTestHandle(crop, imageRect, start)
                },
                onDragEnd = { activeHandle = null },
                onDragCancel = { activeHandle = null },
            ) { change, dragAmount ->
                val handle = activeHandle ?: return@detectDragGestures
                change.consume()
                if (imageRect.width <= 0f || imageRect.height <= 0f) return@detectDragGestures
                val dxNorm = dragAmount.x / imageRect.width
                val dyNorm = dragAmount.y / imageRect.height
                onCropChange(crop.dragged(handle, dxNorm, dyNorm, lockedAspect))
            }
        },
    ) {
        if (imageRect.width <= 0f || imageRect.height <= 0f) return@Canvas
        val box = Rect(
            left = imageRect.left + crop.left * imageRect.width,
            top = imageRect.top + crop.top * imageRect.height,
            right = imageRect.left + crop.right * imageRect.width,
            bottom = imageRect.top + crop.bottom * imageRect.height,
        )

        // Darken everything outside the box, so the crop reads as "this stays" rather than
        // requiring the viewer to mentally subtract a rectangle from the whole frame.
        val scrim = Color.Black.copy(alpha = 0.55f)
        drawRect(scrim, topLeft = imageRect.topLeft, size = androidx.compose.ui.geometry.Size(imageRect.width, box.top - imageRect.top))
        drawRect(
            scrim,
            topLeft = Offset(imageRect.left, box.bottom),
            size = androidx.compose.ui.geometry.Size(imageRect.width, imageRect.bottom - box.bottom),
        )
        drawRect(scrim, topLeft = Offset(imageRect.left, box.top), size = androidx.compose.ui.geometry.Size(box.left - imageRect.left, box.height))
        drawRect(scrim, topLeft = Offset(box.right, box.top), size = androidx.compose.ui.geometry.Size(imageRect.right - box.right, box.height))

        drawRect(Color.White, topLeft = box.topLeft, size = box.size, style = Stroke(width = 3f))

        // Rule of thirds, shown only while a handle is actively being dragged -- a permanent
        // grid on an unedited photo is visual noise; the moment someone is framing a crop is
        // exactly when it earns its place.
        if (activeHandle != null) {
            val thirdW = box.width / 3f
            val thirdH = box.height / 3f
            for (i in 1..2) {
                drawLine(
                    Color.White.copy(alpha = 0.6f),
                    Offset(box.left + thirdW * i, box.top),
                    Offset(box.left + thirdW * i, box.bottom),
                    strokeWidth = 1.5f,
                )
                drawLine(
                    Color.White.copy(alpha = 0.6f),
                    Offset(box.left, box.top + thirdH * i),
                    Offset(box.right, box.top + thirdH * i),
                    strokeWidth = 1.5f,
                )
            }
        }

        handlePoints(box).forEach { (_, point) ->
            drawCircle(Color.White, radius = HANDLE_RADIUS_PX, center = point)
            drawCircle(Color.Black.copy(alpha = 0.4f), radius = HANDLE_RADIUS_PX, center = point, style = Stroke(width = 2f))
        }
    }
}

/** Which [CropHandle] (if any) a drag starting at [position] should grab — the largest of the
 *  handle hit-circles and, failing that, the box's own interior for a move. Outside the box
 *  entirely returns null, so a drag that starts off the crop box does nothing rather than
 *  silently moving it. */
private fun hitTestHandle(crop: CropRect, imageRect: Rect, position: Offset): CropHandle? {
    if (imageRect.width <= 0f || imageRect.height <= 0f) return null
    val box = Rect(
        left = imageRect.left + crop.left * imageRect.width,
        top = imageRect.top + crop.top * imageRect.height,
        right = imageRect.left + crop.right * imageRect.width,
        bottom = imageRect.top + crop.bottom * imageRect.height,
    )
    handlePoints(box).forEach { (handle, point) ->
        if ((point - position).getDistance() <= HANDLE_HIT_RADIUS_PX) return handle
    }
    return if (box.contains(position)) CropHandle.MOVE else null
}

private fun handlePoints(box: Rect): List<Pair<CropHandle, Offset>> = listOf(
    CropHandle.TOP_LEFT to box.topLeft,
    CropHandle.TOP to Offset(box.center.x, box.top),
    CropHandle.TOP_RIGHT to box.topRight,
    CropHandle.RIGHT to Offset(box.right, box.center.y),
    CropHandle.BOTTOM_RIGHT to box.bottomRight,
    CropHandle.BOTTOM to Offset(box.center.x, box.bottom),
    CropHandle.BOTTOM_LEFT to box.bottomLeft,
    CropHandle.LEFT to Offset(box.left, box.center.y),
)

private const val HANDLE_RADIUS_PX = 8f

/** Bigger than the drawn handle itself — a fingertip is much wider than an 8px dot, and a hit
 *  target this generous is what makes the corner handles reachable at all on a phone screen. */
private const val HANDLE_HIT_RADIUS_PX = 36f
