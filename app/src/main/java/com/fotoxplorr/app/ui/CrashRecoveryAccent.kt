package com.fotoxplorr.app.ui

import android.content.Context
import androidx.compose.ui.graphics.toArgb
import com.fotoxplorr.app.gallery.GalleryPreferences
import dev.aarso.crashrecovery.CrashRecoveryStyle

/**
 * The accent handed to the crash-recovery screen, resolved from the user's persisted choice so a
 * person who picked Forest does not get a violet crash screen. The screen ships its own neutral
 * paper/ink surface and only ever takes an accent from its host, as plain `@ColorInt` values:
 * crash-recovery holds no dependency on Compose or on any design system.
 *
 * Both activities call this instead of keeping their own copy of the colours. It runs while the
 * app is recovering from a crash, so it must never be the thing that fails: any problem reading
 * the preferences or the theme falls back to [VIOLET_CRASH_STYLE].
 */
fun crashRecoveryStyle(context: Context): CrashRecoveryStyle = runCatching {
    val preferences = GalleryPreferences(context.applicationContext).observe().value
    crashRecoveryStyleFor(resolveThemeSpec(preferences))
}.getOrDefault(VIOLET_CRASH_STYLE)

/** The recovery screen's accent pair for each mode: the theme's own primary, with whichever of
 *  white or near-black reads better on it as the on-accent ink. */
internal fun crashRecoveryStyleFor(spec: ThemeSpec): CrashRecoveryStyle {
    val light = hexToColor(spec.light.primary).copy(alpha = 1f)
    val dark = hexToColor(spec.dark.primary).copy(alpha = 1f)
    return CrashRecoveryStyle.accent(
        light = light.toArgb(),
        onLight = readableOn(light).toArgb(),
        dark = dark.toArgb(),
        onDark = readableOn(dark).toArgb(),
    )
}

/** The theme's VIOLET pair (the app's default) as plain `@ColorInt` values: the fallback if the
 *  chosen accent cannot be read. */
internal val VIOLET_CRASH_STYLE = CrashRecoveryStyle.accent(
    light = 0xFF6E49B8.toInt(),
    onLight = 0xFFFFFFFF.toInt(),
    dark = 0xFFCBB4FF.toInt(),
    onDark = 0xFF221636.toInt(),
)
