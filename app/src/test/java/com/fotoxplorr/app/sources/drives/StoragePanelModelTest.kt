package com.fotoxplorr.app.sources.drives

import com.fotoxplorr.app.sources.runtime.SourceMeta
import com.fotoxplorr.app.sources.runtime.SourceStatus
import com.fotoxplorr.app.sources.ui.NoProtectionCount
import com.fotoxplorr.app.sources.ui.PreviewCost
import com.fotoxplorr.app.sources.ui.ProtectionCount
import com.fotoxplorr.app.sources.ui.StorageCopy
import com.fotoxplorr.app.sources.ui.StoragePanelModel
import com.fotoxplorr.core.index.sources.SourceInfo
import com.fotoxplorr.core.index.sources.SourceKind
import com.fotoxplorr.core.index.sources.SourceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoragePanelModelTest {
    private fun status(row: Long, name: String, state: SourceState, files: Int, bytes: Long, meta: SourceMeta = SourceMeta(), unreadable: Int = 0) =
        SourceStatus(SourceInfo(row, SourceKind.SAF_TREE, "content://x/$row", "1234-ABCD", name, state), meta, files, bytes, files, unreadable, null)

    private val fmt = { ms: Long -> "t$ms" }

    @Test
    fun `totals sum files and bytes and count online sources`() {
        val p = StoragePanelModel.build(
            listOf(status(1, "Camera", SourceState.ONLINE, 1000, 2_000_000_000), status(2, "Stick", SourceState.OFFLINE, 200, 500_000_000)),
            fmt,
        )
        assertEquals("2 folders, 1 online, 1200 files.", p.summary)
        assertEquals("2.5 GB", p.totalBytesLabel)
        assertEquals(listOf(true, false), p.rows.map { it.online })
        assertEquals(listOf("Online", "Offline"), p.rows.map { it.stateLabel })
    }

    @Test
    fun `without preview data or a protection count the rows say nothing false and one placeholder is shown`() {
        val p = StoragePanelModel.build(listOf(status(1, "Camera", SourceState.ONLINE, 5, 10)), fmt)
        assertNull(p.rows.single().previewsLabel)
        assertNull(p.rows.single().protectedLabel)
        assertEquals(StorageCopy.PROTECTION_PENDING, p.protectionPlaceholder)
    }

    @Test
    fun `preview cost and protection are read through their interfaces`() {
        val p = StoragePanelModel.build(
            listOf(status(1, "Camera", SourceState.ONLINE, 5, 10), status(2, "Stick", SourceState.ONLINE, 5, 10)),
            fmt,
            previewCost = object : PreviewCost { override fun bytesFor(sourceRow: Long): Long? = if (sourceRow == 1L) 12_000_000 else null },
            protection = object : ProtectionCount { override fun placesFor(sourceRow: Long): Int? = if (sourceRow == 2L) 1 else null },
        )
        assertEquals("Offline previews use 12 MB.", p.rows[0].previewsLabel)
        assertNull(p.rows[1].previewsLabel)
        assertEquals("Protected on 1 place.", p.rows[1].protectedLabel)
        assertNull("one source can say, so no placeholder", p.protectionPlaceholder)
    }

    @Test
    fun `last read and problems come from the same facts as the source card`() {
        val p = StoragePanelModel.build(
            listOf(status(1, "Camera", SourceState.ERROR, 5, 10, SourceMeta(lastIndexedMs = 42, lastError = "The last read stopped early."), unreadable = 2)),
            fmt,
            protection = NoProtectionCount,
        )
        assertEquals("Last read t42.", p.rows.single().lastRead)
        assertEquals(listOf("The last read stopped early.", "2 files could not be read."), p.rows.single().problems)
    }

    @Test
    fun `no sources means no rows and no placeholder`() {
        val p = StoragePanelModel.build(emptyList(), fmt, ignored = listOf(IgnoredDrive("A-1", "Old")))
        assertTrue(p.rows.isEmpty())
        assertNull(p.protectionPlaceholder)
        assertEquals(listOf(IgnoredDrive("A-1", "Old")), p.ignored)
        assertFalse(p.summary.isEmpty())
    }
}
