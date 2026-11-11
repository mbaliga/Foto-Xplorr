package com.fotoxplorr.app.sources.gallery

import com.fotoxplorr.app.gallery.browsableAssets
import com.fotoxplorr.app.gallery.folderIdentity
import com.fotoxplorr.core.index.sources.SourceAssetIds
import com.fotoxplorr.core.index.sources.SourceState
import com.fotoxplorr.core.model.MediaId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceMergeTest {
    @Test
    fun `mapper builds the id the prefixed path the uri and the leaf bucket`() {
        val asset = SourceAssetMapper.toMediaAsset(entry(sourceRow(7, sourceRow = 3, path = "DCIM/Camera")))!!
        assertEquals(MediaId(-(1L shl 33) - 7), asset.id)
        assertEquals("~src/3/DCIM/Camera/", asset.relativePath)
        assertEquals("Camera", asset.bucketName)
        assertEquals("content://fotoz.test.docs/tree/root/document/doc7", asset.contentUriString)
        assertEquals("src:3:7", asset.sourceRef)
        assertTrue(asset.isSource)
        assertFalse(asset.isExternal)
        assertFalse(asset.isFavorite)
        assertFalse(asset.isTrashed)
    }

    @Test
    fun `a file at the root of the tree is bucketed under the source name`() {
        val asset = SourceAssetMapper.toMediaAsset(entry(sourceRow(1, path = "")))!!
        assertEquals("Backup drive", asset.bucketName)
        assertEquals("~src/3/", asset.relativePath)
    }

    @Test
    fun `unenriched rows fall back to modified time and a square and enriched rows carry their facts`() {
        val plain = SourceAssetMapper.toMediaAsset(entry(sourceRow(1, modified = 5_000_000)))!!
        assertEquals(5_000_000L, plain.dateTakenMillis)
        assertEquals(5_000L, plain.dateModifiedSeconds)
        assertEquals(1f, plain.aspectRatio, 0f)

        val enriched = SourceAssetMapper.toMediaAsset(
            entry(sourceRow(2, modified = 5_000_000, dateTaken = 4_000_000, width = 3000, height = 4000)),
        )!!
        assertEquals(4_000_000L, enriched.dateTakenMillis)
        assertEquals(3000, enriched.width)
        assertEquals(4000, enriched.height)
        assertEquals(0.75f, enriched.aspectRatio, 0.0001f)
    }

    @Test
    fun `a row without a uri is not mapped`() {
        assertNull(SourceAssetMapper.toMediaAsset(entry(sourceRow(1, uri = null))))
    }

    @Test
    fun `no sources returns the very same list`() {
        val library = listOf(libraryAsset(1), libraryAsset(2))
        assertSame(library, mergeSourceAssets(library, emptyList()))
    }

    @Test
    fun `library comes first untouched and sources follow newest first`() {
        val library = listOf(libraryAsset(5), libraryAsset(2))
        val merged = mergeSourceAssets(
            library,
            listOf(
                entry(sourceRow(1, modified = 1_000_000)),
                entry(sourceRow(2, modified = 3_000_000)),
                entry(sourceRow(3, modified = 2_000_000)),
            ),
        )
        assertEquals(library, merged.take(2))
        assertEquals(listOf(2L, 3L, 1L), merged.drop(2).map { SourceAssetIds.rowOf(it.id)!! })
        // Same input again: the same order (stable between emissions).
        assertEquals(merged, mergeSourceAssets(library, listOf(
            entry(sourceRow(3, modified = 2_000_000)), entry(sourceRow(1, modified = 1_000_000)), entry(sourceRow(2, modified = 3_000_000)),
        )))
    }

    @Test
    fun `ids stay distinct even with a library id inside the band and a repeated source row`() {
        val clash = SourceAssetIds.fromRow(1)
        val library = listOf(libraryAsset(1).copy(id = clash), libraryAsset(2))
        val result = mergeSourceAssetsReport(library, listOf(entry(sourceRow(1)), entry(sourceRow(2)), entry(sourceRow(2))))
        assertEquals("the library photo is never dropped", library, result.assets.take(2))
        assertEquals(result.assets.size, result.assets.map { it.id }.toSet().size)
        assertEquals(3, result.assets.size)
        assertEquals(2, result.skipped)
    }

    @Test
    fun `not present revoked and uriless rows are not listed`() {
        val result = mergeSourceAssetsReport(
            emptyList(),
            listOf(
                entry(sourceRow(1, present = false)),
                entry(sourceRow(2), sourceInfo(state = SourceState.REVOKED)),
                entry(sourceRow(3, uri = null)),
                entry(sourceRow(4), sourceInfo(state = SourceState.OFFLINE)),
                entry(sourceRow(5), sourceInfo(state = SourceState.ERROR)),
            ),
        )
        assertEquals(listOf(4L, 5L), result.assets.map { SourceAssetIds.rowOf(it.id)!! }.sorted())
        assertEquals(3, result.skipped)
    }

    @Test
    fun `a source file MediaStore already lists on the same volume path name and size is hidden and counted`() {
        val library = listOf(libraryAsset(10, name = "A.jpg", path = "DCIM/Camera/", size = 500))
        val primary = sourceInfo(tree = PRIMARY_TREE)
        val result = mergeSourceAssetsReport(
            library,
            listOf(
                // tree is primary:DCIM, so the file is DCIM/Camera/A.jpg on the primary volume
                entry(sourceRow(1, name = "A.jpg", path = "Camera", size = 500), primary),
                entry(sourceRow(2, name = "A.jpg", path = "Camera", size = 501), primary), // other size
                entry(sourceRow(3, name = "B.jpg", path = "Camera", size = 500), primary), // other name
                entry(sourceRow(4, name = "A.jpg", path = "Other", size = 500), primary), // other folder
            ),
        )
        assertEquals(1, result.hiddenDuplicates)
        assertEquals(listOf(2L, 3L, 4L), result.assets.drop(1).map { SourceAssetIds.rowOf(it.id)!! }.sorted())
    }

    @Test
    fun `a drive volume never matches the phone and a cloud tree is never hidden`() {
        val library = listOf(libraryAsset(10, name = "A.jpg", path = "DCIM/Camera/", size = 500))
        val usb = entry(sourceRow(1, name = "A.jpg", path = "DCIM/Camera", size = 500), sourceInfo(tree = USB_TREE))
        val cloud = entry(
            sourceRow(2, name = "A.jpg", path = "DCIM/Camera", size = 500),
            sourceInfo(tree = "content://com.example.cloud.documents/tree/root"),
        )
        val result = mergeSourceAssetsReport(library, listOf(usb, cloud))
        assertEquals(0, result.hiddenDuplicates)
        assertEquals(3, result.assets.size)
    }

    @Test
    fun `tree uri parsing decodes volume and path and keeps a plus`() {
        assertEquals("primary" to "DCIM/Camera", SourceOverlapKeys.treeVolumeAndPath(
            "content://com.android.externalstorage.documents/tree/primary%3ADCIM%2FCamera",
        ))
        assertEquals("1A2B-3C4D" to "", SourceOverlapKeys.treeVolumeAndPath(USB_TREE))
        assertEquals("primary" to "a+b", SourceOverlapKeys.treeVolumeAndPath(
            "content://com.android.externalstorage.documents/tree/primary%3Aa+b",
        ))
        assertNull(SourceOverlapKeys.treeVolumeAndPath("content://other/tree/x"))
    }

    @Test
    fun `a source folder with the same relative path as a phone folder never merges into it`() {
        val phone = libraryAsset(1, path = "DCIM/Camera/")
        val source = SourceAssetMapper.toMediaAsset(entry(sourceRow(1, path = "DCIM/Camera")))!!
        assertNotEquals(folderIdentity(phone).key, folderIdentity(source).key)
        assertEquals("path:~src/3/dcim/camera", folderIdentity(source).key.value)
        assertEquals("Camera", folderIdentity(source).displayName)
        // Two sources with the same inner path are different folders as well.
        val other = SourceAssetMapper.toMediaAsset(entry(sourceRow(2, sourceRow = 4, path = "DCIM/Camera"), sourceInfo(4)))!!
        assertNotEquals(folderIdentity(other).key, folderIdentity(source).key)
    }

    @Test
    fun `browsable over a merged list hides a source folder while locked and shows it when unlocked`() {
        val merged = mergeSourceAssets(listOf(libraryAsset(1)), listOf(entry(sourceRow(1)), entry(sourceRow(2))))
        val key = folderIdentity(merged.last()).key.value
        fun browse(locked: Set<String>, unlocked: Set<String> = emptySet()) =
            browsableAssets(merged, emptySet(), emptySet(), locked, unlocked, hideSensitive = false)
        assertEquals(3, browse(emptySet()).size)
        val hidden = browse(setOf(key))
        assertEquals(listOf(MediaId(1)), hidden.map { it.id })
        assertEquals(3, browse(setOf(key), setOf(key)).size)
    }

    @Test
    fun `a hundred thousand rows map to distinct ids`() {
        val entries = (1L..100_000L).map { entry(sourceRow(it)) }
        val merged = mergeSourceAssets(emptyList(), entries)
        assertEquals(100_000, merged.map { it.id }.toSet().size)
        assertTrue(merged.all { SourceAssetIds.isSourceId(it.id) })
    }
}
