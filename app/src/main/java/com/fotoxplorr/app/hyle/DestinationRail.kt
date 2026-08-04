package com.fotoxplorr.app.hyle

import android.media.AudioAttributes
import android.media.SoundPool
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.fotoxplorr.app.R
import dev.aarso.hyle.tokens.HyleTokens
import kotlin.math.abs

/**
 * Item for [HyleDestinationRail].
 */
data class HyleRailItem(
    val id: String,
    val label: String,
)

/**
 * The destination rail exactly as the owner's mockups draw it (see the reference images:
 * the nine-item list over the grid, and the Settings panel):
 *
 *  - large, light-weight type on pure black -- no numbering, no icons, no filled tab chrome;
 *  - the active item is bright white and bold, marked by a small filled square bullet in
 *    the gutter to its left;
 *  - every other item dims progressively with distance from the active one.
 *
 * This replaces an earlier reading of the same mockups as Hyle's tactile-kit "Folders"
 * cascading-tab browser -- numbered `01`-`09` rows on an accent-violet fill. The distance
 * falloff from that version was the one part the mockups agree with, so it is kept (as
 * alpha only); everything else here is redrawn from the images. The one non-visual carry
 * over is the select tick + haptic, which the mockups cannot show either way.
 *
 * The web original's `--ease: cubic-bezier(.4,0,.2,1)` remains [FastOutSlowInEasing].
 */
@Composable
fun HyleDestinationRail(
    items: List<HyleRailItem>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    trailingContent: @Composable (HyleRailItem) -> Unit = {},
) {
    val view = LocalView.current
    val tick = rememberTabTickPlayer()
    val selectedIndex = items.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(RAIL_ITEM_GAP.dp),
    ) {
        items.forEachIndexed { index, item ->
            val isSelected = item.id == selectedId
            val targetAlpha = railItemAlpha(abs(selectedIndex - index), isSelected)
            val ease = tween<Float>(HyleTokens.Duration.durationCalm, easing = FastOutSlowInEasing)
            val itemAlpha by animateFloatAsState(targetAlpha, ease, label = "hyle-rail-alpha")

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        // No ripple: the mockups show flat type on black with no touch chrome.
                        indication = null,
                    ) {
                        if (!isSelected) {
                            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                            tick.play()
                            onSelect(item.id)
                        }
                    }
                    .semantics { selected = isSelected }
                    .padding(vertical = RAIL_ITEM_PADDING.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Fixed-width gutter so labels stay on one optical left edge whether or not
                // they carry the bullet -- exactly how the mockups align them.
                Box(
                    modifier = Modifier.width(BULLET_GUTTER.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (isSelected) {
                        Box(
                            Modifier
                                .size(BULLET_SIZE.dp)
                                .background(Color.White),
                        )
                    }
                }
                Text(
                    text = item.label,
                    style = TextStyle(
                        fontSize = RAIL_FONT_SIZE.sp,
                        lineHeight = RAIL_LINE_HEIGHT.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Light,
                        letterSpacing = (-0.5).sp,
                    ),
                    color = Color.White.copy(alpha = itemAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isSelected) {
                    Box(Modifier.padding(start = 12.dp)) { trailingContent(item) }
                }
            }
        }
    }
}

/**
 * Opacity for a rail item [distance] rows from the selected one. Pure so the falloff can be
 * asserted in a unit test rather than eyeballed: the selected row is fully opaque, its
 * neighbours stay clearly legible, and anything four or more rows away settles at a floor
 * that is still readable rather than invisible (matching the mockups, where "Pets" and
 * "Protected" at the extremes are dim but never disappear).
 */
internal fun railItemAlpha(distance: Int, isSelected: Boolean): Float {
    if (isSelected) return 1f
    return (0.62f - 0.10f * distance.coerceAtMost(5)).coerceAtLeast(0.22f)
}

private const val RAIL_FONT_SIZE = 34
private const val RAIL_LINE_HEIGHT = 40
private const val RAIL_ITEM_GAP = 6
private const val RAIL_ITEM_PADDING = 5
private const val BULLET_GUTTER = 26
private const val BULLET_SIZE = 10

/** Real short UI sound cue on tab-select, via SoundPool (res/raw/hyle_tab_tick.wav). */
@Composable
private fun rememberTabTickPlayer(): TabTickPlayer {
    val context = LocalContext.current
    val player = remember(context) { TabTickPlayer(context) }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    return player
}

private class TabTickPlayer(context: android.content.Context) {
    private val soundPool: SoundPool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    private var soundId: Int = 0
    private var loaded = false

    init {
        soundId = soundPool.load(context.applicationContext, R.raw.hyle_tab_tick, 1)
        soundPool.setOnLoadCompleteListener { _, _, status -> loaded = status == 0 }
    }

    fun play() {
        if (loaded) soundPool.play(soundId, 0.5f, 0.5f, 0, 0, 1f)
    }

    fun release() {
        soundPool.release()
    }
}
