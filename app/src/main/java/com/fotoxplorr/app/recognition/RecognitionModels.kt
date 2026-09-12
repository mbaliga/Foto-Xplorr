package com.fotoxplorr.app.recognition

import com.fotoxplorr.app.media.MediaId

/**
 * On-device recognition results for a single image.
 *
 * Everything in this package runs locally: ML Kit's bundled face-detection, image-labelling
 * and text-recognition models plus the pure clustering/heuristic functions here. No image,
 * crop, descriptor or extracted string is ever sent to a network endpoint -- the app's
 * opt-in BYOK remote-AI path (`com.fotoxplorr.app.ai`) is deliberately not involved.
 */
data class AssetRecognition(
    val mediaId: MediaId,
    /** Revision of the source file this result was computed from; stale rows are recomputed. */
    val sourceRevision: Long,
    val faceCount: Int,
    /** One descriptor per detected face, used for person clustering. May be empty. */
    val faceDescriptors: List<FaceDescriptor> = emptyList(),
    val petVerdict: PetVerdict = PetVerdict.NONE,
    val identityVerdict: IdentityVerdict = IdentityVerdict.NONE,
    /**
     * What the on-device labeller saw — "Flower", "Dog", "Beach". Previously computed, fed to
     * [PetClassifier] and then dropped on the floor, which is why searching for `flower` could
     * never work: the app knew, and forgot, several times a second.
     */
    val labels: List<String> = emptyList(),
    /**
     * Text found in the image by on-device OCR, as positioned blocks.
     *
     * Blocks rather than one string, and normalised boxes rather than pixels, because two features
     * need the geometry: searching wants the words, but selecting text on the photo needs to know
     * *where* each run sits, at whatever size the image is being displayed. Storing a flat string
     * would have made the second feature impossible without a full re-scan of the library.
     */
    val textBlocks: List<TextBlock> = emptyList(),
    /**
     * What [SceneClassifier] decided this photo is about, from this photo's own labels and
     * faces alone. Never contains [SceneCategory.FRIENDS_FAMILY] -- that one depends on
     * whether the people in this photo recur *elsewhere* in the library, which is not knowable
     * from a single photo and is not persisted here; see [SceneClassifier]'s KDoc and
     * [RecognitionIndex.categoriesByMedia], which adds it back in at read time.
     */
    val categories: Set<SceneCategory> = emptySet(),
    /**
     * [CaptionGenerator]'s one-line description of this photo, from its labels, [categories]
     * and face count. Empty when there was nothing to say (no labels, no faces) -- an empty
     * string, not a placeholder sentence, because inventing content for an uninteresting or
     * blank photo would be exactly the fabrication [PersonCluster]'s numbered-not-named people
     * and [IdentityDocumentHeuristics]'s uncertainty notes both deliberately avoid elsewhere in
     * this package.
     */
    val caption: String = "",
    /** [CaptionGenerator]'s hashtags for [caption]. Ordered -- the words the caption itself used come first. */
    val hashtags: List<String> = emptyList(),
) {
    /** Every OCR run joined, for the search index and the document heuristics. */
    val text: String get() = textBlocks.joinToString("\n") { it.text }
}

/**
 * One run of recognised text and where it sits in the frame.
 *
 * The box is normalised to 0..1 of the decoded image, so it survives the downsampling the indexer
 * does when loading, and maps onto any display rectangle by simple multiplication.
 */
data class TextBlock(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/**
 * A fixed-length, L2-normalised descriptor of one detected face, together with where in the
 * frame it was found. Descriptors are only ever compared with each other, never stored as
 * an image, and never leave the device.
 */
data class FaceDescriptor(
    val mediaId: MediaId,
    /** Index of this face within its source image (0-based), so a descriptor is addressable. */
    val faceIndex: Int,
    val vector: FloatArray,
    /** Face bounding-box area as a fraction of the frame; larger faces cluster more reliably. */
    val relativeArea: Float,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FaceDescriptor) return false
        return mediaId == other.mediaId &&
            faceIndex == other.faceIndex &&
            relativeArea == other.relativeArea &&
            vector.contentEquals(other.vector)
    }

    override fun hashCode(): Int {
        var result = mediaId.hashCode()
        result = 31 * result + faceIndex
        result = 31 * result + relativeArea.hashCode()
        result = 31 * result + vector.contentHashCode()
        return result
    }
}

enum class PetVerdict {
    NONE,
    CAT,
    DOG,
    /** A pet-like animal that is neither confidently cat nor dog (bird, rabbit, …). */
    OTHER_PET,
    ;

    val isPet: Boolean get() = this != NONE
}

enum class IdentityVerdict {
    NONE,

    /**
     * The image reads as an identity document (passport, driving licence, national ID,
     * membership/insurance card, boarding pass) from the text found on it.
     *
     * INFERRED MEANING -- the mockups label this destination only "Identity" with no
     * further definition. See [IdentityDocumentHeuristics] for the full note; this needs
     * owner confirmation before it should be treated as settled product behaviour.
     */
    DOCUMENT,
    ;

    val isIdentity: Boolean get() = this != NONE
}

/**
 * A coarse "what kind of photo is this" tag, decided by [SceneClassifier] from ML Kit's
 * labels, face count and face geometry. A photo can carry several at once -- a photo of a
 * birthday cake is both [FOOD] and [EVENT] -- so [AssetRecognition.categories] is a set, not a
 * single verdict the way [PetVerdict] is.
 *
 * See [SceneClassifier]'s own KDoc for the confidence bar and keyword sets behind each one,
 * and -- for the categories that are judgement calls rather than a direct reading of ML Kit
 * output -- the reasoning and the "needs owner confirmation" notes.
 */
enum class SceneCategory {
    FLORA,
    FAUNA,
    ARCHITECTURE,
    FOOD,
    VEHICLE,

    /**
     * "This photo is of paper or a screen", from what it visually looks like. Deliberately
     * NOT the same claim as [IdentityVerdict.DOCUMENT], which means "this is specifically a
     * passport/ID-shaped document" and is scored from what on-device OCR found the text to
     * *say*. A menu, a whiteboard or a screenshotted email is this category and never that
     * one; a passport photographed at an angle the OCR reads badly could in principle be the
     * reverse. See [SceneClassifier] for the full note.
     */
    DOCUMENT,

    /** Three or more faces in frame. See [SceneClassifier.GROUP_PHOTO_MIN_FACES]. */
    GROUP_PHOTO,

    /**
     * A small gathering (see [SceneClassifier.FRIENDS_FAMILY_FACE_RANGE]) with someone the
     * library has seen elsewhere. INFERRED MEANING, needs owner confirmation -- see
     * [SceneClassifier].
     */
    FRIENDS_FAMILY,

    /**
     * One large, centred face against a visually plain scene -- passport/ID-photo-style
     * framing. See [SceneClassifier] for the heuristic and its known imprecision.
     */
    PORTRAIT_OFFICIAL,
    NATURE,

    /** INFERRED MEANING, needs owner confirmation -- see [SceneClassifier]. */
    EVENT,
}

/** A group of faces believed to belong to the same person, and the images they came from. */
data class PersonCluster(
    val id: Int,
    val mediaIds: List<MediaId>,
    val faceCount: Int,
) {
    val size: Int get() = mediaIds.size
}
