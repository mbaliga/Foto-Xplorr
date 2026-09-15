package com.fotoxplorr.app.recognition

import kotlin.math.abs

/**
 * One detected face's size and position within its own frame -- as much geometry as scene
 * classification needs, deliberately less than [FaceDescriptor] carries.
 *
 * Kept as its own type rather than reusing [FaceDescriptor]: that type's shape is dictated by
 * [FaceClustering] (a comparison vector plus an area, nothing about *where* in the frame the
 * face sits), and growing it with position fields only this file reads would make every
 * clustering call site carry data it never uses. Nothing here clusters faces across photos --
 * it only asks "is there one big, centred face in this one photo".
 */
data class SceneFace(
    /** Bounding-box area as a fraction of the frame. Same convention as [FaceDescriptor.relativeArea]. */
    val relativeArea: Float,
    /** Horizontal centre of the box as a fraction of frame width, 0 (left) .. 1 (right). */
    val centerXFraction: Float,
    /** Vertical centre of the box as a fraction of frame height, 0 (top) .. 1 (bottom). */
    val centerYFraction: Float,
)

/**
 * Turns ML Kit image-labelling output, plus the face-detection results already computed for
 * People, into the [SceneCategory] set for one photo. Pure, like [PetClassifier] and
 * [IdentityDocumentHeuristics] beside it, so the rules are unit-tested on the JVM rather than
 * eyeballed on a device.
 *
 * ## What is, and is not, decided here
 *
 * Every category except [SceneCategory.FRIENDS_FAMILY] is decided entirely from ONE photo's
 * own evidence -- its labels and its faces -- by [classify], and is computed once, when that
 * photo is first indexed (see `RecognitionIndexer`), then persisted on
 * `AssetRecognition.categories`.
 *
 * FRIENDS_FAMILY is different: "does a person in this photo recur elsewhere in the library" is
 * not knowable from one photo in isolation, and a photo indexed *before* enough of the same
 * person's other photos exist would be judged wrongly and then never revisited -- recognition
 * only reruns a photo when its own file changes (see `MediaAsset.recognitionRevision`), not
 * when some *other* photo's clustering result changes. So [withRecurringPeopleContext] is a
 * second, separate step that `RecognitionIndex.from` applies fresh every time it re-clusters
 * faces, exactly as it already does for the `people` clusters themselves. It is intentionally
 * not folded into [classify] and its result is intentionally not persisted as its own bit --
 * persisting it would reintroduce the same staleness problem it exists to avoid.
 *
 * ## Confidence bar
 *
 * A single keyword hit at [SCENE_LABEL_CONFIDENCE] is enough to tag a category. That is
 * deliberately looser than [PetClassifier.SPECIES_CONFIDENCE]: getting Pets wrong mislabels a
 * *specific* animal, which people notice immediately, while these are broad, low-stakes
 * buckets a photo can belong to several of at once -- a garden photo missing Flora costs far
 * less than a photo missing Pets would. [PetClassifier.SUPPORTING_CONFIDENCE] is reused as
 * that bar rather than inventing a fourth number with no evidence behind it either.
 */
object SceneClassifier {

    /** Confidence bar for every keyword-set hit below. See the class KDoc for why it is one number. */
    const val SCENE_LABEL_CONFIDENCE: Float = PetClassifier.SUPPORTING_CONFIDENCE

    /** Faces at or above this count read as a photo OF a group, not a person who happens to share a frame with others. */
    const val GROUP_PHOTO_MIN_FACES: Int = 3

    /**
     * INFERRED MEANING -- NEEDS OWNER CONFIRMATION, the same status as
     * [IdentityDocumentHeuristics]'s own note. "Friends and family" is not defined anywhere the
     * mockups reach. This reads it as *small and informal, with someone the library has seen
     * before* -- a stranger who happens to be in a landscape shot is not "family" no matter how
     * few other faces are in frame, which is why recurrence ([RECURRING_PERSON_MIN_PHOTOS]) is
     * required and a small face count is not sufficient on its own.
     */
    val FRIENDS_FAMILY_FACE_RANGE: IntRange = 1..4

    /**
     * How many of a person's photos it takes for them to count as "recurring" for
     * [SceneCategory.FRIENDS_FAMILY]. Two is the minimum that means anything: appearing in
     * exactly one photo is indistinguishable from a stranger caught in a crowd shot, which is
     * exactly the case this category exists to exclude.
     */
    const val RECURRING_PERSON_MIN_PHOTOS: Int = 2

    /** Faces at or above this fraction of the frame read as a deliberate closeup, not incidental. */
    private const val LARGE_FACE_MIN_RELATIVE_AREA: Float = 0.10f

    /**
     * How far a face's centre may sit from the frame's exact centre (as a fraction of frame
     * width/height, checked on each axis independently) and still count as "centred" for
     * [SceneCategory.PORTRAIT_OFFICIAL]. Loose enough to allow for the rule-of-thirds framing a
     * phone camera's own guide lines nudge people toward, tight enough to reject a face caught
     * at the edge of a group.
     */
    private const val FACE_CENTER_TOLERANCE: Float = 0.16f

    /**
     * Distinct label count at or below which a scene is treated as visually plain enough for an
     * official-style portrait. IMPRECISE ON PURPOSE -- ML Kit's labeller has no "background" or
     * "backdrop" concept to key off directly; a plain wall and a plain sky both simply give the
     * labeller little to say, which is the only signal available here. A busy background (a
     * street, a crowd, a cluttered room) reliably produces more distinct labels than a seated
     * portrait against a wall does, so label *count* stands in for background complexity. This
     * will occasionally miss a portrait shot against an unusually label-rich backdrop (a
     * bookshelf, say) -- accepted, because the failure mode is "misses an official portrait",
     * never "invents one that was not there".
     */
    private const val MAX_LABELS_FOR_PLAIN_BACKGROUND: Int = 4

    private val FLORA_LABELS = setOf(
        "flower", "flowers", "plant", "plants", "tree", "trees", "leaf", "leaves", "garden",
        "blossom", "petal", "flora", "houseplant", "grass", "bush", "shrub", "cactus",
        "succulent", "moss", "bouquet", "flowering plant", "botany",
    )

    /** Broader than [PetClassifier]'s cat/dog-focused sets on purpose: FAUNA is "any animal", not "any companion animal". */
    private val FAUNA_LABELS = setOf(
        "animal", "wildlife", "mammal", "bird", "insect", "butterfly", "fish", "reptile",
        "cat", "kitten", "dog", "puppy", "horse", "pony", "cow", "cattle", "sheep", "goat",
        "pig", "rabbit", "hamster", "squirrel", "deer", "fox", "bear", "elephant", "lion",
        "tiger", "zebra", "giraffe", "monkey", "owl", "eagle", "duck", "chicken", "turtle",
        "snake", "lizard", "frog", "bee", "spider", "shark", "dolphin", "whale",
    )

    private val ARCHITECTURE_LABELS = setOf(
        "building", "architecture", "skyscraper", "tower", "bridge", "cathedral", "church",
        "temple", "mosque", "castle", "monument", "facade", "landmark", "cityscape",
        "urban area", "house", "roof", "column", "arch", "dome", "stairs",
    )

    private val FOOD_LABELS = setOf(
        "food", "dish", "meal", "cuisine", "dessert", "cake", "bread", "fruit", "vegetable",
        "drink", "beverage", "coffee", "tea", "cooking", "baking", "pizza", "sandwich",
        "salad", "breakfast", "lunch", "dinner", "snack", "recipe", "restaurant",
    )

    private val VEHICLE_LABELS = setOf(
        "car", "vehicle", "truck", "motorcycle", "bicycle", "bike", "bus", "train",
        "airplane", "aircraft", "boat", "ship", "scooter", "van", "taxi",
    )

    /**
     * Visual "this is a piece of paper or a screen full of text" evidence from the *labeller*,
     * not the OCR text [IdentityDocumentHeuristics] scores. See [SceneCategory.DOCUMENT]'s own
     * KDoc for why that is a deliberately different claim from [IdentityVerdict.DOCUMENT].
     */
    private val DOCUMENT_LABELS = setOf(
        "document", "paper", "receipt", "invoice", "book", "page", "screenshot", "whiteboard",
        "menu", "poster", "newspaper", "magazine", "handwriting", "text",
    )

    /**
     * INFERRED MEANING -- NEEDS OWNER CONFIRMATION. Nothing defines "Event" either. Read here
     * as *a gathering held FOR something* -- the label vocabulary ML Kit's base labeller
     * actually offers for celebrations -- rather than any day something notable happened, which
     * is not recoverable from an image labeller regardless of how this set is chosen.
     */
    private val EVENT_LABELS = setOf(
        "party", "celebration", "wedding", "birthday", "concert", "festival", "graduation",
        "ceremony", "parade", "fireworks", "reception", "anniversary",
    )

    private val NATURE_LABELS = setOf(
        "nature", "landscape", "outdoors", "mountain", "sky", "sea", "ocean", "beach",
        "sunset", "sunrise", "forest", "lake", "river", "waterfall", "cloud", "horizon",
        "wilderness", "field", "valley", "desert", "snow", "ice", "hill",
    )

    /**
     * Categories decided from this one photo alone. See the class KDoc for why
     * [SceneCategory.FRIENDS_FAMILY] never appears in this result.
     *
     * [faces] is optional and defaults to empty because most callers -- and every existing
     * test written before [SceneCategory.PORTRAIT_OFFICIAL] existed -- only have labels and a
     * face count to hand; without face geometry, PORTRAIT_OFFICIAL simply never fires, which is
     * the correct, conservative behaviour for missing evidence rather than an error.
     */
    fun classify(
        labels: List<ImageLabel>,
        faceCount: Int,
        faces: List<SceneFace> = emptyList(),
    ): Set<SceneCategory> {
        // Highest confidence per distinct label text: ML Kit does not repeat a label, but a
        // caller building a test fixture (or a future labeller pass merging two runs) might.
        val byText = HashMap<String, Float>()
        labels.forEach { label ->
            val key = label.text.trim().lowercase()
            if (key.isEmpty()) return@forEach
            val existing = byText[key]
            if (existing == null || label.confidence > existing) byText[key] = label.confidence
        }

        fun hits(keywords: Set<String>): Boolean =
            keywords.any { (byText[it] ?: 0f) >= SCENE_LABEL_CONFIDENCE }

        val out = LinkedHashSet<SceneCategory>()
        if (hits(FLORA_LABELS)) out += SceneCategory.FLORA
        if (hits(FAUNA_LABELS)) out += SceneCategory.FAUNA
        if (hits(ARCHITECTURE_LABELS)) out += SceneCategory.ARCHITECTURE
        if (hits(FOOD_LABELS)) out += SceneCategory.FOOD
        if (hits(VEHICLE_LABELS)) out += SceneCategory.VEHICLE
        if (hits(DOCUMENT_LABELS)) out += SceneCategory.DOCUMENT
        if (hits(EVENT_LABELS)) out += SceneCategory.EVENT
        if (hits(NATURE_LABELS)) out += SceneCategory.NATURE

        // Unlike the label-driven categories above, GROUP_PHOTO and PORTRAIT_OFFICIAL read the
        // frame itself rather than what ML Kit thinks is in it -- the task's own instruction,
        // because a labeller has no notion of "how many faces" or "how centred".
        if (faceCount >= GROUP_PHOTO_MIN_FACES) out += SceneCategory.GROUP_PHOTO
        if (isOfficialPortrait(faceCount, faces, byText.size)) out += SceneCategory.PORTRAIT_OFFICIAL

        return out
    }

    private fun isOfficialPortrait(faceCount: Int, faces: List<SceneFace>, labelDiversity: Int): Boolean {
        // Exactly one face, not "at least one": a second face anywhere in frame means this is a
        // couple or candid shot, not the single-subject framing an ID-style photo requires.
        if (faceCount != 1) return false
        val face = faces.singleOrNull() ?: return false
        if (face.relativeArea < LARGE_FACE_MIN_RELATIVE_AREA) return false
        if (abs(face.centerXFraction - 0.5f) > FACE_CENTER_TOLERANCE) return false
        if (abs(face.centerYFraction - 0.5f) > FACE_CENTER_TOLERANCE) return false
        return labelDiversity <= MAX_LABELS_FOR_PLAIN_BACKGROUND
    }

    /**
     * Adds [SceneCategory.FRIENDS_FAMILY] to [base] when this photo's own face count and the
     * library-wide recurrence of the people in it both qualify. See the class KDoc for why this
     * is a separate step from [classify] rather than folded into it -- callers are expected to
     * be `RecognitionIndex.from`, run once per full re-cluster, not `RecognitionIndexer`, run
     * once per photo.
     */
    fun withRecurringPeopleContext(
        base: Set<SceneCategory>,
        faceCount: Int,
        hasRecurringPerson: Boolean,
    ): Set<SceneCategory> =
        if (faceCount in FRIENDS_FAMILY_FACE_RANGE && hasRecurringPerson) {
            base + SceneCategory.FRIENDS_FAMILY
        } else {
            base
        }
}
