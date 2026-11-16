package com.fotoxplorr.app.sources.previews

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewPolicyTest {
    private fun e(row: Long, bytes: Long, access: Long) = PreviewEntry(row, 1, bytes, access)

    @Test
    fun `a name round trips and anything else is not ours`() {
        assertEquals("12-1700000000.webp", PreviewPolicy.fileName(12, 1_700_000_000))
        assertEquals(12L to 1_700_000_000L, PreviewPolicy.parseName("12-1700000000.webp"))
        assertEquals(5L to -3L, PreviewPolicy.parseName(PreviewPolicy.fileName(5, -3)))
        listOf("index.tsv", "12-1.webp.tmp", "notes.txt", "0-5.webp", "x-5.webp", "12.webp", "12-.webp").forEach {
            assertNull(it, PreviewPolicy.parseName(it))
        }
    }

    @Test
    fun `under the cap nothing is evicted and a cap of zero evicts everything`() {
        val all = listOf(e(1, 400, 10), e(2, 500, 20))
        assertTrue(PreviewPolicy.evictionPlan(all, 900).isEmpty())
        assertEquals(setOf(1L, 2L), PreviewPolicy.evictionPlan(all, 0).toSet())
        assertTrue(PreviewPolicy.isOff(0) && PreviewPolicy.isOff(-5) && !PreviewPolicy.isOff(1))
    }

    @Test
    fun `the least recently used go first until the rest fit`() {
        val all = listOf(e(1, 400, 30), e(2, 400, 10), e(3, 400, 20))
        assertEquals(listOf(2L), PreviewPolicy.evictionPlan(all, 800))
        assertEquals(listOf(2L, 3L), PreviewPolicy.evictionPlan(all, 400))
    }

    @Test
    fun `ties break by row so the plan is deterministic`() {
        val all = listOf(e(9, 100, 5), e(3, 100, 5), e(6, 100, 5))
        assertEquals(listOf(3L), PreviewPolicy.evictionPlan(all, 200))
    }

    @Test
    fun `the preview just written survives even when it alone is over the cap`() {
        val all = listOf(e(1, 900, 1), e(2, 900, 99))
        assertEquals(listOf(1L), PreviewPolicy.evictionPlan(all, 100, keepAssetRow = 2))
    }

    @Test
    fun `the ledger total is always the sum and a new revision replaces the old`() {
        val l = PreviewLedger()
        l.put(e(1, 100, 1)); l.put(e(2, 50, 2))
        assertEquals(150L, l.totalBytes)
        val old = l.put(PreviewEntry(1, 2, 70, 3))
        assertEquals(1L, old?.revision)
        assertEquals(120L, l.totalBytes)
        assertEquals(2, l.count)
        l.remove(2)
        assertEquals(70L, l.totalBytes)
        l.touch(1, 99)
        assertEquals(99L, l.get(1)?.lastAccessMs)
        l.clear()
        assertEquals(0L, l.totalBytes)
    }

    @Test
    fun `the index text round trips and a damaged line is skipped`() {
        val l = PreviewLedger(listOf(e(1, 10, 5), e(2, 20, 6)))
        assertEquals(l.entries.toList(), PreviewLedger.decode(l.encode()))
        val damaged = l.encode() + "garbage\n3\tx\t1\t1\n0\t1\t1\t1\n4\t1\t-1\t1\n"
        assertEquals(2, PreviewLedger.decode(damaged).size)
    }

    @Test
    fun `the default cap is one gigabyte`() {
        assertEquals(1_000_000_000L, PreviewPolicy.DEFAULT_CAP_BYTES)
    }
}
