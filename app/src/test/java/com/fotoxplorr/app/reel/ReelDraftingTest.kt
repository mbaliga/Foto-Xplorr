package com.fotoxplorr.app.reel

import com.fotoxplorr.app.reel.ReelScreenKit.DAY
import com.fotoxplorr.app.reel.ReelScreenKit.photo
import com.fotoxplorr.app.reel.ReelScreenKit.video
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Starting a reel from the library: the months to choose from, the pile a draft may use, and the draft itself. */
class ReelDraftingTest {
    private val utc = ZoneOffset.UTC

    private fun at(year: Int, month: Int, day: Int, hour: Int = 12) =
        ZonedDateTime.of(year, month, day, hour, 0, 0, 0, utc).toInstant().toEpochMilli()

    private val library = listOf(
        photo(1, at(2026, 3, 2)), photo(2, at(2026, 3, 2, 13)), photo(3, at(2026, 3, 20)),
        photo(4, at(2026, 1, 5)), video(5, 8_000L, at(2026, 1, 5, 14)),
        photo(6, at(2025, 12, 31, 23)),
        photo(7, 0L), // no date taken
        photo(8, at(2026, 3, 9)).copy(isTrashed = true),
    )

    @Test
    fun `the months are those with something in them, newest first, with counts`() {
        val months = ReelDrafting.months(library, utc)
        assertEquals(listOf("2026-03", "2026-01", "2025-12"), months.map { it.code })
        assertEquals(listOf(3, 2, 1), months.map { it.count })
    }

    @Test
    fun `no more months are offered than the limit`() {
        val many = (0 until 60).map { photo(it + 1L, at(2020 + it / 12, it % 12 + 1, 10)) }
        assertEquals(ReelDrafting.MAX_MONTHS, ReelDrafting.months(many, utc).size)
        assertEquals("2024-12", ReelDrafting.months(many, utc).first().code)
    }

    @Test
    fun `choosing months keeps what was taken in them and none chosen keeps everything`() {
        assertEquals(setOf(4L, 5L), ReelDrafting.inMonths(library, setOf("2026-01"), utc).map { it.id.value }.toSet())
        assertEquals(setOf(1L, 2L, 3L, 6L), ReelDrafting.inMonths(library, setOf("2026-03", "2025-12"), utc).map { it.id.value }.toSet().intersect(setOf(1L, 2L, 3L, 6L)))
        assertEquals(library.size, ReelDrafting.inMonths(library, emptySet(), utc).size)
    }

    @Test
    fun `a month is read in the zone it is asked in`() {
        // 23:30 on the last day of the year in UTC is already next year in Tokyo.
        val late = ZonedDateTime.of(2025, 12, 31, 23, 30, 0, 0, utc).toInstant().toEpochMilli()
        assertEquals("2025-12", ReelDrafting.codeOf(ReelDrafting.monthOf(late, utc)))
        assertEquals("2026-01", ReelDrafting.codeOf(ReelDrafting.monthOf(late, java.time.ZoneId.of("Asia/Tokyo"))))
    }

    @Test
    fun `candidates leave out the trashed and the undated, and keep what the draft needs`() {
        val c = ReelDrafting.candidatesOf(library)
        assertEquals(setOf(1L, 2L, 3L, 4L, 5L, 6L), c.map { it.mediaId }.toSet())
        val v = c.first { it.mediaId == 5L }
        assertTrue(v.isVideo)
        assertEquals(8_000L, v.durationMs)
    }

    @Test
    fun `the draft is a reel that can be made, within the length asked, and the same twice`() {
        val pile = (1..40L).map { photo(it, at(2026, 3, 1) + it * 90_000L + (it / 10) * DAY) } + video(100, 9_000L, at(2026, 3, 2))
        val a = ReelDrafting.draft(pile, emptySet(), 30_000L, ReelAspect.TALL, utc)
        val b = ReelDrafting.draft(pile, emptySet(), 30_000L, ReelAspect.TALL, utc)
        assertEquals(a.project, b.project)
        assertTrue(a.project.items.isNotEmpty())
        assertEquals(ReelAspect.TALL, a.project.aspect)
        assertTrue(ReelPlan.canRender(a.project))
        assertTrue("within the length asked: ${a.project.totalDurationMs}", a.project.totalDurationMs <= 30_000L)
        assertTrue(a.report.picked in 1..a.report.considered)
        assertEquals(a.project.items.size, a.project.items.map { it.id }.toSet().size)
    }

    @Test
    fun `a draft over chosen months uses only those photos`() {
        val d = ReelDrafting.draft(library, setOf("2026-01"), 15_000L, ReelAspect.WIDE, utc)
        val used = d.project.items.mapNotNull { (it as? StillItem)?.mediaId ?: (it as? ClipItem)?.mediaId }
        assertTrue(used.isNotEmpty())
        assertTrue(used.all { it == 4L || it == 5L })
    }

    @Test
    fun `a draft from nothing is an empty reel, not a failure`() {
        val d = ReelDrafting.draft(emptyList(), emptySet(), 30_000L, ReelAspect.WIDE, utc)
        assertTrue(d.project.items.isEmpty())
    }

    @Test
    fun `the lengths offered are ordered and inside what the draft accepts`() {
        assertEquals(ReelDrafting.LENGTHS.sorted(), ReelDrafting.LENGTHS)
        assertTrue(ReelDrafting.LENGTHS.all { it in ReelDraftOptions.MIN_TARGET_MS..ReelDraftOptions.MAX_TARGET_MS })
        assertTrue(ReelDrafting.LENGTHS.contains(ReelDraftOptions.DEFAULT_TARGET_MS))
    }
}
