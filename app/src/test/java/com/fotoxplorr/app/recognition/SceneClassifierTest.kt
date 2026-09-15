package com.fotoxplorr.app.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneClassifierTest {

    private fun labels(vararg pairs: Pair<String, Float>) =
        pairs.map { ImageLabel(it.first, it.second) }

    private fun centredFace(relativeArea: Float = 0.2f) =
        SceneFace(relativeArea = relativeArea, centerXFraction = 0.5f, centerYFraction = 0.5f)

    @Test
    fun `no labels and no faces yields no categories`() {
        assertEquals(emptySet<SceneCategory>(), SceneClassifier.classify(emptyList(), faceCount = 0))
    }

    @Test
    fun `flower labels tag flora`() {
        val categories = SceneClassifier.classify(labels("Flower" to 0.8f, "Plant" to 0.7f), faceCount = 0)
        assertTrue(SceneCategory.FLORA in categories)
    }

    @Test
    fun `weak label below the confidence bar does not tag`() {
        val categories = SceneClassifier.classify(labels("Flower" to 0.4f), faceCount = 0)
        assertTrue(SceneCategory.FLORA !in categories)
    }

    @Test
    fun `a photo can carry several categories at once`() {
        // A cake at a birthday party -- FOOD and EVENT are both true of the same photo.
        val categories = SceneClassifier.classify(
            labels("Cake" to 0.9f, "Birthday" to 0.85f), faceCount = 0,
        )
        assertTrue(SceneCategory.FOOD in categories)
        assertTrue(SceneCategory.EVENT in categories)
    }

    @Test
    fun `label matching is case and whitespace insensitive`() {
        val categories = SceneClassifier.classify(labels("  CAR  " to 0.9f), faceCount = 0)
        assertTrue(SceneCategory.VEHICLE in categories)
    }

    @Test
    fun `three or more faces is a group photo regardless of labels`() {
        assertTrue(SceneCategory.GROUP_PHOTO in SceneClassifier.classify(emptyList(), faceCount = 3))
        assertTrue(SceneCategory.GROUP_PHOTO in SceneClassifier.classify(emptyList(), faceCount = 5))
    }

    @Test
    fun `two faces is not yet a group photo`() {
        assertFalse(SceneCategory.GROUP_PHOTO in SceneClassifier.classify(emptyList(), faceCount = 2))
    }

    @Test
    fun `one large centred face on a plain scene is an official portrait`() {
        val categories = SceneClassifier.classify(
            labels("Person" to 0.9f, "Face" to 0.7f),
            faceCount = 1,
            faces = listOf(centredFace()),
        )
        assertTrue(SceneCategory.PORTRAIT_OFFICIAL in categories)
    }

    @Test
    fun `a second face rules out an official portrait even if one face is large and centred`() {
        val categories = SceneClassifier.classify(
            emptyList(),
            faceCount = 1,
            // faceCount says 1 but two SceneFace entries were handed in -- classify trusts
            // faceCount as ground truth and singleOrNull() on faces rejects the mismatch.
            faces = listOf(centredFace(), centredFace()),
        )
        assertFalse(SceneCategory.PORTRAIT_OFFICIAL in categories)
    }

    @Test
    fun `a small face is not an official portrait`() {
        val categories = SceneClassifier.classify(
            emptyList(), faceCount = 1,
            faces = listOf(SceneFace(relativeArea = 0.02f, centerXFraction = 0.5f, centerYFraction = 0.5f)),
        )
        assertFalse(SceneCategory.PORTRAIT_OFFICIAL in categories)
    }

    @Test
    fun `an off-centre face is not an official portrait`() {
        val categories = SceneClassifier.classify(
            emptyList(), faceCount = 1,
            faces = listOf(SceneFace(relativeArea = 0.2f, centerXFraction = 0.1f, centerYFraction = 0.5f)),
        )
        assertFalse(SceneCategory.PORTRAIT_OFFICIAL in categories)
    }

    @Test
    fun `a busy background disqualifies an otherwise official-looking portrait`() {
        val busyLabels = labels(
            "Person" to 0.9f, "Crowd" to 0.8f, "Building" to 0.7f, "Tree" to 0.65f,
            "Car" to 0.6f, "Sky" to 0.6f,
        )
        val categories = SceneClassifier.classify(busyLabels, faceCount = 1, faces = listOf(centredFace()))
        assertFalse(SceneCategory.PORTRAIT_OFFICIAL in categories)
    }

    @Test
    fun `missing face geometry never fires the official-portrait rule`() {
        // faceCount alone, no SceneFace list -- the conservative default for callers with no
        // face position data (see the classify() KDoc).
        val categories = SceneClassifier.classify(labels("Person" to 0.9f), faceCount = 1)
        assertFalse(SceneCategory.PORTRAIT_OFFICIAL in categories)
    }

    @Test
    fun `withRecurringPeopleContext adds friends-family only within the face range and with recurrence`() {
        val base = emptySet<SceneCategory>()
        assertTrue(
            SceneCategory.FRIENDS_FAMILY in
                SceneClassifier.withRecurringPeopleContext(base, faceCount = 2, hasRecurringPerson = true),
        )
        assertFalse(
            SceneCategory.FRIENDS_FAMILY in
                SceneClassifier.withRecurringPeopleContext(base, faceCount = 2, hasRecurringPerson = false),
        )
        // Outside the 1..4 band -- a crowd of 6 recurring people reads as a group/event, not "family".
        assertFalse(
            SceneCategory.FRIENDS_FAMILY in
                SceneClassifier.withRecurringPeopleContext(base, faceCount = 6, hasRecurringPerson = true),
        )
    }

    @Test
    fun `withRecurringPeopleContext preserves the base set`() {
        val base = setOf(SceneCategory.EVENT)
        val result = SceneClassifier.withRecurringPeopleContext(base, faceCount = 2, hasRecurringPerson = true)
        assertEquals(setOf(SceneCategory.EVENT, SceneCategory.FRIENDS_FAMILY), result)
    }
}
