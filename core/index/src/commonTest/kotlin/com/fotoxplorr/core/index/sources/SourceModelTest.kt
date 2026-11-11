package com.fotoxplorr.core.index.sources

import com.fotoxplorr.core.db.entity.SourceKind as DbSourceKind
import com.fotoxplorr.core.db.entity.SourceState as DbSourceState
import com.fotoxplorr.core.model.MediaId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceModelTest {

    @Test
    fun `100k allocated ids are unique and inside the band`() {
        val seen = HashSet<Long>(120_000)
        for (n in 1L..100_000L) {
            val id = SourceAssetIds.fromRow(n)
            assertTrue(id.value < -SourceAssetIds.BAND_BASE)
            assertTrue(id.value < SourceAssetIds.OPEN_WITH_FLOOR)
            assertTrue(id != MediaId.EXTERNAL)
            assertTrue(seen.add(id.value))
            assertEquals(n, SourceAssetIds.rowOf(id))
        }
    }

    @Test
    fun `ids of other worlds are never source ids`() {
        assertFalse(SourceAssetIds.isSourceId(MediaId(0)))
        assertFalse(SourceAssetIds.isSourceId(MediaId(Long.MAX_VALUE)))
        assertFalse(SourceAssetIds.isSourceId(MediaId(-1)))
        assertFalse(SourceAssetIds.isSourceId(MediaId(-(1L shl 31))))
        assertFalse(SourceAssetIds.isSourceId(MediaId(-(1L shl 33))))
        assertFalse(SourceAssetIds.isSourceId(MediaId.EXTERNAL))
        assertNull(SourceAssetIds.rowOf(MediaId.EXTERNAL))
        assertTrue(SourceAssetIds.isSourceId(MediaId(-(1L shl 33) - 1)))
    }

    @Test
    fun `row range edges are enforced`() {
        assertTrue(SourceAssetIds.fromRow(SourceAssetIds.MAX_ROW) != MediaId.EXTERNAL)
        assertFailsWith0 { SourceAssetIds.fromRow(0) }
        assertFailsWith0 { SourceAssetIds.fromRow(SourceAssetIds.MAX_ROW + 1) }
    }

    private fun assertFailsWith0(block: () -> Unit) {
        var thrown = false
        try { block() } catch (e: IllegalArgumentException) { thrown = true }
        assertTrue(thrown)
    }

    @Test
    fun `source folder prefix keeps a drive apart from the phone folder`() {
        val drive = SourceFolderKey.relativePath(3, "DCIM/Camera")
        assertEquals("~src/3/DCIM/Camera/", drive)
        assertTrue(drive.lowercase() != "dcim/camera/")
        assertEquals("~src/3/", SourceFolderKey.relativePath(3, ""))
        assertEquals(3L, SourceFolderKey.sourceRowOf(drive))
        assertEquals("DCIM/Camera", SourceFolderKey.pathInsideTreeOf(drive))
        assertNull(SourceFolderKey.sourceRowOf("DCIM/Camera/"))
        assertNull(SourceFolderKey.sourceRowOf("~src/x/DCIM/"))
        assertNull(SourceFolderKey.pathInsideTreeOf(null))
        assertEquals(SourceFolderKey.relativePath(3, "a/b"), SourceFolderKey.relativePath(3, "\\a\\\\b/"))
        // Two sources with the same inner path stay apart as well.
        assertTrue(SourceFolderKey.relativePath(3, "DCIM") != SourceFolderKey.relativePath(4, "DCIM"))
    }

    @Test
    fun `asset row exposes media id and prefixed path`() {
        val row = SourceAssetRow(7, 2, "doc:1", "a.jpg", "image/jpeg", 10, 0, 0, 0, 0, 5, "Trips/Oslo", null, true)
        assertEquals(SourceAssetIds.fromRow(7), row.mediaId)
        assertEquals("~src/2/Trips/Oslo/", row.relativePath)
    }

    @Test
    fun `overlap hides a match and keeps different size or volume`() {
        val ms = setOf(SourceOverlap.key("primary", "DCIM/Camera/", "IMG_1.jpg", 100))
        val items = listOf(
            SourceOverlap.key("PRIMARY", "dcim/camera", "img_1.JPG", 100), // hidden
            SourceOverlap.key("primary", "DCIM/Camera", "IMG_1.jpg", 101), // other size
            SourceOverlap.key("1234-ABCD", "DCIM/Camera", "IMG_1.jpg", 100), // unmatched volume
            SourceOverlap.key("primary", "DCIM/Other", "IMG_1.jpg", 100), // other path
        )
        val kept = SourceOverlap.hideDuplicates(items, ms) { it }
        assertEquals(items.drop(1), kept)
        assertEquals(1, SourceOverlap.hideDuplicates(listOf<OverlapKey?>(null), ms) { it }.size)
    }

    @Test
    fun `state machine never revives a revoked grant by reachability alone`() {
        assertEquals(SourceState.OFFLINE, SourceStateMachine.next(SourceState.ONLINE, SourceStateEvent.Unreachable))
        assertEquals(SourceState.ONLINE, SourceStateMachine.next(SourceState.OFFLINE, SourceStateEvent.Reachable))
        assertEquals(SourceState.REVOKED, SourceStateMachine.next(SourceState.ONLINE, SourceStateEvent.GrantRevoked))
        assertEquals(SourceState.REVOKED, SourceStateMachine.next(SourceState.REVOKED, SourceStateEvent.Reachable))
        assertEquals(SourceState.REVOKED, SourceStateMachine.next(SourceState.REVOKED, SourceStateEvent.Unreachable))
        assertEquals(SourceState.REVOKED, SourceStateMachine.next(SourceState.REVOKED, SourceStateEvent.WalkFailed))
        assertEquals(SourceState.ONLINE, SourceStateMachine.next(SourceState.REVOKED, SourceStateEvent.GrantRestored))
        assertEquals(SourceState.ERROR, SourceStateMachine.next(SourceState.ONLINE, SourceStateEvent.WalkFailed))
        // A drive that is not mounted is OFFLINE, not ERROR.
        assertEquals(SourceState.OFFLINE, SourceStateMachine.next(SourceState.ERROR, SourceStateEvent.Unreachable))
    }

    @Test
    fun `sweep only after a completed full walk of an online source`() {
        for (state in SourceState.entries) {
            for (outcome in WalkOutcome.entries) {
                val expected = state == SourceState.ONLINE && outcome == WalkOutcome.COMPLETED_FULL
                assertEquals(expected, SourceStateMachine.maySweep(state, outcome), "$state $outcome")
            }
        }
    }

    @Test
    fun `wire strings extend the stored vocabulary`() {
        assertEquals(DbSourceKind.MEDIASTORE_VOLUME, SourceKind.MEDIASTORE_VOLUME.wire)
        assertEquals(DbSourceState.ONLINE, SourceState.ONLINE.wire)
        assertEquals(DbSourceState.OFFLINE, SourceState.OFFLINE.wire)
        assertEquals(DbSourceState.REVOKED, SourceState.REVOKED.wire)
        assertEquals(DbSourceState.ERROR, SourceState.ERROR.wire)
        assertEquals(SourceKind.USB_SAF, SourceKind.fromWire("USB_SAF"))
        assertEquals(SourceKind.SAF_TREE, SourceKind.fromWire("SAF_TREE"))
        assertNull(SourceKind.fromWire("nope"))
        assertNotNull(SourceState.fromWire("REVOKED"))
    }

    @Test
    fun `walk cursor round trips including awkward ids`() {
        val cursor = WalkCursor(
            queue = listOf("primary:DCIM/Camera", "a\\b", "line\nbreak", "", "trail\\"),
            currentDirectory = "tree:Pictures/Ünï",
            entriesDone = 42,
            filesSeen = 9_000_000_000L,
        )
        assertEquals(cursor, WalkCursor.decode(cursor.encode()))
        val empty = WalkCursor()
        assertEquals(empty, WalkCursor.decode(empty.encode()))
        val noCurrent = WalkCursor(listOf("x"), null, 0, 1)
        assertEquals(noCurrent, WalkCursor.decode(noCurrent.encode()))
    }

    @Test
    fun `corrupt cursors decode to null`() {
        assertNull(WalkCursor.decode(null))
        assertNull(WalkCursor.decode(""))
        assertNull(WalkCursor.decode("wc2\n0\n0\n-\n0"))
        assertNull(WalkCursor.decode("wc1\nx\n0\n-\n0"))
        assertNull(WalkCursor.decode("wc1\n0\n0\n-\n2\nonly-one"))
        assertNull(WalkCursor.decode("wc1\n-1\n0\n-\n0"))
        assertNull(WalkCursor.decode("wc1\n0\n0\n?\n0"))
        assertNull(WalkCursor.decode("wc1\n0\n0\n+bad\\x\n0"))
    }
}
