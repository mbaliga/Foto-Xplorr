package com.fotoxplorr.app.gallery

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Persistence for the active theme: a built-in [AccentPalette], or [AccentPalette.CUSTOM]'s JSON.
 *
 * Robolectric rather than a pure-JVM fake, same reasoning as
 * [com.fotoxplorr.app.organize.LibraryStoreCurationMemoryTest]: the thing under test IS the
 * `SharedPreferences` round-trip, not an approximation of it. A fresh [GalleryPreferences]
 * instance over the SAME [android.content.Context] is how each test stands in for a process
 * restart -- the first instance's in-memory `StateFlow` is gone, so the second can only be
 * reading back what actually reached disk.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GalleryPreferencesThemeTest {

    private fun preferences() = GalleryPreferences(RuntimeEnvironment.getApplication())

    @Test
    fun `accent palette defaults to VIOLET with no custom theme`() {
        val state = preferences().observe().value
        assertEquals(AccentPalette.VIOLET, state.accentPalette)
        assertEquals("", state.customThemeJson)
    }

    @Test
    fun `a built-in palette survives a process restart`() {
        preferences().setAccentPalette(AccentPalette.FOREST)

        val reloaded = preferences()
        assertEquals(AccentPalette.FOREST, reloaded.observe().value.accentPalette)
    }

    @Test
    fun `a custom theme's JSON survives a process restart, verbatim`() {
        val json = """{"version":1,"name":"Mine","colors":{"light":{},"dark":{}}}"""
        preferences().setCustomTheme(json)

        val reloaded = preferences().observe().value
        assertEquals(AccentPalette.CUSTOM, reloaded.accentPalette)
        assertEquals(json, reloaded.customThemeJson)
    }

    @Test
    fun `switching back to a built-in palette clears the stored custom JSON`() {
        val store = preferences()
        store.setCustomTheme("""{"version":1,"name":"Mine","colors":{"light":{},"dark":{}}}""")
        store.setAccentPalette(AccentPalette.OCEAN)

        val reloaded = preferences().observe().value
        assertEquals(AccentPalette.OCEAN, reloaded.accentPalette)
        assertEquals(
            "switching to a built-in must not leave a stale custom JSON behind",
            "",
            reloaded.customThemeJson,
        )
    }

    @Test
    fun `re-selecting CUSTOM directly keeps whatever custom JSON was already stored`() {
        val store = preferences()
        val json = """{"version":1,"name":"Mine","colors":{"light":{},"dark":{}}}"""
        store.setCustomTheme(json)
        store.setAccentPalette(AccentPalette.CUSTOM)

        assertEquals(json, preferences().observe().value.customThemeJson)
    }
}
