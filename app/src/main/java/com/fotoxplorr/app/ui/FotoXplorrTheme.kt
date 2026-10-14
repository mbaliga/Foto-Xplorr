package com.fotoxplorr.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
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
 * into the real [androidx.compose.material3.ColorScheme] below. That conversion also derives the
 * accent-role slots (`onPrimary`, `primaryContainer` and friends) from the chosen accent, so every
 * element that is an accent in the design follows the Settings > Appearance > Accent choice.
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
    // Keyed on exactly what decides the result, so an unrelated preference change (a grid column
    // count, a sort order) does not re-parse a CUSTOM theme's JSON or rebuild the ColorScheme.
    val spec = remember(preferences.accentPalette, preferences.customThemeJson) { resolveThemeSpec(preferences) }
    val scheme = remember(spec, dark) { (if (dark) spec.dark else spec.light).toColorScheme(dark) }
    val accentOnLight = remember(spec) { hexToColor(spec.light.primary) }
    CompositionLocalProvider(LocalAccentOnLight provides accentOnLight) {
        MaterialTheme(colorScheme = scheme, typography = HyleTypography, content = content)
    }
}

/**
 * The chosen accent in its LIGHT-mode form, for the few controls that are a fixed light surface in
 * every [ThemeMode] (the Hyle text field). A dark-mode accent is a pastel meant for black rooms; on
 * a light field it would sit at about 1.6:1, so those controls read the light-mode primary
 * (5:1 or better on the field fill for every bundled preset) whatever the app's mode is.
 * The default is the Violet light primary, used only outside a [FotoXplorrTheme].
 */
val LocalAccentOnLight = staticCompositionLocalOf { Color(0xFF6E49B8) }
