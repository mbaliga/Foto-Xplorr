package com.fotoxplorr.app.search

import com.fotoxplorr.app.gallery.folderIdentity
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaId
import com.fotoxplorr.app.organize.LibraryState
import com.fotoxplorr.app.recognition.RecognitionIndex

/**
 * Bridges the gallery's state to the search language: builds the documents, runs the query, and
 * reports the vocabulary the suggestion engine needs.
 *
 * Everything the AI knows now reaches search from here. Before this, a query saw filenames, MIME
 * types, folders and manual tags — which meant the two most useful things the app had computed
 * (what is IN the picture, and what it SAYS) were invisible to the one feature that wanted them.
 */
object GallerySearch {

    /**
     * Filter [assets] by [query]. An unparseable or empty query returns the list untouched, so a
     * half-typed search never blanks the grid.
     */
    fun filter(
        assets: List<MediaAsset>,
        query: ParsedQuery,
        library: LibraryState,
        recognition: RecognitionIndex,
        favouriteIds: Set<MediaId>,
    ): List<MediaAsset> {
        if (query.isEmpty) return assets
        return assets.filter { asset ->
            matchesQuery(query, documentFor(asset, library, recognition, favouriteIds))
        }
    }

    /** One asset, flattened into everything a query can look at. */
    fun documentFor(
        asset: MediaAsset,
        library: LibraryState,
        recognition: RecognitionIndex,
        favouriteIds: Set<MediaId>,
    ): SearchDocument = SearchDocument(
        mediaId = asset.id,
        name = asset.displayName,
        folder = folderIdentity(asset).displayName,
        mimeType = asset.mimeType,
        takenAtMillis = asset.dateTakenMillis,
        tags = library.tagsFor(asset.id),
        labels = recognition.labelsByMedia[asset.id].orEmpty().toSet(),
        text = recognition.textOf(asset.id),
        categories = categoriesFor(asset, library, recognition, favouriteIds),
        // EXIF is read lazily per photo and is not in memory for the whole library, so camera and
        // ISO stay empty here rather than forcing a per-asset file read during a keystroke. The
        // fields parse and are honoured; they simply match nothing until the EXIF cache lands.
        camera = "",
        iso = null,
        width = asset.width,
        height = asset.height,
        sizeBytes = asset.sizeBytes,
    )

    /**
     * The derived buckets `is:` searches. Deliberately the same words the destinations use, so
     * `is:pet` and the Pets destination cannot disagree about what a pet photo is.
     */
    private fun categoriesFor(
        asset: MediaAsset,
        library: LibraryState,
        recognition: RecognitionIndex,
        favouriteIds: Set<MediaId>,
    ): Set<String> = buildSet {
        if (asset.isVideo) add("video") else add("photo")
        if (asset.isAnimated) add("animated")
        if (asset.id in favouriteIds || asset.isFavorite) add("favourite")
        if (asset.isTrashed) add("trashed")
        if (asset.id in recognition.petMediaIds) add("pet")
        if (asset.id in recognition.peopleMediaIds) add("person")
        if (asset.id in recognition.identityMediaIds) add("document")
        if (asset.id in library.archivedIds) add("archived")
        if (library.tagsFor(asset.id).isEmpty()) add("untagged")
        // Screenshots are a folder convention rather than a classification, and the app already
        // treats them that way in its smart albums.
        if (folderIdentity(asset).displayName.contains("screenshot", ignoreCase = true)) {
            add("screenshot")
        }
    }

    /**
     * What the corpus actually contains, so suggestions can be checked before being offered.
     *
     * Capped: a library with ten thousand distinct labels would otherwise build a ten-thousand
     * entry set on every keystroke, and the suggestion engine only ever shows a handful.
     */
    fun vocabularyOf(
        assets: List<MediaAsset>,
        library: LibraryState,
        recognition: RecognitionIndex,
    ): SearchVocabulary = SearchVocabulary(
        labels = recognition.allLabels.take(VOCABULARY_CAP).toSet(),
        tags = library.allTags.take(VOCABULARY_CAP).toSet(),
        folders = assets.asSequence()
            .mapNotNull { folderIdentity(it).displayName.takeIf(String::isNotBlank) }
            .distinct()
            .take(VOCABULARY_CAP)
            .toSet(),
        cameras = emptySet(),
        categories = STANDARD_CATEGORIES,
    )

    /** The `is:` vocabulary, fixed rather than derived: these are the buckets the app defines. */
    private val STANDARD_CATEGORIES = setOf(
        "photo", "video", "animated", "favourite", "pet", "person", "document",
        "screenshot", "archived", "untagged",
    )

    private const val VOCABULARY_CAP = 200
}
