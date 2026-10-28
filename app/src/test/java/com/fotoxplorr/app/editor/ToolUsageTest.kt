package com.fotoxplorr.app.editor

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolUsageTest {

    @Test
    fun `cold start has no scores and encodes to just the header`() {
        assertTrue(ToolUsage.EMPTY.isEmpty)
        assertEquals(0, ToolUsage.EMPTY.score(ToolIds.EXPOSURE))
        assertEquals("tu1", ToolUsage.EMPTY.encode())
    }

    @Test
    fun `one use is worth one thousand and an unknown id is ignored`() {
        val once = ToolUsage.EMPTY.record(ToolIds.CROP)
        assertEquals(ToolUsage.ONE_USE, once.score(ToolIds.CROP))
        assertEquals(once, once.record("no_such_tool"))
        assertEquals(ToolUsage.EMPTY, ToolUsage.EMPTY.record(""))
    }

    @Test
    fun `every use of another tool decays a score by three percent`() {
        val u = ToolUsage.EMPTY.record(ToolIds.CROP).record(ToolIds.TINT)
        assertEquals(970, u.score(ToolIds.CROP))
        assertEquals(1_000, u.score(ToolIds.TINT))
        val v = u.record(ToolIds.TINT)
        assertEquals(940, v.score(ToolIds.CROP)) // 970 * 97 / 100, rounded down
        assertEquals(1_970, v.score(ToolIds.TINT))
    }

    @Test
    fun `a score halves in about two dozen uses of something else`() {
        var u = ToolUsage.EMPTY.record(ToolIds.CROP)
        repeat(23) { u = u.record(ToolIds.TINT) }
        val s = u.score(ToolIds.CROP)
        assertTrue("score $s", s in 450..520)
    }

    @Test
    fun `a forgotten tool leaves the table`() {
        var u = ToolUsage.EMPTY.record(ToolIds.CROP)
        repeat(150) { u = u.record(ToolIds.TINT) }
        assertEquals(0, u.score(ToolIds.CROP))
        assertFalse(ToolIds.CROP in u.ids)
    }

    @Test
    fun `a score is bounded however much one tool is used`() {
        var u = ToolUsage.EMPTY
        repeat(2_000) { u = u.record(ToolIds.EXPOSURE) }
        assertTrue(u.score(ToolIds.EXPOSURE) <= ToolUsage.MAX_MILLI)
        assertTrue(u.score(ToolIds.EXPOSURE) > 30_000)
    }

    @Test
    fun `the table never holds more than the catalogue has`() {
        val random = Random(7)
        var u = ToolUsage.EMPTY
        repeat(5_000) { u = u.record(ToolCatalog.all[random.nextInt(ToolCatalog.all.size)].id) }
        assertTrue(u.ids.size <= ToolCatalog.all.size)
    }

    @Test
    fun `the same sequence of uses always gives the same scores`() {
        fun run(): ToolUsage {
            val random = Random(42)
            var u = ToolUsage.EMPTY
            repeat(400) { u = u.record(ToolCatalog.all[random.nextInt(ToolCatalog.all.size)].id) }
            return u
        }
        assertEquals(run(), run())
        assertEquals(run().encode(), run().encode())
    }

    @Test
    fun `codec round trip is exact for a seeded history`() {
        val random = Random(1234)
        var u = ToolUsage.EMPTY
        repeat(300) { u = u.record(ToolCatalog.all[random.nextInt(ToolCatalog.all.size)].id) }
        val text = u.encode()
        assertFalse(text.contains('\n'))
        assertEquals(u, ToolUsage.decode(text))
        assertEquals(text, ToolUsage.decode(text).encode())
    }

    @Test
    fun `encode is independent of the order of use`() {
        val a = ToolUsage.decode("tu1;crop=1200;exposure=900")
        val b = ToolUsage.decode("tu1;exposure=900;crop=1200")
        assertEquals(a, b)
        assertEquals("tu1;crop=1200;exposure=900", b.encode())
    }

    @Test
    fun `corrupt input gives empty and never throws`() {
        val bad = listOf(
            null, "", " ", "garbage", "tu2;crop=1000", "TU1;crop=1000", ";crop=1000", "tu1crop=1000",
            "\u0000\u0001\u0002", "tu1;" + "x".repeat(ToolUsage.MAX_ENCODED_CHARS),
        )
        for (text in bad) assertEquals("input: $text", ToolUsage.EMPTY, ToolUsage.decode(text))
        // A header alone is a valid, empty history.
        assertEquals(ToolUsage.EMPTY, ToolUsage.decode("tu1"))
    }

    @Test
    fun `one bad entry is skipped and its neighbours kept`() {
        val u = ToolUsage.decode(
            "tu1;crop=1000;=5;exposure;contrast=abc;highlights=;shadows=-300;whites=10;blacks=2=3;" +
                "no_such_tool=900;Heal=900;heal=2000;heal=3000;;tint=" + Int.MAX_VALUE + "9",
        )
        assertEquals(listOf("crop", "heal"), u.ids)
        assertEquals(1_000, u.score(ToolIds.CROP))
        assertEquals(2_000, u.score(ToolIds.HEAL)) // a repeated id keeps its first value
    }

    @Test
    fun `an absurd score is clamped not trusted`() {
        val u = ToolUsage.decode("tu1;crop=2000000000")
        assertEquals(ToolUsage.MAX_MILLI, u.score(ToolIds.CROP))
    }

    @Test
    fun `a truncated write still reads as a prefix of what was written`() {
        val full = ToolUsage.EMPTY.record(ToolIds.CROP).record(ToolIds.EXPOSURE).record(ToolIds.HEAL).encode()
        for (cut in 0..full.length) {
            val read = ToolUsage.decode(full.substring(0, cut))
            // Whatever a cut leaves, it is some subset of the real scores or a clamped prefix of one digit run:
            // never a crash, never an id the original did not have.
            assertTrue(read.ids.all { it in ToolUsage.decode(full).ids })
        }
        assertNotEquals(ToolUsage.EMPTY, ToolUsage.decode(full))
    }
}
