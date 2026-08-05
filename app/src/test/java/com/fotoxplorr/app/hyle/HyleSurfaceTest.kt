package com.fotoxplorr.app.hyle

import com.fotoxplorr.app.ScanState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pull-to-backup — and the copy its tests used to pin ("PULL TO CREATE BACKUP") — was retired
 * by owner direction (2026-08-05): the pull-down space belongs to the fonebrew top-room reveal
 * (see docs/fonebrew-navigation.md), so no other gesture may claim it and no instructional
 * copy may sit in a gesture space. Its replacement is the shake gesture; the decision logic is
 * pinned here with the same rigor the retired copy had, so the gesture cannot silently drift.
 */
class ShakePeakTrainTest {

    private fun train() = ShakePeakTrain(
        thresholdG = 2.4f,
        required = 3,
        windowMs = 900L,
        separationMs = 90L,
        cooldownMs = 2_000L,
    )

    @Test
    fun `one bump is not a shake`() {
        val t = train()
        assertEquals(false, t.onSample(3.5f, 1_000))
    }

    @Test
    fun `three separated peaks inside the window fire exactly once`() {
        val t = train()
        assertEquals(false, t.onSample(3.0f, 1_000))
        assertEquals(false, t.onSample(3.0f, 1_200))
        assertTrue(t.onSample(3.0f, 1_400))
    }

    @Test
    fun `sub-threshold samples never count`() {
        val t = train()
        assertEquals(false, t.onSample(1.0f, 1_000))
        assertEquals(false, t.onSample(2.3f, 1_200))
        assertEquals(false, t.onSample(1.9f, 1_400))
        // Two real peaks after the noise still are not enough on their own.
        assertEquals(false, t.onSample(3.0f, 1_600))
        assertEquals(false, t.onSample(3.0f, 1_800))
    }

    @Test
    fun `a rapid burst collapses into one peak`() {
        val t = train()
        // Samples 10ms apart are one swing of the hand, not three: no fire.
        assertEquals(false, t.onSample(3.0f, 1_000))
        assertEquals(false, t.onSample(3.2f, 1_010))
        assertEquals(false, t.onSample(3.1f, 1_020))
    }

    @Test
    fun `peaks spread wider than the window never accumulate`() {
        val t = train()
        assertEquals(false, t.onSample(3.0f, 1_000))
        assertEquals(false, t.onSample(3.0f, 2_000))
        // 1_000 has aged out of the 900ms window by now; only 2_000 and 3_000 remain.
        assertEquals(false, t.onSample(3.0f, 3_000))
    }

    @Test
    fun `the cooldown swallows an over-enthusiastic shake`() {
        val t = train()
        t.onSample(3.0f, 1_000)
        t.onSample(3.0f, 1_200)
        assertTrue(t.onSample(3.0f, 1_400))
        // Still shaking: inside the 2s refractory period nothing fires...
        assertEquals(false, t.onSample(3.5f, 1_600))
        assertEquals(false, t.onSample(3.5f, 1_800))
        // ...and afterwards a fresh full train is required.
        assertEquals(false, t.onSample(3.0f, 3_500))
        assertEquals(false, t.onSample(3.0f, 3_700))
        assertTrue(t.onSample(3.0f, 3_900))
    }
}

class AlertBannerTextTest {

    @Test
    fun `idle says nothing at all`() {
        // Was "Notifications & Alerts appear here" — a label describing a container rather
        // than reporting state, which is the placeholder chrome the fonebrew pattern bans.
        // The banner collapses when idle so the grid runs edge to edge.
        assertEquals("", IDLE_MESSAGE)
        assertEquals(IDLE_MESSAGE, alertBannerMessage(ScanState.Idle, completed = false))
    }

    @Test
    fun `an incremental pass reports new work, never the library total`() {
        // The regression this guards: one screenshot used to re-report the whole library
        // ("Indexing 3456 of 21526"), which read as a full re-index of everything.
        assertEquals(
            "Added 2 new items",
            alertBannerMessage(ScanState.Complete(total = 2, incremental = true), completed = true),
        )
        assertEquals(
            "Added 1 new item",
            alertBannerMessage(ScanState.Complete(total = 1, incremental = true), completed = true),
        )
        assertEquals(
            "Library up to date",
            alertBannerMessage(ScanState.Complete(total = 0, incremental = true), completed = true),
        )
        // A genuine full pass still reports the library total, because that is what it did.
        assertEquals(
            "Library up to date · 21526 items",
            alertBannerMessage(ScanState.Complete(total = 21_526, incremental = false), completed = true),
        )
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
