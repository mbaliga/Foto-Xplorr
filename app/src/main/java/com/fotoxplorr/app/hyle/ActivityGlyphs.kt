package com.fotoxplorr.app.hyle

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Upload
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The marks the collapsed strip draws beside each job's bar.
 *
 * Three are traced from the owner's collapsed-strip screenshot (2026-10), which draws them at
 * exactly 12px: a floppy for the save, four arrows meeting in a cross for the move, two pages for
 * the copy. They are drawn here rather than borrowed from Material because Material's nearest
 * equivalents are the wrong way round at this size — its floppy puts the window at the top and
 * the shutter at the bottom, its file-copy puts the outlined page in front — and a 12px mark has
 * no room to be approximately right.
 *
 * The geometry is the screenshot's, pixel for pixel: each vector is a 16-unit viewport whose ink
 * occupies the middle 12 units, so a 16dp drawing lands a 12dp mark with the 2dp of air Material
 * icons carry. The three kinds the screenshots do not show borrow Material glyphs built on the
 * same 24-unit convention, which the same 16dp drawing renders a shade over 12dp — close enough
 * for marks that have no reference to be held to.
 */
internal object ActivityGlyphs {

    /** A floppy disk: chamfered top-right corner, shutter at the top, label notch at the bottom. */
    val Save: ImageVector = ImageVector.Builder(
        name = "ActivitySave",
        defaultWidth = GLYPH_DP.dp,
        defaultHeight = GLYPH_DP.dp,
        viewportWidth = GLYPH_DP,
        viewportHeight = GLYPH_DP,
    ).apply {
        // Body with its two cut-outs. Even-odd so the inner rectangles read as holes.
        path(fill = SolidColor(Color.White), pathFillType = PathFillType.EvenOdd) {
            moveTo(2f, 2f)
            lineTo(10f, 2f)
            lineTo(14f, 6f)
            lineTo(14f, 14f)
            lineTo(2f, 14f)
            close()
            // The shutter window, top centre.
            moveTo(5f, 2f)
            lineTo(9f, 2f)
            lineTo(9f, 5f)
            lineTo(5f, 5f)
            close()
            // The label notch, open at the bottom edge.
            moveTo(5f, 9f)
            lineTo(11f, 9f)
            lineTo(11f, 14f)
            lineTo(5f, 14f)
            close()
        }
        // The shutter's slide and the label itself, drawn back on top of the holes.
        path(fill = SolidColor(Color.White)) {
            moveTo(6f, 2f)
            lineTo(8f, 2f)
            lineTo(8f, 4.5f)
            lineTo(6f, 4.5f)
            close()
            moveTo(6f, 10f)
            lineTo(10f, 10f)
            lineTo(10f, 14f)
            lineTo(6f, 14f)
            close()
        }
    }.build()

    /** Four arrows meeting in a cross: a head in every corner, four shafts crossing at the centre. */
    val Move: ImageVector = ImageVector.Builder(
        name = "ActivityMove",
        defaultWidth = GLYPH_DP.dp,
        defaultHeight = GLYPH_DP.dp,
        viewportWidth = GLYPH_DP,
        viewportHeight = GLYPH_DP,
    ).apply {
        // Heads: right triangles in the corners, legs of 5.
        path(fill = SolidColor(Color.White)) {
            moveTo(2f, 2f); lineTo(7f, 2f); lineTo(2f, 7f); close()
            moveTo(14f, 2f); lineTo(9f, 2f); lineTo(14f, 7f); close()
            moveTo(2f, 14f); lineTo(7f, 14f); lineTo(2f, 9f); close()
            moveTo(14f, 14f); lineTo(9f, 14f); lineTo(14f, 9f); close()
        }
        // Shafts: 4 units wide, from the centre to the midpoint of each head's hypotenuse. Their
        // ends are perpendicular to the diagonal, so they sit flush on the hypotenuse. One path
        // element each: they overlap at the centre, and two sub-paths of one element wound
        // opposite ways cancel there under the non-zero rule and leave a hole in the cross.
        path(fill = SolidColor(Color.White)) {
            moveTo(6.586f, 9.414f); lineTo(3.086f, 5.914f); lineTo(5.914f, 3.086f); lineTo(9.414f, 6.586f); close()
        }
        path(fill = SolidColor(Color.White)) {
            moveTo(9.414f, 9.414f); lineTo(12.914f, 5.914f); lineTo(10.086f, 3.086f); lineTo(6.586f, 6.586f); close()
        }
        path(fill = SolidColor(Color.White)) {
            moveTo(6.586f, 6.586f); lineTo(3.086f, 10.086f); lineTo(5.914f, 12.914f); lineTo(9.414f, 9.414f); close()
        }
        path(fill = SolidColor(Color.White)) {
            moveTo(9.414f, 6.586f); lineTo(12.914f, 10.086f); lineTo(10.086f, 12.914f); lineTo(6.586f, 9.414f); close()
        }
    }.build()

    /** Two pages: a solid page in front, top right, and an outlined one behind it, bottom left. */
    val Copy: ImageVector = ImageVector.Builder(
        name = "ActivityCopy",
        defaultWidth = GLYPH_DP.dp,
        defaultHeight = GLYPH_DP.dp,
        viewportWidth = GLYPH_DP,
        viewportHeight = GLYPH_DP,
    ).apply {
        // The page behind: a one-unit outline.
        path(fill = SolidColor(Color.White), pathFillType = PathFillType.EvenOdd) {
            moveTo(4f, 5f); lineTo(10f, 5f); lineTo(10f, 13f); lineTo(4f, 13f); close()
            moveTo(5f, 6f); lineTo(9f, 6f); lineTo(9f, 12f); lineTo(5f, 12f); close()
        }
        // The page in front, solid.
        path(fill = SolidColor(Color.White)) {
            moveTo(7f, 3f); lineTo(13f, 3f); lineTo(13f, 11f); lineTo(7f, 11f); close()
        }
    }.build()

    /** Reading the library: Material's sync arrows. No reference draws this one. */
    val Scan: ImageVector get() = Icons.Filled.Sync

    /** Recognising faces, pets and documents: Material's face. No reference draws this one. */
    val Recognise: ImageVector get() = Icons.Filled.Face

    /** Exporting a zip: Material's upload tray. No reference draws this one. */
    val Export: ImageVector get() = Icons.Filled.Upload
}

/** The viewport of the traced glyphs, and the size they are drawn at for a 12dp mark. */
internal const val GLYPH_DP = 16f
