package com.fotoxplorr.app.hyle

import com.fotoxplorr.app.ScanState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The strings and geometry the mockups pin down. These exist because the previous version of
 * this surface rendered no copy at all: asserting the exact wording keeps that from silently
 * regressing again.
 */
class BackupHeaderTextTest {

    @Test
    fun `each phase says what the mockups say`() {
        assertEquals("PULL TO CREATE BACKUP", backupStatusText(BackupPullPhase.IDLE))
        assertEquals("RELEASE TO CREATE BACKUP", backupStatusText(BackupPullPhase.ARMED))
        assertEquals("Backing up", backupStatusText(BackupPullPhase.ACTIVE))
    }

    @Test
    fun `image count is grouped and pluralised`() {
        assertEquals("12,366 Images", imageCountText(12366, Locale.US))
        assertEquals("1 Image", imageCountText(1, Locale.US))
        assertEquals("No Images", imageCountText(0, Locale.US))
        assertEquals("No Images", imageCountText(-4, Locale.US))
    }

    @Test
    fun `counter values are grouped`() {
        assertEquals("12,322", formatCount(12322, Locale.US))
        assertEquals("0", formatCount(0, Locale.US))
    }
}

class AlertBannerTextTest {

    @Test
    fun `idle shows the mockups resting sentence`() {
        assertEquals(IDLE_MESSAGE, alertBannerMessage(ScanState.Idle, completed = false))
        assertEquals("Notifications & Alerts appear here", IDLE_MESSAGE)
    }

    @Test
    fun `scanning reports real progress`() {
        assertEquals(
            "Indexing 40 of 100",
            alertBannerMessage(ScanState.Scanning(40, 100), completed = false),
        )
    }

    @Test
    fun `scanning without a discovered total stays honest`() {
        assertEquals(
            "Indexing your library",
            alertBannerMessage(ScanState.Scanning(0, 0), completed = false),
        )
    }

    @Test
    fun `errors surface their own message`() {
        assertEquals(
            "Disk unreadable",
            alertBannerMessage(ScanState.Error("Disk unreadable"), completed = false),
        )
    }

    @Test
    fun `completion is only announced during its hold window`() {
        assertEquals(
            "Library up to date · 12 items",
            alertBannerMessage(ScanState.Complete(12), completed = true),
        )
        assertEquals(IDLE_MESSAGE, alertBannerMessage(ScanState.Complete(12), completed = false))
    }
}

class RailAlphaTest {

    @Test
    fun `the selected row is fully opaque`() {
        assertEquals(1f, railItemAlpha(0, isSelected = true), 0f)
    }

    @Test
    fun `alpha falls off with distance`() {
        val near = railItemAlpha(1, isSelected = false)
        val mid = railItemAlpha(3, isSelected = false)
        val far = railItemAlpha(5, isSelected = false)
        assertTrue(near > mid)
        assertTrue(mid > far)
    }

    @Test
    fun `distant rows stay readable rather than vanishing`() {
        // The mockups keep the extremes dim but visible, so the floor must not reach zero.
        (0..40).forEach { distance ->
            val alpha = railItemAlpha(distance, isSelected = false)
            assertTrue("alpha at distance $distance was $alpha", alpha >= 0.22f)
            assertTrue(alpha <= 1f)
        }
    }
}

class SlideInPanelGeometryTest {

    @Test
    fun `a closed left panel sits one width off the left edge`() {
        assertEquals(-300f, panelOffsetPx(0f, 300f, PanelSide.LEFT), 1e-4f)
    }

    @Test
    fun `a closed right panel sits one width off the right edge`() {
        assertEquals(300f, panelOffsetPx(0f, 300f, PanelSide.RIGHT), 1e-4f)
    }

    @Test
    fun `an open panel is flush with its edge on both sides`() {
        assertEquals(0f, panelOffsetPx(1f, 300f, PanelSide.LEFT), 1e-4f)
        assertEquals(0f, panelOffsetPx(1f, 300f, PanelSide.RIGHT), 1e-4f)
    }

    @Test
    fun `a half open panel is half hidden`() {
        assertEquals(-150f, panelOffsetPx(0.5f, 300f, PanelSide.LEFT), 1e-4f)
        assertEquals(150f, panelOffsetPx(0.5f, 300f, PanelSide.RIGHT), 1e-4f)
    }

    @Test
    fun `progress outside zero to one is clamped`() {
        assertEquals(0f, panelOffsetPx(1.8f, 300f, PanelSide.LEFT), 1e-4f)
        assertEquals(-300f, panelOffsetPx(-2f, 300f, PanelSide.LEFT), 1e-4f)
    }
}

class PillScrubberTest {

    @Test
    fun `the handle starts flush left and ends inside the track`() {
        assertEquals(0f, handleOffsetPx(0f, 200f, 12f), 1e-4f)
        assertEquals(188f, handleOffsetPx(1f, 200f, 12f), 1e-4f)
    }

    @Test
    fun `the handle never leaves the track`() {
        listOf(-1f, 0f, 0.5f, 1f, 2f).forEach { fraction ->
            val offset = handleOffsetPx(fraction, 200f, 12f)
            assertTrue("offset $offset for fraction $fraction", offset >= 0f && offset <= 188f)
        }
    }

    @Test
    fun `an unmeasured track produces no offset`() {
        assertEquals(0f, handleOffsetPx(0.5f, 0f, 12f), 0f)
    }

    @Test
    fun `a handle wider than the track does not push it negative`() {
        assertEquals(0f, handleOffsetPx(1f, 10f, 40f), 0f)
    }
}
