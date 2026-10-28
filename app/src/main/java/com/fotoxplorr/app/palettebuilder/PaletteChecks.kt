package com.fotoxplorr.app.palettebuilder

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fotoxplorr.app.ui.RoomStyle
import kotlin.math.min

/** For tests: the contrast grid. */
internal const val CONTRAST_TAG = "palette-contrast"

/** For tests: the colour-blind check's verdict line. */
internal const val VISION_TAG = "palette-vision"

/** How many pairs the colour-blind check lists before it stops; the worst come first. */
private const val MAX_COLLISIONS_SHOWN = 6

/** The pairs of a palette that read well as text on colour, as a plain list of what passes. */
internal class ContrastSummary(val passing: Int, val total: Int) {
    companion object {
        /** Unordered pairs: contrast does not care which colour is the text, so the matrix counts each twice. */
        fun of(colours: List<Int>): ContrastSummary {
            val n = colours.size
            return ContrastSummary(ContrastMatrix(colours).passCount() / 2, n * (n - 1) / 2)
        }
    }
}

/**
 * "Text on colour": a square grid, one row per background colour and one column per text colour, each cell the
 * background with "Aa" in the text colour and a mark in its corner: a tick when the pair reads well (WCAG AA,
 * 4.5 to 1) and a cross when it does not. Colour alone never carries it: every cell also has the numbers, the
 * ratio and the words in its description. Cells shrink with the palette so twelve colours still fit a phone.
 */
@Composable
internal fun ContrastSection(palette: Palette, modifier: Modifier = Modifier) {
    val colours = palette.argbs
    val matrix = remember(colours) { ContrastMatrix(colours) }
    val summary = remember(colours) { ContrastSummary.of(colours) }
    Column(modifier.fillMaxWidth().testTag(CONTRAST_TAG), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PbHeading(PaletteBuilderCopy.CONTRAST_TITLE)
        Text(
            PaletteBuilderCopy.contrastSummary(summary.passing, summary.total),
            modifier = Modifier.polite(),
            style = MaterialTheme.typography.bodyMedium,
        )
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val n = colours.size
            // One header column and n cells; at most 44 dp a cell, and never a cell too small to hold its mark.
            val cell: Dp = min(44f, ((maxWidth - HEADER).value / n)).coerceAtLeast(MIN_CELL).dp
            Column {
                Row {
                    Box(Modifier.size(HEADER, 20.dp))
                    for (t in 0 until n) {
                        Box(Modifier.size(cell, 20.dp).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
                            Text("${t + 1}", style = MaterialTheme.typography.labelSmall, color = RoomStyle.InkMuted)
                        }
                    }
                }
                for (b in 0 until n) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(HEADER, cell).clearAndSetSemantics { }, contentAlignment = Alignment.CenterStart) {
                            Text("${b + 1}", style = MaterialTheme.typography.labelSmall, color = RoomStyle.InkMuted)
                        }
                        for (t in 0 until n) {
                            if (t == b) {
                                Box(Modifier.size(cell).clearAndSetSemantics { })
                            } else {
                                ContrastCell(t, b, colours[t], colours[b], matrix, cell)
                            }
                        }
                    }
                }
            }
        }
        PbCaption(PaletteBuilderCopy.CONTRAST_KEY)
    }
}

private val HEADER = 20.dp
private const val MIN_CELL = 24f

@Composable
private fun ContrastCell(text: Int, background: Int, textArgb: Int, backgroundArgb: Int, matrix: ContrastMatrix, size: Dp) {
    // The matrix is asked by place in the palette, not by colour.
    val ratio = matrix.ratio(text, background)
    val passes = matrix.passes(text, background)
    Box(
        Modifier
            .size(size)
            // One spoken item: the "Aa" and the mark inside it are not read out again.
            .clearAndSetSemantics {
                contentDescription = PaletteBuilderCopy.contrastCell(
                    text + 1, background + 1, ratio, if (passes) PaletteBuilderCopy.CONTRAST_PASS else PaletteBuilderCopy.CONTRAST_NO,
                )
            }
            .background(Color(backgroundArgb))
            .border(0.5.dp, Color.Black.copy(alpha = 0.4f)),
        contentAlignment = Alignment.Center,
    ) {
        Text("Aa", color = Color(textArgb), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        // The mark sits on a white disc so it reads on any swatch.
        Box(
            Modifier.align(Alignment.BottomEnd).padding(1.dp).size(11.dp).background(Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (passes) Icons.Outlined.Check else Icons.Outlined.Close,
                contentDescription = null,
                tint = Color.Black,
                modifier = Modifier.size(9.dp),
            )
        }
    }
}

/**
 * "Colour-blind check": one verdict line, with a tick or an info mark beside it, and under it the pairs that blur
 * for each of the three kinds of eye ([ColorBlindness.collisions]), worst first. Meant as a rule of thumb.
 */
@Composable
internal fun VisionSection(palette: Palette, modifier: Modifier = Modifier) {
    val collisions = remember(palette.argbs) {
        ColorBlindness.collisions(palette).sortedWith(compareBy({ it.simulatedDeltaE }, { it.first }, { it.second }, { it.type }))
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PbHeading(PaletteBuilderCopy.VISION_TITLE)
        Row(Modifier.testTag(VISION_TAG).polite(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(if (collisions.isEmpty()) Icons.Outlined.Check else Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
                if (collisions.isEmpty()) PaletteBuilderCopy.VISION_ALL_CLEAR else PaletteBuilderCopy.VISION_BLUR,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        collisions.take(MAX_COLLISIONS_SHOWN).forEach {
            Text(
                PaletteBuilderCopy.collision(it.first + 1, it.second + 1, it.type.label),
                style = MaterialTheme.typography.bodySmall,
                color = RoomStyle.InkMuted,
            )
        }
        if (collisions.size > MAX_COLLISIONS_SHOWN) PbCaption(PaletteBuilderCopy.AND_MORE)
    }
}
