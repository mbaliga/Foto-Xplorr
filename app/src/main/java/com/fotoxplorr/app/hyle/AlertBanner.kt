package com.fotoxplorr.app.hyle

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.ScanState
import dev.aarso.hyle.Pulse
import dev.aarso.hyle.tokens.HyleTokens
import kotlinx.coroutines.delay

/**
 * The library-scan activity signal ("Notifications & Alerts" surface in the mockups),
 * expressed per Hyle's law: icon + motion + structured layout carry the state, not a
 * literal "Notifications & Alerts appear here" placeholder or a "Scanning…" sentence.
 * Wired to the app's real [ScanState] (MediaIndexer's background scan/index pass) -- there
 * is no separate notifications subsystem in Foto Xplorr to hang a richer banner off, so
 * this reflects the one real background-activity signal the app already has.
 */
@Composable
fun ScanActivityAlertBanner(
    scanState: ScanState,
    modifier: Modifier = Modifier,
) {
    var showCompletionPulse by remember { mutableStateOf(false) }
    LaunchedEffect(scanState) {
        if (scanState is ScanState.Complete) {
            showCompletionPulse = true
            delay(COMPLETION_HOLD_MILLIS)
            showCompletionPulse = false
        }
    }

    val visible = scanState is ScanState.Scanning || scanState is ScanState.Error || showCompletionPulse
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(HyleTokens.Duration.durationCalm)) + expandVertically(tween(HyleTokens.Duration.durationCalm)),
        exit = fadeOut(tween(HyleTokens.Duration.durationCalm)) + shrinkVertically(tween(HyleTokens.Duration.durationCalm)),
        modifier = modifier,
    ) {
        AlertBannerRow(scanState = scanState, completed = showCompletionPulse && scanState !is ScanState.Scanning)
    }
}

@Composable
private fun AlertBannerRow(scanState: ScanState, completed: Boolean) {
    val isScanning = scanState is ScanState.Scanning
    val isError = scanState is ScanState.Error
    val pulseAlpha by rememberPulseAlpha(Pulse.WATCHED)
    val spinTransition = rememberInfiniteTransition(label = "hyle-alert-spin")
    val rotationDegrees by spinTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(HyleTokens.Duration.durationPane * 4, easing = LinearEasing)),
        label = "hyle-alert-rotation",
    )

    val icon = when {
        isError -> Icons.Outlined.Error
        completed -> Icons.Outlined.CheckCircle
        else -> Icons.Outlined.Refresh
    }
    val tint = when {
        isError -> HyleTokens.Color.colorFeedbackDanger.toComposeColor()
        completed -> HyleTokens.Color.colorFeedbackSuccess.toComposeColor()
        else -> HyleTokens.Color.colorPaletteAccentViolet.toComposeColor().copy(alpha = pulseAlpha)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HyleSpacing.lg.dp, vertical = HyleSpacing.sm.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HyleSpacing.sm.dp),
    ) {
        Icon(
            imageVector = icon,
            // Decorative: the equivalent factual copy already exists elsewhere for assistive
            // tech (GalleryEmptyState's scan-progress/error text) -- this banner is a
            // supplementary ambient signal, not the only channel a screen reader relies on.
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .size(HyleTokens.Dimension.sizeControlSm.dp)
                .graphicsLayer { rotationZ = if (isScanning) rotationDegrees else 0f },
        )
        if (isScanning) {
            val scanning = scanState as ScanState.Scanning
            if (scanning.discovered > 0) {
                LinearProgressIndicator(
                    progress = { (scanning.scanned.toFloat() / scanning.discovered).coerceIn(0f, 1f) },
                    modifier = Modifier.weight(1f).height(3.dp),
                    color = tint,
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.weight(1f).height(3.dp), color = tint)
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
    }
}

private const val COMPLETION_HOLD_MILLIS = 1_800L
