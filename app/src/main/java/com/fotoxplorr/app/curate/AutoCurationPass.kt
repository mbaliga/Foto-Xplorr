package com.fotoxplorr.app.curate

import com.fotoxplorr.app.gallery.folderIdentity
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaId
import com.fotoxplorr.app.organize.LibraryStore
import com.fotoxplorr.app.recognition.RecognitionIndex
import com.fotoxplorr.app.recognition.SceneClassifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Turns what recognition SAW into what the library KNOWS: auto tags, and a caption for a photo
 * that has none.
 *
 * [AutoTagger] and [AutoAnnotator] are pure decisions with no idea where their inputs come from or
 * where their answers go. This is the seam that gives them both — reading
 * [com.fotoxplorr.app.recognition.RecognitionIndex] and writing
 * [com.fotoxplorr.app.organize.LibraryStore] — and it lives in `curate/` for the same reason
 * `RecognitionIndexer` lives in `recognition/`: the package that owns the decision owns the pass
 * that runs it.
 *
 * ## Why this is a second pass and not a step inside RecognitionIndexer
 * The two write to different stores with different durability rules. Recognition results are a
 * derived cache whose upgrade path is "drop it and recompute"; tags and captions are library data
 * a person edits and expects to keep. Running the write into [LibraryStore] from inside the
 * recognition indexer would put a dependency on the durable store inside the disposable one, and
 * every future schema decision about the cache would have to think about the library too.
 *
 * ## Why it decides everything first and writes once
 * The pass runs over the whole library. Writing per photo through the store's per-photo methods
 * would mean one preference commit and one StateFlow emission per photo — twenty thousand
 * recompositions of every screen observing the library, for a pass whose point is to be
 * unnoticed. So it collects every proposed change and hands the lot to
 * [LibraryStore.applyCuration], which commits and emits once. It also runs on
 * [Dispatchers.Default] itself rather than trusting the caller to: the foreground call site is a
 * `LaunchedEffect`, whose dispatcher is Main, and a whole-library loop on Main is an ANR.
 *
 * ## What it never touches
 * - A person's own words. Enforced twice over, deliberately: a typed caption is refused by
 *   [AutoAnnotator.apply] here and again by the store at write time; a typed tag is filtered out of
 *   the candidate list here and rejected again on insert. Silently overwriting something a person
 *   wrote is the one failure in this feature that re-running cannot undo, so it is checked on both
 *   sides of the boundary.
 * - A person's "no". An auto tag they removed and a machine caption they cleared are both
 *   remembered by the store ([LibraryStore.observe]'s `rejectedAutoTagsFor` and
 *   `isMachineCaptionSuppressed`) and treated here exactly like a tag the photo already carries or
 *   a caption a person wrote. Without that, removing an auto tag was a thirty-minute reprieve.
 * - A locked folder. Photos behind [lockedFolders] are skipped entirely, because a tag is visible
 *   library-wide — in the Tags row, in search suggestions — and auto-tagging a locked folder's
 *   contents would print what is in it on screens that are not behind the lock.
 */
class AutoCurationPass(private val library: LibraryStore) {

    /**
     * Apply auto tags and captions for every image in [assets] that recognition has an opinion
     * about, except those in [lockedFolders].
     *
     * @return how many photos were changed. Zero is the normal steady state for a library that
     *   has already been through this once, not a sign anything went wrong.
     */
    suspend fun run(
        assets: List<MediaAsset>,
        recognition: RecognitionIndex,
        lockedFolders: Set<String> = emptySet(),
    ): Int = withContext(Dispatchers.Default) {
        if (assets.isEmpty()) return@withContext 0
        val state = library.observe().value
        val proposedTags = HashMap<MediaId, Set<String>>()
        val proposedCaptions = HashMap<MediaId, String>()

        for (asset in assets) {
            if (asset.isVideo || asset.isTrashed) continue
            if (lockedFolders.isNotEmpty() && folderIdentity(asset).key.value in lockedFolders) continue
            ensureActive()

            val labels = recognition.labelsByMedia[asset.id].orEmpty()
            val categories = recognition.categoriesByMedia[asset.id].orEmpty()
            // Nothing was recognised in this photo, so there is nothing to say about it. Skipped
            // rather than run with empty inputs, which would be the same answer for more work.
            if (labels.isEmpty() && categories.isEmpty()) continue

            val tags = AutoTagger.tagsFor(
                labels = labels,
                categories = categories,
                // The floor the classifier itself used. Passing anything stricter would ask
                // AutoTagger to re-judge confidences it was never given -- see its own KDoc on
                // why this is a whole-call gate rather than a per-label filter.
                confidenceFloor = SceneClassifier.SCENE_LABEL_CONFIDENCE,
                maxTags = MAX_AUTO_TAGS_PER_PHOTO,
                // Rejected auto tags count as "already there" for the purpose of never proposing
                // them again -- see the class KDoc.
                existingTags = state.tagsFor(asset.id) + state.rejectedAutoTagsFor(asset.id),
            )
            if (tags.isNotEmpty()) proposedTags[asset.id] = tags.toSet()

            val candidate = recognition.captionsByMedia[asset.id].orEmpty()
            if (candidate.isNotBlank() && !state.isMachineCaptionSuppressed(asset.id)) {
                AutoAnnotator.apply(
                    currentCaption = state.captionFor(asset.id),
                    currentIsMachineWritten = state.isMachineCaption(asset.id),
                    candidateCaption = candidate,
                )?.let { proposedCaptions[asset.id] = it }
            }
        }

        ensureActive()
        library.applyCuration(proposedTags, proposedCaptions)
    }

    private companion object {
        /**
         * How many tags one photo may be given automatically.
         *
         * Small on purpose. The labeller emits a long tail of increasingly generic guesses
         * ("object", "material", "pattern") and taking all of them would bury the two or three
         * that describe the photo under a dozen that describe nothing — and every one of those is
         * a chip in the details room and a term in search. Better a photo tagged "beach, sky"
         * that reads true than one tagged with everything the model has ever heard of.
         */
        const val MAX_AUTO_TAGS_PER_PHOTO = 4
    }
}
