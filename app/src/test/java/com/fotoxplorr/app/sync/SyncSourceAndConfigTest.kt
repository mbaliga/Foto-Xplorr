package com.fotoxplorr.app.sync

import com.fotoxplorr.app.gallery.folderIdentity
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.model.MediaId
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncSourceAndConfigTest {
    @get:Rule val temp = TemporaryFolder()

    private fun asset(id: Long, path: String = "DCIM/Camera/", favourite: Boolean = false, video: Boolean = false, trashed: Boolean = false, size: Long = 99, external: Boolean = false) = MediaAsset(
        id = MediaId(id), contentUriString = "content://media/external/images/media/$id", displayName = "IMG_$id.jpg",
        mimeType = if (video) "video/mp4" else "image/jpeg", bucketName = path.trim('/').substringAfterLast('/'),
        dateTakenMillis = 1_700_000_000_000L, dateModifiedSeconds = 20, width = 4, height = 3, sizeBytes = size,
        relativePath = path, isFavorite = favourite, isTrashed = trashed, isExternal = external,
    )

    private fun key(a: MediaAsset) = folderIdentity(a).key.value

    @Test
    fun `an empty selection selects nothing`() {
        assertTrue(SyncSelection().isEmpty)
        assertEquals(0, SyncCandidates.from(listOf(asset(1)), SyncSelection()).size)
    }

    @Test
    fun `all photos, chosen folders and favourites combine`() {
        val a = asset(1, "DCIM/Camera/")
        val b = asset(2, "Pictures/Trips/")
        val c = asset(3, "Pictures/Other/", favourite = true)
        val d = asset(4, "Pictures/Other/")
        val list = listOf(a, b, c, d)
        assertEquals(listOf(1L, 2L, 3L, 4L), SyncCandidates.from(list, SyncSelection(allPhotos = true)).map { it.mediaId })
        assertEquals(listOf(2L), SyncCandidates.from(list, SyncSelection(folderKeys = setOf(key(b)))).map { it.mediaId })
        assertEquals(listOf(3L), SyncCandidates.from(list, SyncSelection(favourites = true)).map { it.mediaId })
        assertEquals(listOf(2L, 3L), SyncCandidates.from(list, SyncSelection(folderKeys = setOf(key(b)), favourites = true)).map { it.mediaId })
    }

    @Test
    fun `videos can be left out`() {
        val list = listOf(asset(1), asset(2, video = true))
        assertEquals(listOf(1L), SyncCandidates.from(list, SyncSelection(allPhotos = true, includeVideos = false)).map { it.mediaId })
        assertEquals(2, SyncCandidates.from(list, SyncSelection(allPhotos = true)).size)
    }

    @Test
    fun `shared in, trashed and empty files are never candidates`() {
        val list = listOf(asset(1, external = true), asset(2, trashed = true), asset(3, size = 0), asset(4))
        assertEquals(listOf(4L), SyncCandidates.from(list, SyncSelection(allPhotos = true)).map { it.mediaId })
    }

    @Test
    fun `the sync library keeps locked, archived, sensitive and trashed photos out, even for an unlocked folder`() {
        val normal = asset(1)
        val locked = asset(2, "DCIM/Private/")
        val archived = asset(3)
        val sensitive = asset(4)
        val trashed = asset(5, trashed = true)
        val list = SyncCandidates.browsableForSync(
            listOf(normal, locked, archived, sensitive, trashed),
            archivedIds = setOf(MediaId(3)),
            sensitiveIds = setOf(MediaId(4)),
            lockedFolders = setOf(key(locked)),
        )
        assertEquals(listOf(1L), list.map { it.id.value })
        val candidates = SyncCandidates.from(list, SyncSelection(allPhotos = true))
        assertEquals(listOf(1L), candidates.map { it.mediaId })
    }

    @Test
    fun `the manifest key is the folder and the name, not the media id`() {
        assertEquals("DCIM/Camera/IMG_1.jpg", SyncKeys.localKey("DCIM/Camera/", "IMG_1.jpg"))
        assertEquals("a.jpg", SyncKeys.localKey(null, "a.jpg"))
        assertEquals("a/b/c.jpg", SyncKeys.localKey("\\a\\b\\", "c.jpg"))
    }

    @Test
    fun `the gate follows the live list`() {
        var list = SyncCandidates.browsableForSync(listOf(asset(1), asset(2)), emptySet(), emptySet(), emptySet())
        val gate = BrowsableGate({ list }, SyncSelection(allPhotos = true))
        val c1 = SyncCandidates.from(list, SyncSelection(allPhotos = true)).first { it.mediaId == 1L }
        assertTrue(gate.mayUpload(c1))
        // Folder locked after the run was planned: a new list without the photo.
        list = emptyList()
        assertFalse(gate.mayUpload(c1))
    }

    // ---- config ----

    private fun config() = SyncDestinationConfig(
        id = "abc123",
        kind = SyncDestinationKind.S3,
        displayName = "My B2",
        s3 = S3Settings("https://s3.us-west-004.backblazeb2.com", "us-west-004", "photos-bucket", "004AKEY", pathStyle = false, prefix = "phone/pixel"),
        selection = SyncSelection(allPhotos = false, folderKeys = setOf("dcim/camera", "pictures/we\nird"), favourites = true, includeVideos = false),
        wifiOnly = false,
        byteCapPerRun = 5_000_000_000L,
        encryptEnabled = true,
        encryption = SyncEncryptionSettings("ab".repeat(16), 600_000, "cd".repeat(32)),
    )

    @Test
    fun `settings survive a round trip`() {
        val c = config()
        assertEquals(c, SyncConfigCodec.decode(SyncConfigCodec.encode(c)))
        val dav = SyncDestinationConfig("d", SyncDestinationKind.WEBDAV, "NAS", webDav = WebDavSettings("https://nas.lan/dav/", "me", true))
        assertEquals(dav, SyncConfigCodec.decode(SyncConfigCodec.encode(dav)))
        val folder = SyncDestinationConfig("f", SyncDestinationKind.FOLDER, "Drive", folderTreeUri = "content://com.android.externalstorage.documents/tree/primary%3AFotoz")
        assertEquals(folder, SyncConfigCodec.decode(SyncConfigCodec.encode(folder)))
    }

    @Test
    fun `the defaults are safe and the saved text holds no secret field`() {
        val c = SyncDestinationConfig("x", SyncDestinationKind.WEBDAV, "n", webDav = WebDavSettings("https://h/", "u"))
        assertTrue(c.wifiOnly)
        assertNull(c.byteCapPerRun)
        assertFalse(c.encryptEnabled)
        assertTrue(c.selection.isEmpty)
        val text = SyncConfigCodec.encode(config())
        assertFalse(text.lowercase().contains("password"))
        assertFalse(text.lowercase().contains("secret"))
        assertFalse(text.lowercase().contains("passphrase"))
    }

    @Test
    fun `unknown keys are ignored and a damaged file is no destination`() {
        val text = SyncConfigCodec.encode(config()) + "future.key=whatever\nnot a line\n"
        assertEquals(config(), SyncConfigCodec.decode(text))
        assertNull(SyncConfigCodec.decode(""))
        assertNull(SyncConfigCodec.decode("kind=s3\n"))
        assertNull(SyncConfigCodec.decode("id=a\nkind=carrier-pigeon\n"))
    }

    @Test
    fun `the file store saves atomically, loads, and clears`() {
        val file = File(temp.newFolder(), "sync/destination.txt")
        val store = FileSyncConfigStore(file)
        assertNull(store.load())
        store.save(config())
        assertEquals(config(), store.load())
        assertFalse(File(file.parentFile, "destination.txt.tmp").exists())
        file.writeText("garbage")
        assertNull(store.load())
        store.save(config())
        store.clear()
        assertNull(store.load())
    }

    @Test
    fun `the manifest file store appends durable lines and survives a torn tail`() {
        val file = File(temp.newFolder(), "m/manifest.txt")
        val store = FileSyncManifestStore(file)
        assertEquals(0, store.load().done.size)
        val d = SyncDone("k", 1, 2, "Fotoz/a.jpg", 1, null, 3)
        store.append(SyncManifestCodec.doneLine(d))
        assertEquals(d, store.load().doneFor("k"))
        file.appendText("D\ttorn\t1\t2") // power cut
        store.append(SyncManifestCodec.doneLine(d.copy(key = "k2", remotePath = "Fotoz/b.jpg")))
        val loaded = store.load()
        assertEquals(2, loaded.done.size)
        assertEquals(1, loaded.ignoredLines)
        store.compact(loaded)
        assertEquals(0, FileSyncManifestStore(file).load().ignoredLines)
        assertEquals(2, FileSyncManifestStore(file).load().done.size)
    }

    @Test
    fun `an unsafe line cannot be appended`() {
        val store = FileSyncManifestStore(File(temp.newFolder(), "m.txt"))
        val e = runCatching { store.append("no newline") }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
    }
}
