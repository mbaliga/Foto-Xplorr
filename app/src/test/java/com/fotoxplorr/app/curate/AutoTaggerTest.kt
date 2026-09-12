package com.fotoxplorr.app.curate

import com.fotoxplorr.app.recognition.SceneCategory
import com.fotoxplorr.app.recognition.SceneClassifier
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoTaggerTest {

    private val floor = SceneClassifier.SCENE_LABEL_CONFIDENCE

    @Test
    fun `normalises case and surrounding whitespace so the same word never becomes two tags`() {
        val result = AutoTagger.tagsFor(
            labels = listOf("Flower", " flower ", "FLOWER"),
            categories = emptySet(),
            confidenceFloor = floor,
            maxTags = 10,
        )
        assertEquals(listOf("flower"), result)
    }

    @Test
    fun `collapses internal whitespace runs`() {
        val result = AutoTagger.tagsFor(
            labels = listOf("flower   shop"),
            categories = emptySet(),
            confidenceFloor = floor,
            maxTags = 10,
        )
        assertEquals(listOf("flower shop"), result)
    }

    @Test
    fun `blank and whitespace-only labels are dropped, not turned into an empty tag`() {
        val result = AutoTagger.tagsFor(
            labels = listOf("", "   ", "Dog"),
            categories = emptySet(),
            confidenceFloor = floor,
            maxTags = 10,
        )
        assertEquals(listOf("dog"), result)
    }

    @Test
    fun `caps the result at maxTags, keeping the labeller's own priority order`() {
        val result = AutoTagger.tagsFor(
            labels = listOf("Flower", "Tree", "Beach", "Dog", "Car"),
            categories = emptySet(),
            confidenceFloor = floor,
            maxTags = 3,
        )
        assertEquals(listOf("flower", "tree", "beach"), result)
    }

    @Test
    fun `zero or negative maxTags yields nothing rather than throwing`() {
        assertEquals(
            emptyList<String>(),
            AutoTagger.tagsFor(listOf("Flower"), emptySet(), floor, maxTags = 0),
        )
        assertEquals(
            emptyList<String>(),
            AutoTagger.tagsFor(listOf("Flower"), emptySet(), floor, maxTags = -1),
        )
    }

    @Test
    fun `never emits a tag that duplicates one the user already applied`() {
        val result = AutoTagger.tagsFor(
            labels = listOf("Flower", "Tree"),
            categories = emptySet(),
            confidenceFloor = floor,
            maxTags = 10,
            existingTags = setOf("Flower"),
        )
        assertEquals(listOf("tree"), result)
    }

    @Test
    fun `existing-tag comparison is case-insensitive even though stored tags are not`() {
        // A person's own tag "Paris" (mixed case, as LibraryStore.addTag preserves it) must
        // suppress an auto candidate that normalises to "paris", not coexist with it.
        val result = AutoTagger.tagsFor(
            labels = listOf("Paris"),
            categories = emptySet(),
            confidenceFloor = floor,
            maxTags = 10,
            existingTags = setOf("Paris"),
        )
        assertEquals(emptyList<String>(), result)
    }

    @Test
    fun `re-running on a photo that already carries its own previous auto tags is idempotent`() {
        val result = AutoTagger.tagsFor(
            labels = listOf("Flower", "Beach"),
            categories = emptySet(),
            confidenceFloor = floor,
            maxTags = 10,
            existingTags = setOf("flower", "beach"),
        )
        assertEquals(emptyList<String>(), result)
    }

    @Test
    fun `a DOCUMENT photo gets no auto tags at all, not just none matching the word document`() {
        val result = AutoTagger.tagsFor(
            labels = listOf("Flower", "Paper", "Text"),
            categories = setOf(SceneCategory.DOCUMENT),
            confidenceFloor = floor,
            maxTags = 10,
        )
        assertEquals(emptyList<String>(), result)
    }

    @Test
    fun `a PORTRAIT_OFFICIAL photo gets no auto tags at all`() {
        val result = AutoTagger.tagsFor(
            labels = listOf("Person", "Wall"),
            categories = setOf(SceneCategory.PORTRAIT_OFFICIAL),
            confidenceFloor = floor,
            maxTags = 10,
        )
        assertEquals(emptyList<String>(), result)
    }

    @Test
    fun `the privacy exclusion holds even when DOCUMENT is only one of several categories`() {
        val result = AutoTagger.tagsFor(
            labels = listOf("Flower"),
            categories = setOf(SceneCategory.FLORA, SceneCategory.DOCUMENT),
            confidenceFloor = floor,
            maxTags = 10,
        )
        assertEquals(emptyList<String>(), result)
    }

    @Test
    fun `a confidence floor stricter than SceneClassifier's own bar yields nothing`() {
        val result = AutoTagger.tagsFor(
            labels = listOf("Flower"),
            categories = setOf(SceneCategory.FLORA),
            confidenceFloor = floor + 0.1f,
            maxTags = 10,
        )
        assertEquals(emptyList<String>(), result)
    }

    @Test
    fun `a confidence floor at or below SceneClassifier's own bar behaves normally`() {
        val atBar = AutoTagger.tagsFor(listOf("Flower"), setOf(SceneCategory.FLORA), floor, 10)
        assertEquals(listOf("flower"), atBar)

        val belowBar = AutoTagger.tagsFor(listOf("Flower"), setOf(SceneCategory.FLORA), floor - 0.2f, 10)
        assertEquals(listOf("flower"), belowBar)
    }

    @Test
    fun `no labels yields no tags`() {
        assertEquals(emptyList<String>(), AutoTagger.tagsFor(emptyList(), emptySet(), floor, 10))
    }
}
