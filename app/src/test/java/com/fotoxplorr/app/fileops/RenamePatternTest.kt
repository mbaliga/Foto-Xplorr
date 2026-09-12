package com.fotoxplorr.app.fileops

import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

/** The bulk-rename token language, checked token by token. */
class RenamePatternTest {

    // 2026-08-21 14:05:09 UTC exactly, so date-token assertions are exact rather than "close to now".
    private val takenAt = 1_787_321_109_000L

    private fun subject(name: String, dateTakenMillis: Long = takenAt, dateModifiedSeconds: Long = 0L) =
        RenameSubject(name, dateTakenMillis, dateModifiedSeconds)

    @Test
    fun `a base name plus a padded counter increments per photo`() {
        val subjects = listOf(subject("a.jpg"), subject("b.jpg"), subject("c.jpg"))

        val result = RenamePattern.expand("Holiday_{counter:3}", subjects, startAt = 1)

        assertEquals(listOf("Holiday_001", "Holiday_002", "Holiday_003"), result)
    }

    @Test
    fun `an unpadded counter has no leading zeros`() {
        val subjects = List(11) { subject("img$it.jpg") }
        val result = RenamePattern.expand("P{counter}", subjects, startAt = 8)
        // 8, 9, 10, 11, ... — never zero-padded to a common width.
        assertEquals(listOf("P8", "P9", "P10", "P11") + (12..18).map { "P$it" }, result)
    }

    @Test
    fun `startAt controls where the counter begins`() {
        val subjects = listOf(subject("a.jpg"), subject("b.jpg"))
        assertEquals(listOf("100", "101"), RenamePattern.expand("{counter}", subjects, startAt = 100))
    }

    @Test
    fun `counter beyond its padding width is not truncated`() {
        val subjects = listOf(subject("a.jpg"))
        assertEquals(listOf("1000"), RenamePattern.expand("{counter:3}", subjects, startAt = 1000))
    }

    @Test
    fun `date tokens are read from dateTaken in the given zone`() {
        val subjects = listOf(subject("a.jpg"))
        val result = RenamePattern.expand(
            "{yyyy}-{MM}-{dd}_{HH}{mm}{ss}",
            subjects,
            zoneId = ZoneOffset.UTC,
        )
        assertEquals(listOf("2026-08-21_140509"), result)
    }

    @Test
    fun `the two-digit year token takes the last two digits`() {
        val result = RenamePattern.expand("{yy}", listOf(subject("a.jpg")), zoneId = ZoneOffset.UTC)
        assertEquals(listOf("26"), result)
    }

    @Test
    fun `a photo with no capture date falls back to its modified time`() {
        val modifiedSeconds = 1_700_000_000L // a fixed, known instant
        val subjects = listOf(subject("a.jpg", dateTakenMillis = 0L, dateModifiedSeconds = modifiedSeconds))

        val fromModified = RenamePattern.expand("{yyyy}{MM}{dd}", subjects, zoneId = ZoneOffset.UTC)
        val expected = RenamePattern.expand(
            "{yyyy}{MM}{dd}",
            listOf(subject("a.jpg", dateTakenMillis = modifiedSeconds * 1000L)),
            zoneId = ZoneOffset.UTC,
        )
        assertEquals(expected, fromModified)
    }

    @Test
    fun `orig expands to the original name without its extension`() {
        val subjects = listOf(subject("Vacation Photo.HEIC"))
        assertEquals(listOf("Vacation Photo_edited"), RenamePattern.expand("{orig}_edited", subjects))
    }

    @Test
    fun `orig on a file with no extension is the whole name`() {
        val subjects = listOf(subject("noextension"))
        assertEquals(listOf("noextension_x"), RenamePattern.expand("{orig}_x", subjects))
    }

    @Test
    fun `the extension is never part of what the pattern produces`() {
        // The pattern text has no {ext} token at all, and none is implicitly appended — the
        // extension is BulkRenamePlanner's job, deliberately, not this function's.
        val subjects = listOf(subject("photo.HEIC"))
        assertEquals(listOf("Holiday_001"), RenamePattern.expand("Holiday_{counter:3}", subjects))
    }

    @Test
    fun `an unrecognised token is left visible rather than silently deleted`() {
        val subjects = listOf(subject("a.jpg"))
        assertEquals(listOf("Trip_{nonsense}_1"), RenamePattern.expand("Trip_{nonsense}_{counter}", subjects))
    }

    @Test
    fun `literal text with no tokens at all passes through unchanged for every photo`() {
        val subjects = listOf(subject("a.jpg"), subject("b.jpg"))
        assertEquals(listOf("Holiday", "Holiday"), RenamePattern.expand("Holiday", subjects))
    }

    @Test
    fun `multiple tokens combine in one pattern`() {
        val subjects = listOf(subject("a.jpg"))
        val result = RenamePattern.expand("{yyyy}_{orig}_{counter:2}", subjects, zoneId = ZoneOffset.UTC)
        assertEquals(listOf("2026_a_01"), result)
    }
}
