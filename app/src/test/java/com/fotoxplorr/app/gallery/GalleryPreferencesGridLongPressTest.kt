package com.fotoxplorr.app.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Persistence and migration for what a grid hold does -- see [GridLongPress].
 *
 * Same shape as [GalleryPreferencesViewerPinchModeTest]: a fresh [GalleryPreferences] over the same
 * context stands in for a process restart. The raw `SharedPreferences` is written directly to
 * stand in for an install that predates this setting.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GalleryPreferencesGridLongPressTest {

    private val context get() = RuntimeEnvironment.getApplication()

    private fun preferences() = GalleryPreferences(context)

    private fun raw() = context.getSharedPreferences("foto_xplorr_gallery", android.content.Context.MODE_PRIVATE)

    @Test
    fun `a hold defaults to the quick preview`() {
        assertEquals(GridLongPress.PEEK, preferences().observe().value.gridLongPress)
    }

    @Test
    fun `the chosen action survives a process restart`() {
        GridLongPress.entries.forEach { choice ->
            preferences().setGridLongPress(choice)
            assertEquals(choice, preferences().observe().value.gridLongPress)
        }
    }

    @Test
    fun `an install that turned peeking off now selects on a hold`() {
        raw().edit().putBoolean("long_press_preview", false).commit()
        assertEquals(GridLongPress.SELECT, preferences().observe().value.gridLongPress)
    }

    @Test
    fun `an install that left peeking on keeps the preview`() {
        raw().edit().putBoolean("long_press_preview", true).commit()
        assertEquals(GridLongPress.PEEK, preferences().observe().value.gridLongPress)
    }

    @Test
    fun `a stored choice wins over a contradicting retired switch`() {
        raw().edit().putBoolean("long_press_preview", false).commit()
        preferences().setGridLongPress(GridLongPress.PEEK)
        assertEquals(GridLongPress.PEEK, preferences().observe().value.gridLongPress)
    }

    @Test
    fun `choosing leaves the retired switch exactly as it was`() {
        raw().edit().putBoolean("long_press_preview", false).commit()
        preferences().setGridLongPress(GridLongPress.EDIT)
        assertFalse(raw().getBoolean("long_press_preview", true))
    }

    @Test
    fun `a name this build does not know falls back like an absent one`() {
        raw().edit().putString("grid_long_press", "SLIDE_UP").commit()
        assertEquals(GridLongPress.PEEK, preferences().observe().value.gridLongPress)
        raw().edit().putBoolean("long_press_preview", false).commit()
        assertEquals(GridLongPress.SELECT, preferences().observe().value.gridLongPress)
    }
}
