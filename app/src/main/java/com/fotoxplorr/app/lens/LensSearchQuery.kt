package com.fotoxplorr.app.lens

/**
 * Turning a photo's raw OCR text into something worth typing into search.
 *
 * Pure Kotlin, pinned in [LensSearchQueryTest] -- the one piece of the Search action that has a
 * right and a wrong answer independent of any device.
 */
object LensSearchQuery {

    /**
     * [recognizedText], collapsed to one line and capped.
     *
     * [com.fotoxplorr.app.recognition.RecognitionIndex.textOf] joins every OCR block with a
     * newline -- one run per line of a sign, one row per line of a receipt -- so a busy photo's
     * text can run to hundreds of characters across a dozen lines. Handed to
     * [com.fotoxplorr.app.search.parseSearchQuery] unchanged, the newlines themselves would cost
     * nothing (that parser tokenises on whitespace generically, so a line break already behaves
     * like a space to it) -- but a search field showing a dozen-line block of receipt text is not
     * something a person would ever type themselves, and [com.fotoxplorr.app.search] ANDs bare
     * words together by default, so a forty-word paragraph handed over whole demands all forty
     * appear on some OTHER photo too and matches nothing but the one it came from. Collapsing
     * whitespace and capping the length is what turns "the recognised text" into "a search" --
     * the same distinction [com.fotoxplorr.app.viewer.LiveTextOverlay] draws by leaving the
     * *whole block* unselected until a finger picks specific words off the photo.
     */
    fun buildQuery(recognizedText: String): String {
        val collapsed = recognizedText.replace(WHITESPACE_RUN, " ").trim()
        return if (collapsed.length <= MAX_QUERY_LENGTH) collapsed else collapsed.take(MAX_QUERY_LENGTH).trimEnd()
    }

    private val WHITESPACE_RUN = Regex("\\s+")

    /**
     * Long enough to carry a real phrase off a sign or a book spine; short enough that the
     * search field never fills with a wall of receipt text no one meant to type. Arbitrary, but
     * has to be somewhere, and comfortably past what a person types into search by hand.
     */
    private const val MAX_QUERY_LENGTH = 80
}
