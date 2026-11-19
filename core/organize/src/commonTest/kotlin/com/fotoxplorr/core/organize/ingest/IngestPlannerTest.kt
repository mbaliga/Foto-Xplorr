package com.fotoxplorr.core.organize.ingest

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IngestPlannerTest {
    private val base = 1_700_000_000_000L
    private fun planner(rule: RenameRule = IdentityRenameRule, known: KnownFingerprints = KnownFingerprints.None, tpl: String = "{yyyy}/{yyyy-MM-dd}") =
        IngestPlanner(DestinationTemplate.parseOrThrow(tpl), TimeZone.UTC, rule, known)

    private fun item(id: String, name: String, at: Long = base, folder: String = "DCIM/100", camera: String = "Cam") =
        IngestItem(id, name, 0, at, camera, folder)

    @Test fun rawAndJpegStayTogetherWithOneStemAndOneNumber() {
        val s = FakeSource()
        s.add(item("a", "IMG_0001.CR3"), content(1, 5000))
        s.add(item("b", "IMG_0001.JPG"), content(2, 900))
        s.add(item("c", "IMG_0002.JPG", base + 1000), content(3, 900))
        val plan = planner(PatternRenameRule.create("{yyyy-MM-dd}_{seq}").getOrThrow()).plan(s, listOf(MemTarget()))
        assertEquals(1, plan.pairCount)
        val pair = plan.groups.first { it.isPair }
        assertEquals(setOf("2023/2023-11-14/2023-11-14_0001.CR3", "2023/2023-11-14/2023-11-14_0001.JPG"), pair.files.map { it.relPath }.toSet())
        assertEquals("2023/2023-11-14/2023-11-14_0002.JPG", plan.files.first { it.item.id == "c" }.relPath)
    }

    @Test fun sameStemInDifferentFoldersIsNotAPair() {
        val s = FakeSource()
        s.add(item("a", "IMG_0001.CR3", folder = "DCIM/100"), content(1, 500))
        s.add(item("b", "IMG_0001.JPG", base + 5, folder = "DCIM/101"), content(2, 500))
        assertEquals(0, planner().plan(s, listOf(MemTarget())).pairCount)
    }

    @Test fun caseInsensitiveCollisionsKeepBoth() {
        val s = FakeSource()
        s.add(item("a", "A.JPG", base), content(1, 500))
        s.add(item("b", "a.jpg", base + 5, folder = "DCIM/101"), content(2, 500))
        s.add(item("c", "A.jpg", base + 9, folder = "DCIM/102"), content(3, 500))
        val plan = planner().plan(s, listOf(MemTarget()))
        val paths = plan.toCopy.map { it.relPath }
        assertEquals(3, paths.map { it.lowercase() }.toSet().size)
        assertTrue(paths.any { it.endsWith("a (2).jpg") } && paths.any { it.endsWith("A (3).jpg") }, paths.toString())
        assertEquals(2, plan.toCopy.count { it.conflictRenamed })
    }

    @Test fun existingFileOnEitherTargetIsNeverOverwritten() {
        val s = FakeSource()
        s.add(item("a", "X.JPG"), content(1, 500))
        val one = MemTarget(); val two = MemTarget()
        two.put("2023/2023-11-14/x.jpg", content(9, 10))
        val plan = planner().plan(s, listOf(one, two))
        assertEquals("2023/2023-11-14/X (2).JPG", plan.toCopy.single().relPath)
    }

    @Test fun duplicatesAreSkippedByFingerprintAndMiddleDifferenceIsNot() {
        val s = FakeSource()
        val big = content(7, 300_000)
        s.add(item("a", "A.JPG"), big)
        s.add(item("b", "B.JPG", folder = "DCIM/101"), big.copyOf())
        s.add(item("c", "C.JPG", folder = "DCIM/102"), big.copyOf().also { it[150_000] = (it[150_000] + 1).toByte() })
        val plan = planner().plan(s, listOf(MemTarget()))
        assertEquals(Disposition.COPY, plan.files.first { it.item.id == "a" }.disposition)
        val b = plan.files.first { it.item.id == "b" }
        assertEquals(Disposition.SKIP_DUPLICATE_IN_CARD, b.disposition)
        assertEquals("a", b.duplicateOf)
        assertEquals(Disposition.COPY, plan.files.first { it.item.id == "c" }.disposition)
        assertEquals(300_000L, plan.bytesSkipped)
    }

    @Test fun libraryKnownFingerprintSkips() {
        val s = FakeSource()
        val bytes = content(4, 4000)
        s.add(item("a", "A.JPG"), bytes)
        s.add(item("b", "B.JPG", base + 9), content(5, 4000))
        val known = KnownFingerprints { key ->
            if (key == Fingerprints.cheap(MemReader(bytes), base)) listOf(FullHashProvider { Fingerprints.full(MemReader(bytes)) }) else emptyList()
        }
        val plan = planner(known = known).plan(s, listOf(MemTarget()))
        assertEquals(Disposition.SKIP_ALREADY_IN_LIBRARY, plan.files.first { it.item.id == "a" }.disposition)
        assertEquals(Disposition.COPY, plan.files.first { it.item.id == "b" }.disposition)
    }

    @Test fun planListsBytesPerDestination() {
        val s = FakeSource()
        s.add(item("a", "A.JPG"), content(1, 1000))
        s.add(item("b", "B.JPG", base + 9), content(2, 2000))
        val one = planner().plan(s, listOf(MemTarget()))
        val two = planner().plan(s, listOf(MemTarget(), MemTarget()))
        assertEquals(3000, one.bytesToWrite)
        assertEquals(6000, two.bytesToWrite)
    }

    @Test fun hostileCardNamesAreSanitisedInThePlan() {
        val s = FakeSource()
        s.add(item("a", "..\\..\\evil:name?.jpg", camera = "../../x"), content(1, 300))
        s.add(item("b", "CON.JPG", base + 9), content(2, 300))
        s.add(item("c", "写真" + "é".repeat(300) + ".JPG", base + 19), content(3, 300))
        val plan = planner(tpl = "{camera}/{yyyy}").plan(s, listOf(MemTarget()))
        for (f in plan.toCopy) {
            assertTrue(f.relPath.split('/').none { it == ".." || it == "." || it.isEmpty() }, f.relPath)
            assertTrue(f.relPath.split('/').all { NameSanitizer.utf8Length(it) <= NameSanitizer.MAX_BYTES }, f.relPath)
            assertTrue('\\' !in f.relPath && ':' !in f.relPath && '?' !in f.relPath)
        }
        assertTrue(plan.toCopy.any { it.relPath.endsWith("/_CON.JPG") })
    }
}
