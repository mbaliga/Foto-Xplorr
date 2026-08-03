package com.fotoxplorr.app.hyle

import android.media.AudioAttributes
import android.media.SoundPool
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.R
import dev.aarso.hyle.tokens.HyleTokens
import kotlin.math.abs

/**
 * Item for [HyleDestinationRail]. Deliberately opaque to the caller's data model (no
 * MediaAsset here) so this stays a reusable Hyle-styled UI primitive; callers supply
 * per-item trailing content (e.g. thumbnail previews) via [HyleDestinationRail]'s
 * `trailingContent` slot.
 */
data class HyleRailItem(
    val id: String,
    val label: String,
)

/**
 * A Compose port of Hyle's tactile-kit "Folders" cascading-tab browser (see
 * hyle-design-system/kit/tactile-kit.html's `.folder`/`.flip`/`.ftab` rules and the
 * `layoutStack()` function): numbered, labelled rounded-rect tabs stacked with the
 * selected one expanded, centred and accent-filled (`.flip.foc .ftab{background:var(--acc)}`)
 * while the rest recede -- alternating indent and dimming alpha with distance from the
 * selection, exactly like the web version's per-tab left/right offset and z-index stack.
 *
 * The web original's `--ease: cubic-bezier(.4,0,.2,1)` is [FastOutSlowInEasing] verbatim
 * (see kit/README.md's "Bridges to the design system" table), so this reuses that Compose
 * easing rather than approximating it.
 */
@Composable
fun HyleDestinationRail(
    items: List<HyleRailItem>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    icons: Map<String, ImageVector> = emptyMap(),
    trailingContent: @Composable (HyleRailItem) -> Unit = {},
) {
    val view = LocalView.current
    val tick = rememberTabTickPlayer()
    val selectedIndex = items.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
    val accent = HyleTokens.Color.colorPaletteAccentViolet.toComposeColor()
    val surface = HyleTokens.Color.controlSurfaceRaised.toComposeColor()

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(HyleSpacing.sm.dp),
    ) {
        items.forEachIndexed { index, item ->
            val isSelected = item.id == selectedId
            val distance = abs(selectedIndex - index).coerceAtMost(4)
            val targetWidthFraction = if (isSelected) 1f else (0.95f - 0.025f * distance)
            val targetAlpha = if (isSelected) 1f else (0.78f - 0.11f * distance).coerceAtLeast(0.32f)
            val ease = tween<Float>(HyleTokens.Duration.durationCalm, easing = FastOutSlowInEasing)
            val widthFraction by animateFloatAsState(targetWidthFraction, ease, label = "hyle-rail-width")
            val rowAlpha by animateFloatAsState(targetAlpha, ease, label = "hyle-rail-alpha")

            Row(
                modifier = Modifier
                    .fillMaxWidth(widthFraction)
                    .alpha(rowAlpha)
                    .clip(RoundedCornerShape(HyleTokens.Dimension.radiusLg.dp))
                    .background(if (isSelected) accent else surface)
                    .clickable {
                        if (!isSelected) {
                            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                            tick.play()
                            onSelect(item.id)
                        }
                    }
                    .padding(horizontal = HyleSpacing.lg.dp, vertical = HyleSpacing.md.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(HyleSpacing.md.dp),
            ) {
                val onTint = if (isSelected) {
                    HyleTokens.Color.colorTextInverse.toComposeColor()
                } else {
                    HyleTokens.Color.colorTextPrimary.toComposeColor()
                }
                Text(
                    text = pad(index + 1),
                    style = MaterialTheme.typography.labelSmall,
                    color = onTint.copy(alpha = if (isSelected) 0.72f else 0.5f),
                )
                icons[item.id]?.let { icon ->
                    Icon(icon, contentDescription = null, tint = onTint)
                }
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = onTint,
                    modifier = Modifier.weight(1f),
                )
                if (isSelected) trailingContent(item)
            }
        }
    }
}

private fun pad(n: Int): String = if (n < 10) "0$n" else n.toString()

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
