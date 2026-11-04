package com.fotoxplorr.app.gallery

/**
 * Compare (2-up) and survey (N-up): several photos side by side with ONE shared zoom and pan, so
 * the same detail is under the finger or pointer in every cell. Pure; the Compose view is
 * SurveyView.kt.
 */

/** Most photos a survey holds; more than this would put the cells under a usable size on a phone. */
internal const val SURVEY_MAX = 9

private const val PHOTO_ASPECT = 4f / 3f

internal data class SurveyGrid(val columns: Int, val rows: Int)

/**
 * The columns x rows that give each of [count] photos the largest cell in a [width] x [height]
 * area, scored by how big a typical 4:3 photo fits in a cell. Two photos pick side-by-side or stacked from the area's own shape (a landscape tablet puts
 * them side by side, a portrait phone stacks them).
 */
internal fun surveyGrid(count: Int, width: Float, height: Float): SurveyGrid {
    if (count <= 1 || width <= 0f || height <= 0f) return SurveyGrid(1, 1)
    var best = SurveyGrid(count, 1)
    var bestArea = -1f
    for (cols in 1..count) {
        val rows = (count + cols - 1) / cols
        // a column count that leaves a whole empty row is never better than one fewer row
        if ((rows - 1) * cols >= count) continue
        // the area of a typical 4:3 photo fitted inside the cell, not the cell's own area
        val fitW = minOf(width / cols, height / rows * PHOTO_ASPECT)
        val area = fitW * fitW / PHOTO_ASPECT
        if (area > bestArea + 1e-3f) {
            bestArea = area
            best = SurveyGrid(cols, rows)
        }
    }
    return best
}

/** Cell [i]'s rectangle in a [width] x [height] area (row-major; the last row is centred). */
internal data class CellRect(val left: Float, val top: Float, val width: Float, val height: Float)

internal fun surveyCell(i: Int, count: Int, grid: SurveyGrid, width: Float, height: Float): CellRect {
    val w = width / grid.columns
    val h = height / grid.rows
    val row = i / grid.columns
    val col = i % grid.columns
    val inLast = if (row == grid.rows - 1) count - row * grid.columns else grid.columns
    val offset = (grid.columns - inLast) * w / 2f
    return CellRect(offset + col * w, row * h, w, h)
}

/** Zoom and pan shared by every cell. Pan is in cell pixels from the centred position. */
internal data class SyncedView(val zoom: Float = 1f, val panX: Float = 0f, val panY: Float = 0f) {
    companion object {
        const val MAX_ZOOM = 8f
    }
}

/**
 * Applies a pinch ([zoomBy] about [centroidX]/[centroidY], relative to the cell centre) and a drag
 * ([dx]/[dy]) to the shared view. Pan is clamped so the photo never leaves the cell, and is zero at
 * zoom 1 (nothing to pan).
 */
internal fun SyncedView.transformed(
    zoomBy: Float,
    dx: Float,
    dy: Float,
    centroidX: Float,
    centroidY: Float,
    cellWidth: Float,
    cellHeight: Float,
): SyncedView {
    val z = (zoom * zoomBy).coerceIn(1f, SyncedView.MAX_ZOOM)
    val k = z / zoom
    val maxX = (cellWidth * (z - 1f)) / 2f
    val maxY = (cellHeight * (z - 1f)) / 2f
    return SyncedView(
        zoom = z,
        panX = ((panX - centroidX) * k + centroidX + dx).coerceIn(-maxX, maxX),
        panY = ((panY - centroidY) * k + centroidY + dy).coerceIn(-maxY, maxY),
    )
}

/** Buttons' step for those who do not pinch (every gesture has a non-gesture path). */
internal fun SyncedView.stepZoom(zoomIn: Boolean, cellWidth: Float, cellHeight: Float): SyncedView =
    transformed(if (zoomIn) 1.5f else 1f / 1.5f, 0f, 0f, 0f, 0f, cellWidth, cellHeight)

/** Which way a pan button or arrow key looks: LEFT shows more of the photos' left side. */
internal enum class PanDirection { LEFT, RIGHT, UP, DOWN }

/** The share of a cell one pan step moves the view. */
internal const val PAN_STEP_OF_CELL = 0.25f

/**
 * The non-drag pan: one step of [PAN_STEP_OF_CELL] of the cell towards [direction], clamped like a
 * drag. Looking left moves the photo right, so its left edge comes into the cell. Nothing moves at
 * zoom 1.
 */
internal fun SyncedView.stepPan(direction: PanDirection, cellWidth: Float, cellHeight: Float): SyncedView {
    val dx = cellWidth * PAN_STEP_OF_CELL
    val dy = cellHeight * PAN_STEP_OF_CELL
    return when (direction) {
        PanDirection.LEFT -> transformed(1f, dx, 0f, 0f, 0f, cellWidth, cellHeight)
        PanDirection.RIGHT -> transformed(1f, -dx, 0f, 0f, 0f, cellWidth, cellHeight)
        PanDirection.UP -> transformed(1f, 0f, dy, 0f, 0f, cellWidth, cellHeight)
        PanDirection.DOWN -> transformed(1f, 0f, -dy, 0f, 0f, cellWidth, cellHeight)
    }
}

/** The photos in a survey, in order, capped at [SURVEY_MAX]. */
internal data class SurveySet(val ids: List<Long> = emptyList()) {
    fun add(id: Long): SurveySet = if (id in ids || ids.size >= SURVEY_MAX) this else SurveySet(ids + id)
    fun remove(id: Long): SurveySet = SurveySet(ids - id)
    fun full(): Boolean = ids.size >= SURVEY_MAX
    val mode: SurveyMode get() = when (ids.size) { 0, 1 -> SurveyMode.NONE; 2 -> SurveyMode.COMPARE; else -> SurveyMode.SURVEY }
}

internal enum class SurveyMode { NONE, COMPARE, SURVEY }

/** Opens the survey from a selection: the first [SURVEY_MAX] selected, in the order given. */
internal fun surveyFromSelection(selected: List<Long>): SurveySet = SurveySet(selected.distinct().take(SURVEY_MAX))

internal object SurveyCopy {
    const val NEED_TWO = "Pick at least two photos to compare."
    const val ZOOM_IN = "Zoom in on all photos."
    const val ZOOM_OUT = "Zoom out on all photos."
    const val DROP = "Remove this photo from the comparison."
    const val CLOSE = "Leave the comparison."
    const val PAN_LEFT = "Look further left in all photos."
    const val PAN_RIGHT = "Look further right in all photos."
    const val PAN_UP = "Look further up in all photos."
    const val PAN_DOWN = "Look further down in all photos."
    const val ACTION = "See these photos side by side."
}
