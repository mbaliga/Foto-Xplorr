package com.fotoxplorr.app.hyle

import com.fotoxplorr.app.ScanState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shake detection, the rail's focus falloff, the slide-in panel geometry and the pill's
 * scrubber all used to be pinned here. Every one of those moved to `dev.aarso:cell-shell`
 * (shake and the rail) or was retired outright (the slide-over panels the spatial shell
 * replaced, and the pill's scrubber the edge scrubber replaced), and their tests moved or
 * retired with them. What is left is what is still this app's own.
 */
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
