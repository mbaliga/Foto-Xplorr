package com.fotoxplorr.app.sync

import com.fotoxplorr.app.gallery.browsableAssets
import com.fotoxplorr.app.sources.gallery.entry
import com.fotoxplorr.app.sources.gallery.libraryAsset
import com.fotoxplorr.app.sources.gallery.mergeSourceAssets
import com.fotoxplorr.app.sources.gallery.sourceRow
import com.fotoxplorr.core.model.MediaId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Finding 1: no path lets a file of an added folder or drive reach the cloud-sync service. */
class SyncSourceLockTest {
    private val library = listOf(libraryAsset(1), libraryAsset(2))
    private val merged = mergeSourceAssets(library, listOf(entry(sourceRow(1)), entry(sourceRow(2))))
    private val everything = SyncSelection(allPhotos = true)

    @Test
    fun `the merged list really holds source files`() {
        assertEquals(4, merged.size)
        assertEquals(2, merged.count { it.isSource })
    }

    @Test
    fun `browsableForSync drops source files even when handed the merged list`() {
        val out = SyncCandidates.browsableForSync(merged, emptySet(), emptySet(), emptySet())
        assertEquals(library.map { it.id }, out.map { it.id })
        assertTrue(out.none { it.isSource })
    }

    @Test
    fun `from drops source files even when handed an unfiltered browsable list`() {
        val unfiltered = browsableAssets(merged, emptySet(), emptySet(), emptySet(), emptySet(), hideSensitive = true)
        assertEquals(4, unfiltered.size)
        val candidates = SyncCandidates.from(unfiltered, everything)
        assertEquals(library.map { it.id.value }.sorted(), candidates.map { it.mediaId }.sorted())
        assertTrue(candidates.none { it.mediaId < 0 })
    }

    @Test
    fun `the settings host and the activity feed give the sync service the same list`() {
        // Both call browsableForSync over the library-only list (pinned in LibraryOnlyWiringTest);
        // fed the merged list instead, they would still agree because of the lock itself.
        val fromMerged = SyncCandidates.browsableForSync(merged, emptySet(), emptySet(), emptySet())
        val fromLibrary = SyncCandidates.browsableForSync(library, emptySet(), emptySet(), emptySet())
        assertEquals(fromLibrary.map { it.id }, fromMerged.map { it.id })
        assertEquals(
            SyncCandidates.from(fromLibrary, everything).map { it.mediaId },
            SyncCandidates.from(fromMerged, everything).map { it.mediaId },
        )
        assertTrue(SyncCandidates.from(fromMerged, everything).none { MediaId(it.mediaId).value < 0 })
    }
}
