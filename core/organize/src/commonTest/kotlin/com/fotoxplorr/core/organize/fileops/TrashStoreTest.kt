package com.fotoxplorr.core.organize.fileops

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrashStoreTest {
    private val day = 86_400_000L

    @Test
    fun roundTripRestoresToTheOriginalPath() {
        val h = Harness()
        h.fs.put("/usb/DCIM/a.jpg", "AAA")
        val store = TrashStore(h.fs, "/usb", h.clock)
        val e = store.add("/usb/DCIM/a.jpg")
        assertNull(h.fs.files["/usb/DCIM/a.jpg"])
        assertEquals(3L, e.size)
        assertEquals("a.jpg", e.name)
        assertEquals(listOf(e), store.list())
        assertEquals("/usb/DCIM/a.jpg", store.restore(e.id))
        assertEquals("AAA", h.fs.text("/usb/DCIM/a.jpg"))
        assertEquals(emptyList(), store.list())
    }

    @Test
    fun theTrashFolderIsVisibleWithANomediaAndAManifest() {
        val h = Harness()
        h.fs.put("/usb/a.jpg", "A")
        TrashStore(h.fs, "/usb", h.clock).add("/usb/a.jpg")
        assertNotNull(h.fs.files["/usb/.fotoz-trash/.nomedia"])
        val manifest = h.fs.text("/usb/.fotoz-trash/manifest.json")!!
        assertTrue(manifest.contains("\"originalPath\":\"/usb/a.jpg\""))
        assertTrue(manifest.contains("\"expiresAtMs\""))
    }

    @Test
    fun restoreOnAClashKeepsBoth() {
        val h = Harness()
        h.fs.put("/usb/a.jpg", "OLD")
        val store = TrashStore(h.fs, "/usb", h.clock)
        val e = store.add("/usb/a.jpg")
        h.fs.put("/usb/a.jpg", "NEWER")
        assertEquals("/usb/a (2).jpg", store.restore(e.id))
        assertEquals("NEWER", h.fs.text("/usb/a.jpg"))
        assertEquals("OLD", h.fs.text("/usb/a (2).jpg"))
    }

    @Test
    fun restoreRecreatesAMissingFolder() {
        val h = Harness()
        h.fs.put("/usb/x/y/a.jpg", "A")
        val store = TrashStore(h.fs, "/usb", h.clock)
        val e = store.add("/usb/x/y/a.jpg")
        h.fs.dirs.remove("/usb/x/y")
        assertEquals("/usb/x/y/a.jpg", store.restore(e.id))
    }

    @Test
    fun sameNameTwiceGetsUniqueStoredNames() {
        val h = Harness()
        val store = TrashStore(h.fs, "/usb", h.clock)
        h.fs.put("/usb/a/IMG.jpg", "ONE")
        h.fs.put("/usb/b/IMG.jpg", "TWO")
        val e1 = store.add("/usb/a/IMG.jpg")
        val e2 = store.add("/usb/b/IMG.jpg")
        assertTrue(e1.storedName != e2.storedName)
        assertEquals("TWO", h.fs.text("/usb/.fotoz-trash/${e2.storedName}"))
        assertEquals(2, store.list().size)
    }

    @Test
    fun expiryIsThirtyDaysAndNothingIsEverPurgedAutomatically() {
        val h = Harness()
        h.fs.put("/usb/a.jpg", "A")
        val store = TrashStore(h.fs, "/usb", h.clock)
        val e = store.add("/usb/a.jpg")
        assertEquals(h.clock.now + 30 * day, e.expiresAtMs)
        assertEquals(30, TrashExpiry.daysLeft(e, h.clock.now))
        assertTrue(store.expired().isEmpty())
        h.clock.now += 400 * day
        assertTrue(e.isExpired(h.clock.now))
        assertEquals(0, TrashExpiry.daysLeft(e, h.clock.now))
        // Expired is a flag for the UI: the file is still there, still listed, still restorable.
        assertEquals(listOf(e.id), store.expired().map { it.id })
        assertEquals(1, store.list().size)
        assertEquals(emptyList(), h.fs.deleted)
        // Opening a store, listing and adding more never purges it either.
        h.fs.put("/usb/b.jpg", "B")
        store.add("/usb/b.jpg")
        assertEquals(2, TrashStore(h.fs, "/usb", h.clock).list().size)
        assertEquals(emptyList(), h.fs.deleted.filter { it.contains("-a.jpg") })
    }

    @Test
    fun emptyTrashIsExplicitAndRemovesOnlyTrashedFiles() {
        val h = Harness()
        h.fs.put("/usb/keep.jpg", "K")
        h.fs.put("/usb/a.jpg", "A")
        val store = TrashStore(h.fs, "/usb", h.clock)
        store.add("/usb/a.jpg")
        assertEquals(1, store.emptyTrash())
        assertEquals(emptyList(), store.list())
        assertEquals("K", h.fs.text("/usb/keep.jpg"))
        assertTrue(h.fs.deleted.all { it.contains(".fotoz-trash/") })
    }

    @Test
    fun purgeOneRemovesOnlyThatEntry() {
        val h = Harness()
        h.fs.put("/usb/a.jpg", "A")
        h.fs.put("/usb/b.jpg", "B")
        val store = TrashStore(h.fs, "/usb", h.clock)
        val a = store.add("/usb/a.jpg")
        store.add("/usb/b.jpg")
        store.purge(a.id)
        assertEquals(listOf("/usb/b.jpg"), store.list().map { it.originalPath })
    }

    @Test
    fun anOrphanManifestEntryIsIgnoredAndPruned() {
        val h = Harness()
        h.fs.put("/usb/a.jpg", "A")
        val store = TrashStore(h.fs, "/usb", h.clock)
        val e = store.add("/usb/a.jpg")
        h.fs.files.remove("/usb/.fotoz-trash/${e.storedName}") // crash left an entry without its file
        assertEquals(emptyList(), store.list())
        h.fs.put("/usb/b.jpg", "B")
        store.add("/usb/b.jpg")
        assertTrue(!h.fs.text("/usb/.fotoz-trash/manifest.json")!!.contains(e.id))
    }

    @Test
    fun aCrashBetweenWritingTheManifestAndMovingTheFileLosesNothing() {
        val h = Harness()
        h.fs.put("/usb/a.jpg", "A")
        val store = TrashStore(h.fs, "/usb", h.clock)
        h.fs.crashWhen = { op, p -> op == "rename" && p.startsWith("/usb/a.jpg") }
        assertFailsWith<SimulatedCrash> { store.add("/usb/a.jpg", "fixed") }
        h.fs.resetFaults()
        assertEquals("A", h.fs.text("/usb/a.jpg"))
        assertEquals(emptyList(), store.list())
        // Retrying with the same id completes it exactly once.
        store.add("/usb/a.jpg", "fixed")
        store.add("/usb/a.jpg", "fixed")
        assertEquals(1, store.list().size)
    }

    @Test
    fun aCrashBetweenDeletingAndRenamingTheManifestStillLoads() {
        val h = Harness()
        h.fs.put("/usb/a.jpg", "A")
        h.fs.put("/usb/b.jpg", "B")
        val store = TrashStore(h.fs, "/usb", h.clock)
        store.add("/usb/a.jpg")
        h.fs.crashWhen = { op, p -> op == "rename" && p.endsWith("manifest.json.new -> /usb/.fotoz-trash/manifest.json") }
        assertFailsWith<SimulatedCrash> { store.add("/usb/b.jpg") }
        h.fs.resetFaults()
        assertNull(h.fs.files["/usb/.fotoz-trash/manifest.json"])
        assertEquals(1, TrashStore(h.fs, "/usb", h.clock).list().size.coerceAtLeast(1))
        assertTrue(TrashStore(h.fs, "/usb", h.clock).list().any { it.originalPath == "/usb/a.jpg" })
    }

    @Test
    fun anUnreadableManifestIsAnErrorNotAnEmptyTrash() {
        val h = Harness()
        h.fs.put("/usb/.fotoz-trash/manifest.json", "{ not json")
        h.fs.put("/usb/a.jpg", "A")
        val store = TrashStore(h.fs, "/usb", h.clock)
        assertFailsWith<FileOpsException> { store.list() }
        assertFailsWith<FileOpsException> { store.add("/usb/a.jpg") }
        assertEquals("A", h.fs.text("/usb/a.jpg"))
    }

    @Test
    fun namesWithQuotesAndNewlinesSurviveTheManifest() {
        val h = Harness()
        val name = "we\"ird\\na\nme\t.jpg"
        h.fs.put("/usb/$name", "A")
        val store = TrashStore(h.fs, "/usb", h.clock)
        val e = store.add("/usb/$name")
        assertEquals(name, TrashStore(h.fs, "/usb", h.clock).list().single().name)
        assertEquals("/usb/$name", store.restore(e.id))
    }

    @Test
    fun trashingAFolderOrAMissingFileFails() {
        val h = Harness()
        h.fs.mkdirs("/usb/d")
        val store = TrashStore(h.fs, "/usb", h.clock)
        assertEquals(FileOpsError.UNSUPPORTED, assertFailsWith<FileOpsException> { store.add("/usb/d") }.error)
        assertEquals(FileOpsError.SOURCE_GONE, assertFailsWith<FileOpsException> { store.add("/usb/none") }.error)
    }
}
