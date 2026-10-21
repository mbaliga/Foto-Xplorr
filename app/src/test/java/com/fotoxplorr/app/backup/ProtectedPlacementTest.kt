package com.fotoxplorr.app.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectedPlacementTest {
    private fun entry(id: Long, dir: String, protected: Boolean = true, mime: String = "image/jpeg", flags: Set<EntryFlag> = emptySet()) =
        ManifestEntry(
            entryId = id, mediaId = id, displayName = "p$id.jpg", relativePath = dir, mimeType = mime, sizeBytes = 10,
            dateTakenMillis = 0, dateModifiedSeconds = 0, sha256 = "00".repeat(32), quickHash = null, packName = "pack-0001.fzbp",
            flags = if (protected) flags + EntryFlag.WAS_PROTECTED else flags,
        )

    private fun lock(path: String) = BackupSections.Lock(ProtectedPlacement.lockKey(path), "c2FsdA==", "aGFzaA==")

    @Test
    fun `the key is the one the library uses for the folder`() {
        assertEquals("path:dcim/private", ProtectedPlacement.lockKey("DCIM/Private/"))
        assertEquals("path:dcim/private", ProtectedPlacement.lockKey("dcim\\Private"))
    }

    @Test
    fun `a photo returns to its own folder when media store accepts that folder`() {
        val arranged = ProtectedPlacement.arrange(listOf(entry(1, "DCIM/Private/")), listOf(lock("DCIM/Private/")), canChooseFolder = true)
        assertEquals("DCIM/Private/", arranged.entries.single().relativePath)
        assertEquals(listOf("path:dcim/private"), arranged.locks.map { it.folderKey })
        assertEquals(0, arranged.relocated)
        assertTrue(arranged.heldBack.isEmpty())
    }

    @Test
    fun `a photo from a folder media store refuses gets a folder of its own under the same lock`() {
        val original = lock("Download/Secret/")
        val arranged = ProtectedPlacement.arrange(listOf(entry(1, "Download/Secret/"), entry(2, "Download/Secret/")), listOf(original), canChooseFolder = true)

        val own = "Pictures/Fotoz/Restored protected/Download/Secret/"
        assertEquals(listOf(own, own), arranged.entries.map { it.relativePath })
        val applied = arranged.locks.single()
        assertEquals(ProtectedPlacement.lockKey(own), applied.folderKey)
        assertEquals(original.saltBase64, applied.saltBase64)
        assertEquals(original.hashBase64, applied.hashBase64)
        assertEquals(2, arranged.relocated)
    }

    @Test
    fun `videos from a folder only images may use are moved too`() {
        val arranged = ProtectedPlacement.arrange(listOf(entry(1, "Movies/Private/", mime = "video/mp4")), listOf(lock("Movies/Private/")), canChooseFolder = true)
        // Movies is a folder videos may use, so the video returns home.
        assertEquals("Movies/Private/", arranged.entries.single().relativePath)
        val image = ProtectedPlacement.arrange(listOf(entry(2, "Movies/Private/")), listOf(lock("Movies/Private/")), canChooseFolder = true)
        assertEquals("Pictures/Fotoz/Restored protected/Movies/Private/", image.entries.single().relativePath)
    }

    @Test
    fun `a folder with photos in it and no lock here is not locked, its protected photos move`() {
        val key = ProtectedPlacement.lockKey("DCIM/Private/")
        val arranged = ProtectedPlacement.arrange(
            listOf(entry(1, "DCIM/Private/")),
            listOf(lock("DCIM/Private/")),
            canChooseFolder = true,
            lockedHere = emptySet(),
            inUse = setOf(key),
        )
        assertEquals("Pictures/Fotoz/Restored protected/DCIM/Private/", arranged.entries.single().relativePath)
        assertTrue(arranged.locks.none { it.folderKey == key })
    }

    @Test
    fun `a folder that is already locked here takes its photos back`() {
        val key = ProtectedPlacement.lockKey("DCIM/Private/")
        val arranged = ProtectedPlacement.arrange(
            listOf(entry(1, "DCIM/Private/")),
            listOf(lock("DCIM/Private/")),
            canChooseFolder = true,
            lockedHere = setOf(key),
            inUse = setOf(key),
        )
        assertEquals("DCIM/Private/", arranged.entries.single().relativePath)
    }

    @Test
    fun `before Android 10 protected photos are held back and nothing else is`() {
        val arranged = ProtectedPlacement.arrange(
            listOf(entry(1, "DCIM/Private/"), entry(2, "DCIM/Camera/", protected = false)),
            listOf(lock("DCIM/Private/")),
            canChooseFolder = false,
        )
        assertEquals(listOf(1L), arranged.heldBack.map { it.entryId })
        assertEquals(listOf(2L), arranged.entries.map { it.entryId })
        assertTrue(arranged.locks.isEmpty())
    }

    @Test
    fun `a protected photo with no lock in the backup is counted and left where it was`() {
        val arranged = ProtectedPlacement.arrange(listOf(entry(1, "DCIM/Private/")), emptyList(), canChooseFolder = true)
        assertEquals(1, arranged.withoutLock)
        assertEquals("DCIM/Private/", arranged.entries.single().relativePath)
        assertTrue(arranged.locks.isEmpty())
    }

    @Test
    fun `photos that were not protected are never touched`() {
        val e = entry(1, "Download/x/", protected = false)
        val arranged = ProtectedPlacement.arrange(listOf(e), listOf(lock("Download/x/")), canChooseFolder = true)
        assertEquals(listOf(e), arranged.entries)
        assertTrue(arranged.locks.isEmpty())
    }

    @Test
    fun `an entry that will not be written is left for the planner to report`() {
        val missing = entry(1, "Download/Secret/", flags = setOf(EntryFlag.MISSING))
        val unsupported = entry(2, "Download/Secret/", mime = "audio/mp3")
        val arranged = ProtectedPlacement.arrange(listOf(missing, unsupported), listOf(lock("Download/Secret/")), canChooseFolder = false)
        assertEquals(listOf(missing, unsupported), arranged.entries)
        assertTrue(arranged.heldBack.isEmpty())
    }

    @Test
    fun `a very deep original path stays inside the segment limit so the key is the folder's real key`() {
        val deep = List(40) { "d$it" }.joinToString("/") + "/"
        val arranged = ProtectedPlacement.arrange(listOf(entry(1, deep)), listOf(lock(deep)), canChooseFolder = true)
        val own = arranged.entries.single().relativePath
        assertTrue(own.trimEnd('/').split('/').size <= 32)
        assertEquals(ProtectedPlacement.lockKey(own), arranged.locks.single().folderKey)
        // ...and the planner leaves that path exactly as it is.
        assertEquals(own, RestorePlanner.sanitiseRelativePath(own))
    }
}
