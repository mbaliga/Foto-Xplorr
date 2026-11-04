package com.fotoxplorr.app.composite

import com.fotoxplorr.app.composite.CompositeTestFiles.asset
import com.fotoxplorr.core.formats.composite.AppleIdentifiers
import com.fotoxplorr.core.model.MediaId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The grid's composite presentation: pure over MediaAsset lists, nothing stored. */
class CompositeGroupingTest {
    private val burst = (1L..4L).map { asset(it, "00000IMG_0000${it}_BURST20240101101010123${if (it == 2L) "_COVER" else ""}.jpg", taken = 1_000L + it) }
    private val other = asset(10, "IMG_9999.JPG", taken = 50_000)
    private val still = asset(20, "IMG_0100.HEIC", taken = 90_000, mime = "image/heic")
    private val movie = asset(21, "IMG_0100.MOV", taken = 90_400)

    private fun groups(list: List<com.fotoxplorr.app.media.MediaAsset>, apple: Map<MediaId, AppleIdentifiers> = emptyMap(), live: Boolean = true, bursts: Boolean = true) =
        CompositeGrouping.groups(list, apple, live, bursts)

    @Test
    fun `a burst collapses to its cover with a count and opens in place`() {
        val list = listOf(other) + burst
        val g = groups(list)
        val closed = CompositeGrouping.present(list, g, emptySet())
        assertEquals(listOf(other.id, MediaId(2)), closed.visible.map { it.id })
        assertEquals(4, closed.badges.getValue(MediaId(2)).count)

        val open = CompositeGrouping.present(list, g, toggledStack(emptySet(), MediaId(2)))
        assertEquals(list.map { it.id }, open.visible.map { it.id })
        assertTrue(open.badges.getValue(MediaId(2)).expanded)
        assertEquals(emptySet<MediaId>(), toggledStack(setOf(MediaId(2)), MediaId(2)))
    }

    @Test
    fun `a Live Photo shows as its still and the viewer gets the movie`() {
        val list = listOf(still, movie, other)
        val view = CompositeGrouping.present(list, groups(list), emptySet())
        assertEquals(listOf(still.id, other.id), view.visible.map { it.id })
        assertEquals(movie, view.liveMovies[still.id])
    }

    @Test
    fun `a pair whose movie is not in the view is not shown or played`() {
        val g = groups(listOf(still, movie))
        val view = CompositeGrouping.present(listOf(still, other), g, emptySet())
        assertEquals(listOf(still.id, other.id), view.visible.map { it.id })
        assertTrue(view.liveMovies.isEmpty())
    }

    @Test
    fun `both switches off leave the very same list`() {
        val list = listOf(still, movie) + burst
        val view = CompositeGrouping.present(list, groups(list, live = false, bursts = false), emptySet())
        assertSame(list, view.visible)
    }

    @Test
    fun `the Apple identifiers pair across names and stack by burst id`() {
        val a = asset(30, "IMG_A.HEIC", taken = 1, mime = "image/heic")
        val b = asset(31, "IMG_B.HEIC", taken = 9_000, mime = "image/heic")
        val m = asset(32, "renamed.MOV", taken = 70_000)
        val apple = mapOf(
            a.id to AppleIdentifiers("C1", "B1"),
            b.id to AppleIdentifiers(null, "B1"),
            m.id to AppleIdentifiers("C1", null),
        )
        val g = groups(listOf(a, b, m), apple)
        assertEquals(mapOf(a.id to m.id), g.livePairs)
        assertEquals(listOf(a.id, b.id), g.bursts.single().members)
    }

    @Test
    fun `only HEIF files and stills or movies beside a QuickTime movie are opened for identifiers`() {
        val elsewhere = asset(40, "DSC_1.JPG", bucket = 9)
        val candidates = CompositeGrouping.scanCandidates(listOf(still, movie, elsewhere, other), known = setOf(other.id))
        assertEquals(setOf(still.id, movie.id), candidates.map { it.id }.toSet())
        assertFalse(candidates.any { it.id == elsewhere.id })
        assertEquals(1, CompositeGrouping.scanCandidates(listOf(still, movie), emptySet(), cap = 1).size)
    }

    @Test
    fun `a tap toggles and a hold or hover plays only while it lasts`() {
        var s = MotionPlayState()
        assertFalse(s.playing)
        s = s.copy(held = true)
        assertTrue(s.playing)
        s = s.copy(held = false)
        assertFalse(s.playing)
        s = s.tap()
        assertTrue(s.playing)
        s = s.copy(hovered = true).ended()
        assertFalse(s.playing)
    }

    @Test
    fun `exported files get new names and never the original's`() {
        assertEquals("PXL_1.MP-still.jpg", CompositeExporter.exportName("PXL_1.MP.jpg", video = false))
        assertEquals("PXL_1.MP-motion.mp4", CompositeExporter.exportName("PXL_1.MP.jpg", video = true))
        assertEquals("a_b-still.jpg", CompositeExporter.exportName("a/b.jpg", video = false))
    }

    @Test
    fun `received files with no taken date written in one second stay separate`() {
        // The scanner's stand-in for a missing taken date is the modification second.
        val received = (1L..8L).map { asset(100 + it, "IMG-20231004-WA000$it.jpg").copy(dateTakenMillis = 1_696_400_000_000, dateModifiedSeconds = 1_696_400_000) }
        assertFalse(CompositeGrouping.takenIsCaptureTime(received.first()))
        val view = CompositeGrouping.present(received, groups(received), emptySet())
        assertEquals(received.map { it.id }, view.visible.map { it.id })
    }

    @Test
    fun `select all and the selection follow the photos the grid shows`() {
        val list = listOf(other) + burst
        val g = groups(list)
        val closed = CompositeGrouping.present(list, g, emptySet()).visible.map { it.id }
        // Select all in a collapsed view takes the cover only, never the hidden frames.
        assertEquals(setOf(other.id, MediaId(2)), com.fotoxplorr.app.gallery.GallerySelection().selectAll(closed).selectedIds)
        // Frames picked while the stack was open drop out when it closes.
        val open = CompositeGrouping.present(list, g, setOf(MediaId(2))).visible.map { it.id }
        val picked = com.fotoxplorr.app.gallery.GallerySelection().selectAll(open.filter { it != other.id })
        assertEquals(setOf(MediaId(2)), picked.retainAvailable(closed.toSet()).selectedIds)
    }

    @Test
    fun `open stacks survive a save and restore`() {
        val scope = androidx.compose.runtime.saveable.SaverScope { true }
        val saved = with(MediaIdSetSaver) { scope.save(setOf(MediaId(2), MediaId(9))) }!!
        assertEquals(setOf(MediaId(2), MediaId(9)), MediaIdSetSaver.restore(saved))
    }

    @Test
    fun `the last groups are drawn at once for the same switches only`() {
        val g = groups(listOf(other) + burst)
        CompositeGroupsMemory.last = Triple(true, true, g)
        try {
            assertSame(g, CompositeGroupsMemory.groupsFor(pairLive = true, groupBursts = true))
            assertTrue(CompositeGroupsMemory.groupsFor(pairLive = true, groupBursts = false).isEmpty)
        } finally {
            CompositeGroupsMemory.last = null
        }
    }

    @Test
    fun `an exported still is named and typed after its own bytes`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte())
        fun ftyp(brand: String) = byteArrayOf(0, 0, 0, 24) + "ftyp".toByteArray() + brand.toByteArray() + ByteArray(12)
        assertEquals(CompositeExporter.ExportFormat.JPEG, CompositeExporter.stillFormat(jpeg))
        assertEquals(CompositeExporter.ExportFormat.HEIC, CompositeExporter.stillFormat(ftyp("heic")))
        assertEquals(CompositeExporter.ExportFormat.HEIF, CompositeExporter.stillFormat(ftyp("mif1")))
        assertEquals(null, CompositeExporter.stillFormat(ftyp("isom")))
        assertEquals(null, CompositeExporter.stillFormat(ByteArray(16)))
        val heic = CompositeExporter.ExportFormat.HEIC
        assertEquals("PXL_1.MP-still.heic", CompositeExporter.exportName("PXL_1.MP.HEIC", video = false, extension = heic.extension))
        assertEquals("image/heic", heic.mime)
    }
}
