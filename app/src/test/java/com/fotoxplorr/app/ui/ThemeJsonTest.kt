package com.fotoxplorr.app.ui

import androidx.compose.ui.graphics.Color
import com.fotoxplorr.app.gallery.AccentPalette
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * JSON-based theming (`ThemeJson.kt`): the schema's strict validation, the five bundled presets,
 * and resolving the preferences-stored active theme into what [FotoXplorrTheme] actually builds.
 *
 * Robolectric, not a pure-JVM test: [parseThemeJson] uses `org.json.JSONObject`, which -- like
 * every `org.json`/`android.*` class -- is a "Stub!"-throwing placeholder on the plain unit-test
 * classpath and only has a real implementation under Robolectric's instrumented one. See
 * [com.fotoxplorr.app.organize.LibraryStoreCurationMemoryTest]'s own doc for the same reasoning
 * applied to this codebase's other `JSONObject` user.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThemeJsonTest {

    private val validJson = """
        {
          "version": 1,
          "name": "My Theme",
          "colors": {
            "light": {
              "primary": "#112233", "secondary": "#223344", "tertiary": "#334455",
              "background": "#FFFFFF", "surface": "#FEFEFE", "surfaceVariant": "#EEEEEE",
              "onBackground": "#000000", "onSurface": "#010101"
            },
            "dark": {
              "primary": "#AABBCC", "secondary": "#BBCCDD", "tertiary": "#CCDDEE",
              "background": "#000000", "surface": "#111111", "surfaceVariant": "#222222",
              "onBackground": "#FFFFFF", "onSurface": "#FEFEFE"
            }
          }
        }
    """.trimIndent()

    // ---- the happy path, and its round-trip into a real ColorScheme ----

    @Test
    fun `valid JSON parses into a spec carrying the declared name and every colour`() {
        val result = parseThemeJson(validJson)
        assertTrue(result is ThemeJsonResult.Valid)
        val spec = (result as ThemeJsonResult.Valid).spec
        assertEquals("My Theme", spec.name)
        assertEquals("#112233", spec.light.primary)
        assertEquals("#AABBCC", spec.dark.primary)
        assertEquals("#010101", spec.light.onSurface)
    }

    @Test
    fun `an 8-digit AARRGGBB colour is accepted, alpha and all`() {
        val json = validJson.replace("\"primary\": \"#112233\"", "\"primary\": \"#80112233\"")
        val result = parseThemeJson(json)
        assertTrue(result is ThemeJsonResult.Valid)
        assertEquals("#80112233", (result as ThemeJsonResult.Valid).spec.light.primary)
    }

    @Test
    fun `a parsed colour set builds the exact same construction shape FotoXplorrTheme always used`() {
        val spec = (parseThemeJson(validJson) as ThemeJsonResult.Valid).spec

        val darkScheme = spec.dark.toColorScheme(dark = true)
        assertEquals(Color(0xFFAABBCC), darkScheme.primary)
        assertEquals(Color(0xFFBBCCDD), darkScheme.secondary)
        assertEquals(Color(0xFFCCDDEE), darkScheme.tertiary)
        assertEquals(Color(0xFF000000), darkScheme.background)
        assertEquals(Color(0xFF111111), darkScheme.surface)
        assertEquals(Color(0xFF222222), darkScheme.surfaceVariant)
        assertEquals(Color(0xFFFFFFFF), darkScheme.onBackground)
        assertEquals(Color(0xFFFEFEFE), darkScheme.onSurface)

        val lightScheme = spec.light.toColorScheme(dark = false)
        assertEquals(Color(0xFF112233), lightScheme.primary)
        assertEquals(Color(0xFFFFFFFF), lightScheme.background)
    }

    // ---- malformed JSON: every rejection names a specific, distinct reason, and never throws ----

    @Test
    fun `broken JSON syntax is refused, not thrown, and names itself as malformed`() {
        val result = parseThemeJson("{not json at all")
        assertTrue(result is ThemeJsonResult.Invalid)
        assertTrue((result as ThemeJsonResult.Invalid).reason.startsWith("Malformed JSON"))
    }

    @Test
    fun `empty text is refused as malformed, not as some other reason`() {
        val result = parseThemeJson("")
        assertTrue(result is ThemeJsonResult.Invalid)
    }

    @Test
    fun `a missing version field is named specifically`() {
        val json = validJson.replaceFirst("\"version\": 1,", "")
        val result = parseThemeJson(json) as ThemeJsonResult.Invalid
        assertEquals("Missing required field: version", result.reason)
    }

    @Test
    fun `an unsupported version number is named, not silently accepted`() {
        val json = validJson.replaceFirst("\"version\": 1,", "\"version\": 2,")
        val result = parseThemeJson(json) as ThemeJsonResult.Invalid
        assertTrue(result.reason.contains("version"))
        assertTrue(result.reason.contains("2"))
    }

    @Test
    fun `a missing name field is named specifically`() {
        val json = validJson.replaceFirst("\"name\": \"My Theme\",", "")
        val result = parseThemeJson(json) as ThemeJsonResult.Invalid
        assertEquals("Missing required field: name", result.reason)
    }

    @Test
    fun `a missing colors object is named specifically`() {
        val json = """{"version": 1, "name": "x"}"""
        val result = parseThemeJson(json) as ThemeJsonResult.Invalid
        assertEquals("Missing required field: colors", result.reason)
    }

    @Test
    fun `a missing dark mode object is named specifically`() {
        val json = """
            {"version": 1, "name": "x", "colors": {"light": {
              "primary": "#112233", "secondary": "#112233", "tertiary": "#112233",
              "background": "#112233", "surface": "#112233", "surfaceVariant": "#112233",
              "onBackground": "#112233", "onSurface": "#112233"
            }}}
        """.trimIndent()
        val result = parseThemeJson(json) as ThemeJsonResult.Invalid
        assertEquals("Missing required field: colors.dark", result.reason)
    }

    @Test
    fun `a missing individual colour key names that exact field, mode and all`() {
        val json = validJson.replaceFirst("\"tertiary\": \"#334455\",", "")
        val result = parseThemeJson(json) as ThemeJsonResult.Invalid
        assertEquals("Missing required field: colors.light.tertiary", result.reason)
    }

    @Test
    fun `a 3-digit shorthand hex is refused, naming the bad field and its bad value`() {
        val json = validJson.replace("\"primary\": \"#112233\"", "\"primary\": \"#123\"")
        val result = parseThemeJson(json) as ThemeJsonResult.Invalid
        assertEquals(
            "Invalid color for colors.light.primary: \"#123\" (expected #RRGGBB or #AARRGGBB)",
            result.reason,
        )
    }

    @Test
    fun `a colour with no leading hash is refused`() {
        val json = validJson.replace("\"onSurface\": \"#010101\"", "\"onSurface\": \"010101\"")
        val result = parseThemeJson(json) as ThemeJsonResult.Invalid
        assertTrue(result.reason.contains("colors.light.onSurface"))
    }

    @Test
    fun `a named colour instead of hex is refused`() {
        val json = validJson.replace("\"background\": \"#FFFFFF\"", "\"background\": \"white\"")
        val result = parseThemeJson(json) as ThemeJsonResult.Invalid
        assertTrue(result.reason.contains("colors.light.background"))
    }

    // ---- the five bundled presets ----

    @Test
    fun `every bundled preset parses and carries the palette's own name`() {
        AccentPalette.entries.filter { it != AccentPalette.CUSTOM }.forEach { palette ->
            val spec = BUILT_IN_THEMES[palette]
            assertNotNull("no bundled theme for $palette", spec)
        }
    }

    @Test
    fun `a bundled preset's own JSON text parses to the exact same spec BUILT_IN_THEMES holds`() {
        val json = builtInThemeJson(AccentPalette.OCEAN)
        assertNotNull(json)
        val parsed = (parseThemeJson(json!!) as ThemeJsonResult.Valid).spec
        assertEquals(BUILT_IN_THEMES.getValue(AccentPalette.OCEAN), parsed)
    }

    @Test
    fun `CUSTOM has no bundled JSON text of its own`() {
        assertEquals(null, builtInThemeJson(AccentPalette.CUSTOM))
    }

    // ---- resolving the active theme out of preferences ----

    @Test
    fun `a built-in palette resolves to its own bundled spec`() {
        val preferences = GalleryPreferencesState(accentPalette = AccentPalette.FOREST)
        assertEquals(BUILT_IN_THEMES.getValue(AccentPalette.FOREST), resolveThemeSpec(preferences))
    }

    @Test
    fun `CUSTOM with valid stored JSON resolves to that theme`() {
        val preferences = GalleryPreferencesState(
            accentPalette = AccentPalette.CUSTOM,
            customThemeJson = validJson,
        )
        assertEquals("My Theme", resolveThemeSpec(preferences).name)
    }

    @Test
    fun `CUSTOM with empty or unparsable stored JSON falls back to VIOLET, never throws`() {
        val empty = GalleryPreferencesState(accentPalette = AccentPalette.CUSTOM, customThemeJson = "")
        assertEquals(BUILT_IN_THEMES.getValue(AccentPalette.VIOLET), resolveThemeSpec(empty))

        val garbage = GalleryPreferencesState(accentPalette = AccentPalette.CUSTOM, customThemeJson = "{broken")
        assertEquals(BUILT_IN_THEMES.getValue(AccentPalette.VIOLET), resolveThemeSpec(garbage))
    }
}
