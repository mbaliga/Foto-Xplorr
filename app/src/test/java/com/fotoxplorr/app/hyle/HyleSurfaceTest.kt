package com.fotoxplorr.app.hyle

import com.fotoxplorr.app.ScanState
import com.fotoxplorr.app.recognition.RecognitionProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        //
        // Now NULL rather than "". This assertion changed deliberately: returning an empty
        // string let the row render anyway, so the app's resting state was a red warning
        // triangle with no text beside it. "Nothing to say" has to be unrepresentable as a
        // String for a caller to be unable to draw it.
        assertNull(alertBannerMessage(ScanState.Idle, completed = false))
    }

    @Test
    fun `a recognition failure is reported instead of a bare glyph`() {
        // The notification layer is opened by recognition state, so recognition state has to be
        // able to write the copy. Before, it could not, and the layer opened onto an empty line.
        assertEquals(
            "On-device recognition failed",
            alertBannerMessage(
                ScanState.Idle,
                completed = false,
                recognition = RecognitionProgress(message = "On-device recognition failed"),
            ),
        )
    }

    @Test
    fun `a running recognition pass reports its progress`() {
        assertEquals(
            "Recognising 40 of 100",
            alertBannerMessage(
                ScanState.Idle,
                completed = false,
                recognition = RecognitionProgress(running = true, completed = 40, total = 100),
            ),
        )
    }

    @Test
    fun `a scan error outranks recognition copy`() {
        // Two things wrong at once: the one that stopped the library being read wins.
        assertEquals(
            "Disk unreadable",
            alertBannerMessage(
                ScanState.Error("Disk unreadable"),
                completed = false,
                recognition = RecognitionProgress(message = "On-device recognition failed"),
            ),
        )
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
        assertNull(alertBannerMessage(ScanState.Complete(12), completed = false))
    }
}
