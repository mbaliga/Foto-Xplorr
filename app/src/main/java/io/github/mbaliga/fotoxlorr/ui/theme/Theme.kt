package io.github.mbaliga.fotoxlorr.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF1C5E4A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3F5E7),
    onPrimaryContainer = Color(0xFF07372A),
    background = Color(0xFFF7F7F4),
    onBackground = Color(0xFF171815),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF171815),
    surfaceVariant = Color(0xFFE7E8E2),
    onSurfaceVariant = Color(0xFF454741),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9DD7C2),
    onPrimary = Color(0xFF06382A),
    primaryContainer = Color(0xFF1B4F3D),
    onPrimaryContainer = Color(0xFFC6F1E0),
    background = Color(0xFF0F100E),
    onBackground = Color(0xFFE7E8E3),
    surface = Color(0xFF171916),
    onSurface = Color(0xFFE7E8E3),
    surfaceVariant = Color(0xFF2C2F2A),
    onSurfaceVariant = Color(0xFFC4C7BF),
)

@Composable
fun FotoXlorrTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    useDynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme ->
            dynamicDarkColorScheme(context)

        useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            dynamicLightColorScheme(context)

        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colors,
        content = content,
    )
}
