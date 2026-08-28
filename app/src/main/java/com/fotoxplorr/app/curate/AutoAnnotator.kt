package com.fotoxplorr.app.curate

/**
 * Decides whether a freshly generated caption
 * ([com.fotoxplorr.app.recognition.CaptionGenerator]'s output) should become the caption stored
 * for a photo, and what to store if so. Pure -- this makes no decision that depends on the
 * clock, storage, or anything beyond the three values it is handed, so the one rule that matters
 * here is exhaustively unit-testable rather than trusted to a manual pass over a device.
 *
 * ## The one mistake this exists to prevent
 *
 * A person who writes their own caption for a photo and comes back later to find it silently
 * replaced by a machine sentence has lost something with no way to get it back -- the app does
 * not know what they originally typed, so there is no undo. Every other mistake this whole body
 * of work can make (a wrong auto tag, a bad archive suggestion) is reviewable and reversible in
 * bulk; overwriting a human caption is not, which is exactly why the task that produced this file
 * called it out by name as "the one unrecoverable mistake available here". [apply] is built so
 * that mistake requires this function to return non-null for a caption it was explicitly told is
 * human-written and non-empty -- which it never does.
 *
 * ## What "machine-written" buys back
 *
 * A caption this function DID write is safe to touch again: re-running recognition (a model
 * update, a re-index) may produce a better sentence for the same photo, and refreshing a
 * previous machine caption is not the mistake above, because there was never anything
 * irreplaceable in it. This is the same asymmetry [com.fotoxplorr.app.moments.VideoMoment] draws
 * between AUTO and MANUAL markers: automatic output may be replaced wholesale on a later pass,
 * hand-authored content never is. See
 * [com.fotoxplorr.app.organize.LibraryStore.applyMachineCaption] for where the provenance flag
 * this reads is actually stored, and [com.fotoxplorr.app.organize.LibraryStore.clearMachineCaptions]
 * for the bulk-undo the task also required -- "keep the machine caption separately-marked so it
 * can be cleared en masse".
 */
object AutoAnnotator {

    /**
     * @param currentCaption Whatever is stored for this photo right now. Blank (empty, or only
     *   whitespace) reads as "nothing to protect" regardless of [currentIsMachineWritten] --
     *   there is no content to lose by filling it, so a stray blank human caption (someone
     *   cleared their own text) is just as fillable as a photo that never had one.
     * @param currentIsMachineWritten Whether [currentCaption] -- if non-blank -- was written by
     *   a previous run of this same function rather than typed by a person. Ignored when
     *   [currentCaption] is blank, for the reason above.
     * @param candidateCaption The freshly generated caption on offer, e.g. from
     *   [com.fotoxplorr.app.recognition.CaptionGenerator.generate]. A blank candidate (nothing
     *   worth saying about this photo) is never written even into an empty slot -- see
     *   `AssetRecognition.caption`'s own doc for why an empty string, not an invented sentence,
     *   is the correct answer when there is nothing to say; this function extends that same
     *   restraint to storage instead of manufacturing filler just because a slot is available.
     * @return The caption to store, or `null` for "make no change at all". A non-null result is
     *   always [candidateCaption] verbatim and is always destined to be stored as
     *   machine-written -- this function never hands back something that should be marked as
     *   though a person wrote it.
     */
    fun apply(
        currentCaption: String,
        currentIsMachineWritten: Boolean,
        candidateCaption: String,
    ): String? {
        if (candidateCaption.isBlank()) return null
        val currentIsEmpty = currentCaption.isBlank()
        // The whole rule: write into an empty slot, or refresh a slot this function itself
        // filled before. Anything else is a human's own words and is left alone, full stop --
        // no "unless the generated one looks better" escape hatch, because the escape hatch IS
        // the bug: there is no automatic test for "looks better" that a person would trust with
        // their own sentence.
        if (!currentIsEmpty && !currentIsMachineWritten) return null
        return candidateCaption
    }
}
