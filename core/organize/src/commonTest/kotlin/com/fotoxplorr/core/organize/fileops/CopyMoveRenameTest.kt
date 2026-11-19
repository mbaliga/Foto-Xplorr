package com.fotoxplorr.core.organize.fileops

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CopyMoveRenameTest {
    @Test
    fun copyStreamsVerifiesAndLeavesNoTemp() {
        val h = Harness()
        val data = bytesOf(1, 1000)
        h.fs.put("/usb/DCIM/a.jpg", data)
        val plan = h.plan(listOf(FileOp.Copy("/usb/DCIM/a.jpg", "/phone/Pictures")))
        assertEquals(1000L, plan.estimatedBytes)
        val r = h.engine.run(plan)
        assertOk(r)
        assertEqualsBytes(data, h.fs.files["/phone/Pictures/a.jpg"]?.bytes)
        assertEqualsBytes(data, h.fs.files["/usb/DCIM/a.jpg"]?.bytes)
        assertEquals(emptyList(), h.partFiles())
        assertEquals(hashOf(data), r.results[0].sha256)
        assertEquals(1000L, r.bytesCopied)
    }

    @Test
    fun targetOnlyAppearsViaRenameOfAVerifiedTemp() {
        val h = Harness()
        h.fs.put("/usb/a.jpg", bytesOf(1, 100))
        h.engine.run(h.plan(listOf(FileOp.Copy("/usb/a.jpg", "/phone/p"))))
        val ops = h.fs.log.filter { "/phone/p/a.jpg" in it }
        assertTrue(ops.none { it.startsWith("openCreate /phone/p/a.jpg") && !it.endsWith(".fotoz-part") })
        assertTrue(ops.any { it.startsWith("rename /phone/p/a.jpg.fotoz-part -> /phone/p/a.jpg") })
    }

    @Test
    fun moveInsideOneSourceIsANativeRename() {
        val h = Harness()
        val data = bytesOf(2, 50)
        h.fs.put("/usb/in/a.jpg", data)
        val plan = h.plan(listOf(FileOp.Move("/usb/in/a.jpg", "/usb/out")))
        assertEquals(StepKind.MOVE_NATIVE, plan.steps[0].kind)
        assertEquals(0L, plan.estimatedBytes)
        assertOk(h.engine.run(plan))
        assertNull(h.fs.files["/usb/in/a.jpg"])
        assertEqualsBytes(data, h.fs.files["/usb/out/a.jpg"]?.bytes)
        assertTrue(h.fs.log.none { it.startsWith("openRead") })
    }

    @Test
    fun crossSourceMoveCopiesVerifiesThenTrashesTheOriginal() {
        val h = Harness()
        val data = bytesOf(3, 300)
        h.fs.put("/usb/a.jpg", data)
        val r = h.engine.run(h.plan(listOf(FileOp.Move("/usb/a.jpg", "/sd/in"))))
        assertOk(r)
        assertEqualsBytes(data, h.fs.files["/sd/in/a.jpg"]?.bytes)
        assertNull(h.fs.files["/usb/a.jpg"])
        val trash = TrashStore(h.fs, "/usb", h.clock).list()
        assertEquals(1, trash.size)
        assertEquals("/usb/a.jpg", trash[0].originalPath)
        assertEqualsBytes(data, h.fs.files["/usb/.fotoz-trash/${trash[0].storedName}"]?.bytes)
        assertEquals(trash[0].id, r.results[0].trashId)
        // The original was moved by rename, never deleted.
        assertEquals(emptyList(), h.fs.deleted.filter { it == "/usb/a.jpg" })
        // The source was not touched until the copy had been renamed into place.
        val renameIntoPlace = h.fs.log.indexOfFirst { it.startsWith("rename /sd/in/a.jpg.fotoz-part") }
        val trashing = h.fs.log.indexOfFirst { it.startsWith("rename /usb/a.jpg") }
        assertTrue(renameIntoPlace in 0 until trashing)
    }

    @Test
    fun renameKeepsBothOnACollision() {
        val h = Harness()
        h.fs.put("/usb/a.jpg", "A")
        h.fs.put("/usb/b.jpg", "B")
        val r = h.engine.run(h.plan(listOf(FileOp.Rename("/usb/a.jpg", "b.jpg"))))
        assertOk(r)
        assertEquals("B", h.fs.text("/usb/b.jpg"))
        assertEquals("A", h.fs.text("/usb/b (2).jpg"))
    }

    @Test
    fun renameWithSkipPolicyLeavesBothAlone() {
        val h = Harness()
        h.fs.put("/usb/a.jpg", "A")
        h.fs.put("/usb/b.jpg", "B")
        val r = h.engine.run(h.plan(listOf(FileOp.Rename("/usb/a.jpg", "b.jpg")), ConflictSettings(ConflictPolicy.SKIP)))
        assertEquals(StepStatus.SKIPPED, r.results[0].status)
        assertEquals("A", h.fs.text("/usb/a.jpg"))
        assertEquals("B", h.fs.text("/usb/b.jpg"))
    }

    @Test
    fun renameToTheSameNameIsSkipped() {
        val h = Harness()
        h.fs.put("/usb/a.jpg", "A")
        val r = h.engine.run(h.plan(listOf(FileOp.Rename("/usb/a.jpg", "a.jpg"))))
        assertEquals(StepStatus.SKIPPED, r.results[0].status)
        assertEquals("A", h.fs.text("/usb/a.jpg"))
    }

    @Test
    fun illegalCharactersInANewNameAreReplaced() {
        val h = Harness()
        h.fs.put("/usb/a.jpg", "A")
        assertOk(h.engine.run(h.plan(listOf(FileOp.Rename("/usb/a.jpg", "x/y:z.jpg")))))
        assertEquals("A", h.fs.text("/usb/x_y_z.jpg"))
    }

    @Test
    fun trashStepMovesTheFileToTheTrash() {
        val h = Harness()
        h.fs.put("/usb/a.jpg", "A")
        val r = h.engine.run(h.plan(listOf(FileOp.Trash("/usb/a.jpg"))))
        assertOk(r)
        assertNull(h.fs.files["/usb/a.jpg"])
        assertEquals(1, TrashStore(h.fs, "/usb", h.clock).list().size)
    }

    @Test
    fun foldersAreRefusedNotCopied() {
        val h = Harness()
        h.fs.mkdirs("/usb/dir")
        val plan = h.plan(listOf(FileOp.Copy("/usb/dir", "/phone/x")))
        assertTrue(plan.warnings.any { it is PlanWarning.IsFolder })
        val r = h.engine.run(plan)
        assertEquals(FileOpsError.UNSUPPORTED, r.results[0].error)
    }

    @Test
    fun aSourceGoneStepFailsAloneAndTheRestRuns() {
        val h = Harness()
        h.fs.put("/usb/b.jpg", "B")
        val r = h.engine.run(h.plan(listOf(FileOp.Copy("/usb/missing.jpg", "/phone/x"), FileOp.Copy("/usb/b.jpg", "/phone/x"))))
        assertEquals(FileOpsError.SOURCE_GONE, r.results[0].error)
        assertEquals(StepStatus.DONE, r.results[1].status)
        assertNull(r.abortedBy)
    }
}
