package com.fotoxplorr.app.editor

import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.model.MediaId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Pins the "Continue editing" safety rule: the editor opens on an identity placeholder recipe, and
 * [RecipeStore.save] deletes the stored file for an identity recipe, so the debounced autosave
 * must stay out of the way until the user has answered the dialog.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorAutosaveGateTest {

    private val asset = MediaAsset(
        id = MediaId(7L),
        contentUriString = "content://media/external/images/media/7",
        displayName = "photo.jpg",
        mimeType = "image/jpeg",
        bucketName = null,
        dateTakenMillis = 0L,
        dateModifiedSeconds = 1_000L,
        width = 100,
        height = 100,
        sizeBytes = 1_000L,
        relativePath = "DCIM/Camera/",
        isFavorite = false,
        isTrashed = false,
    )

    /** What the settled-recipe effect does once its debounce elapses, minus the history commit. */
    private fun settle(store: RecipeStore, recipe: EditRecipe, pendingResumeChoice: Boolean) {
        if (!shouldAutosave(pendingResumeChoice)) return
        store.save(asset, recipe)
    }

    @Test
    fun `autosave is withheld while the resume choice is pending and allowed after`() {
        assertFalse(shouldAutosave(pendingResumeChoice = true))
        assertTrue(shouldAutosave(pendingResumeChoice = false))
    }

    @Test
    fun `a slow answer to the dialog does not destroy the stored edit`() {
        val store = RecipeStore(RuntimeEnvironment.getApplication())
        val stored = EditRecipe(adjustments = Adjustments(exposure = 0.5f))
        store.save(asset, stored)

        // The editor opened on identity with the dialog up; the debounce elapses before an answer.
        settle(store, EditRecipe(), pendingResumeChoice = true)

        assertTrue(store.has(asset))
        assertEquals(stored, store.load(asset))
    }

    @Test
    fun `once answered an identity recipe still forgets the autosave`() {
        val store = RecipeStore(RuntimeEnvironment.getApplication())
        store.save(asset, EditRecipe(adjustments = Adjustments(exposure = 0.5f)))

        settle(store, EditRecipe(), pendingResumeChoice = false)

        assertFalse(store.has(asset))
    }
}
