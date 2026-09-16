package com.fotoxplorr.app.editor

import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecipeStoreTest {

    private fun asset(id: Long = 1L, dateModified: Long = 1_000L) = MediaAsset(
        id = MediaId(id),
        contentUriString = "content://media/external/images/media/$id",
        displayName = "photo.jpg",
        mimeType = "image/jpeg",
        bucketName = null,
        dateTakenMillis = 0L,
        dateModifiedSeconds = dateModified,
        width = 100,
        height = 100,
        sizeBytes = 1_000L,
        relativePath = "DCIM/Camera/",
        isFavorite = false,
        isTrashed = false,
    )

    private fun store() = RecipeStore(RuntimeEnvironment.getApplication())

    @Test
    fun `nothing is stored for an asset that was never saved`() {
        val recipeStore = store()
        assertFalse(recipeStore.has(asset()))
        assertNull(recipeStore.load(asset()))
    }

    @Test
    fun `a saved recipe loads back exactly`() {
        val recipeStore = store()
        val recipe = EditRecipe(quarterTurns = 2, adjustments = Adjustments(exposure = 0.4f))
        recipeStore.save(asset(), recipe)

        assertTrue(recipeStore.has(asset()))
        assertEquals(recipe, recipeStore.load(asset()))
    }

    @Test
    fun `saving an identity recipe deletes any existing autosave instead of writing one`() {
        val recipeStore = store()
        recipeStore.save(asset(), EditRecipe(quarterTurns = 1))
        assertTrue(recipeStore.has(asset()))

        recipeStore.save(asset(), EditRecipe())
        assertFalse(recipeStore.has(asset()))
    }

    @Test
    fun `a changed dateModifiedSeconds looks like no stored recipe at all`() {
        val recipeStore = store()
        recipeStore.save(asset(dateModified = 1_000L), EditRecipe(quarterTurns = 1))

        assertTrue(recipeStore.has(asset(dateModified = 1_000L)))
        assertFalse("a different dateModifiedSeconds must not see the old recipe", recipeStore.has(asset(dateModified = 2_000L)))
    }

    @Test
    fun `revert deletes the autosave and returns an identity recipe`() {
        val recipeStore = store()
        recipeStore.save(asset(), EditRecipe(quarterTurns = 3))

        val reverted = recipeStore.revert(asset())

        assertEquals(EditRecipe(), reverted)
        assertFalse(recipeStore.has(asset()))
    }

    @Test
    fun `two different assets do not collide`() {
        val recipeStore = store()
        recipeStore.save(asset(id = 1L), EditRecipe(quarterTurns = 1))
        recipeStore.save(asset(id = 2L), EditRecipe(quarterTurns = 2))

        assertEquals(1, recipeStore.load(asset(id = 1L))?.quarterTurns)
        assertEquals(2, recipeStore.load(asset(id = 2L))?.quarterTurns)
    }
}
