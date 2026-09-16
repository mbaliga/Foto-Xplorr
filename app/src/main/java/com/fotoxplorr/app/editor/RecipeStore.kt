package com.fotoxplorr.app.editor

import android.content.Context
import com.fotoxplorr.app.media.MediaAsset
import java.io.File

/**
 * Autosaves an [EditRecipe] per photo, non-destructively, so re-opening the editor on a photo
 * with unsaved-but-not-abandoned changes offers to pick up where it left off instead of starting
 * blank — the actual persistence [Adjustments]'s own class doc always claimed and, before this
 * class existed, did not have.
 *
 * Keyed by [MediaAsset.id] AND `dateModifiedSeconds` TOGETHER, not the id alone: if the file's
 * own bytes have changed since this recipe was saved — replaced by a sync client, edited in
 * another app, or overwritten by this app's own "Replace the original" — the old recipe describes
 * pixels that no longer exist, and silently applying it to whatever is there now would be a
 * second, unrelated photograph. A changed `dateModifiedSeconds` simply looks like "no stored
 * recipe" rather than something this class has to detect and invalidate.
 *
 * One JSON file per asset under `filesDir/edits/` rather than one shared index: a half-written
 * save (the process dying mid-write) can then never corrupt any recipe but the one being written,
 * and forgetting one (see [revert]) is a single file delete.
 */
class RecipeStore(context: Context) {
    private val directory = File(context.filesDir, "edits").apply { mkdirs() }

    /** The stored recipe for [asset] at its CURRENT `dateModifiedSeconds`, or null when there is
     *  none — either nothing was ever saved, or the file has changed since the last one was. */
    fun load(asset: MediaAsset): EditRecipe? {
        val file = fileFor(asset)
        if (!file.exists()) return null
        return runCatching { parseJson(file.readText())?.let { EditRecipe.fromJson(it) } }.getOrNull()
    }

    /** What [EditorScreen] checks to decide whether to offer "Continue editing / Start over" at
     *  all, without paying for a full parse just to answer a yes/no question. */
    fun has(asset: MediaAsset): Boolean = fileFor(asset).exists()

    /** Call on every committed change. An identity recipe deletes the autosave rather than
     *  writing one: a since-reverted edit must not go on offering "continue editing" for changes
     *  that no longer exist. */
    fun save(asset: MediaAsset, recipe: EditRecipe) {
        val file = fileFor(asset)
        if (recipe.isIdentity) {
            file.delete()
            return
        }
        file.writeText(recipe.toJson().stringify())
    }

    /** "Revert to original": forgets the autosave. Returns a fresh identity [EditRecipe] so the
     *  caller has, in one call, both the side effect and the value to reset the on-screen recipe
     *  to. */
    fun revert(asset: MediaAsset): EditRecipe {
        fileFor(asset).delete()
        return EditRecipe()
    }

    private fun fileFor(asset: MediaAsset): File =
        File(directory, "${asset.id.value}-${asset.dateModifiedSeconds}.json")
}
