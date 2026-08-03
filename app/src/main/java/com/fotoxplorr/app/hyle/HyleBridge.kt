package com.fotoxplorr.app.hyle

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.graphics.Color
import dev.aarso.hyle.Argb
import dev.aarso.hyle.Provenance
import dev.aarso.hyle.Pulse
import dev.aarso.hyle.tokens.HyleTokens

/**
 * Bridges dev.aarso:hyle's platform-neutral tokens/contract into Compose. `:hyle` is
 * deliberately Compose-free (its own build.gradle.kts: "pure data + contract ... no
 * Compose, no colour committed") so every consumer needs this tiny adapter layer; this
 * is Foto Xplorr's copy of it, not something that belongs upstream in `:hyle` itself.
 */

/** [Argb] is `0xAARRGGBB` as a `Long` -- exactly the format `androidx.compose.ui.graphics.Color`'s
 * `Color(color: Long)` constructor expects, so this is a lossless, non-lossy adapter. */
fun Argb.toComposeColor(): Color = Color(this)

/** Hyle's provenance hues as Compose [Color]s -- see [Provenance] for the glyph pairing rule. */
val Provenance.composeHue: Color get() = hue.toComposeColor()

/**
 * "Heartbeat, not weather" (Hyle's [Pulse] contract): a slow, regular, low-amplitude alpha
 * breath -- never aperiodic churn -- for anything that should read as *alive / watched /
 * in-progress* without saying so in words. Drives alpha only; callers pair it with glyph,
 * motion or position per the law (never colour alone).
 */
@Composable
fun rememberPulseAlpha(pulse: Pulse): State<Float> {
    val transition = rememberInfiniteTransition(label = "hyle-pulse")
    return transition.animateFloat(
        initialValue = pulse.minAlphaPct / 100f,
        targetValue = pulse.maxAlphaPct / 100f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = pulse.periodMs / 2, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "hyle-pulse-alpha",
    )
}

/** `HyleTokens.Dimension` values are already unitless dp per the token doc comment. */
object HyleSpacing {
    val xs = HyleTokens.Dimension.spacing1
    val sm = HyleTokens.Dimension.spacing2
    val md = HyleTokens.Dimension.spacing3
    val lg = HyleTokens.Dimension.spacing4
    val xl = HyleTokens.Dimension.spacing6
}
