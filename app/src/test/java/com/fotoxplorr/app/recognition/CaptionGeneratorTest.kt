package com.fotoxplorr.app.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionGeneratorTest {

    @Test
    fun `flowers in a garden reproduces the spec example exactly`() {
        val result = CaptionGenerator.generate(
            labels = listOf("Flower", "Plant", "Garden"),
            categories = setOf(SceneCategory.FLORA),
            faceCount = 0,
        )
        assertEquals("Flowers in a garden", result.caption)
        assertEquals(listOf("#flowers", "#garden", "#nature"), result.hashtags)
    }

    @Test
    fun `flora without a place word falls back to a plain photo-of sentence`() {
        val result = CaptionGenerator.generate(
            labels = listOf("Flower"),
            categories = setOf(SceneCategory.FLORA),
            faceCount = 0,
        )
        assertEquals("A photo of flowers", result.caption)
        assertEquals(listOf("#flowers", "#nature"), result.hashtags)
    }

    @Test
    fun `no labels and no faces is an honest empty caption`() {
        val result = CaptionGenerator.generate(emptyList(), emptySet(), faceCount = 0)
        assertEquals("A photo", result.caption)
        assertTrue(result.hashtags.isEmpty())
    }

    @Test
    fun `three or more faces reads as a group photo and outranks scene labels`() {
        val result = CaptionGenerator.generate(
            labels = listOf("Flower"),
            categories = setOf(SceneCategory.FLORA, SceneCategory.GROUP_PHOTO),
            faceCount = 3,
        )
        assertEquals("A group photo of three people", result.caption)
        // The scene category is still relevant even though it lost the caption sentence itself.
        // Hashtag order follows SceneCategory's own declaration order (FLORA before
        // GROUP_PHOTO), not the order categories happen to be listed in the call above.
        assertEquals(listOf("#nature", "#friends"), result.hashtags)
    }

    @Test
    fun `a large group falls back to a plain number past the named ones`() {
        val result = CaptionGenerator.generate(emptyList(), setOf(SceneCategory.GROUP_PHOTO), faceCount = 9)
        assertEquals("A group photo of 9 people", result.caption)
    }

    @Test
    fun `an official portrait gets its own caption regardless of labels`() {
        val result = CaptionGenerator.generate(
            labels = listOf("Person", "Face"),
            categories = setOf(SceneCategory.PORTRAIT_OFFICIAL),
            faceCount = 1,
        )
        assertEquals("A portrait photo", result.caption)
        assertTrue(result.hashtags.isEmpty())
    }

    @Test
    fun `one person without the official-portrait category is a plain people caption`() {
        val result = CaptionGenerator.generate(labels = emptyList(), categories = emptySet(), faceCount = 1)
        assertEquals("A photo of one person", result.caption)
    }

    @Test
    fun `two people is its own caption`() {
        val result = CaptionGenerator.generate(labels = emptyList(), categories = emptySet(), faceCount = 2)
        assertEquals("A photo of two people", result.caption)
    }

    @Test
    fun `fauna caption uses an article and the animals hashtag`() {
        val result = CaptionGenerator.generate(
            labels = listOf("Dog", "Grass"),
            categories = setOf(SceneCategory.FAUNA),
            faceCount = 0,
        )
        assertEquals("A photo of a dog", result.caption)
        assertEquals(listOf("#dog", "#animals"), result.hashtags)
    }

    @Test
    fun `an animal starting with a vowel gets 'an'`() {
        val result = CaptionGenerator.generate(
            labels = listOf("Elephant"),
            categories = setOf(SceneCategory.FAUNA),
            faceCount = 0,
        )
        assertEquals("A photo of an elephant", result.caption)
    }

    @Test
    fun `food caption has no article`() {
        val result = CaptionGenerator.generate(
            labels = listOf("Pizza"),
            categories = setOf(SceneCategory.FOOD),
            faceCount = 0,
        )
        assertEquals("A photo of pizza", result.caption)
        assertEquals(listOf("#pizza", "#food"), result.hashtags)
    }

    @Test
    fun `vehicle caption`() {
        val result = CaptionGenerator.generate(
            labels = listOf("Car"),
            categories = setOf(SceneCategory.VEHICLE),
            faceCount = 0,
        )
        assertEquals("A photo of a car", result.caption)
        assertEquals(listOf("#car", "#travel"), result.hashtags)
    }

    @Test
    fun `architecture caption`() {
        val result = CaptionGenerator.generate(
            labels = listOf("Bridge"),
            categories = setOf(SceneCategory.ARCHITECTURE),
            faceCount = 0,
        )
        assertEquals("A photo of a bridge", result.caption)
        assertEquals(listOf("#bridge", "#architecture"), result.hashtags)
    }

    @Test
    fun `document caption is fixed and carries no hashtag`() {
        val result = CaptionGenerator.generate(
            labels = listOf("Receipt"),
            categories = setOf(SceneCategory.DOCUMENT),
            faceCount = 0,
        )
        assertEquals("A photo of a document", result.caption)
        assertTrue(result.hashtags.isEmpty())
    }

    @Test
    fun `nature caption`() {
        val result = CaptionGenerator.generate(
            labels = listOf("Mountain"),
            categories = setOf(SceneCategory.NATURE),
            faceCount = 0,
        )
        assertEquals("A photo of a mountain", result.caption)
        assertEquals(listOf("#mountain", "#nature"), result.hashtags)
    }

    @Test
    fun `event caption`() {
        val result = CaptionGenerator.generate(
            labels = listOf("Wedding"),
            categories = setOf(SceneCategory.EVENT),
            faceCount = 0,
        )
        assertEquals("A photo of a wedding", result.caption)
        assertEquals(listOf("#wedding", "#celebration"), result.hashtags)
    }

    @Test
    fun `a matched category whose own keyword never actually appears falls back to the top label`() {
        // FOOD is present (perhaps decided from a label below the caption's own smaller
        // vocabulary) but nothing in `labels` is a FOOD_SUBJECT_WORDS hit -- must not crash or
        // silently produce an empty caption.
        val result = CaptionGenerator.generate(
            labels = listOf("Table"),
            categories = setOf(SceneCategory.FOOD),
            faceCount = 0,
        )
        assertEquals("A photo of a table", result.caption)
    }

    @Test
    fun `unmatched labels with no category still get a generic caption from the top label`() {
        val result = CaptionGenerator.generate(
            labels = listOf("Umbrella", "Rain"),
            categories = emptySet(),
            faceCount = 0,
        )
        assertEquals("A photo of an umbrella", result.caption)
        assertEquals(listOf("#umbrella"), result.hashtags)
    }

    @Test
    fun `hashtags are capped and deduplicated`() {
        // FLORA and NATURE both map to '#nature' -- the cap/dedup must not surface it twice.
        val result = CaptionGenerator.generate(
            labels = listOf("Flower", "Garden"),
            categories = setOf(SceneCategory.FLORA, SceneCategory.NATURE, SceneCategory.EVENT),
            faceCount = 0,
        )
        assertEquals(listOf("#flowers", "#garden", "#nature", "#celebration"), result.hashtags)
    }

    @Test
    fun `blank and whitespace-only labels are ignored`() {
        val result = CaptionGenerator.generate(
            labels = listOf("  ", "", "Car"),
            categories = setOf(SceneCategory.VEHICLE),
            faceCount = 0,
        )
        assertEquals("A photo of a car", result.caption)
    }

    @Test
    fun `generation is deterministic`() {
        val labels = listOf("Flower", "Plant", "Garden")
        val categories = setOf(SceneCategory.FLORA)
        val first = CaptionGenerator.generate(labels, categories, faceCount = 0)
        val second = CaptionGenerator.generate(labels, categories, faceCount = 0)
        assertEquals(first, second)
    }
}
