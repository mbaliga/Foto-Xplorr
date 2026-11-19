package com.fotoxplorr.app.sources.gallery

import com.fotoxplorr.app.gallery.GalleryActions
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.index.sources.SourceAssetIds
import com.fotoxplorr.core.model.MediaId

/** The one user-facing sentence the gate needs (ADR-020 decision 5). */
object SourceGateCopy {
    /** Shown where a row is disabled, and as the notice when a tap reached a gated action anyway. */
    const val WRITES_UNAVAILABLE = "Not available for files from added folders yet."
}

/**
 * ADR-020 decision 5 / PHASE-3-DESIGN 1.7: a source asset is read-only in v1. Its negative id keeps
 * it out of the Favourite and Library codecs, but `SensitiveIdCodec` would persist it and
 * `FavoriteStore` keeps it in memory, so the sign is not a safety net; this is. [wrap] returns a
 * [GalleryActions] whose every callback that takes media (ids or assets) drops the source ones
 * before anything reaches a store, MediaStore request or file operation.
 *
 * Works on what the callback is given, by id band and by [MediaAsset.sourceRef], so it holds even
 * for a stale selection. A mixed selection acts on its library part only ([onBlocked] is told so the
 * host can say why part of it did nothing). The classification below is checked against the real
 * `GalleryActions` fields by a reflection test: adding a callback without classifying it fails CI.
 */
object SourceActionGate {
    /** Callbacks that take media and WRITE (store, MediaStore, file): source items are dropped. */
    val GATED_WRITES: Set<String> = setOf(
        "onEditAsset", "onSetFavorite", "onSetSensitive", "onSetArchived", "onMoveToFolder",
        "onRenameAsset", "onMoveToTrash", "onRestore", "onDeletePermanently", "onAddToCollection",
        "onRemoveFromCollection", "onAddTag", "onRemoveTag", "onRejectArchiveSuggestions", "onPeople",
    )

    /** Callbacks that take media and only READ it: view, share, copy out, slideshow. Allowed for sources. */
    val READ_ONLY_WITH_MEDIA: Set<String> = setOf(
        "onShare", "onShareClean", "onCopyToFolder", "onExportZip", "onOpenAsset", "onStartSlideshow",
    )

    fun isSourceId(id: MediaId): Boolean = SourceAssetIds.isSourceId(id)

    fun isSource(asset: MediaAsset): Boolean = asset.isSource || isSourceId(asset.id)

    /** False when any of [assets] is a source file: the rows that write are then disabled. */
    fun writesAllowed(assets: Collection<MediaAsset>): Boolean = assets.none(::isSource)

    fun writesAllowedForIds(ids: Collection<MediaId>): Boolean = ids.none(::isSourceId)

    fun libraryOnly(ids: Set<MediaId>): Set<MediaId> = ids.filterNotTo(linkedSetOf(), ::isSourceId)

    fun libraryOnly(assets: List<MediaAsset>): List<MediaAsset> = assets.filterNot(::isSource)

    fun wrap(actions: GalleryActions, onBlocked: () -> Unit = {}): GalleryActions {
        fun ids(block: (Set<MediaId>) -> Unit): (Set<MediaId>) -> Unit = { given ->
            val kept = libraryOnly(given)
            if (kept.size != given.size) onBlocked()
            if (kept.isNotEmpty()) block(kept)
        }
        fun assets(block: (List<MediaAsset>) -> Unit): (List<MediaAsset>) -> Unit = { given ->
            val kept = libraryOnly(given)
            if (kept.size != given.size) onBlocked()
            if (kept.isNotEmpty()) block(kept)
        }
        return actions.copy(
            onEditAsset = { asset -> if (isSource(asset)) onBlocked() else actions.onEditAsset(asset) },
            onSetFavorite = { given, mark -> ids { actions.onSetFavorite(it, mark) }(given) },
            onSetSensitive = { given, mark -> ids { actions.onSetSensitive(it, mark) }(given) },
            onSetArchived = { given, archived -> ids { actions.onSetArchived(it, archived) }(given) },
            onMoveToFolder = assets(actions.onMoveToFolder),
            onRenameAsset = { asset, name -> if (isSource(asset)) onBlocked() else actions.onRenameAsset(asset, name) },
            onMoveToTrash = assets(actions.onMoveToTrash),
            onRestore = assets(actions.onRestore),
            onDeletePermanently = assets(actions.onDeletePermanently),
            onAddToCollection = { collection, given -> ids { actions.onAddToCollection(collection, it) }(given) },
            onRemoveFromCollection = { collection, given -> ids { actions.onRemoveFromCollection(collection, it) }(given) },
            onAddTag = { given, tag -> ids { actions.onAddTag(it, tag) }(given) },
            onRemoveTag = { given, tag -> ids { actions.onRemoveTag(it, tag) }(given) },
            onRejectArchiveSuggestions = ids(actions.onRejectArchiveSuggestions),
            onPeople = actions.onPeople.copy(
                onNotThisPerson = { person, given -> ids { actions.onPeople.onNotThisPerson(person, it) }(given) },
            ),
        )
    }
}
