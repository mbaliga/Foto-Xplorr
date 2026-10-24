package com.fotoxplorr.app.gallery

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Persistence for the photo scroll direction. Same shape as [GalleryPreferencesTimelineTest]: a new
 * [GalleryPreferences] over the same context stands in for a process restart, the raw
 * `SharedPreferences` for an install with an odd stored value.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GalleryPreferencesScrollAxisTest {

    private val context get() = RuntimeEnvironment.getApplication()

    private fun preferences() = GalleryPreferences(context)

    private fun raw() = context.getSharedPreferences("foto_xplorr_gallery", android.content.Context.MODE_PRIVATE)

    @Test
    fun `a fresh install scrolls vertically`() {
        assertEquals(ScrollAxis.VERTICAL, preferences().observe().value.scrollAxis)
    }

    @Test
    fun `each direction survives a process restart`() {
        for (axis in ScrollAxis.entries) {
            preferences().setScrollAxis(axis)
            assertEquals(axis, preferences().observe().value.scrollAxis)
        }
    }

    @Test
    fun `a stored name this build does not know falls back to vertical`() {
        raw().edit().putString("scroll_axis", "DIAGONAL").commit()
        assertEquals(ScrollAxis.VERTICAL, preferences().observe().value.scrollAxis)
    }

    @Test
    fun `choosing a direction leaves the timeline and the sort alone`() {
        val before = preferences().observe().value
        preferences().setScrollAxis(ScrollAxis.HORIZONTAL)
        val after = preferences().observe().value
        assertEquals(before.timelinePlacement, after.timelinePlacement)
        assertEquals(before.timelineOn, after.timelineOn)
        assertEquals(before.sort, after.sort)
        assertEquals(before.gridZoomRung, after.gridZoomRung)
    }
}
