package com.fotoxplorr.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.gallery.ThemeMode

/**
 * The app's theme, built from JSON rather than a hardcoded enum branch -- see `ThemeJson.kt` for
 * the schema, strict validation, and the five bundled presets [AccentPalette] now names rather
 * than defines.
 *
 * [ThemeMode] (`SYSTEM`/`LIGHT`/`DARK`) still decides light vs. dark exactly as before; what
 * changed is where the eight Material3 colours for whichever mode is active come from --
 * [resolveThemeSpec] resolves the active palette (a bundled preset, or a pasted CUSTOM theme) to
 * a [ThemeSpec], and [ThemeColorSet.toColorScheme] turns that mode's eight validated hex strings
 * into the real [androidx.compose.material3.ColorScheme] below, the exact same
 * `darkColorScheme`/`lightColorScheme` call this file always made.
 */
@Composable
fun FotoXplorrTheme(
    preferences: GalleryPreferencesState,
    content: @Composable () -> Unit,
) {
    val dark = when (preferences.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val spec = resolveThemeSpec(preferences)
    val scheme = (if (dark) spec.dark else spec.light).toColorScheme(dark)
    MaterialTheme(colorScheme = scheme, typography = HyleTypography, content = content)
}
