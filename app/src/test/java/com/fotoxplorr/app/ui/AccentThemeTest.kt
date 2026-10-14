package com.fotoxplorr.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.createComposeRule
import com.fotoxplorr.app.gallery.AccentPalette
import com.fotoxplorr.app.gallery.GalleryPreferences
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.gallery.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The accent choice reaches the theme: picking each bundled accent gives a different primary (and
 * different accent-role containers), the colours drawn ON an accent stay legible, a running
 * composition re-themes when the choice changes, and the persisted choice is what a restart
 * restores into the theme.
 *
 * Robolectric for the same reason as [ThemeJsonTest] (`org.json`) and
 * [com.fotoxplorr.app.gallery.GalleryPreferencesThemeTest] (a real `SharedPreferences`), with
 * native graphics only because the Compose runtime wants them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class AccentThemeTest {

    @get:Rule
    val compose = createComposeRule()

    private val bundled = AccentPalette.entries.filter { it != AccentPalette.CUSTOM }

    /** Material3's baseline purple for the slots this app used to leave unset. */
    private val baselineDarkPrimaryContainer = Color(0xFF4F378B)
    private val baselineLightPrimaryContainer = Color(0xFFEADDFF)

    private fun contrast(a: Color, b: Color): Double {
        val hi = maxOf(a.luminance(), b.luminance()) + 0.05
        val lo = minOf(a.luminance(), b.luminance()) + 0.05
        return (hi / lo).toDouble()
    }

    // ---- the schemes themselves ----

    @Test
    fun `each bundled accent yields a different primary in dark mode`() {
        val primaries = bundled.map { BUILT_IN_THEMES.getValue(it).dark.toColorScheme(dark = true).primary }
        assertEquals("two accents share a dark primary", primaries.size, primaries.toSet().size)
    }

    @Test
    fun `each bundled accent yields a different primary in light mode`() {
        val primaries = bundled.map { BUILT_IN_THEMES.getValue(it).light.toColorScheme(dark = false).primary }
        assertEquals("two accents share a light primary", primaries.size, primaries.toSet().size)
    }

    @Test
    fun `each bundled accent yields a different primary container in both modes`() {
        val dark = bundled.map { BUILT_IN_THEMES.getValue(it).dark.toColorScheme(dark = true).primaryContainer }
        val light = bundled.map { BUILT_IN_THEMES.getValue(it).light.toColorScheme(dark = false).primaryContainer }
        assertEquals(dark.size, dark.toSet().size)
        assertEquals(light.size, light.toSet().size)
    }

    @Test
    fun `no accent-role slot is left on the Material baseline purple`() {
        bundled.filter { it != AccentPalette.VIOLET }.forEach { palette ->
            val spec = BUILT_IN_THEMES.getValue(palette)
            val dark = spec.dark.toColorScheme(dark = true)
            val light = spec.light.toColorScheme(dark = false)
            assertNotEquals("$palette dark primaryContainer", baselineDarkPrimaryContainer, dark.primaryContainer)
            assertNotEquals("$palette light primaryContainer", baselineLightPrimaryContainer, light.primaryContainer)
            assertEquals("$palette dark onPrimaryContainer follows the accent", dark.primary, dark.onPrimaryContainer)
            assertEquals("$palette light onPrimaryContainer follows the accent", light.primary, light.onPrimaryContainer)
        }
    }

    @Test
    fun `the ink drawn on an accent is readable for every bundled accent in both modes`() {
        bundled.forEach { palette ->
            val spec = BUILT_IN_THEMES.getValue(palette)
            val dark = spec.dark.toColorScheme(dark = true)
            val light = spec.light.toColorScheme(dark = false)
            assertTrue(
                "$palette dark onPrimary contrast ${contrast(dark.primary, dark.onPrimary)}",
                contrast(dark.primary, dark.onPrimary) >= 4.5,
            )
            assertTrue(
                "$palette light onPrimary contrast ${contrast(light.primary, light.onPrimary)}",
                contrast(light.primary, light.onPrimary) >= 4.5,
            )
        }
    }

    @Test
    fun `readableOn picks near-black for a pale accent and white for a dark one`() {
        assertEquals(DARK_INK, readableOn(Color(0xFFCBB4FF)))
        assertEquals(Color.White, readableOn(Color(0xFF6E49B8)))
    }

    @Test
    fun `a pasted custom theme also gets derived accent slots`() {
        val json = builtInThemeJson(AccentPalette.OCEAN)!!.replace("#80D1FF", "#FF3366")
        val spec = (parseThemeJson(json) as ThemeJsonResult.Valid).spec
        val scheme = spec.dark.toColorScheme(dark = true)
        assertEquals(Color(0xFFFF3366), scheme.primary)
        assertEquals(scheme.primary, scheme.onPrimaryContainer)
        assertEquals(readableOn(scheme.primary), scheme.onPrimary)
    }

    // ---- a running composition ----

    private class Seen {
        var primary: Color = Color.Unspecified
        var onPrimary: Color = Color.Unspecified
        var primaryContainer: Color = Color.Unspecified
        var accentOnLight: Color = Color.Unspecified
    }

    @Test
    fun `changing the accent in a running composition re-themes it`() {
        var state by mutableStateOf(GalleryPreferencesState(themeMode = ThemeMode.DARK, accentPalette = AccentPalette.VIOLET))
        val seen = Seen()
        compose.setContent {
            FotoXplorrTheme(state) {
                seen.primary = MaterialTheme.colorScheme.primary
                seen.onPrimary = MaterialTheme.colorScheme.onPrimary
                seen.primaryContainer = MaterialTheme.colorScheme.primaryContainer
                seen.accentOnLight = LocalAccentOnLight.current
            }
        }
        compose.waitForIdle()

        val primaries = mutableListOf<Color>()
        val containers = mutableListOf<Color>()
        for (palette in bundled) {
            state = state.copy(accentPalette = palette)
            compose.waitForIdle()
            val expected = BUILT_IN_THEMES.getValue(palette)
            val dark = expected.dark.toColorScheme(dark = true)
            assertEquals("$palette primary", dark.primary, seen.primary)
            assertEquals("$palette onPrimary", dark.onPrimary, seen.onPrimary)
            assertEquals("$palette primaryContainer", dark.primaryContainer, seen.primaryContainer)
            assertEquals("$palette light accent for light controls", hexToColor(expected.light.primary), seen.accentOnLight)
            primaries += seen.primary
            containers += seen.primaryContainer
        }
        assertEquals(bundled.size, primaries.toSet().size)
        assertEquals(bundled.size, containers.toSet().size)
    }

    @Test
    fun `the light and dark modes each take their own mode's accent`() {
        var state by mutableStateOf(GalleryPreferencesState(themeMode = ThemeMode.DARK, accentPalette = AccentPalette.FOREST))
        val seen = Seen()
        compose.setContent {
            FotoXplorrTheme(state) { seen.primary = MaterialTheme.colorScheme.primary }
        }
        compose.waitForIdle()
        val spec = BUILT_IN_THEMES.getValue(AccentPalette.FOREST)
        assertEquals(hexToColor(spec.dark.primary), seen.primary)

        state = state.copy(themeMode = ThemeMode.LIGHT)
        compose.waitForIdle()
        assertEquals(hexToColor(spec.light.primary), seen.primary)
    }

    // ---- the persisted choice ----

    private fun preferences() = GalleryPreferences(RuntimeEnvironment.getApplication())

    @Test
    fun `a persisted accent choice is restored into the theme after a restart`() {
        preferences().setAccentPalette(AccentPalette.AMBER)

        val restored = preferences().observe().value.copy(themeMode = ThemeMode.DARK)
        assertEquals(AccentPalette.AMBER, restored.accentPalette)
        val seen = Seen()
        compose.setContent {
            FotoXplorrTheme(restored) { seen.primary = MaterialTheme.colorScheme.primary }
        }
        compose.waitForIdle()
        assertEquals(hexToColor(BUILT_IN_THEMES.getValue(AccentPalette.AMBER).dark.primary), seen.primary)
    }

    @Test
    fun `a persisted custom theme is restored into the theme after a restart`() {
        val json = builtInThemeJson(AccentPalette.OCEAN)!!.replace("#80D1FF", "#FF3366")
        preferences().setCustomTheme(json)

        val restored = preferences().observe().value.copy(themeMode = ThemeMode.DARK)
        val seen = Seen()
        compose.setContent {
            FotoXplorrTheme(restored) { seen.primary = MaterialTheme.colorScheme.primary }
        }
        compose.waitForIdle()
        assertEquals(Color(0xFFFF3366), seen.primary)
    }
}
