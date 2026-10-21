package com.fotoxplorr.app.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPlannerTest {
    private val mib = 1L shl 20
    private val limits = PackLimits(targetBytes = 10 * mib, hardCapBytes = 30 * mib)
    private val sha = "11".repeat(32)

    private fun photo(
        id: Long,
        name: String = "IMG_$id.jpg",
        dir: String? = "DCIM/Camera/",
        size: Long = 2 * mib,
        modified: Long = 1000 + id,
        taken: Long = 5000 + id,
        quick: String? = null,
        mime: String = "image/jpeg",
    ) = BackupCandidate(
        mediaId = id, displayName = name, relativePath = dir, mimeType = mime, sizeBytes = size,
        dateTakenMillis = taken, dateModifiedSeconds = modified, quickHash = quick,
    )

    private fun recordOf(c: BackupCandidate, entryId: Long, pack: String = "pack-0001.fzbp", flags: Set<EntryFlag> = emptySet()) =
        PlannedCopy(entryId, c, pack).toEntry(sha, flags.contains(EntryFlag.ORIGINAL_LOCATION)).copy(flags = flags)

    private fun plan(
        known: List<ManifestEntry>,
        candidates: List<BackupCandidate>,
        options: BackupOptions = BackupOptions(),
        packs: Set<String> = known.map { it.packName }.toSet(),
    ) = BackupPlanner.plan(known, candidates, options, packs, limits)

    @Test
    fun `a first backup copies everything that is not trashed or empty`() {
        val plan = plan(
            emptyList(),
            listOf(
                photo(1), photo(2), photo(3).copy(isTrashed = true), photo(4, size = 0), photo(5, mime = "video/mp4"),
            ),
        )
        assertEquals(listOf(1L, 2L, 5L), plan.copies.map { it.candidate.mediaId }.sorted())
        assertEquals(listOf(SkipReason.TRASHED, SkipReason.EMPTY_FILE), plan.skipped.map { it.reason })
        assertEquals(6 * mib, plan.bytesToCopy)
        assertEquals(6 * mib, plan.bytesOnPhone)
        assertTrue(plan.kept.isEmpty())
        assertFalse(plan.isUpToDate)
    }

    @Test
    fun `entry ids and pack numbers start at zero and one and never repeat`() {
        val plan = plan(emptyList(), List(7) { photo(it + 1L, size = 4 * mib) })
        assertEquals((0L..6L).toList(), plan.copies.map { it.entryId })
        assertEquals(listOf("pack-0001.fzbp", "pack-0002.fzbp", "pack-0003.fzbp", "pack-0004.fzbp"), plan.packs.map { it.name })
        assertEquals(plan.packs.map { it.name }, plan.packs.map { pack -> pack.copies.map { it.packName }.distinct().single() })
    }

    @Test
    fun `files that are already backed up are not copied again`() {
        val a = photo(1)
        val b = photo(2)
        val known = listOf(recordOf(a, 0), recordOf(b, 1))
        val plan = plan(known, listOf(a, b))
        assertTrue(plan.isUpToDate)
        assertEquals(0, plan.copies.size)
        assertEquals(2, plan.unchanged.size)
        assertEquals(0L, plan.bytesToCopy)
    }

    @Test
    fun `a changed size or modified time copies the file again and drops the old record`() {
        val a = photo(1)
        val b = photo(2)
        val known = listOf(recordOf(a, 0), recordOf(b, 1))
        val plan = plan(known, listOf(a.copy(sizeBytes = a.sizeBytes + 1), b.copy(dateModifiedSeconds = b.dateModifiedSeconds + 1)))
        assertEquals(listOf(2L, 3L), plan.copies.map { it.entryId })
        assertTrue("the superseded records leave the snapshot", plan.kept.isEmpty() && plan.retained.isEmpty())
    }

    @Test
    fun `a pack missing from the destination forces a copy and is reported as lost`() {
        val a = photo(1)
        val b = photo(2)
        val known = listOf(recordOf(a, 0, "pack-0001.fzbp"), recordOf(b, 1, "pack-0002.fzbp"))
        val plan = plan(known, listOf(a, b), packs = setOf("pack-0002.fzbp"))
        assertEquals(listOf(a.mediaId), plan.copies.map { it.candidate.mediaId })
        assertEquals(listOf(0L), plan.lost.map { it.entryId })
        assertEquals(listOf(1L), plan.unchanged.map { it.entryId })
        // the new pack continues after the highest number the set ever used
        assertEquals("pack-0003.fzbp", plan.packs.single().name)
        assertEquals(2L, plan.copies.single().entryId)
    }

    @Test
    fun `a lost record whose file is gone from the phone just disappears`() {
        val known = listOf(recordOf(photo(1), 0, "pack-0001.fzbp"))
        val plan = plan(known, emptyList(), packs = emptySet())
        assertTrue(plan.kept.isEmpty())
        assertEquals(1, plan.lost.size)
    }

    @Test
    fun `a file deleted from the phone stays in the backup`() {
        val gone = photo(1)
        val known = listOf(recordOf(gone, 0))
        val plan = plan(known, listOf(photo(2)))
        assertEquals(listOf(0L), plan.retained.map { it.entryId })
        assertEquals(listOf(0L), plan.kept.map { it.entryId })
        assertEquals(1, plan.copies.size)
    }

    @Test
    fun `a file that became trashed stays in the backup but a new trashed file is not added`() {
        val a = photo(1)
        val plan = plan(listOf(recordOf(a, 0)), listOf(a.copy(isTrashed = true), photo(2).copy(isTrashed = true)))
        assertEquals(listOf(0L), plan.retained.map { it.entryId })
        assertTrue(plan.copies.isEmpty())
        assertEquals(2, plan.skippedFor(SkipReason.TRASHED).size)
    }

    @Test
    fun `protected and sensitive photos follow the options and carry flags when included`() {
        val locked = photo(1).copy(isProtected = true)
        val sensitive = photo(2).copy(isSensitive = true)
        val archived = photo(3).copy(isArchived = true)
        val all = listOf(locked, sensitive, archived)

        val included = plan(emptyList(), all)
        assertEquals(3, included.copies.size)
        val flags = included.copies.associate { it.candidate.mediaId to it.toEntry(sha, true).flags }
        assertEquals(setOf(EntryFlag.ORIGINAL_LOCATION, EntryFlag.WAS_PROTECTED), flags[1L])
        assertEquals(setOf(EntryFlag.ORIGINAL_LOCATION, EntryFlag.WAS_SENSITIVE), flags[2L])
        assertEquals(setOf(EntryFlag.ORIGINAL_LOCATION, EntryFlag.WAS_ARCHIVED), flags[3L])

        val excluded = plan(emptyList(), all, BackupOptions(includeProtected = false, includeSensitive = false))
        assertEquals(listOf(3L), excluded.copies.map { it.candidate.mediaId })
        assertEquals(listOf(SkipReason.PROTECTED_EXCLUDED, SkipReason.SENSITIVE_EXCLUDED), excluded.skipped.map { it.reason })
    }

    @Test
    fun `a lock or sensitive change refreshes the record without copying the bytes again`() {
        val a = photo(1)
        val known = listOf(recordOf(a, 0, flags = setOf(EntryFlag.ORIGINAL_LOCATION)))
        val plan = plan(known, listOf(a.copy(isProtected = true, mediaId = 900)))
        assertTrue(plan.isUpToDate)
        val refreshed = plan.unchanged.single()
        assertEquals(setOf(EntryFlag.ORIGINAL_LOCATION, EntryFlag.WAS_PROTECTED), refreshed.flags)
        assertEquals(900L, refreshed.mediaId)
        assertEquals(0L, refreshed.entryId)
        assertEquals("pack-0001.fzbp", refreshed.packName)
    }

    @Test
    fun `files too large for any pack are reported never silently dropped`() {
        val big = photo(1, size = 31 * mib)
        val plan = plan(emptyList(), listOf(big, photo(2)))
        assertEquals(listOf(SkipReason.TOO_LARGE_FOR_PACK), plan.skipped.map { it.reason })
        assertEquals(1L, plan.skipped.single().candidate.mediaId)
        assertEquals(listOf(2L), plan.copies.map { it.candidate.mediaId })
        assertEquals(2 * mib, plan.bytesOnPhone)
    }

    @Test
    fun `a big file between the target and the cap gets a pack to itself`() {
        val plan = plan(emptyList(), listOf(photo(1, size = 2 * mib), photo(2, size = 25 * mib), photo(3, size = 2 * mib)))
        assertEquals(3, plan.packs.size)
        assertEquals(1, plan.packs.count { it.copies.single().candidate.sizeBytes == 25 * mib })
    }

    @Test
    fun `totals add up`() {
        val a = photo(1)
        val plan = plan(listOf(recordOf(a, 0)), listOf(a, photo(2, size = 3 * mib), photo(3, size = 5 * mib), photo(4).copy(isTrashed = true)))
        assertEquals(a.sizeBytes + 8 * mib, plan.bytesOnPhone)
        assertEquals(8 * mib, plan.bytesToCopy)
        assertEquals(3, plan.copies.size + plan.unchanged.size)
    }

    @Test
    fun `the plan does not depend on the order of its inputs`() {
        val candidates = List(30) { photo(it + 1L, size = (1 + it % 4) * mib, dir = if (it % 2 == 0) "DCIM/A/" else "DCIM/B/") }
        val one = plan(emptyList(), candidates)
        val two = plan(emptyList(), candidates.reversed())
        assertEquals(one.copies.map { it.candidate.mediaId to it.entryId }, two.copies.map { it.candidate.mediaId to it.entryId })
        assertEquals(one.packs.map { it.name }, two.packs.map { it.name })
    }

    @Test
    fun `two files with the same path and name each get their own record`() {
        val first = photo(1, name = "same.jpg", dir = "DCIM/Camera/")
        val second = photo(2, name = "same.jpg", dir = "DCIM/Camera/", modified = 77)
        val firstRun = plan(emptyList(), listOf(first, second))
        assertEquals(2, firstRun.copies.size)
        val known = firstRun.copies.map { it.toEntry(sha, false) }
        val secondRun = plan(known, listOf(first, second))
        assertTrue(secondRun.isUpToDate)
        assertEquals(setOf(0L, 1L), secondRun.unchanged.map { it.entryId }.toSet())
    }

    // ---- resume ----

    @Test
    fun `a run interrupted after some packs resumes from the records those packs left`() {
        val candidates = List(6) { photo(it + 1L, size = 4 * mib) }
        val full = plan(emptyList(), candidates)
        assertEquals(3, full.packs.size)
        // Pack 1 was closed (and its index read back) before the process died.
        val recovered = full.packs.first().copies.map { it.toEntry(sha, true) }
        val resumed = plan(recovered, candidates, packs = setOf("pack-0001.fzbp", "pack-0002.fzbp") /* 2 = a partial leftover */)
        assertEquals(recovered.size, resumed.unchanged.size)
        assertEquals(candidates.size - recovered.size, resumed.copies.size)
        assertTrue("new ids never reuse a recovered one", resumed.copies.all { c -> recovered.none { it.entryId == c.entryId } })
        assertTrue("a leftover pack's name is not reused", resumed.packs.none { it.name == "pack-0002.fzbp" })
    }

    // ---- moves ----

    @Test
    fun `a moved file is recognised by name size date and quick hash and not copied`() {
        val quick = "ee".repeat(32)
        val before = photo(1, dir = "DCIM/Camera/", quick = quick)
        val known = listOf(recordOf(before, 0).copy(quickHash = quick))
        val after = before.copy(relativePath = "Pictures/Holiday/", mediaId = 77, dateModifiedSeconds = 9999)

        val withoutHash = after.copy(quickHash = null)
        assertEquals("without a quick hash a move cannot be proven", 1, plan(known, listOf(withoutHash)).copies.size)
        assertEquals(listOf(withoutHash), BackupPlanner.candidatesNeedingQuickHash(known, listOf(withoutHash), BackupOptions(), setOf("pack-0001.fzbp")))

        val plan = plan(known, listOf(after))
        assertTrue(plan.isUpToDate)
        val moved = plan.moved.single()
        assertEquals("Pictures/Holiday/", moved.relativePath)
        assertEquals(77L, moved.mediaId)
        assertEquals(0L, moved.entryId)
        assertTrue(plan.retained.isEmpty())
    }

    @Test
    fun `a different quick hash or date is a different photo not a move`() {
        val quick = "ee".repeat(32)
        val before = photo(1, quick = quick)
        val known = listOf(recordOf(before, 0).copy(quickHash = quick))
        val moved = before.copy(relativePath = "Pictures/X/")
        assertEquals(1, plan(known, listOf(moved.copy(quickHash = "ff".repeat(32)))).copies.size)
        assertEquals(1, plan(known, listOf(moved.copy(dateTakenMillis = 1))).copies.size)
        assertEquals(1, plan(known, listOf(moved.copy(displayName = "other.jpg"))).copies.size)
    }

    @Test
    fun `a record can be claimed by one move only`() {
        val quick = "ee".repeat(32)
        val before = photo(1, quick = quick)
        val known = listOf(recordOf(before, 0).copy(quickHash = quick))
        val twins = listOf(before.copy(relativePath = "A/", mediaId = 10), before.copy(relativePath = "B/", mediaId = 11))
        val plan = plan(known, twins)
        assertEquals(1, plan.moved.size)
        assertEquals(1, plan.copies.size)
    }

    @Test
    fun `quick hash only matters when both sides have one`() {
        val a = photo(1, quick = "aa".repeat(32))
        val known = listOf(recordOf(a, 0).copy(quickHash = "aa".repeat(32)))
        assertTrue(plan(known, listOf(a.copy(quickHash = null))).isUpToDate)
        assertTrue(plan(known, listOf(a)).isUpToDate)
        assertEquals("same size and time but different bytes", 1, plan(known, listOf(a.copy(quickHash = "bb".repeat(32)))).copies.size)
    }

    // ---- QuickHash ----

    @Test
    fun `quick hash covers the length the head and the tail`() {
        val small = ByteArray(1000) { it.toByte() }
        assertEquals(1000, QuickHash.headLength(1000))
        assertEquals(0, QuickHash.tailLength(1000))
        val h1 = QuickHash.of(1000, small, ByteArray(0))
        assertEquals(64, h1.length)
        assertEquals(h1, QuickHash.of(1000, small.copyOf(), ByteArray(0)))

        val size = 10L * QuickHash.SAMPLE
        assertEquals(QuickHash.SAMPLE, QuickHash.headLength(size))
        assertEquals(QuickHash.SAMPLE, QuickHash.tailLength(size))
        assertEquals(size - QuickHash.SAMPLE, QuickHash.tailOffset(size))
        val head = ByteArray(QuickHash.SAMPLE) { 1 }
        val tail = ByteArray(QuickHash.SAMPLE) { 2 }
        val base = QuickHash.of(size, head, tail)
        assertFalse(base == QuickHash.of(size + 1, head, tail))
        assertFalse(base == QuickHash.of(size, tail, head))
        assertFalse(base == QuickHash.of(size, head, ByteArray(QuickHash.SAMPLE) { 3 }))
    }

    @Test
    fun `quick hash for a file just over one sample has a short tail`() {
        val size = QuickHash.SAMPLE + 100L
        assertEquals(QuickHash.SAMPLE, QuickHash.headLength(size))
        assertEquals(QuickHash.SAMPLE.toLong(), QuickHash.tailOffset(size))
        assertEquals(100, QuickHash.tailLength(size))
    }

    @Test
    fun `quick hash refuses buffers of the wrong size`() {
        try {
            QuickHash.of(1000, ByteArray(999), ByteArray(0))
            org.junit.Assert.fail()
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `a planned copy becomes the manifest line the writer records`() {
        val c = photo(5, dir = null)
        val entry = PlannedCopy(9, c, "pack-0004.fzbp").toEntry(sha, originalLocationIncluded = false, quickHash = "cc".repeat(32))
        assertEquals("", entry.relativePath)
        assertEquals(9L, entry.entryId)
        assertEquals(5L, entry.mediaId)
        assertEquals("pack-0004.fzbp", entry.packName)
        assertTrue(entry.flags.isEmpty())
        // and it is a valid manifest line
        ManifestWriter(StringBuilder()).apply {
            writeHeader(ManifestHeader(1, 0, "x", BackupFormat.MIN_ITERATIONS, BackupFormat.MIN_SEGMENT_SIZE))
            writeEntry(entry)
        }
    }
}
