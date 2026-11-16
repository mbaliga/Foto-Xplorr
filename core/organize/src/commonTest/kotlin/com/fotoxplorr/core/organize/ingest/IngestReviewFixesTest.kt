package com.fotoxplorr.core.organize.ingest

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Regression tests for the independent review of the ingest core (findings 5 and 11 to 14). */
class IngestReviewFixesTest {
    private val base = 1_700_000_000_000L
    private val planner = IngestPlanner(DestinationTemplate.parseOrThrow("{yyyy}/{yyyy-MM-dd}"), TimeZone.UTC)
    private fun item(id: String, name: String, at: Long = base, folder: String = "DCIM/100") = IngestItem(id, name, 0, at, "Cam", folder)
    private val on = IngestOptions(offerDeleteFromCard = true)

    private fun oneFileCard(id: String = "a", seed: Int = 1): FakeSource =
        FakeSource().also { it.add(item(id, "IMG_1.JPG"), content(seed, 3000)) }

    // ---- 5: the delete gate ----

    @Test fun aStaleVerifiedLineWithoutAMatchingFingerprintIsNotTrusted() {
        val s = oneFileCard()
        val t = MemTarget()
        val plan = planner.plan(s, listOf(t))
        val rel = plan.toCopy.single().relPath
        // A VERIFIED line for the same id and destination left by ANOTHER card (or an old journal): no fingerprint.
        val journal = InMemoryIngestJournal()
        journal.append(JournalEntry("a", 0, JournalState.VERIFIED, rel))
        val runner = IngestRunner(s, listOf(t), journal)
        val report = runner.run(plan, on)
        assertTrue(t.exists(rel), "the file must really be copied, not skipped on the strength of a foreign id")
        assertEquals(listOf("a"), report.deleteCandidates)
    }

    @Test fun aVerifiedLineOfAnotherFingerprintIsNotTrusted() {
        val s = oneFileCard()
        val t = MemTarget()
        val plan = planner.plan(s, listOf(t))
        val rel = plan.toCopy.single().relPath
        val journal = InMemoryIngestJournal()
        journal.append(JournalEntry("a", 0, JournalState.VERIFIED, rel, "", "9999-1-x-y", 9999))
        val res = IngestRunner(s, listOf(t), journal).deleteFromCard(plan, listOf("a"), on)
        assertEquals(listOf("a"), res.refused)
        assertTrue(s.deleted.isEmpty())
    }

    @Test fun deleteReReadsTheCopyAndRefusesAfterItWasTamperedWith() {
        val s = oneFileCard()
        val t = MemTarget()
        val plan = planner.plan(s, listOf(t))
        val runner = IngestRunner(s, listOf(t), InMemoryIngestJournal())
        runner.run(plan, on)
        val rel = plan.toCopy.single().relPath
        val bad = content(1, 3000).also { it[1500] = (it[1500] + 1).toByte() }
        t.put(rel, bad) // the copy changed on the destination after it verified
        val res = runner.deleteFromCard(plan, listOf("a"), on)
        assertEquals(listOf("a"), res.refused)
        assertTrue(s.deleted.isEmpty())
    }

    @Test fun deleteRefusesWhenTheCopyWentMissingOrTheCardItemChanged() {
        val s = oneFileCard()
        val t = MemTarget()
        val plan = planner.plan(s, listOf(t))
        val runner = IngestRunner(s, listOf(t), InMemoryIngestJournal())
        runner.run(plan, on)
        val rel = plan.toCopy.single().relPath
        t.discardOwnUnverified(rel)
        assertEquals(listOf("a"), runner.deleteFromCard(plan, listOf("a"), on).refused)
        t.put(rel, content(1, 3000))
        s.data["a"] = content(77, 3000) // the card item now has other bytes than the verified copy
        assertEquals(listOf("a"), runner.deleteFromCard(plan, listOf("a"), on).refused)
        assertTrue(s.deleted.isEmpty())
    }

    @Test fun deleteStillWorksForAnUntouchedVerifiedCopy() {
        val s = oneFileCard()
        val t = MemTarget()
        val plan = planner.plan(s, listOf(t))
        val journal = InMemoryIngestJournal()
        val runner = IngestRunner(s, listOf(t), journal)
        runner.run(plan, on)
        assertEquals(listOf("a"), runner.deleteFromCard(plan, listOf("a"), on).deleted)
        assertEquals(listOf("a"), s.deleted)
        // A second delete of the same id does not trust the CARD_DELETED line blindly: the card item is not re-deleted twice by accident.
        assertTrue(journal.entries().any { it.state == JournalState.CARD_DELETED && it.fingerprint.isNotEmpty() })
    }

    @Test fun twoCardsWithTheSameItemIdsDoNotShareJournalLines() {
        val journal = InMemoryIngestJournal()
        val cardA = oneFileCard(seed = 1)
        val ta = MemTarget()
        val planA = planner.plan(cardA, listOf(ta))
        IngestRunner(cardA, listOf(ta), journal, scope = "cardA").run(planA, on)

        val cardB = oneFileCard(seed = 2) // same id "a", other bytes
        val tb = MemTarget()
        val planB = planner.plan(cardB, listOf(tb))
        val runnerB = IngestRunner(cardB, listOf(tb), journal, scope = "cardB")
        runnerB.run(planB, on)
        assertTrue(tb.exists(planB.toCopy.single().relPath))
        assertEquals(emptyMap(), journal.plannedPaths("cardC"))
        assertEquals(1, journal.plannedPaths("cardA").size)
        assertEquals(1, journal.plannedPaths("cardB").size)
    }

    @Test fun theOlderSixFieldJournalLineStillDecodes() {
        val old = listOf("a", "0", "VERIFIED", "x/y.jpg", "", "#").joinToString("\t")
        val e = assertNotNull(JournalCodec.decode(old))
        assertEquals(JournalState.VERIFIED, e.state)
        assertEquals("", e.fingerprint)
        val full = JournalEntry("a", 1, JournalState.VERIFIED, "p\tq", "d", "fp", 42, "s1")
        assertEquals(full, JournalCodec.decode(JournalCodec.encode(full)))
    }

    // ---- 13: one journal read ----

    private class CountingJournal(val inner: IngestJournal) : IngestJournal {
        var reads = 0
        override fun append(entry: JournalEntry) = inner.append(entry)
        override fun entries(): List<JournalEntry> { reads++; return inner.entries() }
    }

    @Test fun theRunnerReadsTheJournalOncePerRunNotOncePerCopy() {
        val s = FakeSource()
        for (i in 0 until 30) s.add(item("i$i", "IMG_$i.JPG", base + i * 1000L), content(i, 400))
        val t1 = MemTarget(); val t2 = MemTarget()
        val plan = planner.plan(s, listOf(t1, t2))
        val journal = CountingJournal(InMemoryIngestJournal())
        val report = IngestRunner(s, listOf(t1, t2), journal).run(plan)
        assertTrue(report.complete)
        assertEquals(60, report.verifiedCopies)
        assertEquals(1, journal.reads)
    }

    // ---- 11: conflict suffix on long names ----

    @Test fun conflictNumbersSurviveVeryLongNames() {
        val s = FakeSource()
        val long = "a".repeat(250)
        s.add(item("a", "$long.JPG", base), content(1, 500))
        s.add(item("b", "$long.JPG", base + 1, "DCIM/101"), content(2, 500))
        s.add(item("c", "$long.JPG", base + 2, "DCIM/102"), content(3, 500))
        val plan = planner.plan(s, listOf(MemTarget()))
        assertEquals(3, plan.toCopy.size, "no item may fall through to SKIP_NO_FREE_NAME")
        assertEquals(3, plan.toCopy.map { it.relPath.lowercase() }.toSet().size)
        for (f in plan.toCopy) assertTrue(NameSanitizer.utf8Length(f.relPath.substringAfterLast('/')) <= NameSanitizer.MAX_BYTES + 8)
        assertTrue(plan.toCopy.any { it.relPath.contains(" (2).JPG") } && plan.toCopy.any { it.relPath.contains(" (3).JPG") })
    }

    @Test fun fileNameKeepsTheSuffixWhateverTheStemLength() {
        val n = NameSanitizer.fileName("é".repeat(300), "jpg", suffix = " (9999)")
        assertTrue(n.endsWith(" (9999).jpg"), n)
        assertTrue(NameSanitizer.utf8Length(n) <= NameSanitizer.MAX_BYTES)
    }

    // ---- 12: absurd capture times ----

    @Test fun absurdCaptureTimesFallBackToUnknownDate() {
        val tpl = DestinationTemplate.parseOrThrow("{yyyy}/{yyyy-MM-dd}")
        for (ms in listOf(Long.MAX_VALUE, Long.MIN_VALUE, -9_000_000_000_000_000L, 9_000_000_000_000_000L)) {
            val ctx = TemplateContext(ms, TimeZone.UTC, "Cam", 1, "IMG", "jpg")
            assertEquals(listOf("Unknown date", "Unknown date"), tpl.expand(ctx), "ms=$ms")
        }
        val ok = tpl.expand(TemplateContext(base, TimeZone.UTC, "Cam", 1, "IMG", "jpg"))
        assertEquals(listOf("2023", "2023-11-14"), ok)
    }

    @Test fun aPlanSurvivesOneFileWithAGarbageDate() {
        val s = FakeSource()
        s.add(item("a", "A.JPG", Long.MAX_VALUE), content(1, 300))
        s.add(item("b", "B.JPG", base), content(2, 300))
        val plan = planner.plan(s, listOf(MemTarget()))
        assertEquals(2, plan.toCopy.size)
        assertTrue(plan.toCopy.first { it.item.id == "a" }.relPath.startsWith("Unknown date/"))
    }

    // ---- 14: the full hash is SHA-256 ----

    @Test fun fullHashIsSha256() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Fingerprints.full(MemReader("abc".encodeToByteArray())),
        )
        assertEquals(64, Fingerprints.full(MemReader(ByteArray(0))).length)
        assertFalse(Fingerprints.full(MemReader(ByteArray(5))) == Fingerprints.full(MemReader(ByteArray(6))))
    }
}
