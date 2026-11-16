package com.fotoxplorr.app.sources.gallery

import com.fotoxplorr.app.gallery.GalleryUiState
import com.fotoxplorr.app.gallery.archiveReviewItems
import com.fotoxplorr.app.gallery.bulkRenameRequestOrNull
import com.fotoxplorr.app.gallery.libraryOnly
import com.fotoxplorr.app.gallery.showMediaPermissionStrip
import com.fotoxplorr.app.gallery.showPermissionEmptyState
import com.fotoxplorr.app.render.emptyState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which consumers read the library-only list and which the merged one (Phase 3 app review: sync
 * feed, location index, Tidy up queue, bulk rename, permission prompt). The behaviours are tested
 * on the functions the screens call; the call sites themselves are pinned by reading the source,
 * because the screens are composables that need a device-sized host to run.
 */
class LibraryOnlyWiringTest {
    private val library = listOf(libraryAsset(1, size = 2_000), libraryAsset(2, size = 3_000))
    private val sources = listOf(entry(sourceRow(1, size = 2_000, width = 4000, height = 3000)))
    private val merged = mergeSourceAssets(library, sources)

    private fun source(path: String): String {
        val roots = listOf("src/main/java/com/fotoxplorr/app/", "app/src/main/java/com/fotoxplorr/app/")
        return roots.map { File(it + path) }.first { it.exists() }.readText()
    }

    // ---- GalleryUiState

    @Test
    fun `the state's library list never holds a source file even when nobody passes it`() {
        val state = GalleryUiState(
            assets = merged, favoriteIds = emptySet(), sensitiveIds = emptySet(), lockedFolders = emptySet(),
            unlockedFolders = emptySet(), library = com.fotoxplorr.app.organize.LibraryState(), permissionGranted = true,
            scanState = com.fotoxplorr.app.ScanState.Idle, preferences = com.fotoxplorr.app.gallery.GalleryPreferencesState(),
        )
        assertEquals(library, state.libraryAssets)
        assertTrue(state.libraryAssets.none { it.isSource })
        assertSame(library, libraryOnly(library))
    }

    // ---- Tidy up queue

    @Test
    fun `a source twin is not a redundant copy and no source file is ever queued`() {
        // library photo 1 and the source file share size and dimensions: duplicates if merged
        val lib = listOf(libraryAsset(1, size = 2_000).copy(width = 4000, height = 3000))
        val withSource = mergeSourceAssets(lib, sources)
        assertEquals(2, withSource.size)
        val queue = archiveReviewItems(emptyState().copy(assets = withSource, libraryAssets = lib))
        assertTrue(queue.none { it.asset.isSource })
        assertTrue("the library photo is not offered as a duplicate of its twin", queue.isEmpty())
        // even if a caller hands the merged list as the library list, the queue filters itself
        val sloppy = archiveReviewItems(emptyState().copy(assets = withSource, libraryAssets = withSource))
        assertTrue(sloppy.none { it.asset.isSource })
        assertTrue(sloppy.isEmpty())
    }

    // ---- bulk rename

    @Test
    fun `a bulk rename request keeps the library photos only and is null when none is left`() {
        val request = bulkRenameRequestOrNull(merged, "Trip_{n}", 1)!!
        assertEquals(library, request.assets)
        assertNull(bulkRenameRequestOrNull(merged.filter { it.isSource }, "x", 1))
        assertNull(bulkRenameRequestOrNull(emptyList(), "x", 1))
    }

    // ---- permission prompt

    @Test
    fun `media access denied shows the full prompt with no source and the small strip with one`() {
        assertTrue(showPermissionEmptyState(false, library))
        assertFalse(showMediaPermissionStrip(false, library))
        assertFalse(showPermissionEmptyState(false, merged))
        assertTrue(showMediaPermissionStrip(false, merged))
        assertFalse(showMediaPermissionStrip(true, merged))
    }

    // ---- call sites

    @Test
    fun `the activity hands the library list to sync, the geo input stays in the screen and the state gets both`() {
        val activity = source("FotoXplorrActivity.kt")
        assertTrue(Regex("SyncLibraryFeed\\(\\s*libraryAssets,").containsMatchIn(activity))
        assertTrue(Regex("GalleryUiState\\(\\s*assets = assets,\\s*libraryAssets = libraryAssets,").containsMatchIn(activity))
        assertTrue(activity.contains("mergedCatalogueFlow("))
        assertFalse("the merge is no longer a remember on the main thread", activity.contains("mergeSourceAssets("))
    }

    @Test
    fun `settings services feeds sync from the library list`() {
        val text = source("gallery/SettingsServices.kt")
        assertTrue(text.contains("SyncCandidates.browsableForSync(state.libraryAssets,"))
        assertFalse(text.contains("browsableForSync(state.assets"))
    }

    @Test
    fun `the location index reads and prunes by the library list`() {
        val text = source("gallery/GalleryScreen.kt")
        assertTrue(text.contains("val spatialIndexInput = state.libraryAssets"))
        assertTrue(text.contains("pruneTo(state.libraryAssets"))
        assertFalse(text.contains("pruneTo(state.assets"))
    }

    @Test
    fun `the tidy up queue is keyed on and built from the library list`() {
        val text = source("gallery/DestinationBrowserScreen.kt")
        assertTrue(text.contains("remember(state.libraryAssets, state.favoriteIds"))
        assertTrue(text.contains("libraryOnly(state.libraryAssets)"))
    }
}
