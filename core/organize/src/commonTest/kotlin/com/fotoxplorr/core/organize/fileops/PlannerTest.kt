package com.fotoxplorr.core.organize.fileops

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PlannerTest {
    @Test
    fun estimatesBytesOnlyForStreamedSteps() {
        val h = Harness()
        h.fs.put("/usb/a.jpg", bytesOf(1, 100))
        h.fs.put("/usb/b.jpg", bytesOf(2, 200))
        h.fs.put("/usb/c.jpg", bytesOf(3, 300))
        h.fs.put("/usb/d.jpg", bytesOf(4, 400))
        val plan = h.plan(
            listOf(
                FileOp.Copy("/usb/a.jpg", "/phone/x"),
                FileOp.Move("/usb/b.jpg", "/sd/x"),
                FileOp.Move("/usb/c.jpg", "/usb/y"),
                FileOp.Rename("/usb/d.jpg", "e.jpg"),
                FileOp.Trash("/usb/d.jpg"),
            ),
        )
        assertEquals(listOf(StepKind.COPY, StepKind.MOVE_CROSS, StepKind.MOVE_NATIVE, StepKind.RENAME, StepKind.TRASH), plan.steps.map { it.kind })
        assertEquals(300L, plan.estimatedBytes)
    }

    @Test
    fun warnsAboutMissingSources() {
        val h = Harness()
        val plan = h.plan(listOf(FileOp.Copy("/usb/none.jpg", "/phone/x")))
        assertEquals(listOf(PlanWarning.SourceMissing("0")), plan.warnings)
    }

    @Test
    fun planIdFollowsTheContentOfTheBatch() {
        val h = Harness()
        h.fs.put("/usb/a.jpg", "A")
        val a = h.plan(listOf(FileOp.Copy("/usb/a.jpg", "/phone/x")))
        val b = h.plan(listOf(FileOp.Copy("/usb/a.jpg", "/phone/x")))
        val c = h.plan(listOf(FileOp.Copy("/usb/a.jpg", "/phone/y")))
        assertEquals(a.id, b.id)
        assertNotEquals(a.id, c.id)
    }

    @Test
    fun sanitizeNameRules() {
        assertEquals("a_b_c", FileOpsPlanner.sanitizeName("a/b:c"))
        assertEquals("", FileOpsPlanner.sanitizeName(".."))
        assertEquals("", FileOpsPlanner.sanitizeName("   "))
        assertEquals("ok name.jpg", FileOpsPlanner.sanitizeName("  ok name.jpg "))
    }

    @Test
    fun anEmptyNewNameKeepsTheOldName() {
        val h = Harness()
        h.fs.put("/usb/a.jpg", "A")
        val plan = h.plan(listOf(FileOp.Copy("/usb/a.jpg", "/phone/x", newName = "..")))
        assertEquals("/phone/x/a.jpg", plan.steps[0].target)
    }

    @Test
    fun spaceWarningCountsEverythingBoundForOneVolume() {
        val h = Harness(fs = FakeFileSystem(mapOf("/phone" to null, "/usb" to null, "/sd" to 250L, "/app" to null)))
        h.fs.put("/usb/a.jpg", bytesOf(1, 100))
        h.fs.put("/usb/b.jpg", bytesOf(2, 200))
        val plan = h.plan(listOf(FileOp.Copy("/usb/a.jpg", "/sd/x"), FileOp.Copy("/usb/b.jpg", "/sd/x")))
        assertTrue(plan.warnings.single() is PlanWarning.InsufficientSpace)
    }
}
