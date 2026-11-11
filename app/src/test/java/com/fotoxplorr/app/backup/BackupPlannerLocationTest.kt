package com.fotoxplorr.app.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A photo copied while the location could not be read is stored with its GPS cut out. Once the
 * permission is granted the planner must copy it again, and keep the old record until the new copy
 * is really written; before, "already backed up" was forever and the geotags stayed lost.
 */
class BackupPlannerLocationTest {
    private val mib = 1L shl 20
    private val limits = PackLimits(targetBytes = 10 * mib, hardCapBytes = 30 * mib)
    private val sha = "11".repeat(32)

    private fun photo(id: Long, quick: String? = null) = BackupCandidate(
        mediaId = id, displayName = "IMG_$id.jpg", relativePath = "DCIM/Camera/", mimeType = "image/jpeg", sizeBytes = 2 * mib,
        dateTakenMillis = 5000 + id, dateModifiedSeconds = 1000 + id, quickHash = quick,
    )

    private fun record(c: BackupCandidate, entryId: Long, withLocation: Boolean, quick: String? = null) =
        PlannedCopy(entryId, c, "pack-0001.fzbp").toEntry(sha, withLocation, quick)

    private fun plan(known: List<ManifestEntry>, candidates: List<BackupCandidate>, locationReadable: Boolean) =
        BackupPlanner.plan(known, candidates, BackupOptions(), known.map { it.packName }.toSet(), limits, locationReadable = locationReadable)

    @Test
    fun `without the permission a copy made without location is up to date, as before`() {
        val a = photo(1)
        val plan = plan(listOf(record(a, 0, withLocation = false)), listOf(a), locationReadable = false)
        assertTrue(plan.isUpToDate)
        assertEquals(0, plan.locationRecopies)
    }

    @Test
    fun `once the location is readable a copy made without it is copied again`() {
        val a = photo(1)
        val b = photo(2)
        val old = record(a, 0, withLocation = false)
        val plan = plan(listOf(old, record(b, 1, withLocation = true)), listOf(a, b), locationReadable = true)

        assertEquals(listOf(1L), plan.copies.map { it.candidate.mediaId })
        assertEquals(1, plan.locationRecopies)
        assertEquals("the one with its location is left alone", listOf(2L), plan.unchanged.map { it.mediaId })
        assertEquals("the old record leaves the snapshot only when the new copy is written", listOf(old), plan.superseded)
    }

    @Test
    fun `the old record stays until the new copy is written`() {
        val a = photo(1)
        val old = record(a, 0, withLocation = false)
        val plan = plan(listOf(old), listOf(a), locationReadable = true)

        // The copy failed or never ran: the snapshot still has the old record.
        assertEquals(listOf(old), plan.nextEntries(emptyList()))
        // The copy was written: it replaces the old record.
        val fresh = plan.copies.single().toEntry(sha, originalLocationIncluded = true)
        assertEquals(listOf(fresh), plan.nextEntries(listOf(fresh)))
    }

    @Test
    fun `a record that has its location is never copied again for that reason`() {
        val a = photo(1)
        val plan = plan(listOf(record(a, 0, withLocation = true)), listOf(a), locationReadable = true)
        assertTrue(plan.isUpToDate)
        assertEquals(0, plan.locationRecopies)
    }

    @Test
    fun `a moved file recorded without its location is copied again, not carried over`() {
        val before = photo(1, quick = "q1")
        val old = record(before, 0, withLocation = false, quick = "q1")
        val moved = before.copy(mediaId = 9, relativePath = "Pictures/Album/")
        val plan = plan(listOf(old), listOf(moved), locationReadable = true)
        assertEquals(listOf(9L), plan.copies.map { it.candidate.mediaId })
        assertTrue(plan.moved.isEmpty())
        // ...whereas one recorded with its location is recognised as a move.
        val withLocation = plan(listOf(record(before, 0, withLocation = true, quick = "q1")), listOf(moved), locationReadable = true)
        assertTrue(withLocation.copies.isEmpty())
        assertEquals(1, withLocation.moved.size)
    }

    /**
     * What a folder looks like from run 3 on: the manifest lists only the packs its entries still
     * use, so the pack of the first run is out of it but still in the folder with its valid index,
     * and the loader hands its records back as recovered ones.
     */
    private fun nextRun(manifest: List<ManifestEntry>, recovered: List<ManifestEntry>, candidates: List<BackupCandidate>): BackupPlan {
        val known = manifest + recovered
        return BackupPlanner.plan(known, candidates, BackupOptions(), known.map { it.packName }.toSet(), limits, locationReadable = true)
    }

    private fun written(plan: BackupPlan, withLocation: Boolean) = plan.copies.map { it.toEntry(sha, withLocation) }

    @Test
    fun `a location copy converges over runs even when the old pack comes back as a recovered one`() {
        val a = photo(1)
        val b = photo(2)
        // Run 1: the location could not be read.
        val run1 = BackupPlanner.plan(emptyList(), listOf(a, b), BackupOptions(), emptySet(), limits, locationReadable = false)
        val first = written(run1, withLocation = false)
        assertEquals(2, first.size)

        // Run 2: it can be, so both are copied again into a new pack, and the manifest drops the old records.
        val run2 = nextRun(first, emptyList(), listOf(a, b))
        assertEquals(2, run2.copies.size)
        assertEquals(2, run2.locationRecopies)
        val second = written(run2, withLocation = true)
        val manifest2 = run2.nextEntries(second)
        assertEquals(second, manifest2)
        assertTrue("the new pack is not the old one", run2.packs.none { pack -> pack.name in first.map { it.packName } })

        // Run 3 and 4: the old pack is still in the folder, its index is read back, and nothing is copied.
        val run3 = nextRun(manifest2, first, listOf(a, b))
        assertTrue("run 3: ${run3.copies.map { it.candidate.mediaId }}", run3.isUpToDate)
        assertEquals(0, run3.locationRecopies)
        assertEquals(second.map { it.entryId }, run3.unchanged.map { it.entryId }.sorted())
        val manifest3 = run3.nextEntries(emptyList())
        assertEquals("the superseded copies do not come back into the snapshot", second, manifest3.sortedBy { it.entryId })
        val run4 = nextRun(manifest3, first, listOf(a, b))
        assertTrue("run 4", run4.isUpToDate)
        assertEquals(second, run4.nextEntries(emptyList()).sortedBy { it.entryId })
    }

    @Test
    fun `a newer record with the location is used even when an older one without it comes first`() {
        val a = photo(1)
        val old = PlannedCopy(0, a, "pack-0001.fzbp").toEntry(sha, false)
        val fresh = PlannedCopy(1, a, "pack-0002.fzbp").toEntry(sha, true)
        val plan = plan(listOf(old, fresh), listOf(a), locationReadable = true)
        assertTrue(plan.isUpToDate)
        assertEquals(0, plan.locationRecopies)
        assertEquals(listOf(1L), plan.unchanged.map { it.entryId })
    }

    @Test
    fun `a file is counted as a location copy only when every record of it lacks the location`() {
        val a = photo(1)
        val one = PlannedCopy(0, a, "pack-0001.fzbp").toEntry(sha, false)
        val two = PlannedCopy(1, a, "pack-0002.fzbp").toEntry(sha, false)
        val plan = plan(listOf(one, two), listOf(a), locationReadable = true)
        assertEquals(1, plan.copies.size)
        assertEquals("one file, one copy, however many records it had", 1, plan.locationRecopies)
        assertEquals(listOf(0L, 1L), plan.superseded.map { it.entryId })
    }

    @Test
    fun `without the permission the record that has the location is the one kept`() {
        val a = photo(1)
        val old = PlannedCopy(0, a, "pack-0001.fzbp").toEntry(sha, false)
        val fresh = PlannedCopy(1, a, "pack-0002.fzbp").toEntry(sha, true)
        val plan = plan(listOf(old, fresh), listOf(a), locationReadable = false)
        assertTrue(plan.isUpToDate)
        assertEquals(listOf(1L), plan.unchanged.map { it.entryId })
        assertEquals("the weaker copy of the same file is not carried on", listOf(fresh), plan.nextEntries(emptyList()))
    }

    @Test
    fun `the preflight says how many files will be copied again`() {
        val text = BackupCopy.preflight(facts(locationRecopies = 3)).map { it.text }
        assertTrue(text.toString(), text.contains("3 files were backed up without their location and will be copied again."))
        val one = BackupCopy.preflight(facts(locationRecopies = 1)).map { it.text }
        assertTrue(one.toString(), one.contains("1 file was backed up without its location and will be copied again."))
        assertTrue(BackupCopy.preflight(facts(locationRecopies = 0)).none { it.text.contains("without") && it.text.contains("copied again") })
    }

    @Test
    fun `the preflight mentions fewer tags and files of another backup only when they apply`() {
        val plain = BackupCopy.preflight(facts()).map { it.text }
        assertTrue(plain.none { it == BackupCopy.SECTIONS_FEWER })
        assertTrue(plain.none { it.contains("different backup") })
        val both = BackupCopy.preflight(facts(sectionsFewer = true, foreignCount = 2)).map { it.text }
        assertTrue(both.contains(BackupCopy.SECTIONS_FEWER))
        assertTrue(both.toString(), both.any { it.startsWith("2 files in this folder belong to a different backup") })
        assertTrue(BackupCopy.preflight(facts(foreignCount = 1)).any { it.text.startsWith("1 file in this folder belongs to a different backup") })
    }

    private fun facts(locationRecopies: Int = 0, foreignCount: Int = 0, sectionsFewer: Boolean = false) = BackupCopy.PreflightFacts(
        newFiles = 3, newSourceBytes = 3_000_000, estimatedBytes = 3_100_000, packs = 1, alreadyBackedUp = 2,
        tooLargeNames = emptyList(), tooLargeCount = 0, leftoverCount = 0, leftoverBytes = 0, freeBytes = 64_000_000_000,
        photosSeen = 5, locationApplicable = true, locationGranted = true, onPhoneStorage = false, managedByAnotherApp = false,
        batteryLowAndNotCharging = false, newSet = false, locationRecopies = locationRecopies, foreignCount = foreignCount,
        sectionsFewer = sectionsFewer,
    )
}
