package com.fotoxplorr.app.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SurveyTest {
    @Test
    fun `two photos go side by side when wide and stacked when tall`() {
        assertEquals(SurveyGrid(2, 1), surveyGrid(2, 1000f, 500f))
        assertEquals(SurveyGrid(1, 2), surveyGrid(2, 500f, 1000f))
    }

    @Test
    fun `larger sets fill without empty rows`() {
        assertEquals(SurveyGrid(2, 2), surveyGrid(4, 800f, 800f))
        val g = surveyGrid(5, 900f, 600f)
        assertTrue(g.columns * g.rows >= 5 && (g.rows - 1) * g.columns < 5)
        assertEquals(SurveyGrid(1, 1), surveyGrid(1, 100f, 100f))
        assertEquals(SurveyGrid(1, 1), surveyGrid(3, 0f, 100f))
    }

    @Test
    fun `cells tile the area and the short last row is centred`() {
        val g = SurveyGrid(2, 2)
        val a = surveyCell(0, 3, g, 400f, 400f)
        assertEquals(CellRect(0f, 0f, 200f, 200f), a)
        val last = surveyCell(2, 3, g, 400f, 400f)
        assertEquals(CellRect(100f, 200f, 200f, 200f), last)
    }

    @Test
    fun `zoom is clamped and pan is zero at zoom one`() {
        val v = SyncedView().transformed(100f, 50f, 50f, 0f, 0f, 200f, 200f)
        assertEquals(SyncedView.MAX_ZOOM, v.zoom, 0f)
        val flat = SyncedView().transformed(1f, 80f, 80f, 0f, 0f, 200f, 200f)
        assertEquals(SyncedView(), flat)
    }

    @Test
    fun `pan stays inside the zoomed photo`() {
        var v = SyncedView().transformed(2f, 0f, 0f, 0f, 0f, 200f, 200f)
        v = v.transformed(1f, 5000f, -5000f, 0f, 0f, 200f, 200f)
        assertEquals(100f, v.panX, 0.01f)
        assertEquals(-100f, v.panY, 0.01f)
    }

    @Test
    fun `zooming about a point keeps that point still`() {
        val v = SyncedView().transformed(2f, 0f, 0f, 50f, 0f, 200f, 200f)
        // the point at +50 stays at +50 on screen: pan = 50 - 2*50 = -50
        assertEquals(-50f, v.panX, 0.01f)
    }

    @Test
    fun `step zoom round trips`() {
        val v = SyncedView().stepZoom(true, 200f, 200f).stepZoom(false, 200f, 200f)
        assertEquals(1f, v.zoom, 0.001f)
    }

    @Test
    fun `survey set caps, dedupes and names its mode`() {
        var s = SurveySet()
        assertEquals(SurveyMode.NONE, s.mode)
        s = s.add(1).add(1).add(2)
        assertEquals(SurveyMode.COMPARE, s.mode)
        s = s.add(3)
        assertEquals(SurveyMode.SURVEY, s.mode)
        for (i in 4L..30L) s = s.add(i)
        assertEquals(SURVEY_MAX, s.ids.size)
        assertTrue(s.full())
        assertEquals(SURVEY_MAX - 1, s.remove(1).ids.size)
        assertEquals(listOf(5L, 6L), surveyFromSelection(listOf(5, 5, 6)).ids)
    }
}
