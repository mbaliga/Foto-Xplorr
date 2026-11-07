package com.fotoxplorr.app.editor

import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.model.MediaId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** [RecipeStore.importStored]: a saved edit coming back from an encrypted backup. Additive, and refuses what is not a recipe. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecipeStoreImportTest {
    private fun asset(id: Long, modified: Long) = MediaAsset(
        id = MediaId(id), contentUriString = "content://media/external/images/media/$id", displayName = "p.jpg", mimeType = "image/jpeg",
        bucketName = null, dateTakenMillis = 0L, dateModifiedSeconds = modified, width = 10, height = 10, sizeBytes = 1L,
        relativePath = "DCIM/Camera/", isFavorite = false, isTrashed = false,
    )

    private val store = RecipeStore(RuntimeEnvironment.getApplication())
    private val recipe = EditRecipe(quarterTurns = 1, adjustments = Adjustments(exposure = 0.3f))

    @Test
    fun `an imported recipe loads for the photo at that revision and no other`() {
        assertTrue(store.importStored(41, 7_100, recipe.toJson().stringify()))
        assertEquals(recipe, store.load(asset(41, 7_100)))
        assertNull("another revision of the file is another picture", store.load(asset(41, 7_101)))
        assertNull(store.load(asset(42, 7_100)))
    }

    @Test
    fun `a recipe the person already has is never replaced`() {
        val mine = EditRecipe(quarterTurns = 3)
        store.save(asset(41, 7_100), mine)
        assertFalse(store.importStored(41, 7_100, recipe.toJson().stringify()))
        assertEquals(mine, store.load(asset(41, 7_100)))
    }

    @Test
    fun `text that is not a recipe is refused`() {
        assertFalse(store.importStored(41, 7_100, "not json"))
        assertFalse(store.importStored(41, 7_100, "[1,2]"))
        assertFalse(store.importStored(41, 7_100, "\"x\""))
        assertFalse("nothing to continue editing", store.importStored(41, 7_100, EditRecipe().toJson().stringify()))
        assertFalse(store.has(asset(41, 7_100)))
    }

    @Test
    fun `an id or revision that cannot name a file is refused`() {
        assertFalse(store.importStored(-1, 7_100, recipe.toJson().stringify()))
        assertFalse(store.importStored(41, 0, recipe.toJson().stringify()))
        assertFalse(store.importStored(41, -5, recipe.toJson().stringify()))
        assertTrue(store.stored().isEmpty())
    }

    @Test
    fun `an imported recipe is also found by the backup's own listing`() {
        store.importStored(41, 7_100, recipe.toJson().stringify())
        val listed = store.stored().single()
        assertEquals(41L, listed.mediaId)
        assertEquals(7_100L, listed.dateModifiedSeconds)
        assertNotNull(parseJson(listed.json))
    }
}
