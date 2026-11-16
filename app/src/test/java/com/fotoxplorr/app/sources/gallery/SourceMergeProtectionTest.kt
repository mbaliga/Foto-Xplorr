package com.fotoxplorr.app.sources.gallery

import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.index.sources.SourceAssetIds
import com.fotoxplorr.core.model.MediaId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Finding 5 (protected twins) and finding 4 (merge cost, cache, flow) of the Phase 3 app review. */
class SourceMergeProtectionTest {
    // A library photo of a folder the person locked. The library records no volume, so this photo
    // could just as well live on an SD card; the source below sits on a USB volume, other folder.
    private val locked = libraryAsset(10, name = "Secret.jpg", path = "Private/Trip/", size = 777)
    private val lockedKey = com.fotoxplorr.app.gallery.folderIdentity(locked).key.value
    private fun usbTwin(row: Long = 1, name: String = "Secret.jpg", size: Long = 777) =
        entry(sourceRow(row, name = name, path = "Backups/Phone", size = size), sourceInfo(tree = USB_TREE))

    private fun rows(r: SourceMergeResult) = r.assets.filter { it.isSource }.map { SourceAssetIds.rowOf(it.id)!! }

    @Test
    fun `a source twin of a locked library photo is dropped on any volume and path`() {
        val result = mergeSourceAssetsReport(listOf(locked), listOf(usbTwin()), SourceMergeProtection(lockedFolders = setOf(lockedKey)))
        assertEquals(emptyList<Long>(), rows(result))
        assertEquals(1, result.hiddenDuplicates)
    }

    @Test
    fun `without protection the same twin on another volume is shown`() {
        assertEquals(listOf(1L), rows(mergeSourceAssetsReport(listOf(locked), listOf(usbTwin()))))
    }

    @Test
    fun `sensitive and archived library photos drop their twins too`() {
        val sensitive = mergeSourceAssetsReport(listOf(locked), listOf(usbTwin()), SourceMergeProtection(sensitiveIds = setOf(locked.id)))
        val archived = mergeSourceAssetsReport(listOf(locked), listOf(usbTwin()), SourceMergeProtection(archivedIds = setOf(locked.id)))
        assertEquals(emptyList<Long>(), rows(sensitive))
        assertEquals(emptyList<Long>(), rows(archived))
    }

    @Test
    fun `name and size must both match and a case difference does not save the twin`() {
        val prot = SourceMergeProtection(lockedFolders = setOf(lockedKey))
        val result = mergeSourceAssetsReport(
            listOf(locked),
            listOf(usbTwin(1, size = 778), usbTwin(2, name = "Other.jpg"), usbTwin(3, name = "SECRET.JPG")),
            prot,
        )
        assertEquals(listOf(1L, 2L), rows(result).sorted())
        assertEquals(1, result.hiddenDuplicates)
    }

    @Test
    fun `an unprotected library photo hides nothing beyond the exact overlap key`() {
        val open = libraryAsset(11, name = "Plain.jpg", path = "DCIM/Camera/", size = 5)
        val prot = SourceMergeProtection(lockedFolders = setOf(lockedKey))
        val twin = entry(sourceRow(4, name = "Plain.jpg", path = "Elsewhere", size = 5), sourceInfo(tree = USB_TREE))
        assertEquals(listOf(4L), rows(mergeSourceAssetsReport(listOf(open, locked), listOf(twin), prot)))
    }

    @Test
    fun `the cache follows the protection state and the library list`() {
        val merger = SourceMerger()
        val library = listOf(locked)
        val sources = listOf(usbTwin())
        assertEquals(1, merger.merge(library, sources).assets.count { it.isSource })
        // the folder gets locked: same list instance, new protection, the twin goes
        assertEquals(0, merger.merge(library, sources, SourceMergeProtection(lockedFolders = setOf(lockedKey))).assets.count { it.isSource })
        // and back
        assertEquals(1, merger.merge(library, sources).assets.count { it.isSource })
        // a new library list: derived again
        assertEquals(0, merger.merge(listOf(locked), sources, SourceMergeProtection(sensitiveIds = setOf(locked.id))).assets.count { it.isSource })
    }

    @Test
    fun `the library volume is read without a regex and defaults to external`() {
        assertEquals("external", SourceOverlapKeys.mediaStoreVolume("content://media/external/images/media/5"))
        assertEquals("1a2b-3c4d", SourceOverlapKeys.mediaStoreVolume("content://media/1a2b-3c4d/images/media/5"))
        assertEquals("external", SourceOverlapKeys.mediaStoreVolume("content://other/x"))
        assertEquals("external", SourceOverlapKeys.mediaStoreVolume("content://media/"))
        assertEquals("external", SourceOverlapKeys.mediaStoreVolume("content://media/vol"))
    }

    @Test
    fun `no sources returns the library instance through the flow and the merger`() = runBlocking {
        val library = listOf(libraryAsset(1), libraryAsset(2))
        assertSame(library, SourceMerger().merge(library, emptyList(), SourceMergeProtection(lockedFolders = setOf("x"))).assets)
        val c = mergedCatalogueFlow(flowOf(library), flowOf(emptyList()), flowOf(SourceMergeProtection.NONE)).first()
        assertSame(library, c.assets)
        assertEquals(0, c.sourceFiles)
        assertTrue(c.sourcesSeen)
    }

    @Test
    fun `the flow merges off the caller and counts the source files`() = runBlocking {
        val callerThread = Thread.currentThread()
        var mergedOn: Thread? = null
        val library = flowOf(listOf(libraryAsset(1)))
        val c = mergedCatalogueFlow(
            library,
            kotlinx.coroutines.flow.flow { mergedOn = Thread.currentThread(); emit(listOf(entry(sourceRow(1)), entry(sourceRow(2)))) },
            flowOf(SourceMergeProtection.NONE),
        ).first()
        assertEquals(2, c.sourceFiles)
        assertEquals(3, c.assets.size)
        // everything upstream of the flowOn, the merge included, runs on the Default pool
        assertTrue("ran on ${mergedOn?.name}", mergedOn?.name?.startsWith("DefaultDispatcher") == true && mergedOn !== callerThread)
    }

    @Test
    fun `the pending placeholder has not seen the sources and a source id waits for them`() {
        assertFalse(MergedCatalogue.pending(emptyList()).sourcesSeen)
        val sourceId = SourceAssetIds.fromRow(5)
        assertTrue(mustWaitForSources(sourceId, sourcesSeen = false))
        assertFalse(mustWaitForSources(sourceId, sourcesSeen = true))
        assertFalse("a library id never waits", mustWaitForSources(MediaId(5), sourcesSeen = false))
        assertFalse(mustWaitForSources(null, sourcesSeen = false))
    }

    @Test
    fun `a hundred thousand library photos merge fast and a source-only change reuses the library work`() {
        val library: List<MediaAsset> = (1L..100_000L).map { libraryAsset(it, path = "DCIM/Dir${it % 500}/") }
        val prot = SourceMergeProtection(lockedFolders = setOf("path:dcim/dir7"), sensitiveIds = (1L..2_000L).mapTo(HashSet()) { MediaId(it) })
        val merger = SourceMerger()
        val first = (1L..500L).map { entry(sourceRow(it, name = "S$it.jpg")) }
        // warm up the JIT with a small run, then time the real one
        SourceMerger().merge(library.take(2_000), first, prot)
        val t0 = System.nanoTime()
        val a = merger.merge(library, first, prot)
        val coldMs = (System.nanoTime() - t0) / 1_000_000
        val t1 = System.nanoTime()
        val b = merger.merge(library, first + entry(sourceRow(501, name = "S501.jpg")), prot)
        val warmMs = (System.nanoTime() - t1) / 1_000_000
        assertEquals(100_500, a.assets.size)
        assertEquals(100_501, b.assets.size)
        println("merge 100k library: cold=${coldMs}ms, source-only change=${warmMs}ms")
        assertTrue("cold merge took ${coldMs}ms", coldMs < 5_000)
        assertTrue("a source-only change took ${warmMs}ms (cold ${coldMs}ms)", warmMs < 2_000 && warmMs <= coldMs)
        // library-only fast path: no copy at all
        val t2 = System.nanoTime()
        assertSame(library, merger.merge(library, emptyList(), prot).assets)
        assertTrue((System.nanoTime() - t2) / 1_000_000 < 50)
    }
}
