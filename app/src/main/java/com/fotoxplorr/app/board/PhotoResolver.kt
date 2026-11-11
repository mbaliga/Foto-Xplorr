package com.fotoxplorr.app.board

import com.fotoxplorr.app.backup.IdRemap
import com.fotoxplorr.app.backup.MediaIdentity

/** Where a board's photo is now. */
sealed interface PhotoResolution {
    /** The photo is in the library under the id the board has. */
    data class Same(val mediaId: Long) : PhotoResolution

    /** The same photo (by name, size and date taken) is in the library under another id. */
    data class Moved(val mediaId: Long) : PhotoResolution

    /** Not in the library any more, or not one that may be shown (deleted, trashed, locked, hidden). Draws as a placeholder. */
    data object Missing : PhotoResolution
}

/**
 * Finds each photo of a board in the library again, by the rule the backup uses for pins and recipes
 * ([IdRemap.matchIdentities]): the same id with the same name, size and date taken keeps its id; a
 * photo that moved is found by name and size, narrowed by date and folder, and a match that is not
 * certain is no match. A photo with no identity in the board (an older file) is trusted by id alone.
 *
 * The catalogue passed in is what may be shown now (the Experimental host's list, TRAPS #33), so a
 * photo that is locked, hidden or trashed is Missing here and never reaches a decoder.
 */
object PhotoResolver {

    fun resolve(refs: List<PhotoRef>, catalogue: Map<Long, MediaIdentity>): Map<Long, PhotoResolution> {
        val distinct = refs.distinctBy { it.mediaId }
        val wanted = distinct.mapNotNull { r -> r.identity?.let { r.mediaId to it } }.toMap()
        val match = IdRemap.matchIdentities(wanted, catalogue)
        return distinct.associate { ref ->
            ref.mediaId to when {
                ref.identity == null -> if (ref.mediaId in catalogue) PhotoResolution.Same(ref.mediaId) else PhotoResolution.Missing
                else -> match.oldToNew[ref.mediaId]?.let { new ->
                    if (new == ref.mediaId) PhotoResolution.Same(new) else PhotoResolution.Moved(new)
                } ?: PhotoResolution.Missing
            }
        }
    }

    /** The board with every moved photo pointed at its new id. Missing photos are left as they are. */
    fun relink(board: Board, resolutions: Map<Long, PhotoResolution>): Board {
        var changed = false
        val layers = board.layers.map { layer ->
            val photo = layer.content as? LayerContent.Photo ?: return@map layer
            val moved = resolutions[photo.ref.mediaId] as? PhotoResolution.Moved ?: return@map layer
            changed = true
            layer.copy(content = LayerContent.Photo(photo.ref.copy(mediaId = moved.mediaId)))
        }
        return if (changed) board.copy(layers = layers) else board
    }
}
