package com.fotoxplorr.core.organize.fileops

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConflictPolicyTest {
    private fun setup(): Harness {
        val h = Harness()
        h.fs.put("/usb/a.jpg", "NEW-A")
        h.fs.put("/usb/b.jpg", "NEW-B")
        h.fs.put("/phone/p/a.jpg", "OLD-A")
        h.fs.put("/phone/p/b.jpg", "OLD-B")
        return h
    }

    private val both = { h: Harness -> listOf(FileOp.Copy("/usb/a.jpg", "/phone/p"), FileOp.Copy("/usb/b.jpg", "/phone/p")) }

    @Test
    fun skipLeavesExistingFilesAlone() {
        val h = setup()
        val r = h.engine.run(h.plan(both(h), ConflictSettings(ConflictPolicy.SKIP)))
        assertEquals(listOf(StepStatus.SKIPPED, StepStatus.SKIPPED), r.results.map { it.status })
        assertEquals("OLD-A", h.fs.text("/phone/p/a.jpg"))
        assertEquals(emptyList(), h.fs.log.filter { it.startsWith("openRead") })
    }

    @Test
    fun keepBothUsesNumberedNamesThatNeverCollide() {
        val h = setup()
        h.fs.put("/phone/p/a (2).jpg", "OLD-A2")
        val r = h.engine.run(h.plan(both(h), ConflictSettings(ConflictPolicy.KEEP_BOTH)))
        assertOk(r)
        assertEquals("OLD-A", h.fs.text("/phone/p/a.jpg"))
        assertEquals("OLD-A2", h.fs.text("/phone/p/a (2).jpg"))
        assertEquals("NEW-A", h.fs.text("/phone/p/a (3).jpg"))
        assertEquals("NEW-B", h.fs.text("/phone/p/b (2).jpg"))
    }

    @Test
    fun twoSourcesWithTheSameNameBothSurvive() {
        val h = Harness()
        h.fs.put("/usb/x/IMG.jpg", "ONE")
        h.fs.put("/sd/y/IMG.jpg", "TWO")
        val r = h.engine.run(h.plan(listOf(FileOp.Copy("/usb/x/IMG.jpg", "/phone/p"), FileOp.Copy("/sd/y/IMG.jpg", "/phone/p"))))
        assertOk(r)
        assertEquals("ONE", h.fs.text("/phone/p/IMG.jpg"))
        assertEquals("TWO", h.fs.text("/phone/p/IMG (2).jpg"))
    }

    @Test
    fun replaceWithoutConsentTouchesNothing() {
        val h = setup()
        val plan = h.plan(both(h), ConflictSettings(ConflictPolicy.REPLACE, replaceConsent = false))
        assertTrue(plan.warnings.contains(PlanWarning.ReplaceWithoutConsent))
        val r = h.engine.run(plan)
        assertEquals(listOf(FileOpsError.CONSENT_REQUIRED, FileOpsError.CONSENT_REQUIRED), r.results.map { it.error })
        assertEquals("OLD-A", h.fs.text("/phone/p/a.jpg"))
        assertEquals(emptyList(), h.fs.deleted)
        assertEquals(0, TrashStore(h.fs, "/phone", h.clock).list().size)
    }

    @Test
    fun replaceWithConsentTrashesTheOldFileFirstAndItRestores() {
        val h = setup()
        val r = h.engine.run(h.plan(both(h), ConflictSettings(ConflictPolicy.REPLACE, replaceConsent = true)))
        assertOk(r)
        assertEquals("NEW-A", h.fs.text("/phone/p/a.jpg"))
        val trash = TrashStore(h.fs, "/phone", h.clock)
        assertEquals(setOf("/phone/p/a.jpg", "/phone/p/b.jpg"), trash.list().map { it.originalPath }.toSet())
        assertEquals(trash.list().first { it.originalPath == "/phone/p/a.jpg" }.id, r.results[0].replacedTrashId)
        // Restore puts the old file back next to the new one (keep both on the clash).
        val back = trash.restore(r.results[0].replacedTrashId!!)
        assertEquals("/phone/p/a (2).jpg", back)
        assertEquals("OLD-A", h.fs.text(back))
        assertEquals("NEW-A", h.fs.text("/phone/p/a.jpg"))
        // The replace never deleted anything.
        assertEquals(emptyList(), h.fs.deletedOriginals())
    }

    @Test
    fun askWithApplyToAllPromptsOnce() {
        val h = setup()
        var asked = 0
        val r = h.engine.run(h.plan(both(h), ConflictSettings(ConflictPolicy.ASK)), prompt = {
            asked++
            ConflictAnswer(ConflictChoice.KEEP_BOTH, applyToAll = true)
        })
        assertOk(r)
        assertEquals(1, asked)
        assertEquals("NEW-B", h.fs.text("/phone/p/b (2).jpg"))
    }

    @Test
    fun askWithoutApplyToAllPromptsEachTime() {
        val h = setup()
        val answers = ArrayList<String>()
        val r = h.engine.run(h.plan(both(h), ConflictSettings(ConflictPolicy.ASK)), prompt = {
            answers += it.targetPath
            if (answers.size == 1) ConflictAnswer(ConflictChoice.SKIP) else ConflictAnswer(ConflictChoice.KEEP_BOTH)
        })
        assertEquals(2, answers.size)
        assertEquals(listOf(StepStatus.SKIPPED, StepStatus.DONE), r.results.map { it.status })
    }

    @Test
    fun askWithNoPromptWiredSkips() {
        val h = setup()
        val r = h.engine.run(h.plan(both(h), ConflictSettings(ConflictPolicy.ASK)))
        assertEquals(listOf(StepStatus.SKIPPED, StepStatus.SKIPPED), r.results.map { it.status })
    }

    @Test
    fun askAnsweredReplaceStillNeedsConsent() {
        val h = setup()
        val r = h.engine.run(h.plan(both(h), ConflictSettings(ConflictPolicy.ASK, replaceConsent = false)), prompt = {
            ConflictAnswer(ConflictChoice.REPLACE, true)
        })
        assertEquals(FileOpsError.CONSENT_REQUIRED, r.results[0].error)
        assertEquals("OLD-A", h.fs.text("/phone/p/a.jpg"))
    }

    @Test
    fun numberingKeepsTheExtensionAndHandlesNoExtension() {
        assertEquals("/d/a.tar (2).gz", ConflictNaming.keepBoth("/d/a.tar.gz") { false })
        assertEquals("/d/README (2)", ConflictNaming.keepBoth("/d/README") { false })
        assertEquals("/d/.hidden (2)", ConflictNaming.keepBoth("/d/.hidden") { false })
        assertEquals("/d/a (4).jpg", ConflictNaming.keepBoth("/d/a.jpg") { it == "/d/a (2).jpg" || it == "/d/a (3).jpg" })
    }
}
