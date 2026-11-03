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
 * Persistence and defaults for the timeline (owner #25): on or off, which edge the strip sits on,
 * and the grouping of the header rows, whose fresh-install default became months.
 *
 * Same shape as [GalleryPreferencesGridZoomTest]: a fresh [GalleryPreferences] over the same context
 * stands in for a process restart, and the raw `SharedPreferences` is written directly to stand in
 * for an install that stored an earlier choice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GalleryPreferencesTimelineTest {

    private val context get() = RuntimeEnvironment.getApplication()

    private fun preferences() = GalleryPreferences(context)

    private fun raw() = context.getSharedPreferences("foto_xplorr_gallery", android.content.Context.MODE_PRIVATE)

    @Test
    fun `a fresh install has the timeline on, on the right, grouped by month`() {
        val state = preferences().observe().value
        assertTrue(state.timelineOn)
        assertEquals(TimelinePlacement.RIGHT, state.timelinePlacement)
        assertEquals(TimelineGrouping.MONTH, state.timelineGrouping)
    }

    @Test
    fun `the state's own defaults are the same as a fresh install's`() {
        val state = GalleryPreferencesState()
        assertTrue(state.timelineOn)
        assertEquals(TimelinePlacement.RIGHT, state.timelinePlacement)
        assertEquals(TimelineGrouping.MONTH, state.timelineGrouping)
    }

    @Test
    fun `turning the timeline off survives a process restart`() {
        preferences().setTimelineOn(false)
        assertFalse(preferences().observe().value.timelineOn)
        preferences().setTimelineOn(true)
        assertTrue(preferences().observe().value.timelineOn)
    }

    @Test
    fun `every placement survives a process restart`() {
        for (placement in TimelinePlacement.entries) {
            preferences().setTimelinePlacement(placement)
            assertEquals(placement, preferences().observe().value.timelinePlacement)
        }
    }

    @Test
    fun `an unknown stored placement falls back to the right`() {
        raw().edit().putString("timeline_placement", "DIAGONAL").commit()
        assertEquals(TimelinePlacement.RIGHT, preferences().observe().value.timelinePlacement)
    }

    @Test
    fun `a grouping someone already stored is read back untouched`() {
        for (grouping in TimelineGrouping.entries) {
            raw().edit().putString("timeline_grouping", grouping.name).commit()
            assertEquals(grouping, preferences().observe().value.timelineGrouping)
        }
    }

    @Test
    fun `choosing a grouping is still stored under its old key`() {
        preferences().setTimelineGrouping(TimelineGrouping.NONE)
        assertEquals("NONE", raw().getString("timeline_grouping", null))
        assertEquals(TimelineGrouping.NONE, preferences().observe().value.timelineGrouping)
    }

    @Test
    fun `a fresh install shows the rail only while scrubbing and writes that down`() {
        assertEquals(TimelineVisibility.WHILE_SCRUBBING, preferences().observe().value.timelineVisibility)
        assertEquals("WHILE_SCRUBBING", raw().getString("timeline_visibility", null))
    }

    @Test
    fun `an install that had the timeline on keeps it always visible`() {
        raw().edit().putBoolean("timeline_on", true).putString("timeline_placement", "LEFT").commit()
        val state = preferences().observe().value
        assertEquals(TimelineVisibility.ALWAYS, state.timelineVisibility)
        assertEquals(TimelinePlacement.LEFT, state.timelinePlacement)
    }

    @Test
    fun `an install that had the timeline off gets the scrubbing default`() {
        raw().edit().putBoolean("timeline_on", false).commit()
        assertEquals(TimelineVisibility.WHILE_SCRUBBING, preferences().observe().value.timelineVisibility)
    }

    @Test
    fun `the migration is decided once so later writes do not turn a fresh install into an old one`() {
        preferences()
        raw().edit().putString("sort", "OLDEST").commit()
        assertEquals(TimelineVisibility.WHILE_SCRUBBING, preferences().observe().value.timelineVisibility)
    }

    @Test
    fun `the visibility choice survives a process restart`() {
        for (v in TimelineVisibility.entries) {
            preferences().setTimelineVisibility(v)
            assertEquals(v, preferences().observe().value.timelineVisibility)
        }
    }
}
