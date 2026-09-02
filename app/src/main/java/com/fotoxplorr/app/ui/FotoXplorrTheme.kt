package com.fotoxplorr.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.fotoxplorr.app.gallery.AccentPalette
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.gallery.ThemeMode

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
    val accent = preferences.accentPalette.accent(dark)
    val scheme = if (dark) {
        darkColorScheme(
            primary = accent,
            secondary = accent.copy(alpha = 0.84f),
            tertiary = accent.copy(alpha = 0.68f),
            background = Color(0xFF0D0D10),
            surface = Color(0xFF121216),
            surfaceVariant = Color(0xFF25242B),
            onBackground = Color(0xFFF4F1F7),
            onSurface = Color(0xFFF4F1F7),
        )
    } else {
        lightColorScheme(
            primary = accent,
            secondary = accent.copy(alpha = 0.88f),
            tertiary = accent.copy(alpha = 0.72f),
            background = Color(0xFFFFFBFF),
            surface = Color(0xFFFFFBFF),
            surfaceVariant = Color(0xFFF0EBF2),
            onBackground = Color(0xFF1D1A20),
            onSurface = Color(0xFF1D1A20),
        )
    }
    MaterialTheme(colorScheme = scheme, typography = HyleTypography, content = content)
}

private fun AccentPalette.accent(dark: Boolean): Color = when (this) {
    AccentPalette.VIOLET -> if (dark) Color(0xFFCBB4FF) else Color(0xFF6E49B8)
    AccentPalette.OCEAN -> if (dark) Color(0xFF80D1FF) else Color(0xFF006B9C)
    AccentPalette.FOREST -> if (dark) Color(0xFF8ED6A3) else Color(0xFF236C3A)
    AccentPalette.AMBER -> if (dark) Color(0xFFFFCC80) else Color(0xFF9A5A00)
    AccentPalette.MONOCHROME -> if (dark) Color(0xFFE4E1E6) else Color(0xFF4A474D)
}
