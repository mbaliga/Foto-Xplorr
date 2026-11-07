package com.fotoxplorr.app.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.hyle.SelectionToolbarTallShape
import com.fotoxplorr.app.hyle.TOOLBAR_DESIGN_H
import com.fotoxplorr.app.hyle.TOOLBAR_DESIGN_W

/**
 * The slideshow's controls (owner, #16: "Once slideshow mode is on, the slideshow controls should
 * be overlayed in the style of the actions bar at the top"): previous, play or pause, next, on the
 * same black coved bar the gallery's selection toolbar uses ([com.fotoxplorr.app.hyle.SelectionToolbarShape]).
 *
 * It hangs from the top edge like that toolbar does. In the gallery the bar is flush to y = 0
 * with its glyphs at the mockup's own height, because the selection chrome lives where the status
 * bar is not in the way; here the status bar is drawn over the photo, so the same bar would put
 * its glyphs under the clock. [topInset] is the status bar's height: the bar is drawn that much
 * taller ([SelectionToolbarTallShape]), still flush to y = 0 so both coves meet the top edge at
 * their designed radius, and the glyphs sit below the inset. The status icons are then drawn over
 * the bar's black. With no inset it is exactly the gallery's bar.
 *
 * Three glyphs and nothing else: shuffle and seconds per photo stay in the view options room.
 * [playing] false shows the play glyph and [onPlayPause] means "resume".
 */
@Composable
internal fun SlideshowBar(
    playing: Boolean,
    hasPrevious: Boolean,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    topInset: Dp = 0.dp,
) {
    Box(
        modifier = modifier
            .padding(start = SlideshowBarGeometry.LEFT_DP.dp)
            .shadow(SlideshowBarGeometry.SHADOW_DP.dp, SelectionToolbarTallShape, clip = false)
            .background(Color.Black, SelectionToolbarTallShape)
            .size(
                width = SlideshowBarGeometry.BAR_WIDTH_DP.dp,
                height = SlideshowBarGeometry.BAR_HEIGHT_DP.dp + topInset,
            )
            .testTag(SLIDESHOW_BAR_TAG),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = SlideshowBarGeometry.ROW_START_DP.dp, top = topInset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarGlyph(
                icon = Icons.Filled.SkipPrevious,
                description = "Previous photo",
                enabled = hasPrevious,
                tag = SLIDESHOW_PREVIOUS_TAG,
                onClick = onPrevious,
            )
            BarGlyph(
                icon = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                description = if (playing) "Pause slideshow" else "Resume slideshow",
                enabled = true,
                tag = SLIDESHOW_PLAY_PAUSE_TAG,
                onClick = onPlayPause,
            )
            BarGlyph(
                icon = Icons.Filled.SkipNext,
                description = "Next photo",
                enabled = true,
                tag = SLIDESHOW_NEXT_TAG,
                onClick = onNext,
            )
        }
    }
}

/**
 * One 30dp white glyph in a 48dp target. The gallery's toolbar sits its glyphs on the mockup's
 * 46dp pitch; this one takes 48 because three targets of 46 are below the platform's minimum and
 * the bar has the width for it. A disabled glyph is dimmed and inert.
 */
@Composable
private fun BarGlyph(icon: ImageVector, description: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(SlideshowBarGeometry.TARGET_DP.dp)
            .testTag(tag)
            .semantics { contentDescription = description }
            .clickable(enabled = enabled, onClickLabel = description, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = if (enabled) 1f else DISABLED_ALPHA),
            modifier = Modifier.size(SlideshowBarGeometry.GLYPH_DP.dp),
        )
    }
}

/** The bar's measurements, in dp, shared with [ViewerChromePolicy] so the rows beneath can clear it. */
internal object SlideshowBarGeometry {
    /** The bar is drawn at the mockup's own size so the cove keeps its designed radius. */
    const val BAR_WIDTH_DP = TOOLBAR_DESIGN_W
    const val BAR_HEIGHT_DP = TOOLBAR_DESIGN_H

    /** Hangs 8dp in from the left edge, as the gallery's toolbar does (mockup `left: 8px`). */
    const val LEFT_DP = 8f

    const val GLYPH_DP = 30
    const val TARGET_DP = 48

    /**
     * The first target starts 32dp in: three 48dp targets then span 32..176, centred on the
     * bar's flat bottom (49..161 wide at its lowest, 105 at its middle) with each glyph inside
     * the black at every height.
     */
    const val ROW_START_DP = 32

    const val SHADOW_DP = 8
}

private const val DISABLED_ALPHA = 0.38f

internal const val SLIDESHOW_BAR_TAG = "viewer-slideshow-bar"
internal const val SLIDESHOW_PREVIOUS_TAG = "viewer-slideshow-previous"
internal const val SLIDESHOW_PLAY_PAUSE_TAG = "viewer-slideshow-play-pause"
internal const val SLIDESHOW_NEXT_TAG = "viewer-slideshow-next"
