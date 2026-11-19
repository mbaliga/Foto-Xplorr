package com.fotoxplorr.core.organize.ingest

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IngestRunnerTest {
    private val base = 1_700_000_000_000L
    private val planner = IngestPlanner(DestinationTemplate.parseOrThrow("{yyyy}/{yyyy-MM-dd}"), TimeZone.UTC)

    private fun item(id: String, name: String, at: Long, folder: String = "DCIM/100") = IngestItem(id, name, 0, at, "Cam", folder)

    private fun smallCard(): FakeSource = FakeSource().also { s ->
        s.add(item("r1", "IMG_1.CR3", base), content(1, 3000))
        s.add(item("j1", "IMG_1.JPG", base), content(2, 700))
        s.add(item("j2", "IMG_2.JPG", base + 5000), content(3, 800))
        s.add(item("d2", "IMG_2.JPG", base + 5000, "DCIM/101"), content(3, 800)) // same bytes: duplicate
        s.add(item("j3", "IMG_3.JPG", base + 90_000_000), content(4, 900))
    }

    @Test fun fiveHundredItemCardWithPairsDuplicatesAndASecondDestination() {
        val s = FakeSource()
        var n = 0
        for (i in 0 until 100) { // 100 RAW+JPEG pairs
            val at = base + i * 3_600_000L
            s.add(item("r$i", "IMG_%04d.CR3".replace("%04d", i.toString().padStart(4, '0')), at), content(1000 + i, 2000 + i))
            s.add(item("p$i", "IMG_${i.toString().padStart(4, '0')}.JPG", at), content(2000 + i, 500 + i))
            n += 2
        }
        for (i in 0 until 150) { // 150 singles
            s.add(item("s$i", "DSC_${i.toString().padStart(4, '0')}.JPG", base + 1 + i * 3_600_000L, "DCIM/101"), content(3000 + i, 600 + i))
            n++
        }
        for (i in 0 until 50) { // 50 exact copies of earlier singles under other names
            val o = s.items.first { it.id == "s$i" }
            s.add(o.copy(id = "dup$i", name = "COPY_$i.JPG", folder = "DCIM/102"), s.data.getValue(o.id).copyOf())
            n++
        }
        for (i in 0 until 100) { // 100 more files sharing names with earlier ones in other folders (conflicts)
            s.add(item("c$i", "DSC_${i.toString().padStart(4, '0')}.JPG", base + 2 + i * 3_600_000L, "DCIM/103"), content(5000 + i, 400 + i))
            n++
        }
        assertEquals(500, n)
        val t1 = MemTarget(); val t2 = MemTarget()
        val plan = planner.plan(s, listOf(t1, t2))
        assertEquals(100, plan.pairCount)
        assertEquals(50, plan.files.count { it.disposition == Disposition.SKIP_DUPLICATE_IN_CARD })
        assertEquals(450, plan.toCopy.size)
        assertEquals(plan.toCopy.size, plan.toCopy.map { it.relPath.lowercase() }.toSet().size)
        assertTrue(plan.toCopy.count { it.conflictRenamed } >= 100)

        val journal = InMemoryIngestJournal()
        val report = IngestRunner(s, listOf(t1, t2), journal).run(plan)
        assertTrue(report.complete)
        assertEquals(450, report.copiedItems)
        assertEquals(900, report.verifiedCopies)
        assertEquals(plan.bytesToWrite, report.bytesWritten)
        assertEquals(50, report.skippedInCard)
        assertEquals(100, report.pairs)
        for (t in listOf(t1, t2)) {
            assertEquals(450, t.files.size)
            for (f in plan.toCopy) assertContentEquals(s.data.getValue(f.item.id), t.files.getValue(f.relPath.lowercase()))
            assertTrue(t.overwrites.isEmpty())
        }
        assertTrue(s.deleted.isEmpty(), "the card is untouched by default")
        assertTrue(report.deleteCandidates.isEmpty(), "delete is off by default")
    }

    @Test fun resumeAfterKillAtEveryStepFinishesWithoutDuplicatesOrOverwrites() {
        var reachedEnd = false
        var limit = 1
        while (!reachedEnd && limit < 600) {
            val s = smallCard()
            val chaos = Chaos(limit)
            val t1 = MemTarget(chaos); val t2 = MemTarget(chaos)
            val inner = InMemoryIngestJournal()
            val journal = ChaosJournal(inner, chaos)
            try {
                val plan = planner.plan(s, listOf(t1, t2))
                IngestRunner(s, listOf(t1, t2), journal).run(plan)
                reachedEnd = true
            } catch (k: Kill) {
                // fall through to resume
            }
            // Resume: fresh run objects, same journal and same destination state, no more kills.
            chaos.limit = Int.MAX_VALUE
            val plan2 = planner.plan(s, listOf(t1, t2), prior = inner.plannedPaths())
            val report = IngestRunner(s, listOf(t1, t2), inner).run(plan2)
            assertTrue(report.complete, "limit $limit: ${report.failed}")
            for (t in listOf(t1, t2)) {
                assertEquals(4, t.files.size, "limit $limit: ${t.names.values}")
                assertTrue(t.overwrites.isEmpty(), "limit $limit")
                for (f in plan2.toCopy) assertContentEquals(s.data.getValue(f.item.id), t.files.getValue(f.relPath.lowercase()), "limit $limit ${f.relPath}")
            }
            limit++
        }
        assertTrue(reachedEnd, "the run finished within the loop bound")
        assertTrue(limit > 20, "many distinct kill points were exercised: $limit")
    }

    @Test fun rerunOfFinishedImportCopiesNothingAgain() {
        val s = smallCard()
        val t = MemTarget()
        val journal = InMemoryIngestJournal()
        val plan = planner.plan(s, listOf(t))
        IngestRunner(s, listOf(t), journal).run(plan)
        val before = t.files.size
        val again = IngestRunner(s, listOf(t), journal).run(planner.plan(s, listOf(t), journal.plannedPaths()))
        assertEquals(0, again.bytesWritten)
        assertEquals(before, t.files.size)
        assertTrue(again.complete)
    }

    @Test fun journalSurvivesAsLinesWithATornTail() {
        val lines = ArrayList<String>()
        val j = LineIngestJournal({ lines.add(it) }, { lines.toList() })
        j.append(JournalEntry("a", 0, JournalState.VERIFIED, "x.jpg"))
        lines.add(JournalCodec.encode(JournalEntry("b", 0, JournalState.VERIFIED, "y.jpg")).dropLast(4))
        assertEquals(listOf("a"), j.entries().map { it.itemId })
    }

    @Test fun existingDifferentFileAppearingAfterPlanIsNotOverwritten() {
        val s = FakeSource()
        s.add(item("a", "A.JPG", base), content(1, 500))
        val t = MemTarget()
        val plan = planner.plan(s, listOf(t))
        val theirs = content(99, 40)
        t.put(plan.toCopy.single().relPath, theirs)
        val report = IngestRunner(s, listOf(t), InMemoryIngestJournal()).run(plan)
        assertTrue(report.complete)
        assertEquals(1, report.renamedForConflict)
        assertContentEquals(theirs, t.files.getValue(plan.toCopy.single().relPath.lowercase()))
        assertEquals(2, t.files.size)
        assertTrue(t.overwrites.isEmpty())
    }

    @Test fun identicalFileAlreadyAtTargetCountsAsPresentAndIsNotRewritten() {
        val s = FakeSource()
        s.add(item("a", "A.JPG", base), content(1, 500))
        val t = MemTarget()
        val plan = planner.plan(s, listOf(t))
        t.put(plan.toCopy.single().relPath, content(1, 500))
        val report = IngestRunner(s, listOf(t), InMemoryIngestJournal()).run(plan)
        assertEquals(0, report.bytesWritten)
        assertEquals(1, report.copiedItems)
        assertEquals(1, t.files.size)
    }

    private fun options(on: Boolean) = IngestOptions(offerDeleteFromCard = on)

    @Test fun deleteIsOffByDefaultAndRefusedWhenOff() {
        val s = smallCard(); val t = MemTarget()
        val journal = InMemoryIngestJournal()
        val plan = planner.plan(s, listOf(t))
        val runner = IngestRunner(s, listOf(t), journal)
        val report = runner.run(plan)
        assertTrue(report.deleteCandidates.isEmpty())
        val res = runner.deleteFromCard(plan, plan.toCopy.map { it.item.id }, options(false))
        assertTrue(res.deleted.isEmpty())
        assertTrue(s.deleted.isEmpty())
    }

    @Test fun deleteOnlyWhenEveryCopyVerified() {
        val s = smallCard(); val t1 = MemTarget(); val t2 = MemTarget()
        t2.failFor = { it.contains("IMG_3") }          // second destination fails for j3
        t1.corrupt = { it.contains("IMG_2") }          // first destination corrupts j2: verify mismatch
        val journal = InMemoryIngestJournal()
        val plan = planner.plan(s, listOf(t1, t2))
        val runner = IngestRunner(s, listOf(t1, t2), journal)
        val report = runner.run(plan, options(true))
        assertFalse(report.complete)
        assertEquals(setOf("r1", "j1"), report.deleteCandidates.toSet())
        assertFalse("j2" in report.deleteCandidates || "j3" in report.deleteCandidates)
        assertFalse(t1.exists(plan.files.first { it.item.id == "j2" }.relPath), "own unverified copy was discarded")
        // Forged ids and unverified ids are refused; verified ones go.
        val res = runner.deleteFromCard(plan, listOf("j2", "j3", "d2", "nope", "r1", "j1"), options(true))
        assertEquals(setOf("r1", "j1"), res.deleted.toSet())
        assertEquals(setOf("j2", "j3", "d2", "nope"), res.refused.toSet())
        assertEquals(setOf("r1", "j1"), s.deleted.toSet())
    }

    @Test fun duplicatesSkippedAreNeverOfferedForDeletion() {
        val s = smallCard(); val t = MemTarget()
        val plan = planner.plan(s, listOf(t))
        val runner = IngestRunner(s, listOf(t), InMemoryIngestJournal())
        val report = runner.run(plan, options(true))
        assertFalse("d2" in report.deleteCandidates)
        assertTrue("j2" in report.deleteCandidates)
    }

    @Test fun cardThatCannotDeleteOffersNothing() {
        val s = FakeSource(canDelete = false); s.add(item("a", "A.JPG", base), content(1, 100))
        val t = MemTarget()
        val plan = planner.plan(s, listOf(t))
        assertTrue(IngestRunner(s, listOf(t), InMemoryIngestJournal()).run(plan, options(true)).deleteCandidates.isEmpty())
    }

    @Test fun aFailedFileDoesNotStopTheRestAndRetriesOnRerun() {
        val s = smallCard(); val t = MemTarget()
        t.failFor = { it.contains("IMG_2") }
        val journal = InMemoryIngestJournal()
        val plan = planner.plan(s, listOf(t))
        val first = IngestRunner(s, listOf(t), journal).run(plan)
        assertEquals(1, first.failed.size)
        assertEquals(3, first.copiedItems)
        t.failFor = { false }
        val second = IngestRunner(s, listOf(t), journal).run(planner.plan(s, listOf(t), journal.plannedPaths()))
        assertTrue(second.complete)
        assertEquals(4, t.files.size)
    }
}
