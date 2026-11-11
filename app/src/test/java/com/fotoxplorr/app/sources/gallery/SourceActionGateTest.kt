package com.fotoxplorr.app.sources.gallery

import com.fotoxplorr.app.gallery.GalleryActions
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.render.noOpActions
import com.fotoxplorr.core.index.sources.SourceAssetIds
import com.fotoxplorr.core.model.MediaId
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every callback on [GalleryActions] is classified, and every one that takes media and writes is
 * proven not to deliver a source id or asset (ADR-020 decision 5). The classification is checked
 * against the real class by reflection, so a new callback that nobody classified fails here.
 */
class SourceActionGateTest {
    /** Callbacks that carry no media: preferences, permission, folder-lock keys, collection names. */
    private val noMedia = setOf(
        "onRequestPermission", "onRefresh", "onSetSort", "onSetGridZoomRung", "onSetBlurSensitive",
        "onSetHideSensitive", "onSetShowVideos", "onSetTimelineGrouping", "onSetTimelineOn",
        "onSetTimelinePlacement", "onSetTimelineVisibility", "onSetScrollAxis", "onSetThemeMode",
        "onSetAccentPalette", "onSetCustomTheme", "onSetSlideshowInterval", "onSetDefaultDestination",
        "onSetKeepScreenOn", "onSetSlideshowShuffle", "onSetAutoplayVideos", "onSetFitToTile",
        "onSetJustifiedLayout", "onSetGroupBursts", "onSetPairLivePhotos", "onSetCullSidecars",
        "onSetLoopAnimations", "onSetGridLongPress", "onSetShowFilmstrip", "onSetEditorSaveMode",
        "onIndexRecognition", "onProtectFolder", "onUnlockFolder", "onLockFolder",
        "onRemoveFolderProtection", "onCreateCollection", "onRenameCollection", "onDeleteCollection",
        "onExportMetadata", "onImportMetadata", "onPendingSearchConsumed", "onManageSelectedMedia",
    )

    private fun realFields(): Set<String> = GalleryActions::class.java.declaredFields
        .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
        .map { it.name }
        .toSet()

    @Test
    fun `every GalleryActions callback is classified exactly once`() {
        val gated = SourceActionGate.GATED_WRITES
        val readOnly = SourceActionGate.READ_ONLY_WITH_MEDIA
        assertTrue("overlap gated/readOnly", gated.intersect(readOnly).isEmpty())
        assertTrue("overlap gated/noMedia", gated.intersect(noMedia).isEmpty())
        assertTrue("overlap readOnly/noMedia", readOnly.intersect(noMedia).isEmpty())
        val classified = gated + readOnly + noMedia
        assertEquals("unclassified: ${realFields() - classified}; stale: ${classified - realFields()}", realFields(), classified)
    }

    private val library = libraryAsset(1)
    private val source: MediaAsset = SourceAssetMapper.toMediaAsset(entry(sourceRow(1)))!!
    private val sourceId = source.id
    private val libraryId = library.id

    private class Calls {
        val log = mutableListOf<String>()
        var blocked = 0
    }

    private fun recording(calls: Calls) = noOpActions().copy(
        onEditAsset = { calls.log += "edit:${it.id.value}" },
        onSetFavorite = { ids, _ -> calls.log += "fav:${ids.map { it.value }}" },
        onSetSensitive = { ids, _ -> calls.log += "sens:${ids.map { it.value }}" },
        onSetArchived = { ids, _ -> calls.log += "arch:${ids.map { it.value }}" },
        onMoveToFolder = { calls.log += "move:${it.map { a -> a.id.value }}" },
        onRenameAsset = { a, _ -> calls.log += "rename:${a.id.value}" },
        onMoveToTrash = { calls.log += "trash:${it.map { a -> a.id.value }}" },
        onRestore = { calls.log += "restore:${it.map { a -> a.id.value }}" },
        onDeletePermanently = { calls.log += "delete:${it.map { a -> a.id.value }}" },
        onAddToCollection = { _, ids -> calls.log += "coll+:${ids.map { it.value }}" },
        onRemoveFromCollection = { _, ids -> calls.log += "coll-:${ids.map { it.value }}" },
        onAddTag = { ids, _ -> calls.log += "tag+:${ids.map { it.value }}" },
        onRemoveTag = { ids, _ -> calls.log += "tag-:${ids.map { it.value }}" },
        onRejectArchiveSuggestions = { calls.log += "reject:${it.map { id -> id.value }}" },
        onPeople = noOpActions().onPeople.copy(onNotThisPerson = { _, ids -> calls.log += "notthis:${ids.map { it.value }}" }),
        // The read-only ones keep working for a source:
        onShare = { calls.log += "share:${it.map { a -> a.id.value }}" },
        onShareClean = { calls.log += "shareClean:${it.map { a -> a.id.value }}" },
        onCopyToFolder = { calls.log += "copy:${it.map { a -> a.id.value }}" },
        onExportZip = { calls.log += "zip:${it.map { a -> a.id.value }}" },
        onOpenAsset = { a, _ -> calls.log += "open:${a.id.value}" },
        onStartSlideshow = { calls.log += "show:${it.map { a -> a.id.value }}" },
    )

    /** Calls every gated callback with source-only and mixed arguments. */
    private fun fireGated(a: GalleryActions, withSource: Boolean) {
        val ids: Set<MediaId> = if (withSource) setOf(sourceId, libraryId) else setOf(sourceId)
        val assets = if (withSource) listOf(source, library) else listOf(source)
        a.onEditAsset(source)
        a.onSetFavorite(ids, true)
        a.onSetSensitive(ids, true)
        a.onSetArchived(ids, true)
        a.onMoveToFolder(assets)
        a.onRenameAsset(source, "x.jpg")
        a.onMoveToTrash(assets)
        a.onRestore(assets)
        a.onDeletePermanently(assets)
        a.onAddToCollection("c", ids)
        a.onRemoveFromCollection("c", ids)
        a.onAddTag(ids, "t")
        a.onRemoveTag(ids, "t")
        a.onRejectArchiveSuggestions(ids)
        a.onPeople.onNotThisPerson(1L, ids)
    }

    @Test
    fun `no gated callback delivers a source id or asset`() {
        val calls = Calls()
        val gate = SourceActionGate.wrap(recording(calls)) { calls.blocked++ }
        fireGated(gate, withSource = false)
        assertEquals("nothing reached any callback: ${calls.log}", emptyList<String>(), calls.log)
        assertTrue("the host was told", calls.blocked >= 15)
    }

    @Test
    fun `a mixed selection acts on its library part only`() {
        val calls = Calls()
        val gate = SourceActionGate.wrap(recording(calls)) { calls.blocked++ }
        fireGated(gate, withSource = true)
        // 13 library-bearing callbacks ran once each (the edit and the rename take a source asset only).
        assertEquals(13, calls.log.size)
        val band = SourceAssetIds.BAND_BASE
        assertTrue(calls.log.toString(), calls.log.none { line -> line.contains("-${band + 1}") })
        assertTrue(calls.log.all { it.endsWith("[1]") })
    }

    @Test
    fun `a library only call passes through untouched and silently`() {
        val calls = Calls()
        val gate = SourceActionGate.wrap(recording(calls)) { calls.blocked++ }
        gate.onSetFavorite(setOf(libraryId), true)
        gate.onMoveToTrash(listOf(library))
        gate.onEditAsset(library)
        assertEquals(listOf("fav:[1]", "trash:[1]", "edit:1"), calls.log)
        assertEquals(0, calls.blocked)
    }

    @Test
    fun `a source asset is recognised by id band or by reference`() {
        assertTrue(SourceActionGate.isSource(source))
        assertTrue(SourceActionGate.isSource(library.copy(id = sourceId)))
        assertTrue(SourceActionGate.isSource(library.copy(sourceRef = "src:1:1")))
        assertFalse(SourceActionGate.isSource(library))
        assertFalse(SourceActionGate.isSource(library.copy(id = MediaId(-5))))
        assertFalse(SourceActionGate.writesAllowed(listOf(library, source)))
        assertTrue(SourceActionGate.writesAllowed(listOf(library)))
        assertTrue(SourceActionGate.writesAllowed(emptyList()))
    }

    @Test
    fun `reading actions keep working for a source file`() {
        val calls = Calls()
        val gate = SourceActionGate.wrap(recording(calls)) { calls.blocked++ }
        gate.onShare(listOf(source))
        gate.onShareClean(listOf(source))
        gate.onCopyToFolder(listOf(source))
        gate.onExportZip(listOf(source))
        gate.onOpenAsset(source, listOf(source))
        gate.onStartSlideshow(listOf(source))
        assertEquals(6, calls.log.size)
        assertEquals(0, calls.blocked)
    }

    @Test
    fun `the copy is one short sentence`() {
        val text = SourceGateCopy.WRITES_UNAVAILABLE
        assertTrue(text.endsWith("."))
        assertEquals(1, text.count { it == '.' })
        assertTrue(text.length < 60)
    }
}
