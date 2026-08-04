package com.fotoxplorr.app.recognition

/** One label as returned by ML Kit's on-device image labeller. */
data class ImageLabel(val text: String, val confidence: Float)

/**
 * Turns ML Kit image-labelling output into a [PetVerdict]. Pure, so it can be unit-tested
 * without an Android runtime or the ML Kit model.
 *
 * ML Kit's bundled base labeller emits ~400 generic entity labels. It has "Cat" and "Dog"
 * but also plenty of near-misses ("Fur", "Whiskers", "Snout"), so this uses a two-tier
 * scheme: a *species* label at [SPECIES_CONFIDENCE] is enough on its own, while weaker
 * *supporting* evidence only counts when paired with a generic animal/pet label. That keeps
 * the Pets destination from filling up with close-cropped fur textures and soft toys.
 */
object PetClassifier {

    const val SPECIES_CONFIDENCE: Float = 0.62f
    const val SUPPORTING_CONFIDENCE: Float = 0.55f

    private val CAT_LABELS = setOf("cat", "kitten")
    private val DOG_LABELS = setOf("dog", "puppy")

    /** Companion animals that are neither cat nor dog but still belong in "Pets". */
    private val OTHER_PET_LABELS = setOf(
        "rabbit", "hamster", "guinea pig", "ferret", "parrot", "parakeet", "budgerigar",
        "canary", "cockatoo", "turtle", "tortoise", "goldfish", "horse", "pony",
    )

    /** Generic labels that establish "this frame contains an animal" but not which one. */
    private val ANIMAL_LABELS = setOf("animal", "pet", "mammal", "bird", "wildlife")

    /** Weak cues that only count alongside an [ANIMAL_LABELS] hit. */
    private val SUPPORTING_LABELS = setOf("fur", "whiskers", "snout", "paw", "tail", "muzzle")

    fun classify(labels: List<ImageLabel>): PetVerdict {
        if (labels.isEmpty()) return PetVerdict.NONE
        val byText = labels.associate { it.text.trim().lowercase() to it.confidence }

        fun confidenceOfAny(candidates: Set<String>): Float =
            candidates.mapNotNull { byText[it] }.maxOrNull() ?: 0f

        val cat = confidenceOfAny(CAT_LABELS)
        val dog = confidenceOfAny(DOG_LABELS)
        if (cat >= SPECIES_CONFIDENCE || dog >= SPECIES_CONFIDENCE) {
            return if (cat >= dog) PetVerdict.CAT else PetVerdict.DOG
        }

        if (confidenceOfAny(OTHER_PET_LABELS) >= SPECIES_CONFIDENCE) return PetVerdict.OTHER_PET

        val animal = confidenceOfAny(ANIMAL_LABELS)
        if (animal >= SUPPORTING_CONFIDENCE) {
            // A generic animal hit plus a species hint that was just below the species bar.
            if (cat >= SUPPORTING_CONFIDENCE || dog >= SUPPORTING_CONFIDENCE) {
                return if (cat >= dog) PetVerdict.CAT else PetVerdict.DOG
            }
            if (confidenceOfAny(SUPPORTING_LABELS) >= SUPPORTING_CONFIDENCE ||
                confidenceOfAny(OTHER_PET_LABELS) >= SUPPORTING_CONFIDENCE
            ) {
                return PetVerdict.OTHER_PET
            }
        }
        return PetVerdict.NONE
    }
}
