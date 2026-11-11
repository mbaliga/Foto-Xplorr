package com.fotoxplorr.core.organize.fileops

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FaultInjectionTest {
    /** The scenario: the drive is pulled while a file is being copied onto it. */
    @Test
    fun pullTheUsbDriveMidCopy() {
        val h = Harness()
        val a = bytesOf(1, 400)
        val b = bytesOf(2, 400)
        val c = bytesOf(3, 400)
        h.fs.put("/phone/in/a.jpg", a)
        h.fs.put("/phone/in/b.jpg", b)
        h.fs.put("/phone/in/c.jpg", c)
        val plan = h.plan(listOf("a", "b", "c").map { FileOp.Copy("/phone/in/$it.jpg", "/usb/out") })

        // The drive disappears after 500 bytes: a.jpg is done, b.jpg is half written.
        h.fs.writeBudget = 500
        h.fs.unplugRootOnWriteBudget = "/usb"
        val r1 = h.engine.run(plan)

        assertEquals(FileOpsError.DESTINATION_GONE, r1.abortedBy)
        assertEquals(listOf(StepStatus.DONE, StepStatus.FAILED, StepStatus.NOT_RUN), r1.results.map { it.status })
        assertEquals(FileOpsError.DESTINATION_GONE, r1.results[1].error)
        // While it is unplugged we cannot see /usb at all; plug it back and look.
        h.fs.replug("/usb")
        assertEqualsBytes(a, h.fs.files["/usb/out/a.jpg"]?.bytes)
        assertNull(h.fs.files["/usb/out/b.jpg"], "no partial file under the final name")
        assertNull(h.fs.files["/usb/out/c.jpg"])
        // The originals were never touched.
        assertEqualsBytes(b, h.fs.files["/phone/in/b.jpg"]?.bytes)
        assertEquals(emptyList(), h.fs.deleted.filter { it.startsWith("/phone/") })
        // The half-written temp may still be there (the drive vanished before it could be removed)...
        assertTrue(h.partFiles().size <= 1)

        // ...and the journal lets the next run finish exactly the remaining work.
        h.fs.log.clear()
        val r2 = h.engine.run(plan)
        assertOk(r2)
        assertEquals(true, r2.results[0].resumed)
        assertEqualsBytes(b, h.fs.files["/usb/out/b.jpg"]?.bytes)
        assertEqualsBytes(c, h.fs.files["/usb/out/c.jpg"]?.bytes)
        assertEquals(emptyList(), h.partFiles())
        assertTrue(h.fs.log.none { it.contains("/usb/out/a.jpg") }, "finished step is not redone")
    }

    @Test
    fun unpluggingTheSourceMidReadFailsTheStepAndKeepsNoPartial() {
        val h = Harness()
        h.fs.put("/usb/a.jpg", bytesOf(1, 400))
        h.fs.readBudget = 100
        h.fs.unplugRootOnReadBudget = "/usb"
        val r = h.engine.run(h.plan(listOf(FileOp.Copy("/usb/a.jpg", "/phone/out"))))
        assertEquals(FileOpsError.SOURCE_GONE, r.results[0].error)
        assertNull(h.fs.files["/phone/out/a.jpg"])
        assertEquals(emptyList(), h.partFiles(), "the destination temp is removed because the destination is still reachable")
        h.fs.replug("/usb")
        assertOk(h.engine.run(h.plan(listOf(FileOp.Copy("/usb/a.jpg", "/phone/out")))))
        assertEqualsBytes(bytesOf(1, 400), h.fs.files["/phone/out/a.jpg"]?.bytes)
    }

    @Test
    fun verifyMismatchKeepsTheSourceAndRemovesOnlyTheTemp() {
        val h = Harness()
        val data = bytesOf(5, 200)
        h.fs.put("/usb/a.jpg", data)
        h.fs.corruptWrites = true
        val r = h.engine.run(h.plan(listOf(FileOp.Move("/usb/a.jpg", "/sd/out"))))
        assertEquals(FileOpsError.VERIFY_MISMATCH, r.results[0].error)
        assertEqualsBytes(data, h.fs.files["/usb/a.jpg"]?.bytes)
        assertNull(h.fs.files["/sd/out/a.jpg"])
        assertEquals(emptyList(), h.partFiles())
        assertEquals(listOf("/sd/out/a.jpg.fotoz-part"), h.fs.deleted, "only the temp was deleted")
        assertEquals(0, TrashStore(h.fs, "/usb", h.clock).list().size)
    }

    @Test
    fun aSourceThatChangesWhileCopyingIsNotTrusted() {
        val h = Harness()
        h.fs.put("/usb/a.jpg", bytesOf(1, 400))
        var n = 0
        h.fs.onRead = { if (++n == 3) h.fs.put("/usb/a.jpg", bytesOf(9, 400)) }
        val r = h.engine.run(h.plan(listOf(FileOp.Move("/usb/a.jpg", "/sd/out"))))
        assertEquals(FileOpsError.VERIFY_MISMATCH, r.results[0].error)
        assertEqualsBytes(bytesOf(9, 400), h.fs.files["/usb/a.jpg"]?.bytes)
        assertNull(h.fs.files["/sd/out/a.jpg"])
    }

    @Test
    fun noSpaceKnownUpFrontFailsBeforeWritingAnything() {
        val h = Harness(fs = FakeFileSystem(mapOf("/phone" to null, "/usb" to null, "/sd" to 500L, "/app" to null)))
        h.fs.put("/usb/a.jpg", bytesOf(1, 400))
        h.fs.put("/usb/b.jpg", bytesOf(2, 400))
        val plan = h.plan(listOf(FileOp.Copy("/usb/a.jpg", "/sd/out"), FileOp.Copy("/usb/b.jpg", "/sd/out")))
        assertTrue(plan.warnings.any { it is PlanWarning.InsufficientSpace && it.neededBytes == 800L && it.freeBytes == 500L })
        val r = h.engine.run(plan)
        assertEquals(listOf(StepStatus.DONE, StepStatus.FAILED), r.results.map { it.status })
        assertEquals(FileOpsError.NO_SPACE, r.abortedBy)
        assertNull(h.fs.files["/sd/out/b.jpg"])
        assertEquals(emptyList(), h.partFiles())
        assertEquals(emptyList(), h.fs.deleted)
    }

    @Test
    fun noSpaceDiscoveredWhileWritingRemovesTheTemp() {
        val h = Harness(fs = FakeFileSystem(mapOf("/phone" to null, "/usb" to null, "/sd" to 300L, "/app" to null)))
        h.fs.reportFree = false
        h.fs.put("/usb/a.jpg", bytesOf(1, 400))
        h.fs.put("/usb/b.jpg", bytesOf(2, 10))
        val r = h.engine.run(h.plan(listOf(FileOp.Copy("/usb/a.jpg", "/sd/out"), FileOp.Copy("/usb/b.jpg", "/sd/out"))))
        assertEquals(FileOpsError.NO_SPACE, r.results[0].error)
        assertEquals(StepStatus.NOT_RUN, r.results[1].status)
        assertEquals(emptyList(), h.partFiles())
        assertNull(h.fs.files["/sd/out/a.jpg"])
        assertEqualsBytes(bytesOf(1, 400), h.fs.files["/usb/a.jpg"]?.bytes)
    }

    @Test
    fun aMissingWriteTargetFolderPermissionErrorIsReportedPerStep() {
        val h = Harness()
        h.fs.put("/usb/a.jpg", "A")
        val denying = object : FileSystem by h.fs {
            override fun openCreate(path: String): ByteSink =
                if (path.startsWith("/sd/")) throw FileOpsException(FileOpsError.PERMISSION, "no grant") else h.fs.openCreate(path)
        }
        val engine = FileOpsEngine(denying, h.journal, h.clock)
        val r = engine.run(h.plan(listOf(FileOp.Copy("/usb/a.jpg", "/sd/out"), FileOp.Copy("/usb/a.jpg", "/phone/out"))))
        assertEquals(FileOpsError.PERMISSION, r.results[0].error)
        assertEquals(StepStatus.DONE, r.results[1].status, "a permission error does not stop the batch")
    }

    @Test
    fun crashBetweenCopyAndTrashingTheOriginalKeepsBothThenFinishes() {
        val h = Harness()
        val data = bytesOf(4, 300)
        h.fs.put("/usb/a.jpg", data)
        val plan = h.plan(listOf(FileOp.Move("/usb/a.jpg", "/sd/out")))
        h.fs.crashWhen = { op, p -> op == "rename" && p.startsWith("/usb/a.jpg") }
        assertFailsWith<SimulatedCrash> { h.engine.run(plan) }
        h.fs.resetFaults()
        // Crash point: verified copy in place, original still in place. Nothing lost, nothing partial.
        assertEqualsBytes(data, h.fs.files["/sd/out/a.jpg"]?.bytes)
        assertEqualsBytes(data, h.fs.files["/usb/a.jpg"]?.bytes)
        h.fs.log.clear()
        val r = h.engine.run(plan)
        assertOk(r)
        assertTrue(h.fs.log.none { it.startsWith("openCreate /sd") }, "the copy is not redone")
        assertNull(h.fs.files["/usb/a.jpg"])
        assertEquals(1, TrashStore(h.fs, "/usb", h.clock).list().size)
    }

    @Test
    fun resumeRefusesToTrashAnOriginalThatChangedAfterTheCopy() {
        val h = Harness()
        h.fs.put("/usb/a.jpg", bytesOf(4, 300))
        val plan = h.plan(listOf(FileOp.Move("/usb/a.jpg", "/sd/out")))
        h.fs.crashWhen = { op, p -> op == "rename" && p.startsWith("/usb/a.jpg") }
        assertFailsWith<SimulatedCrash> { h.engine.run(plan) }
        h.fs.resetFaults()
        h.fs.put("/usb/a.jpg", bytesOf(8, 300)) // edited in between, same size
        val r = h.engine.run(plan)
        assertEquals(FileOpsError.VERIFY_MISMATCH, r.results[0].error)
        assertEqualsBytes(bytesOf(8, 300), h.fs.files["/usb/a.jpg"]?.bytes)
    }

    @Test
    fun cancellingMidCopyRemovesTheTempAndALaterRunFinishes() {
        val h = Harness()
        h.fs.put("/usb/a.jpg", bytesOf(1, 400))
        h.fs.put("/usb/b.jpg", bytesOf(2, 400))
        val plan = h.plan(listOf(FileOp.Copy("/usb/a.jpg", "/phone/out"), FileOp.Copy("/usb/b.jpg", "/phone/out")))
        val cancel = CancelToken()
        val r1 = h.engine.run(plan, cancel, onProgress = { if (it.bytesDone >= 100) cancel.cancel() })
        assertTrue(r1.cancelled)
        assertEquals(listOf(StepStatus.CANCELLED, StepStatus.CANCELLED), r1.results.map { it.status })
        assertEquals(emptyList(), h.partFiles())
        assertNull(h.fs.files["/phone/out/a.jpg"])
        assertOk(h.engine.run(plan))
        assertEqualsBytes(bytesOf(2, 400), h.fs.files["/phone/out/b.jpg"]?.bytes)
    }

    @Test
    fun anUnwritableJournalStopsTheRunBeforeTheNextAction() {
        val h = Harness()
        h.fs.put("/usb/a.jpg", "A")
        val broken = object : FileSystem by h.fs {
            override fun openAppend(path: String): ByteSink = throw FileOpsException(FileOpsError.IO, "journal full")
        }
        val engine = FileOpsEngine(broken, FileJournal(broken, "/app/journal"), h.clock)
        val r = engine.run(h.plan(listOf(FileOp.Copy("/usb/a.jpg", "/phone/out"))))
        assertEquals(FileOpsError.JOURNAL_UNWRITABLE, r.abortedBy)
        assertNull(h.fs.files["/phone/out/a.jpg"])
        assertEquals(emptyList(), h.fs.log.filter { it.startsWith("openCreate") })
    }

    @Test
    fun aTornJournalLineIsIgnored() {
        val h = Harness()
        h.fs.put("/usb/a.jpg", "A")
        val plan = h.plan(listOf(FileOp.Copy("/usb/a.jpg", "/phone/out")))
        assertOk(h.engine.run(plan))
        val path = "/app/journal/${plan.id}.journal"
        h.fs.files[path]!!.bytes += "F\t0\tDON".encodeToByteArray()
        val state = h.journal.load(plan.id)
        assertEquals(StepStatus.DONE, state.terminal("0")?.status)
    }

    @Test
    fun theEngineNeverDeletesAnOriginal() {
        val h = Harness()
        h.fs.put("/usb/a.jpg", bytesOf(1, 100))
        h.fs.put("/usb/b.jpg", bytesOf(2, 100))
        h.fs.put("/sd/out/a.jpg", "OLD")
        h.fs.corruptWrites = false
        h.engine.run(
            h.plan(
                listOf(FileOp.Move("/usb/a.jpg", "/sd/out"), FileOp.Trash("/usb/b.jpg"), FileOp.Copy("/usb/a.jpg", "/sd/out")),
                ConflictSettings(ConflictPolicy.REPLACE, true),
            ),
        )
        assertTrue(h.fs.deletedOriginals().isEmpty(), "deleted: ${h.fs.deleted}")
    }
}
