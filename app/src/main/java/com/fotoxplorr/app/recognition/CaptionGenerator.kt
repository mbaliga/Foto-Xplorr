package com.fotoxplorr.app.recognition

/**
 * A generated caption and its hashtags for one photo. Deliberately just these two flat fields
 * -- see [CaptionGenerator]'s KDoc for what informed them and what deliberately did not.
 */
data class GeneratedCaption(val caption: String, val hashtags: List<String>) {
    companion object {
        val EMPTY = GeneratedCaption("", emptyList())
    }
}

/**
 * Writes a one-line caption and a short hashtag list for a photo, from exactly three inputs:
 * its labels, its [SceneCategory] set, and its face count. Nothing else -- no location, no
 * date, no on-device language model.
 *
 * ## Why templates, not a model
 *
 * The `offline` flavour has no INTERNET permission and a Gradle gate that fails the build if a
 * network-capable dependency reaches its runtime classpath (see the enforcement gates in
 * app/build.gradle.kts). A genuinely generative captioner is either a network call
 * (impossible here) or a bundled language model (a real new dependency, which this work was
 * told not to add). Template composition over signal the recognition pass already computed is
 * the only captioning that is simultaneously on-device, dependency-free and deterministic
 * enough to unit test -- which is also why every caption reads plainly rather than cleverly: a
 * template that reaches for cleverness reaches for words the evidence does not actually support.
 *
 * ## Determinism, and what it does not cover
 *
 * Two calls with the same three arguments always return the same [GeneratedCaption] -- no
 * randomness, no wall-clock or locale input -- so a caption is stable across re-runs of the
 * recognition pass and safe to persist on `AssetRecognition.caption`. It IS, deliberately, a
 * pure function of evidence available from *this one photo alone*: like
 * `AssetRecognition.categories`, it is computed once at index time and is not retroactively
 * rewritten when a *different* photo's clustering later reveals that a person here recurs
 * elsewhere in the library. There is accordingly no "these are your friends" caption, for the
 * same reason [SceneClassifier.withRecurringPeopleContext] is kept out of
 * [SceneClassifier.classify] -- see that KDoc for the staleness problem this avoids.
 *
 * ## Caption priority
 *
 * Highest priority first: a face count of three or more reads as a *group*, one large centred
 * face reads as an *official-style portrait*, one or two faces read as *a photo of that many
 * people* -- faces outrank scenery in all three cases, because a garden photo with someone
 * standing in it is a photo of that person, not of the garden they happen to be standing in.
 * Only with no faces worth mentioning does a label-driven category take over, in roughly
 * "how specific a single thing this names" order (FLORA/FAUNA/FOOD/VEHICLE/ARCHITECTURE before
 * the whole-scene categories EVENT and NATURE). This ordering is itself a judgement call --
 * there is no source of truth for which subject "wins" a photo that is honestly both.
 *
 * ## Hashtags
 *
 * Whichever label(s) actually appear in the caption's own sentence are hashtagged first, in
 * the order they were used, followed by one hashtag per matched category from
 * [CATEGORY_HASHTAGS] (walked in [SceneCategory] declaration order regardless of which
 * `Set` implementation [categories] happens to be, so hashtag order never depends on incidental
 * caller-side iteration order). A label that was NOT used in the caption's sentence is
 * deliberately never hash-tagged on its own: a caption about "a group of four people"
 * hashtagged with every incidental background label ("floor", "wall", "chair") would drown the
 * two or three hashtags someone would actually want in noise nobody asked for.
 */
object CaptionGenerator {

    /** A caption sitting under thirty background labels should still not carry thirty hashtags. */
    private const val MAX_HASHTAGS = 6

    /**
     * One hashtag per category, or none. `null` is a deliberate choice for two of these, not an
     * omission: [SceneCategory.DOCUMENT] and [SceneCategory.PORTRAIT_OFFICIAL] photos are
     * personal or administrative rather than shareable, so tagging them for social use would be
     * inventing an intent the photo never had.
     *
     * The words themselves are a judgement call with no source of truth to check them against
     * -- there is no mockup or copy deck for hashtag text -- chosen as the plain, common word a
     * person would actually type, which is why [SceneCategory.FLORA] and [SceneCategory.NATURE]
     * both map to the familiar "#nature" rather than the technical "#flora".
     */
    private val CATEGORY_HASHTAGS: Map<SceneCategory, String?> = mapOf(
        SceneCategory.FLORA to "#nature",
        SceneCategory.FAUNA to "#animals",
        SceneCategory.ARCHITECTURE to "#architecture",
        SceneCategory.FOOD to "#food",
        SceneCategory.VEHICLE to "#travel",
        SceneCategory.DOCUMENT to null,
        SceneCategory.GROUP_PHOTO to "#friends",
        SceneCategory.FRIENDS_FAMILY to "#family",
        SceneCategory.PORTRAIT_OFFICIAL to null,
        SceneCategory.NATURE to "#nature",
        SceneCategory.EVENT to "#celebration",
    )

    // These are deliberately smaller, and separate from, SceneClassifier's own keyword sets.
    // Classification wants to catch weak supporting evidence ("whiskers" implies an animal is
    // somewhere in frame); captioning wants only words worth saying out loud in a sentence a
    // person will read ("a dog", never "a whiskers"). Merging the two tables would tie a
    // wording change to a category-detection change and vice versa.
    private val FLORA_SUBJECT_WORDS = setOf(
        "flower", "flowers", "plant", "plants", "tree", "trees", "leaf", "leaves", "blossom",
        "petal", "flora", "houseplant", "bush", "shrub", "cactus", "succulent", "bouquet",
    )
    private val PLACE_WORDS = setOf(
        "garden", "park", "forest", "field", "meadow", "greenhouse", "backyard", "yard", "farm",
    )
    private val FAUNA_SUBJECT_WORDS = setOf(
        "cat", "kitten", "dog", "puppy", "bird", "horse", "cow", "sheep", "goat", "rabbit",
        "squirrel", "deer", "fox", "bear", "elephant", "lion", "tiger", "monkey", "owl", "duck",
        "chicken", "turtle", "butterfly", "fish", "animal",
    )
    private val FOOD_SUBJECT_WORDS = setOf(
        "pizza", "cake", "bread", "coffee", "tea", "dessert", "breakfast", "lunch", "dinner",
        "salad", "sandwich", "food", "dish", "meal",
    )
    private val VEHICLE_SUBJECT_WORDS = setOf(
        "car", "truck", "motorcycle", "bicycle", "bike", "bus", "train", "airplane", "boat",
        "scooter", "vehicle",
    )
    private val ARCHITECTURE_SUBJECT_WORDS = setOf(
        "building", "skyscraper", "tower", "bridge", "cathedral", "church", "castle", "house",
        "monument", "landmark",
    )
    private val EVENT_SUBJECT_WORDS = setOf(
        "party", "wedding", "birthday", "concert", "festival", "graduation", "parade",
        "fireworks", "celebration",
    )
    private val NATURE_SUBJECT_WORDS = setOf(
        "mountain", "beach", "sunset", "sunrise", "forest", "lake", "river", "waterfall",
        "valley", "desert", "snow", "landscape", "sea", "ocean",
    )

    fun generate(labels: List<String>, categories: Set<SceneCategory>, faceCount: Int): GeneratedCaption {
        val clean = labels.map { it.trim() }.filter { it.isNotEmpty() }
        val used = LinkedHashSet<String>()
        val caption = caption(clean, categories, faceCount, used)
        return GeneratedCaption(caption, hashtags(categories, used))
    }

    private fun caption(
        labels: List<String>,
        categories: Set<SceneCategory>,
        faceCount: Int,
        used: MutableSet<String>,
    ): String = when {
        faceCount >= SceneClassifier.GROUP_PHOTO_MIN_FACES ->
            "A group photo of ${numberWord(faceCount)} people"
        SceneCategory.PORTRAIT_OFFICIAL in categories -> "A portrait photo"
        faceCount == 2 -> "A photo of two people"
        faceCount == 1 -> "A photo of one person"
        SceneCategory.FLORA in categories -> floraCaption(labels, used) ?: fallback(labels, used)
        SceneCategory.FAUNA in categories ->
            subjectCaption(labels, FAUNA_SUBJECT_WORDS, used, withArticle = true) ?: fallback(labels, used)
        SceneCategory.FOOD in categories ->
            subjectCaption(labels, FOOD_SUBJECT_WORDS, used, withArticle = false) ?: fallback(labels, used)
        SceneCategory.VEHICLE in categories ->
            subjectCaption(labels, VEHICLE_SUBJECT_WORDS, used, withArticle = true) ?: fallback(labels, used)
        SceneCategory.ARCHITECTURE in categories ->
            subjectCaption(labels, ARCHITECTURE_SUBJECT_WORDS, used, withArticle = true) ?: fallback(labels, used)
        SceneCategory.DOCUMENT in categories -> "A photo of a document"
        SceneCategory.EVENT in categories ->
            subjectCaption(labels, EVENT_SUBJECT_WORDS, used, withArticle = true) ?: fallback(labels, used)
        SceneCategory.NATURE in categories ->
            subjectCaption(labels, NATURE_SUBJECT_WORDS, used, withArticle = true) ?: fallback(labels, used)
        else -> fallback(labels, used)
    }

    /**
     * The one category with a specific two-slot template ("Flowers in a garden") rather than
     * the generic "A photo of X" every other branch uses -- flora is the case where a *place*
     * word (garden, park, forest...) commonly sits right next to the subject in ML Kit's own
     * output, and saying so reads far more like a caption a person would write than the
     * generic template does. No other category's label vocabulary offered the same reliable
     * subject+place pairing, which is why this one branch gets special treatment and the rest
     * do not.
     */
    private fun floraCaption(labels: List<String>, used: MutableSet<String>): String? {
        val subject = labels.firstOrNull { matchesWord(it, FLORA_SUBJECT_WORDS) } ?: return null
        val place = labels.firstOrNull { matchesWord(it, PLACE_WORDS) }
        val pluralSubject = pluralize(subject).replaceFirstChar { it.uppercase() }
        used += pluralSubject
        return if (place != null) {
            used += place
            "$pluralSubject in a ${place.lowercase()}"
        } else {
            "A photo of ${pluralSubject.lowercase()}"
        }
    }

    private fun subjectCaption(
        labels: List<String>,
        keywords: Set<String>,
        used: MutableSet<String>,
        withArticle: Boolean,
    ): String? {
        val subject = labels.firstOrNull { matchesWord(it, keywords) } ?: return null
        used += subject
        val lower = subject.lowercase()
        return if (withArticle) "A photo of ${article(lower)} $lower" else "A photo of $lower"
    }

    /** No category matched (or matched but none of its subject words were actually present) -- fall back to the top label. */
    private fun fallback(labels: List<String>, used: MutableSet<String>): String {
        val topLabel = labels.firstOrNull() ?: return "A photo"
        used += topLabel
        val lower = topLabel.lowercase()
        return "A photo of ${article(lower)} $lower"
    }

    private fun hashtags(categories: Set<SceneCategory>, used: Set<String>): List<String> {
        val out = LinkedHashSet<String>()
        used.forEach { term -> hashtagOf(term)?.let { out += it } }
        SceneCategory.entries.forEach { category ->
            if (category in categories) CATEGORY_HASHTAGS[category]?.let { out += it }
        }
        return out.take(MAX_HASHTAGS).toList()
    }

    /** Word-boundary match, the same reasoning as [IdentityDocumentHeuristics.containsPhrase]: "cats" must not miss "cat", and "cattle" must not falsely hit it either. */
    private fun matchesWord(label: String, keywords: Set<String>): Boolean {
        val words = label.lowercase().split(WORD_SPLIT).filter { it.isNotEmpty() }
        return words.any { it in keywords }
    }

    private fun hashtagOf(word: String): String? {
        val cleaned = word.lowercase().filter { it.isLetterOrDigit() }
        if (cleaned.isEmpty()) return null
        return "#$cleaned"
    }

    private fun article(lowercaseWord: String): String =
        if (lowercaseWord.firstOrNull() in VOWELS) "an" else "a"

    private val VOWELS = setOf('a', 'e', 'i', 'o', 'u')
    private val WORD_SPLIT = Regex("[^a-z0-9]+")

    /** Small and deliberately short: the only caller is the group-photo caption, and nobody reads "12 people" as a number word past a handful anyway. */
    private fun numberWord(count: Int): String = when (count) {
        3 -> "three"
        4 -> "four"
        5 -> "five"
        6 -> "six"
        else -> count.toString()
    }

    /**
     * Naive English pluralisation -- correct for the plain common-noun labels ML Kit's base
     * labeller actually returns ("flower", "tree", "building"), not attempted as a general
     * solution. The one irregular the labeller is known to emit ("leaf") is special-cased;
     * everything else falls through to the regular rule, which is right far more often than
     * wrong for a vocabulary this plain.
     */
    private fun pluralize(word: String): String {
        val lower = word.lowercase()
        return when {
            lower.endsWith("s") -> word
            lower == "leaf" -> word.dropLast(1) + "ves"
            lower.endsWith("y") && word.length > 1 && word[word.length - 2].lowercaseChar() !in VOWELS ->
                word.dropLast(1) + "ies"
            lower.endsWith("x") || lower.endsWith("ch") || lower.endsWith("sh") || lower.endsWith("z") ->
                word + "es"
            else -> word + "s"
        }
    }
}
