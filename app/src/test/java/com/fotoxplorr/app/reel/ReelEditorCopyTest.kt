package com.fotoxplorr.app.reel

import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The words of the reel screens: short, trimmed, one sentence (owner feedback #6), and the few that are built from numbers. */
class ReelEditorCopyTest {
    private val strings: List<Pair<String, String>> = ReelCopy::class.java.declaredFields
        .filter { it.type == String::class.java && java.lang.reflect.Modifier.isStatic(it.modifiers) }
        .map { it.isAccessible = true; it.name to it.get(null) as String }

    @Test
    fun `every fixed word is short, trimmed and not empty`() {
        assertTrue("found ${strings.size} strings", strings.size > 100)
        for ((name, text) in strings) {
            assertTrue("$name is empty", text.isNotBlank())
            assertEquals("$name has spaces at an end", text.trim(), text)
            assertTrue("$name is long (${text.length}): $text", text.length <= 70)
        }
    }

    @Test
    fun `a sentence is one sentence`() {
        for ((name, text) in strings) {
            assertFalse("$name is more than one sentence: $text", text.dropLast(1).contains(". "))
        }
    }

    @Test
    fun `no word names the machinery`() {
        val banned = listOf("codec", "Media3", "Transformer", "mux", "MediaStore", "exception", "H.264", "AAC", "pixel", "bitmap", "URI", "JSON", "cache")
        for ((name, text) in strings) for (word in banned) assertFalse("$name mentions $word", text.contains(word, ignoreCase = true))
    }

    @Test
    fun `time is written as minutes and seconds, rounded to the nearest second`() {
        assertEquals("0:00", ReelCopy.clock(0))
        assertEquals("0:03", ReelCopy.clock(3_000))
        assertEquals("0:04", ReelCopy.clock(3_500))
        assertEquals("1:05", ReelCopy.clock(65_000))
        assertEquals("0:00", ReelCopy.clock(-5))
        assertEquals("0:03 of 0:12", ReelCopy.clockOf(3_000, 12_000))
        assertEquals("12:00", ReelCopy.clock(720_000))
    }

    @Test
    fun `seconds are whole when they are whole`() {
        assertEquals("3 s", ReelCopy.seconds(3_000))
        assertEquals("3.5 s", ReelCopy.seconds(3_500))
        assertEquals("0.5 s", ReelCopy.seconds(500))
        assertEquals("0 s", ReelCopy.seconds(-1))
        assertEquals("Lasts 2.5 s", ReelCopy.lastsFor(2_500))
        assertEquals("Starts at 1 s", ReelCopy.clipStarts(1_000))
        assertEquals("Ends at 4.5 s", ReelCopy.clipEnds(4_500))
    }

    @Test
    fun `the lengths on offer read as words`() {
        assertEquals(listOf("15 seconds", "30 seconds", "1 minute", "2 minutes"), ReelDrafting.LENGTHS.map(ReelCopy::lengthChoice))
    }

    @Test
    fun `counts agree with their nouns`() {
        assertEquals("1 item, 0:03", ReelCopy.reelSummary(1, 3_000))
        assertEquals("5 items, 0:12", ReelCopy.reelSummary(5, 12_000))
        assertEquals("1 item is no longer in the library.", ReelCopy.missingItems(1))
        assertEquals("3 items are no longer in the library.", ReelCopy.missingItems(3))
        assertEquals("None chosen.", ReelCopy.itemsChosen(0))
        assertEquals("2 chosen.", ReelCopy.itemsChosen(2))
        assertEquals("Add", ReelCopy.addItems(0))
        assertEquals("Add 4", ReelCopy.addItems(4))
        assertEquals("Added 3.", ReelCopy.itemsAdded(3, 0))
        assertEquals("Added 3; 1 too short to use.", ReelCopy.itemsAdded(3, 1))
        assertEquals("Item 2 of 5", ReelCopy.itemPosition(2, 5))
        assertEquals("1920 by 1080, 0:30", ReelCopy.madeNote(1920, 1080, 30_000))
        assertEquals("720p", ReelCopy.sizeChoice(720))
    }

    @Test
    fun `a month is written for the language and a screen reader gets it in full`() {
        assertEquals("Mar 2026", ReelCopy.month(2026, 3, Locale.US))
        assertEquals("March 2026, 3 items", ReelCopy.monthDescription(2026, 3, 3, Locale.US))
        assertEquals("March 2026, 1 item", ReelCopy.monthDescription(2026, 3, 1, Locale.US))
    }

    @Test
    fun `every move and hand-over has its own name`() {
        val moves = MotionChoice.entries.map(ReelCopy::motionChoice)
        assertEquals(moves.size, moves.toSet().size)
        val hands = ReelTransition.entries.map(ReelCopy::transition)
        assertEquals(hands.size, hands.toSet().size)
        val shapes = ReelAspect.entries.map(ReelCopy::aspect)
        assertEquals(shapes.size, shapes.toSet().size)
    }

    @Test
    fun `an item is named by its kind, number and time`() {
        assertEquals("Photo 2, 3 s", ReelCopy.itemLabel(ReelCopy.ITEM_STILL, 2, 3_000))
        assertEquals("Delete My reel", ReelCopy.deleteReel(""))
        assertEquals("Delete Beach", ReelCopy.deleteReel("Beach"))
        assertEquals(ZoneOffset.UTC.id, "Z")
    }
}
