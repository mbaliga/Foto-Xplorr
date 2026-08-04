package com.fotoxplorr.app.recognition

/**
 * Decides whether an image looks like an identity document, from on-device OCR text.
 *
 * ## Inferred product meaning -- NEEDS OWNER CONFIRMATION
 *
 * The mockups label this destination only "Identity", with no copy defining it. Nothing in
 * the repo defines it either. This implementation reads it as **"photos of my identity
 * documents"** -- passports, driving licences, national ID cards, residence permits, and the
 * card-shaped things people photograph for the same reason (insurance, membership, boarding
 * passes). That reading was chosen because:
 *
 *  - it is the only reading that does not duplicate an adjacent destination (a "photos of
 *    me / my own face" reading is People with one cluster pinned; a "biometric unlock"
 *    reading is a settings feature, not a gallery destination), and
 *  - it is the reading a privacy-first local gallery can actually serve well: finding and
 *    then protecting these images is a real job people have.
 *
 * If the owner meant something else, only this file and the destination's copy need to
 * change -- the storage, indexing and UI wiring are meaning-agnostic.
 *
 * ## How it decides
 *
 * Scoring over normalised OCR text, requiring *converging* evidence rather than a single
 * keyword, because a single word like "passport" appears in plenty of screenshots (airline
 * emails, forms). A strong document-type phrase plus one field cue, or several field cues
 * together, is what trips it. Machine-readable-zone lines (the `<<<`-chevron block on
 * passports and ID cards) are near-conclusive on their own.
 *
 * Everything here is pure and offline: the OCR itself is ML Kit's bundled on-device Latin
 * recogniser, and the extracted text is scored and discarded -- it is never stored and never
 * sent anywhere.
 */
object IdentityDocumentHeuristics {

    const val DECISION_THRESHOLD: Int = 5

    /** Names the document type outright. */
    private val DOCUMENT_TYPE_PHRASES = listOf(
        "passport", "driving licence", "driver's license", "drivers license", "driving license",
        "driver licence", "identity card", "identification card", "national id", "id card",
        "residence permit", "residence card", "aadhaar", "pan card", "social security",
        "health insurance card", "insurance card", "membership card", "boarding pass",
        "voter id", "permanent account number",
    )

    /**
     * Field labels that appear on documents but rarely together elsewhere. Kept deliberately
     * specific: a generic word like "class" (a real driving-licence field) also appears in
     * ordinary messages about school classes, and a unit test caught it scoring on exactly
     * that, so it was removed rather than left to false-positive.
     */
    private val FIELD_PHRASES = listOf(
        "date of birth", "dob", "place of birth", "date of issue", "date of expiry",
        "expiry date", "expires", "valid until", "valid from", "nationality", "surname",
        "given name", "given names", "sex", "issuing authority", "authority", "holder",
        "signature", "licence no", "license no", "document no", "card number", "id no",
        "issued by", "blood group", "father's name", "endorsements",
    )

    /** Issuer-ish words: weak alone, meaningful next to the above. */
    private val ISSUER_PHRASES = listOf(
        "republic", "government", "ministry", "department of", "state of", "kingdom of",
        "union of", "federal", "dvla", "dmv",
    )

    fun classify(ocrText: String): IdentityVerdict =
        if (score(ocrText) >= DECISION_THRESHOLD) IdentityVerdict.DOCUMENT else IdentityVerdict.NONE

    /** Exposed for tests and for tuning [DECISION_THRESHOLD] against real samples. */
    fun score(ocrText: String): Int {
        val normalized = normalize(ocrText)
        if (normalized.isBlank()) return 0

        // A machine-readable zone is essentially conclusive: two 30+ character lines of
        // uppercase/digits/chevrons is a format almost nothing else produces.
        if (hasMachineReadableZone(ocrText)) return DECISION_THRESHOLD + 3

        val typeHits = DOCUMENT_TYPE_PHRASES.count { normalized.containsPhrase(it) }
        val fieldHits = FIELD_PHRASES.count { normalized.containsPhrase(it) }
        val issuerHits = ISSUER_PHRASES.count { normalized.containsPhrase(it) }

        var score = 0
        score += (typeHits.coerceAtMost(2)) * 3
        score += fieldHits.coerceAtMost(4)
        score += if (issuerHits > 0) 1 else 0

        // A type phrase with nothing else around it is far more likely to be prose about a
        // document than a photo of one, so require at least one corroborating cue.
        if (typeHits > 0 && fieldHits == 0 && issuerHits == 0) score -= 2
        return score.coerceAtLeast(0)
    }

    fun hasMachineReadableZone(ocrText: String): Boolean {
        val candidates = ocrText.lineSequence()
            .map { it.trim().replace(" ", "") }
            .filter { it.length >= 28 && it.contains("<<") }
            .filter { line -> line.all { it in 'A'..'Z' || it in '0'..'9' || it == '<' } }
            .count()
        return candidates >= 2
    }

    /** Word-boundary containment, so "sex" does not match "sussex" nor "dob" match "dobson". */
    private fun String.containsPhrase(phrase: String): Boolean {
        val needle = normalize(phrase)
        if (needle.isEmpty()) return false
        return " $this ".contains(" $needle ")
    }

    /** Lowercases and reduces every non-alphanumeric run to a single space. */
    private fun normalize(text: CharSequence): String = buildString(text.length) {
        text.forEach { character ->
            append(if (character.isLetterOrDigit()) character.lowercaseChar() else ' ')
        }
    }.replace(WHITESPACE, " ").trim()

    private val WHITESPACE = Regex("\\s+")
}
