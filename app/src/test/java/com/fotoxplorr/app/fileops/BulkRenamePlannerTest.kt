package com.fotoxplorr.app.fileops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Collision safety for bulk rename — the part of this feature that actually matters. A pattern
 * that expands wrong just looks odd; a plan that hands two photos the same target name, or hands
 * one photo the name of an untouched neighbour, is a MediaStore rename that silently overwrites
 * and a file that is simply gone. Every test here asserts that never happens, not just that the
 * happy path produces nice-looking names.
 */
class BulkRenamePlannerTest {

    private fun subject(name: String) = RenameSubject(name, dateTakenMillis = 0L, dateModifiedSeconds = 0L)

    @Test
    fun `two photos never resolve to the same final name`() {
        // The classic naive-implementation failure: a pattern with no differentiating token at
        // all, applied to more than one photo.
        val subjects = listOf(subject("a.jpg"), subject("b.jpg"), subject("c.jpg"))

        val plan = BulkRenamePlanner.plan("Holiday", subjects)

        val names = plan.map { it.finalName }
        assertEquals("no two photos may share a final name", names.size, names.toSet().size)
        assertEquals(listOf("Holiday.jpg", "Holiday (2).jpg", "Holiday (3).jpg"), names)
    }

    @Test
    fun `a rename never reuses a name an untouched file already has`() {
        val subjects = listOf(subject("a.jpg"))

        val plan = BulkRenamePlanner.plan(
            "Holiday",
            subjects,
            existingNames = setOf("Holiday.jpg"), // owned by a photo NOT in this selection
        )

        assertNotEquals("Holiday.jpg", plan.single().finalName)
        assertEquals("Holiday (2).jpg", plan.single().finalName)
    }

    @Test
    fun `collision checking is case-insensitive`() {
        // External storage is commonly a case-insensitive volume; "Holiday.jpg" and
        // "holiday.JPG" are the same file there even though the two strings differ.
        val subjects = listOf(subject("a.jpg"))
        val plan = BulkRenamePlanner.plan("Holiday", subjects, existingNames = setOf("holiday.JPG"))
        assertEquals("Holiday (2).jpg", plan.single().finalName)
    }

    @Test
    fun `same stem but different extensions do not collide with each other`() {
        val subjects = listOf(subject("a.jpg"), subject("b.png"))
        val plan = BulkRenamePlanner.plan("Cover", subjects)
        // Genuinely different files (different extension = different final name), so neither
        // should be pushed into a "(2)" it does not need.
        assertEquals(listOf("Cover.jpg", "Cover.png"), plan.map { it.finalName })
    }

    @Test
    fun `a counter token keeps every name distinct without needing the collision suffix`() {
        val subjects = List(40) { subject("IMG_%05d.jpg".format(it)) }
        val plan = BulkRenamePlanner.plan("Trip_{counter:3}", subjects)

        val names = plan.map { it.finalName }
        assertEquals(40, names.toSet().size)
        assertEquals("Trip_001.jpg", names.first())
        assertEquals("Trip_040.jpg", names.last())
        assertTrue("a counter pattern should never need a (2) suffix", names.none { it.contains('(') })
    }

    @Test
    fun `extension is preserved exactly even though the pattern never mentions it`() {
        val subjects = listOf(subject("photo.HEIC"), subject("clip.PNG"))
        val plan = BulkRenamePlanner.plan("Renamed_{counter}", subjects)
        assertEquals(listOf("Renamed_1.HEIC", "Renamed_2.PNG"), plan.map { it.finalName })
    }

    @Test
    fun `a photo with no extension is not given one`() {
        val subjects = listOf(subject("noext"))
        assertEquals("Renamed", BulkRenamePlanner.plan("Renamed", subjects).single().finalName)
    }

    @Test
    fun `illegal filesystem characters in the pattern are sanitised`() {
        val subjects = listOf(subject("a.jpg"))
        val plan = BulkRenamePlanner.plan("Trip: Day/One?", subjects)
        assertEquals("Trip_ Day_One_.jpg", plan.single().finalName)
    }

    @Test
    fun `an empty selection plans nothing and does not throw`() {
        assertEquals(emptyList<BulkRenamePlanner.PlannedName>(), BulkRenamePlanner.plan("Holiday", emptyList()))
    }

    @Test
    fun `a blank pattern is rejected outright`() {
        val error = runCatching { BulkRenamePlanner.plan("   ", listOf(subject("a.jpg"))) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `a negative starting number is rejected outright`() {
        val error = runCatching {
            BulkRenamePlanner.plan("{counter}", listOf(subject("a.jpg")), startAt = -1)
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `a large batch with no differentiating token still produces zero collisions`() {
        // The pattern is deliberately pathological: no counter, no date, no {orig} — every photo
        // wants the identical stem. This is the stress case for the (2), (3), ... suffix search.
        val subjects = List(200) { subject("photo_$it.jpg") }
        val plan = BulkRenamePlanner.plan("Same", subjects)

        val names = plan.map { it.finalName }
        assertEquals(200, names.toSet().size)
        assertEquals("Same.jpg", names.first())
        assertEquals("Same (200).jpg", names.last())
    }

    @Test
    fun `existing names in the destination and in-batch collisions are both respected together`() {
        val subjects = listOf(subject("a.jpg"), subject("b.jpg"), subject("c.jpg"))
        val plan = BulkRenamePlanner.plan(
            "Holiday",
            subjects,
            existingNames = setOf("Holiday (2).jpg"), // pre-occupies the SECOND slot, not the first
        )
        // First photo gets the bare name (free), second must skip the pre-occupied "(2)" and land
        // on "(3)", third continues from there rather than colliding with the second.
        assertEquals(listOf("Holiday.jpg", "Holiday (3).jpg", "Holiday (4).jpg"), plan.map { it.finalName })
    }
}
