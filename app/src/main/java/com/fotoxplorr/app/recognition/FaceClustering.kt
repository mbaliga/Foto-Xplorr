package com.fotoxplorr.app.recognition

import com.fotoxplorr.app.media.MediaId
import kotlin.math.sqrt

/**
 * Pure, dependency-free grouping of face descriptors into people.
 *
 * Single-link agglomerative clustering over cosine distance, run greedily: descriptors are
 * visited largest-face-first (bigger faces give more reliable descriptors) and each one
 * either joins the nearest existing cluster whose *centroid* is within [maxCosineDistance]
 * or starts a new cluster. That ordering makes the result deterministic for a given input
 * list, which matters because this feeds a UI that users will re-open and expect to be
 * stable.
 *
 * This is intentionally not a full HAC/DBSCAN implementation: the descriptor quality
 * available on-device (a general-purpose image embedder over a face crop, not a
 * face-recognition-specific network) does not justify a more elaborate algorithm, and a
 * conservative threshold that occasionally splits one person into two clusters is a much
 * better failure mode for a photo gallery than merging two people into one.
 */
object FaceClustering {

    /**
     * Cosine distance (1 - cosine similarity) above which two faces are treated as different
     * people.
     *
     * ### Why this number is so small
     * Cosine distance over [FaceDescriptorBuilder]'s descriptor is *compressive*: because the
     * descriptor is a set of aligned coordinates rather than a trained embedding, even a
     * drastic change in face geometry moves it only a little. A face stretched to 2.4x its
     * height -- far more distortion than any two real people differ by -- lands at a cosine
     * distance of roughly 0.075 (asserted in FaceDescriptorBuilderTest, so the figure stays
     * honest if the descriptor changes). A threshold in the 0.3 range, which is the usual
     * ballpark for a trained face embedding, would therefore merge the entire library into a
     * single "person". This is set well below that measured figure instead.
     *
     * It is still **not calibrated against a labelled face dataset** -- no such data was
     * available here. It is chosen to fail in the recoverable direction: too tight, so one
     * person may appear as several groups, rather than too loose, which would show one
     * person's photos under another's.
     */
    const val DEFAULT_MAX_COSINE_DISTANCE: Float = 0.03f

    /** Faces smaller than this fraction of the frame are too low-detail to cluster. */
    const val MIN_RELATIVE_AREA: Float = 0.004f

    fun cluster(
        descriptors: List<FaceDescriptor>,
        maxCosineDistance: Float = DEFAULT_MAX_COSINE_DISTANCE,
        minRelativeArea: Float = MIN_RELATIVE_AREA,
    ): List<PersonCluster> {
        val usable = descriptors
            .filter { it.relativeArea >= minRelativeArea && it.vector.isNotEmpty() }
            .sortedWith(
                compareByDescending<FaceDescriptor> { it.relativeArea }
                    .thenBy { it.mediaId.value }
                    .thenBy { it.faceIndex },
            )
        if (usable.isEmpty()) return emptyList()

        val centroids = ArrayList<FloatArray>()
        val members = ArrayList<MutableList<FaceDescriptor>>()

        usable.forEach { descriptor ->
            var bestIndex = -1
            var bestDistance = Float.MAX_VALUE
            centroids.forEachIndexed { index, centroid ->
                if (centroid.size != descriptor.vector.size) return@forEachIndexed
                val distance = cosineDistance(centroid, descriptor.vector)
                if (distance < bestDistance) {
                    bestDistance = distance
                    bestIndex = index
                }
            }
            if (bestIndex >= 0 && bestDistance <= maxCosineDistance) {
                members[bestIndex] += descriptor
                centroids[bestIndex] = recomputeCentroid(members[bestIndex])
            } else {
                members += mutableListOf(descriptor)
                centroids += normalized(descriptor.vector)
            }
        }

        // Largest groups first: that is the order the People destination lists them in.
        return members
            .mapIndexed { index, group -> index to group }
            .sortedWith(
                compareByDescending<Pair<Int, List<FaceDescriptor>>> { it.second.size }
                    .thenBy { it.second.minOf { face -> face.mediaId.value } },
            )
            .mapIndexed { rank, (_, group) ->
                PersonCluster(
                    id = rank,
                    // MediaId is a value class over Long and so is not Comparable; sort on
                    // the wrapped value. Newest-id-first matches the grid's default order.
                    mediaIds = group.map { it.mediaId }.distinct().sortedByDescending { it.value },
                    faceCount = group.size,
                )
            }
    }

    /** Cosine distance in [0, 2]. Zero-length vectors are treated as maximally distant. */
    fun cosineDistance(a: FloatArray, b: FloatArray): Float {
        if (a.isEmpty() || b.isEmpty() || a.size != b.size) return Float.MAX_VALUE
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (index in a.indices) {
            dot += a[index] * b[index]
            normA += a[index] * a[index]
            normB += b[index] * b[index]
        }
        if (normA <= 0f || normB <= 0f) return Float.MAX_VALUE
        val similarity = dot / (sqrt(normA) * sqrt(normB))
        return 1f - similarity.coerceIn(-1f, 1f)
    }

    /** Returns an L2-normalised copy; a zero vector is returned unchanged. */
    fun normalized(vector: FloatArray): FloatArray {
        var sumOfSquares = 0f
        vector.forEach { sumOfSquares += it * it }
        if (sumOfSquares <= 0f) return vector.copyOf()
        val inverse = 1f / sqrt(sumOfSquares)
        return FloatArray(vector.size) { vector[it] * inverse }
    }

    private fun recomputeCentroid(group: List<FaceDescriptor>): FloatArray {
        val length = group.first().vector.size
        val sum = FloatArray(length)
        var counted = 0
        group.forEach { descriptor ->
            if (descriptor.vector.size != length) return@forEach
            for (index in 0 until length) sum[index] += descriptor.vector[index]
            counted += 1
        }
        if (counted == 0) return sum
        for (index in 0 until length) sum[index] /= counted
        return normalized(sum)
    }
}

/**
 * Everything a destination needs to answer "which assets belong here", derived purely from
 * stored [AssetRecognition] rows. Kept separate from the UI so it is unit-testable.
 */
data class RecognitionIndex(
    val people: List<PersonCluster>,
    val peopleMediaIds: Set<MediaId>,
    val petMediaIds: Set<MediaId>,
    val identityMediaIds: Set<MediaId>,
    /**
     * What the labeller saw, per photo — the vocabulary `label:flower` searches against.
     *
     * Per-media rather than folded into a set of ids like the three above, because this index is
     * the only published view of recognition data and search needs to know *which* photo has
     * which label, not merely that some photo does.
     */
    val labelsByMedia: Map<MediaId, List<String>> = emptyMap(),
    /** OCR text per photo, positioned. Feeds both text search and selecting text on a photo. */
    val textByMedia: Map<MediaId, List<TextBlock>> = emptyMap(),
    /**
     * [SceneCategory] set per photo -- the union of what [SceneClassifier.classify] decided
     * from that photo alone (persisted on `AssetRecognition.categories`) and, where it
     * qualifies, [SceneCategory.FRIENDS_FAMILY] from THIS library's current clustering (see
     * [SceneClassifier.withRecurringPeopleContext]). That second part is why this field lives
     * on the derived [RecognitionIndex] rather than only ever being read off the stored row:
     * it can change as more of a person's photos get indexed, even though this photo's own
     * file never did.
     *
     * Sparse like [labelsByMedia] and [textByMedia]: a photo with no categories is simply
     * absent from the map, not mapped to an empty set.
     */
    val categoriesByMedia: Map<MediaId, Set<SceneCategory>> = emptyMap(),
    /**
     * The sentence [CaptionGenerator] wrote for each photo during indexing.
     *
     * Computed per photo and stored on its [AssetRecognition] row since captioning landed, but
     * until now never published anywhere a reader could reach — so the app was writing a caption
     * for every photo in the library and then had no way to show one. Exposed here for the same
     * reason [labelsByMedia] is: this index is recognition's only published view, and a fact that
     * does not appear in it may as well not have been computed.
     *
     * Sparse, like its neighbours: a photo the generator had nothing to say about is absent
     * rather than mapped to "".
     */
    val captionsByMedia: Map<MediaId, String> = emptyMap(),
) {
    /** Every distinct label in the library, for offering search alternatives that exist. */
    val allLabels: Set<String> by lazy(LazyThreadSafetyMode.NONE) {
        labelsByMedia.values.flatMapTo(linkedSetOf()) { it }
    }

    /** The flat text of one photo, for matching. Empty when nothing was read. */
    fun textOf(mediaId: MediaId): String =
        textByMedia[mediaId]?.joinToString("\n") { it.text }.orEmpty()

    /** Reverse of [categoriesByMedia], built once and reused: destinations ask "which photos", not "what is this photo". */
    private val mediaIdsByCategory: Map<SceneCategory, Set<MediaId>> by lazy(LazyThreadSafetyMode.NONE) {
        buildMap<SceneCategory, MutableSet<MediaId>> {
            categoriesByMedia.forEach { (mediaId, categories) ->
                categories.forEach { category -> getOrPut(category) { linkedSetOf() } += mediaId }
            }
        }
    }

    /** Every photo tagged with [category]. Empty before recognition has run, same as [peopleMediaIds] and friends. */
    fun mediaIn(category: SceneCategory): Set<MediaId> = mediaIdsByCategory[category].orEmpty()

    companion object {
        val EMPTY = RecognitionIndex(emptyList(), emptySet(), emptySet(), emptySet())

        fun from(
            rows: Collection<AssetRecognition>,
            maxCosineDistance: Float = FaceClustering.DEFAULT_MAX_COSINE_DISTANCE,
        ): RecognitionIndex {
            if (rows.isEmpty()) return EMPTY
            val descriptors = rows.flatMap { it.faceDescriptors }
            val people = FaceClustering.cluster(descriptors, maxCosineDistance)
            // A person only reads as "recurring" once SceneClassifier.RECURRING_PERSON_MIN_PHOTOS
            // of their photos exist in the library -- see SceneClassifier's own KDoc for why
            // that can only be known here, after clustering the *whole* library, and not at the
            // point any single photo was indexed.
            val recurringPersonMediaIds: Set<MediaId> = people
                .asSequence()
                .filter { it.mediaIds.size >= SceneClassifier.RECURRING_PERSON_MIN_PHOTOS }
                .flatMapTo(linkedSetOf()) { it.mediaIds }
            return RecognitionIndex(
                people = people,
                peopleMediaIds = rows.filter { it.faceCount > 0 }.mapTo(linkedSetOf()) { it.mediaId },
                petMediaIds = rows.filter { it.petVerdict.isPet }.mapTo(linkedSetOf()) { it.mediaId },
                identityMediaIds = rows.filter { it.identityVerdict.isIdentity }
                    .mapTo(linkedSetOf()) { it.mediaId },
                labelsByMedia = rows.filter { it.labels.isNotEmpty() }
                    .associate { it.mediaId to it.labels },
                textByMedia = rows.filter { it.textBlocks.isNotEmpty() }
                    .associate { it.mediaId to it.textBlocks },
                categoriesByMedia = rows.associate { row ->
                    row.mediaId to SceneClassifier.withRecurringPeopleContext(
                        base = row.categories,
                        faceCount = row.faceCount,
                        hasRecurringPerson = row.mediaId in recurringPersonMediaIds,
                    )
                }.filterValues { it.isNotEmpty() },
                captionsByMedia = rows.filter { it.caption.isNotBlank() }
                    .associate { it.mediaId to it.caption },
            )
        }
    }
}
