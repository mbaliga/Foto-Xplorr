package com.fotoxplorr.core.organize.fileops

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Regression tests for the independent review of the Phase 3 file-ops core (findings 1 to 4 and 8 to 10). */
class FileOpsReviewFixesTest {
    private fun manifest(vararg entries: String) = "{\"version\":1,\"entries\":[${entries.joinToString(",")}]}"

    private fun entry(id: String, stored: String, original: String) =
        "{\"id\":\"$id\",\"originalPath\":\"$original\",\"name\":\"x\",\"storedName\":\"$stored\",\"size\":1," +
            "\"trashedAtMs\":1,\"expiresAtMs\":2}"

    // ---- 1: hostile manifests ----

    @Test
    fun hostileStoredNamesNeverReachOutsideTheTrash() {
        val h = Harness()
        h.fs.put("/usb/DCIM/IMG_1.jpg", "PRECIOUS")
        h.fs.put("/usb/.fotoz-trash/ok-a.jpg", "TRASHED")
        val bad = listOf(
            entry("h1", "../DCIM/IMG_1.jpg", "/usb/a.jpg"),
            entry("h2", "h2-../DCIM/IMG_1.jpg", "/usb/a.jpg"),
            entry("h3", "..", "/usb/a.jpg"),
            entry("h4", ".", "/usb/a.jpg"),
            entry("h5", "", "/usb/a.jpg"),
            entry("h6", "h6-..\\\\DCIM\\\\IMG_1.jpg", "/usb/a.jpg"),
            entry("h7", "other-a.jpg", "/usb/a.jpg"),
        )
        h.fs.put("/usb/.fotoz-trash/manifest.json", manifest(*bad.toTypedArray(), entry("ok", "ok-a.jpg", "/usb/a.jpg")))
        val store = TrashStore(h.fs, "/usb", h.clock)
        assertEquals(listOf("ok"), store.list().map { it.id })
        for (id in listOf("h1", "h2", "h3", "h6")) {
            assertFailsWith<FileOpsException> { store.restore(id) }
            store.purge(id)
        }
        assertEquals(1, store.emptyTrash())
        assertEquals("PRECIOUS", h.fs.text("/usb/DCIM/IMG_1.jpg"))
        assertTrue(h.fs.deleted.none { it.contains("DCIM") })
    }

    @Test
    fun hostileOriginalPathsAreRejected() {
        val h = Harness()
        h.fs.put("/usb/.fotoz-trash/a-a.jpg", "T")
        h.fs.put(
            "/usb/.fotoz-trash/manifest.json",
            manifest(entry("a", "a-a.jpg", "/usb/../../etc/x.jpg"), entry("b", "b-a.jpg", "")),
        )
        val store = TrashStore(h.fs, "/usb", h.clock)
        assertEquals(emptyList(), store.list())
        assertFailsWith<FileOpsException> { store.restore("a") }
        assertEquals("T", h.fs.text("/usb/.fotoz-trash/a-a.jpg"))
    }

    // ---- 2 and 9 and 10: repeated ids ----

    @Test
    fun trashingTheSameFileTwiceMovesItBothTimes() {
        val h = Harness()
        h.fs.put("/usb/a.jpg", "ONE")
        val store = TrashStore(h.fs, "/usb", h.clock)
        val first = store.add("/usb/a.jpg", "same")
        h.fs.put("/usb/a.jpg", "TWO")
        val second = store.add("/usb/a.jpg", "same")
        assertNull(h.fs.files["/usb/a.jpg"])
        assertNotEquals(first.id, second.id)
        assertEquals(2, store.list().size)
    }

    @Test
    fun resumingATrashAddWhoseSourceIsGoneReturnsTheEarlierEntry() {
        val h = Harness()
        h.fs.put("/usb/a.jpg", "ONE")
        val store = TrashStore(h.fs, "/usb", h.clock)
        val first = store.add("/usb/a.jpg", "same")
        assertEquals(first, store.add("/usb/a.jpg", "same"))
        assertEquals(1, store.list().size)
    }

    @Test
    fun aSecondIdenticalTrashPlanTrashesTheFileAgain() {
        val h = Harness()
        h.fs.put("/usb/a.jpg", "ONE")
        val ops = listOf(FileOp.Trash("/usb/a.jpg"))
        assertOk(h.engine.run(h.plan(ops)))
        assertNull(h.fs.files["/usb/a.jpg"])
        // The user restores it and later trashes it again with the very same op list.
        val store = TrashStore(h.fs, "/usb", h.clock)
        store.restore(store.list().single().id)
        assertEquals("ONE", h.fs.text("/usb/a.jpg"))
        val r = h.engine.run(h.plan(ops))
        assertOk(r)
        assertNull(h.fs.files["/usb/a.jpg"], "the second run must really trash it")
        assertEquals(StepStatus.DONE, r.results.single().status)
        assertEquals(false, r.results.single().resumed)
    }

    @Test
    fun aRepeatedCopyPlanRunsAgainAfterTheTargetIsDeleted() {
        val h = Harness()
        h.fs.put("/phone/a.jpg", "AAAA")
        val ops = listOf(FileOp.Copy("/phone/a.jpg", "/sd/out"))
        assertOk(h.engine.run(h.plan(ops)))
        h.fs.files.remove("/sd/out/a.jpg")
        assertOk(h.engine.run(h.plan(ops)))
        assertEquals("AAAA", h.fs.text("/sd/out/a.jpg"))
    }

    @Test
    fun trashStepResumesWhenTheFileAlreadyReachedTheTrash() {
        val h = Harness()
        h.fs.put("/usb/a.jpg", "ONE")
        val plan = h.plan(listOf(FileOp.Trash("/usb/a.jpg")))
        // Crash after the move into the trash but before the journal heard of it.
        h.fs.crashWhen = { op, p -> op == "write" && p.endsWith(".journal") && h.fs.files["/usb/a.jpg"] == null }
        try {
            h.engine.run(plan)
        } catch (e: SimulatedCrash) {
            // expected
        }
        h.fs.resetFaults()
        assertNull(h.fs.files["/usb/a.jpg"])
        val r = h.engine.run(plan)
        assertOk(r)
        assertNotNull(r.results.single().trashId)
        assertEquals(1, TrashStore(h.fs, "/usb", h.clock).list().size)
    }

    // ---- 3: size and depth caps ----

    @Test
    fun deeplyNestedManifestIsRefusedNotAStackOverflow() {
        val h = Harness()
        val deep = "[".repeat(100_000)
        h.fs.put("/usb/.fotoz-trash/manifest.json", deep)
        val store = TrashStore(h.fs, "/usb", h.clock)
        val e = assertFailsWith<FileOpsException> { store.list() }
        assertEquals(FileOpsError.IO, e.error)
    }

    @Test
    fun oversizedManifestIsRefusedBeforeItIsRead() {
        val h = Harness()
        h.fs.put("/usb/.fotoz-trash/manifest.json", ByteArray(MAX_SMALL_FILE_BYTES + 1) { 32 })
        val e = assertFailsWith<FileOpsException> { TrashStore(h.fs, "/usb", h.clock).list() }
        assertEquals(FileOpsError.IO, e.error)
        assertTrue(h.fs.log.none { it.startsWith("read ") }, "nothing may be read for a file over the cap")
    }

    @Test
    fun readAllBytesReturnsTheExactContent() {
        val h = Harness()
        val data = bytesOf(3, 100_000)
        h.fs.put("/app/big.bin", data)
        assertEqualsBytes(data, h.fs.readAllBytes("/app/big.bin"))
        h.fs.put("/app/empty.bin", ByteArray(0))
        assertEquals(0, h.fs.readAllBytes("/app/empty.bin").size)
    }

    // ---- 4: case-only rename ----

    @Test
    fun caseOnlyRenameOnACaseInsensitiveVolumeIsNotAClashWithItself() {
        for (policy in listOf(ConflictPolicy.KEEP_BOTH, ConflictPolicy.SKIP, ConflictPolicy.REPLACE)) {
            val h = Harness()
            h.fs.caseInsensitive = true
            h.fs.put("/usb/DCIM/IMG.JPG", "PIXELS")
            val plan = h.plan(listOf(FileOp.Rename("/usb/DCIM/IMG.JPG", "img.jpg")), ConflictSettings(policy, replaceConsent = true))
            val r = h.engine.run(plan)
            assertOk(r)
            assertEquals(StepStatus.DONE, r.results.single().status, "policy $policy")
            assertEquals("PIXELS", h.fs.text("/usb/DCIM/img.jpg"), "policy $policy")
            assertEquals(listOf("/usb/DCIM/img.jpg"), h.fs.files.keys.filter { it.startsWith("/usb/DCIM") }, "policy $policy")
            assertTrue(h.fs.files.keys.none { it.contains(".fotoz-trash/") && !it.endsWith(".nomedia") && !it.endsWith("manifest.json") }, "policy $policy")
        }
    }

    @Test
    fun caseOnlyRenameOnACaseSensitiveVolumeWithADistinctFileStillFollowsTheConflictPolicy() {
        val h = Harness()
        h.fs.put("/usb/IMG.JPG", "ONE")
        h.fs.put("/usb/img.jpg", "OTHER")
        val r = h.engine.run(h.plan(listOf(FileOp.Rename("/usb/IMG.JPG", "img.jpg")), ConflictSettings(ConflictPolicy.KEEP_BOTH)))
        assertOk(r)
        assertEquals("OTHER", h.fs.text("/usb/img.jpg"))
        assertEquals("ONE", h.fs.text("/usb/img (2).jpg"))
        assertNull(h.fs.files["/usb/IMG.JPG"])
    }

    @Test
    fun caseOnlyRenameResumesAfterTheFirstHop() {
        val h = Harness()
        h.fs.caseInsensitive = true
        h.fs.put("/usb/IMG.JPG", "PIXELS")
        val plan = h.plan(listOf(FileOp.Rename("/usb/IMG.JPG", "img.jpg")))
        h.fs.crashWhen = { op, p -> op == "rename" && p.endsWith("-> /usb/img.jpg") }
        try {
            h.engine.run(plan)
        } catch (e: SimulatedCrash) {
            // expected
        }
        h.fs.resetFaults()
        assertNull(h.fs.files["/usb/IMG.JPG"])
        assertOk(h.engine.run(plan))
        assertEquals("PIXELS", h.fs.text("/usb/img.jpg"))
        assertEquals(listOf("/usb/img.jpg"), h.fs.files.keys.filter { it.startsWith("/usb/") && !it.contains("journal") })
    }

    // ---- 8: torn journal line ----

    @Test
    fun appendAfterATornLastLineDoesNotGlueEntriesTogether() {
        val h = Harness()
        val journal = FileJournal(h.fs, "/app/journal")
        journal.append("p", JournalEntry.Finished("0", StepStatus.DONE, "/x/a", null, null))
        // A crash mid-append: the next line is cut off before its newline (and would parse if it were glued on).
        h.fs.files["/app/journal/p.journal"]!!.let { it.bytes += "F\t1\tDONE\t=/x/b\t-".encodeToByteArray() }
        val fresh = FileJournal(h.fs, "/app/journal") // a restarted process
        fresh.append("p", JournalEntry.Finished("2", StepStatus.DONE, "/x/c", null, null))
        val state = fresh.load("p")
        assertEquals("/x/a", state.terminal("0")?.target)
        assertNull(state.terminal("1"), "the torn entry must stay dropped")
        assertEquals("/x/c", state.terminal("2")?.target)
    }
}
