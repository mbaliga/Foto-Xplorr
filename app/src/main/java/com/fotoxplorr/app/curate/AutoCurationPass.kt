package com.fotoxplorr.app.curate

import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.organize.LibraryStore
import com.fotoxplorr.app.recognition.RecognitionIndex
import com.fotoxplorr.app.recognition.SceneClassifier
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

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
 * It also means re-running recognition from scratch — which the cache's own upgrade path does —
 * cannot lose a caption or a tag, because nothing here deletes: see [AutoTagger.tagsFor]'s
 * `existingTags` and [AutoAnnotator.apply], both of which are constructed so a second run over
 * the same photo is a no-op rather than a rewrite.
 *
 * ## Why it never touches a person's own words
 * Both guards are enforced twice over, and that is deliberate rather than redundant. A caption
 * someone typed is checked by [AutoAnnotator.apply] here and again by
 * [LibraryStore.applyMachineCaption] at the storage layer; a tag someone added is filtered out of
 * the candidate list by `existingTags` here and rejected again by [LibraryStore.addAutoTags].
 * Silently overwriting something a person wrote is the one failure in this whole feature that
 * cannot be undone by re-running it, so it is worth checking on both sides of the boundary.
 */
class AutoCurationPass(private val library: LibraryStore) {

    /**
     * Apply auto tags and captions for every image in [assets] that recognition has an opinion
     * about.
     *
     * @return how many photos were changed. Zero is the normal steady state for a library that
     *   has already been through this once, not a sign anything went wrong.
     */
    suspend fun run(assets: List<MediaAsset>, recognition: RecognitionIndex): Int {
        if (assets.isEmpty()) return 0
        val state = library.observe().value
        var changed = 0

        for (asset in assets) {
            if (asset.isVideo || asset.isTrashed) continue
            currentCoroutineContext().ensureActive()

            val labels = recognition.labelsByMedia[asset.id].orEmpty()
            val categories = recognition.categoriesByMedia[asset.id].orEmpty()
            // Nothing was recognised in this photo, so there is nothing to say about it. Skipped
            // rather than run with empty inputs, which would be the same answer for more work.
            if (labels.isEmpty() && categories.isEmpty()) continue

            var touched = false

            val tags = AutoTagger.tagsFor(
                labels = labels,
                categories = categories,
                // The floor the classifier itself used. Passing anything stricter would ask
                // AutoTagger to re-judge confidences it was never given -- see its own KDoc on
                // why this is a whole-call gate rather than a per-label filter.
                confidenceFloor = SceneClassifier.SCENE_LABEL_CONFIDENCE,
                maxTags = MAX_AUTO_TAGS_PER_PHOTO,
                existingTags = state.tagsFor(asset.id),
            )
            if (tags.isNotEmpty()) {
                library.addAutoTags(asset.id, tags.toSet())
                touched = true
            }

            val candidate = recognition.captionsByMedia[asset.id].orEmpty()
            if (candidate.isNotBlank()) {
                val caption = AutoAnnotator.apply(
                    currentCaption = state.captionFor(asset.id),
                    currentIsMachineWritten = state.isMachineCaption(asset.id),
                    candidateCaption = candidate,
                )
                // applyMachineCaption re-checks the same condition and reports whether it actually
                // wrote; trusting the decision above without it would count a photo as changed on
                // a write the store declined.
                if (caption != null && library.applyMachineCaption(asset.id, caption)) touched = true
            }

            if (touched) changed++
        }
        return changed
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
