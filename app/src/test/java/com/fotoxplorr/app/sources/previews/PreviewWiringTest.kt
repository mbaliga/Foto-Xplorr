package com.fotoxplorr.app.sources.previews

import com.fotoxplorr.app.gallery.browsableAssets
import com.fotoxplorr.app.gallery.folderIdentity
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.sources.drives.PreviewPolicy as DrivePolicy
import com.fotoxplorr.app.sources.gallery.SourceAssetMapper
import com.fotoxplorr.app.sources.gallery.entry
import com.fotoxplorr.app.sources.gallery.libraryAsset
import com.fotoxplorr.app.sources.gallery.sourceRow
import com.fotoxplorr.app.sources.runtime.SourceMeta
import com.fotoxplorr.app.sources.runtime.SourceStatus
import com.fotoxplorr.app.sources.ui.StoragePanelModel
import com.fotoxplorr.core.index.sources.SourceInfo
import com.fotoxplorr.core.index.sources.SourceKind
import com.fotoxplorr.core.index.sources.SourceState
import com.fotoxplorr.core.model.MediaId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreviewWiringTest {
    private fun sourceAsset(row: Long, source: Long = 3) = SourceAssetMapper.toMediaAsset(entry(sourceRow(row, sourceRow = source)))!!

    private fun status(row: Long, state: SourceState) =
        SourceStatus(SourceInfo(row, SourceKind.SAF_TREE, "content://x/$row", "1234-ABCD", "S$row", state), SourceMeta(), 1, 1, 1, 0, null)

    private class RecordingPass(private val answer: (Int) -> Int = { 0 }) : PreviewPassDriver.Pass {
        val seen = ArrayList<Triple<List<MediaAsset>, Set<Long>, Int>>()
        override suspend fun run(browsable: List<MediaAsset>, onlineRows: Set<Long>, limit: Int): Int {
            seen += Triple(browsable, onlineRows, limit)
            return answer(seen.size)
        }
    }

    // ---- the pass driver ----

    @Test
    fun `the pass receives only the browsable list and a locked folder source asset is left out`() = runBlocking {
        val visible = sourceAsset(1)
        val locked = sourceAsset(2).copy(relativePath = "~src/3/Private/")
        val lib = libraryAsset(8)
        val merged = listOf(visible, locked, lib)
        val lockedKey = folderIdentity(locked).key.value
        val filter = { l: List<MediaAsset> -> browsableAssets(l, emptySet(), emptySet<MediaId>(), setOf(lockedKey), emptySet(), hideSensitive = true) }
        val pass = RecordingPass()
        PreviewPassDriver.drive(merged, setOf(3L), filter, pass)
        val got = pass.seen.single().first
        assertEquals(listOf(visible.id, lib.id), got.map { it.id })
        assertFalse(locked.id in got.map { it.id })
        assertEquals(setOf(3L), pass.seen.single().second)
    }

    @Test
    fun `no pass and no filter call without an online source`() = runBlocking {
        var filtered = 0
        val pass = RecordingPass()
        val made = PreviewPassDriver.drive(listOf(sourceAsset(1)), emptySet(), { filtered++; it }, pass)
        assertEquals(0, made)
        assertTrue(pass.seen.isEmpty())
        assertEquals(0, filtered)
    }

    @Test
    fun `no pass when there are no source assets at all`() = runBlocking {
        var filtered = 0
        val pass = RecordingPass()
        PreviewPassDriver.drive(listOf(libraryAsset(1), libraryAsset(2)), setOf(3L), { filtered++; it }, pass)
        PreviewPassDriver.drive(emptyList(), setOf(3L), { filtered++; it }, pass)
        assertTrue(pass.seen.isEmpty())
        assertEquals(0, filtered)
    }

    @Test
    fun `rounds continue while a round fills its limit and stop at a short round`() = runBlocking {
        var pauses = 0
        val pass = RecordingPass { n -> if (n < 3) 10 else 4 }
        val made = PreviewPassDriver.drive(listOf(sourceAsset(1)), setOf(3L), { it }, pass, limit = 10, pause = { pauses++ })
        assertEquals(24, made)
        assertEquals(3, pass.seen.size)
        assertEquals(2, pauses)
        assertEquals(listOf(10, 10, 10), pass.seen.map { it.third })
    }

    @Test
    fun `a pass that always fills its limit is still bounded by the round cap`() = runBlocking {
        val pass = RecordingPass { 5 }
        PreviewPassDriver.drive(listOf(sourceAsset(1)), setOf(3L), { it }, pass, limit = 5, maxRounds = 4, pause = {})
        assertEquals(4, pass.seen.size)
    }

    @Test
    fun `only online sources count as online`() {
        val rows = PreviewPassDriver.onlineRows(
            listOf(status(1, SourceState.ONLINE), status(2, SourceState.OFFLINE), status(3, SourceState.REVOKED), status(4, SourceState.ERROR), status(5, SourceState.ONLINE)),
        )
        assertEquals(setOf(1L, 5L), rows)
    }

    // ---- the storage panel cost ----

    @Test
    fun `the storage panel shows the real preview size from the store through the cost interface`() {
        var sizes = mapOf(1L to 12_000_000L, 2L to 0L)
        val cost = StorePreviewCost { sizes }
        val statuses = listOf(status(1, SourceState.ONLINE), status(2, SourceState.ONLINE), status(3, SourceState.OFFLINE))
        val p = StoragePanelModel.build(statuses, { "t$it" }, previewCost = cost)
        assertEquals("Offline previews use 12 MB.", p.rows[0].previewsLabel)
        assertNull("a source with nothing stored says so", p.rows[1].previewsLabel)
        assertNull("a source never read says nothing false", p.rows[2].previewsLabel)
        sizes = sizes + (3L to 2_000_000L)
        assertEquals("Offline previews use 2 MB.", StoragePanelModel.build(statuses, { "t$it" }, previewCost = cost).rows[2].previewsLabel)
    }

    // ---- caps and the controls' states ----

    @Test
    fun `the cap choices are off 250 MB 1 GB and 2 GB with 1 GB the default`() {
        assertEquals(listOf(0L, 250_000_000L, 1_000_000_000L, 2_000_000_000L), PreviewCaps.choices)
        assertEquals(listOf("Off", "250 MB", "1 GB", "2 GB"), PreviewCaps.choices.map { PreviewCaps.label(it) })
        assertEquals(PreviewPolicy.DEFAULT_CAP_BYTES, PreviewCaps.GB_1)
        assertEquals(PreviewCaps.GB_1, PreviewCaps.selected(InMemoryPreviewSettings().capBytes(1)))
    }

    @Test
    fun `a cap stored between choices selects the largest choice below it`() {
        assertEquals(PreviewCaps.MB_250, PreviewCaps.selected(900_000_000L))
        assertEquals(PreviewCaps.GB_2, PreviewCaps.selected(5_000_000_000L))
        assertEquals(PreviewCaps.OFF, PreviewCaps.selected(100L))
    }

    @Test
    fun `a keep switch is shown on only while previews are on`() {
        assertEquals(PreviewControls(keepShown = true, keepEnabled = true, cap = PreviewCaps.GB_1), PreviewControls.of(true, PreviewCaps.GB_1))
        assertEquals(PreviewControls(keepShown = false, keepEnabled = true, cap = PreviewCaps.MB_250), PreviewControls.of(false, PreviewCaps.MB_250))
        val off = PreviewControls.of(true, 0)
        assertFalse("keep cannot look on while the cap is off", off.keepShown)
        assertFalse(off.keepEnabled)
        assertEquals(PreviewCaps.OFF, off.cap)
    }

    // ---- the drive policy mapping ----

    @Test
    fun `none switches previews off and evicts`() = runBlocking {
        val s = InMemoryPreviewSettings()
        val evicted = ArrayList<Long>()
        val changed = DrivePreviewMapping.apply(DrivePolicy.NONE, listOf(4L), s) { evicted += it }
        assertEquals(listOf(4L), changed)
        assertEquals(0L, s.capBytes(4))
        assertFalse(s.keep(4))
        assertEquals(listOf(4L), evicted)
    }

    @Test
    fun `as viewed keeps no background pass and ALL turns it on and both keep a chosen cap`() = runBlocking {
        val s = InMemoryPreviewSettings()
        s.setCapBytes(1, PreviewCaps.MB_250); s.setCapBytes(2, PreviewCaps.GB_2)
        DrivePreviewMapping.apply(DrivePolicy.AS_VIEWED, listOf(1L), s) {}
        DrivePreviewMapping.apply(DrivePolicy.ALL, listOf(2L), s) {}
        assertFalse(s.keep(1)); assertEquals(PreviewCaps.MB_250, s.capBytes(1))
        assertTrue(s.keep(2)); assertEquals(PreviewCaps.GB_2, s.capBytes(2))
    }

    @Test
    fun `a drive that was off comes back on with the default cap`() = runBlocking {
        val s = InMemoryPreviewSettings()
        s.setCapBytes(1, 0)
        DrivePreviewMapping.apply(DrivePolicy.ALL, listOf(1L), s) {}
        assertEquals(PreviewPolicy.DEFAULT_CAP_BYTES, s.capBytes(1))
        assertTrue(s.keep(1))
    }

    @Test
    fun `the policy is applied once per answer so a card change survives the next plug in`() = runBlocking {
        val s = InMemoryPreviewSettings()
        assertEquals(listOf(1L), DrivePreviewMapping.apply(DrivePolicy.ALL, listOf(1L), s) {})
        s.setKeep(1, false); s.setCapBytes(1, PreviewCaps.MB_250)
        assertTrue("same policy again changes nothing", DrivePreviewMapping.apply(DrivePolicy.ALL, listOf(1L), s) {}.isEmpty())
        assertFalse(s.keep(1)); assertEquals(PreviewCaps.MB_250, s.capBytes(1))
        assertEquals("a new answer for the drive is applied", listOf(1L), DrivePreviewMapping.apply(DrivePolicy.NONE, listOf(1L), s) {})
        assertEquals(0L, s.capBytes(1))
    }
}
