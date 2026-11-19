package com.fotoxplorr.core.index.walker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The cursor is rewritten at every checkpoint and read back through a ~2 MB cursor window: it must stay compact. */
class WalkCursorFormatTest {
    private fun wideTree(dirs: Int): FakeDocumentTree {
        val t = FakeDocumentTree()
        for (i in 0 until dirs) {
            val n = i.toString().padStart(5, '0')
            t.dir("root", "primary:DCIM/Camera/2021/Event-$n", "Event-$n")
        }
        return t
    }

    @Test
    fun theVisitedSetIsStoredAsHashesNotAsIds() {
        val tree = wideTree(30_000)
        val walker = TreeWalker(tree)
        val slice = walker.advance(walker.start(), WalkBudget(maxDirectories = 1))
        assertEquals(30_001, slice.cursor.queued.size)
        val text = slice.cursor.encode()
        val visited = text.lines().filter { it.startsWith("V\t") }.sumOf { it.length }
        assertTrue(visited < 30_001 * 17 + 3000, "visited part is $visited chars")
        assertFalse(text.lines().any { it.startsWith("Q\t") })
    }

    @Test
    fun aNearlyFinishedWalkOfThirtyThousandFoldersEncodesUnderHalfAMegabyte() {
        val tree = wideTree(30_000)
        val walker = TreeWalker(tree)
        val slice = walker.advance(walker.start(), WalkBudget(maxDirectories = 30_000))
        assertTrue(slice.cursor.pending.size <= 2)
        assertTrue(slice.cursor.encode().length < 600_000, "encoded ${slice.cursor.encode().length}")
    }

    @Test
    fun hashesRoundTripAndResumeVisitsTheSameDirectories() {
        val tree = wideTree(50)
        val walker = TreeWalker(tree)
        val mid = walker.advance(walker.start(), WalkBudget(maxDirectories = 20))
        val back = assertNotNull(WalkCursor.decode(mid.cursor.encode()))
        assertEquals(mid.cursor.queued, back.queued)
        assertEquals(mid.cursor.pending, back.pending)
        val status = TreeWalker(tree).run(from = back) { }
        assertEquals(WalkStatus.Complete(true), status)
        assertEquals(tree.queried.size, tree.queried.toSet().size, "no directory listed twice")
    }

    @Test
    fun theFirstFormatStillDecodesAndKeepsItsLoopGuard() {
        val t = FakeDocumentTree()
        val d1 = t.dir("root", "d1")
        t.file(d1, "f1", "one.jpg")
        val d2 = t.dir("root", "d2")
        t.file(d2, "f2", "two.jpg")
        val counters = "C" + "\t0".repeat(13)
        val v1 = "fotoz-walk-1\nR\troot\n$counters\nP\t1\td2\td2\nQ\troot\nQ\td1\nQ\td2\n"
        val cursor = assertNotNull(WalkCursor.decode(v1))
        assertEquals(3, cursor.queued.size)
        val seen = ArrayList<String>()
        val status = TreeWalker(t).run(from = cursor) { slice -> seen += slice.files.map { it.name } }
        assertEquals(WalkStatus.Complete(true), status)
        assertEquals(listOf("two.jpg"), seen)
        assertFalse("d1" in t.queried)
        assertTrue(cursor.encode().startsWith("fotoz-walk-2\n"))
    }

    @Test
    fun malformedHashLinesAreRejected() {
        val counters = "C" + "\t0".repeat(13)
        assertNull(WalkCursor.decode("fotoz-walk-2\nR\troot\n$counters\nV\t1234\n"))
        assertNull(WalkCursor.decode("fotoz-walk-2\nR\troot\n$counters\nV\t" + "zz".repeat(8) + "\n"))
        assertNull(WalkCursor.decode("fotoz-walk-2\nR\troot\n$counters\nQ\troot\n"))
    }
}
