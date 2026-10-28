package com.fotoxplorr.app.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Persistence and migration for how zoomed the grid is -- rungs 1..6 are columns, 7 is the stamps.
 *
 * Same shape as [GalleryPreferencesGridLongPressTest]: a fresh [GalleryPreferences] over the same
 * context stands in for a process restart, and the raw `SharedPreferences` is written directly to
 * stand in for an install that stored a column count.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GalleryPreferencesGridZoomTest {

    private val context get() = RuntimeEnvironment.getApplication()

    private fun preferences() = GalleryPreferences(context)

    private fun raw() = context.getSharedPreferences("foto_xplorr_gallery", android.content.Context.MODE_PRIVATE)

    @Test
    fun `a fresh install opens at three columns`() {
        assertEquals(3, preferences().observe().value.gridZoomRung)
    }

    @Test
    fun `every rung survives a process restart`() {
        for (rung in 1..7) {
            preferences().setGridZoomRung(rung)
            assertEquals(rung, preferences().observe().value.gridZoomRung)
        }
    }

    @Test
    fun `a rung outside the range is held inside it`() {
        preferences().setGridZoomRung(0)
        assertEquals(1, preferences().observe().value.gridZoomRung)
        preferences().setGridZoomRung(99)
        assertEquals(7, preferences().observe().value.gridZoomRung)
    }

    @Test
    fun `a stored column count from 2 to 6 is the same rung`() {
        for (columns in 2..6) {
            raw().edit().clear().putInt("grid_columns", columns).commit()
            assertEquals(columns, preferences().observe().value.gridZoomRung)
        }
    }

    @Test
    fun `a stored seven columns becomes six and not the stamps`() {
        raw().edit().putInt("grid_columns", 7).commit()
        assertEquals(6, preferences().observe().value.gridZoomRung)
    }

    @Test
    fun `a stored rung wins over a leftover column count`() {
        raw().edit().putInt("grid_columns", 5).commit()
        preferences().setGridZoomRung(7)
        assertEquals(7, preferences().observe().value.gridZoomRung)
    }

    @Test
    fun `choosing a rung leaves the old column count exactly as it was`() {
        raw().edit().putInt("grid_columns", 5).commit()
        preferences().setGridZoomRung(2)
        assertEquals(5, raw().getInt("grid_columns", -1))
        assertTrue(raw().contains("grid_zoom_rung"))
    }

    @Test
    fun `reading the old count does not write the new key`() {
        raw().edit().putInt("grid_columns", 4).commit()
        preferences()
        assertFalse(raw().contains("grid_zoom_rung"))
    }
}
