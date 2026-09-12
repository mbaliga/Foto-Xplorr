package com.fotoxplorr.app.curate

import com.fotoxplorr.app.recognition.SceneCategory
import com.fotoxplorr.app.recognition.SceneClassifier

/**
 * Turns recognition output that has already been computed -- ML Kit labels, [SceneCategory]
 * membership -- into a short list of browsable tags. Pure, like [SceneClassifier] and
 * [com.fotoxplorr.app.recognition.CaptionGenerator] beside it in that package, so the rules are
 * unit-tested on the JVM rather than eyeballed on a device. Nothing here re-runs recognition or
 * invents a confidence score for anything; it only decides which of the words already on hand
 * are worth surfacing as a tag, and how many.
 *
 * ## Why a photo can end up with zero auto tags
 *
 * Three independent gates, any one of which empties the result:
 *
 * 1. [categories] contains [SceneCategory.DOCUMENT] or [SceneCategory.PORTRAIT_OFFICIAL] --
 *    see [PRIVACY_EXCLUDED_CATEGORIES]'s own doc for why this is a full suppression of the
 *    whole photo, not a filter on individual words.
 * 2. [confidenceFloor] asks for more certainty than this function can verify -- see its own
 *    parameter doc.
 * 3. Every surviving label already duplicates a tag the photo carries, via [existingTags].
 *
 * An empty list is the correct, boring answer in all three cases, not an error: a photo this
 * function has nothing safe or new to say about should simply not gain a tag, the same way
 * [com.fotoxplorr.app.recognition.CaptionGenerator] leaves `caption` empty rather than inventing
 * a sentence the evidence does not support.
 */
object AutoTagger {

    /**
     * Auto tags for one photo, most-relevant first.
     *
     * @param labels What the on-device labeller saw for this photo (e.g. from
     *   [com.fotoxplorr.app.recognition.RecognitionIndex.labelsByMedia]), in the labeller's own
     *   ranking order -- first is most confident. Deliberately `List<String>`, not the
     *   confidence-carrying `ImageLabel` [SceneClassifier.classify] itself reads: by the time a
     *   label is persisted onto `AssetRecognition.labels` and reaches this function, its
     *   original per-label confidence has already been spent deciding whether the label
     *   survived indexing at all and is not retained -- see [confidenceFloor] for what that
     *   means for this parameter.
     * @param categories This photo's [SceneCategory] set, used only as a gate (see
     *   [PRIVACY_EXCLUDED_CATEGORIES]) -- never turned into tag words of their own. Category
     *   words ("flora", "food", ...) are deliberately not promoted to tags here: several already
     *   have a near-identical label doing the same job ("flower" for FLORA, "cake" for FOOD),
     *   and inventing a second, coarser vocabulary on top would make the tag list a mix of two
     *   granularities with no way for a browsing user to tell which is which.
     * @param confidenceFloor The minimum confidence the caller wants behind an auto tag, on
     *   the same 0..1 scale [SceneClassifier.SCENE_LABEL_CONFIDENCE] uses. This function has no
     *   per-label confidence left to compare it against (see [labels]), so it cannot honour an
     *   arbitrary floor by filtering individual words -- doing that would mean silently keeping
     *   every label regardless of what the caller asked for, which is worse than doing nothing:
     *   a "stricter tagging" setting that quietly has no effect is a setting that lies. Instead
     *   this is checked once, for the whole call, against
     *   [SceneClassifier.SCENE_LABEL_CONFIDENCE] -- the bar [categories] is already known to have
     *   cleared, since that is how [SceneClassifier.classify] decided them. A caller asking for
     *   more certainty than that gets no auto tags at all rather than an unverifiable promise.
     *   [SceneClassifier.SCENE_LABEL_CONFIDENCE] is reused rather than inventing another number
     *   with no evidence behind it either -- the same call [SceneClassifier] itself makes when it
     *   reuses [com.fotoxplorr.app.recognition.PetClassifier.SUPPORTING_CONFIDENCE].
     * @param maxTags Hard cap on the result size. A photo wearing fifteen machine tags is noise,
     *   not help -- it makes the tag list useless for browsing, which is the entire point of
     *   tagging. Zero or negative yields an empty list rather than throwing: a caller-side
     *   settings bug ("auto-tagging" toggled off by way of a zero limit) should silently tag
     *   nothing, not crash the indexing pass that called this.
     * @param existingTags Every tag already on this photo, of ANY provenance -- both the user's
     *   own and this photo's own tags from an earlier auto-tag run. A candidate that normalises
     *   (see [normalize]) to one of these is dropped before it ever reaches [maxTags], which is
     *   what makes re-running this function on an already-tagged photo idempotent instead of
     *   noisy, and is the specific mechanism behind the rule "never emit a tag that duplicates
     *   one the user already applied". Defaulted to empty so the four-argument call shape this
     *   function was specified with keeps compiling and every test written against it stays
     *   valid -- the same reasoning
     *   [com.fotoxplorr.app.editor.AutoFix.suggestionsFor]'s own trailing, defaulted parameters
     *   use for the identical reason.
     *
     *   Comparison is case-insensitive by normalising both sides, but storage is not: this
     *   codebase's tags keep the exact case a person typed (see
     *   [com.fotoxplorr.app.organize.LibraryStore.addTag]), while every auto tag is lowercase by
     *   construction. A user tag "Paris" and a hypothetical auto tag "paris" for the SAME photo
     *   are therefore recognised as the same tag and the auto one is dropped; a user tag "Paris"
     *   on one photo does not suppress an unrelated auto tag "paris" on a different one, because
     *   [existingTags] is always scoped to a single photo's own tags by the caller. Accepted as
     *   the one remaining edge case: two differently-cased entries for the same word could still
     *   coexist if a user manually types a tag AFTER an auto tag with different casing already
     *   sits on the SAME photo -- fixing that would mean making tag storage case-insensitive,
     *   which is a change to [com.fotoxplorr.app.organize.LibraryStore]'s established contract
     *   well outside this function's job.
     */
    fun tagsFor(
        labels: List<String>,
        categories: Set<SceneCategory>,
        confidenceFloor: Float,
        maxTags: Int,
        existingTags: Set<String> = emptySet(),
    ): List<String> {
        if (maxTags <= 0) return emptyList()
        if (categories.any { it in PRIVACY_EXCLUDED_CATEGORIES }) return emptyList()
        // See @param confidenceFloor: this is the one comparison available to it, and it is a
        // whole-call gate, not a per-label filter.
        if (confidenceFloor > SceneClassifier.SCENE_LABEL_CONFIDENCE) return emptyList()

        val existingNormalized = existingTags.mapTo(HashSet(existingTags.size)) { normalize(it) }
        val out = LinkedHashSet<String>()
        for (label in labels) {
            val normalized = normalize(label)
            if (normalized.isEmpty() || normalized in existingNormalized) continue
            out += normalized
            if (out.size >= maxTags) break
        }
        return out.toList()
    }

    /**
     * [SceneCategory]s a photo must never be auto-tagged from, at all -- not "skip a tag literally
     * named after the category", the whole photo gets nothing.
     *
     * Tagging someone's passport photo "portrait_official" (or, just as bad, tagging it with
     * whatever ELSE the labeller saw on it -- "text", "card", "signature") in a tag list that is
     * visible across the whole library and searchable by anyone with the phone unlocked is a
     * privacy leak, not a convenience. [DOCUMENT] and [PORTRAIT_OFFICIAL] read that way for
     * exactly the reason
     * [com.fotoxplorr.app.recognition.CaptionGenerator.CATEGORY_HASHTAGS] already maps both to
     * `null` -- "personal or administrative rather than shareable" -- except a caption is
     * transient and read once, where a tag sits in a library-wide, cross-photo index that search
     * and browsing both use forever. The stronger claim (suppress the whole photo, not just the
     * matching word) is deliberate: a passport photo's OTHER labels are not safe either, and this
     * function has no way to tell a genuinely innocuous background label apart from one that
     * would, in context, out the photo as an identity document to anyone scrolling past its tags.
     */
    private val PRIVACY_EXCLUDED_CATEGORIES = setOf(SceneCategory.DOCUMENT, SceneCategory.PORTRAIT_OFFICIAL)

    /** "Flower" and "flower " must not become two tags; collapsing internal runs catches "flower   shop" too. */
    private fun normalize(raw: String): String = raw.trim().replace(WHITESPACE, " ").lowercase()

    private val WHITESPACE = Regex("\\s+")
}
