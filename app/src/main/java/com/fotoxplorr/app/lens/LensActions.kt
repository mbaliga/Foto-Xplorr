package com.fotoxplorr.app.lens

/**
 * Whether [android.speech.tts.TextToSpeech] can read this card's text aloud right now.
 *
 * This is three states rather than a boolean because the true answer is only known
 * asynchronously: the engine's constructor returns immediately and the real answer arrives
 * later, on a callback ([android.speech.tts.TextToSpeech.OnInitListener.onInit]), and even a
 * successful init only means "some engine is bound" -- not "it has a voice for this text's
 * language", which needs a second, separate query ([android.speech.tts.TextToSpeech.isLanguageAvailable])
 * once the engine is up.
 *
 * [UNKNOWN] is the state before either answer has arrived, and [LensActions.plan] treats it the
 * same as [READY]: refusing to offer Listen until the async round-trip completes would grey out
 * the pill for every single photo for the hundred-or-so milliseconds a real device takes to bind
 * its TTS service, which reads as a missing feature rather than a briefly loading one. [UNAVAILABLE]
 * is a *learned* fact -- `onInit` reported failure, or the language this card was asked to speak
 * has no voice installed -- and is the one state that turns the pill off, because a second attempt
 * cannot succeed where the first already proved, definitively, that it can't.
 */
enum class TtsReadiness {
    UNKNOWN,
    READY,
    UNAVAILABLE,
}

/**
 * How the Translate pill behaves once tapped.
 *
 * [ON_DEVICE] runs an actual translation and shows the result in the card. [HANDOFF] means this
 * build (or this specific attempt) has no translator to run -- see [TextTranslator]'s own KDoc
 * for exactly why the `offline` flavor can never be [ON_DEVICE] -- so the pill instead opens
 * whatever translator app the person has installed, via [LensHandoff.translateIntent]. The
 * pill's LABEL never changes between the two: it reads "Translate" either way, matching the
 * reference this card is modelled on. Only what happens after the tap differs, and that
 * difference is explained inline, in the card, once it happens -- not by two different-looking
 * buttons for what a person experiences as one action.
 */
enum class TranslateMode {
    ON_DEVICE,
    HANDOFF,
}

/**
 * What the "Search inside this photo" card should show for one photo.
 *
 * Decided once, in one place, rather than re-derived separately by each of the four pills' own
 * click handlers -- so Copy/Search/Listen/Translate cannot each reach a different conclusion
 * about whether this particular photo has anything worth acting on.
 *
 * @param visible the whole card: thumbnail, "Select text from the image" line, and all four
 *   pills. False for a photo the on-device recogniser found no text in -- see [LensActions.plan]
 *   for why that is a hidden card, not four pills with nothing to act on.
 * @param listenEnabled whether the Listen pill should currently invite a tap. See [TtsReadiness].
 * @param translateMode what tapping Translate does. See [TranslateMode]. Meaningless when
 *   [visible] is false, and fixed at [TranslateMode.HANDOFF] in that case only because some
 *   default has to be written down -- nothing reads it once [visible] is false.
 */
data class LensCardPlan(
    val visible: Boolean,
    val listenEnabled: Boolean,
    val translateMode: TranslateMode,
) {
    companion object {
        /** No recognised text: nothing to plan for. See [LensActions.plan]. */
        val HIDDEN = LensCardPlan(visible = false, listenEnabled = false, translateMode = TranslateMode.HANDOFF)
    }
}

/**
 * Decides which of the four "Search inside this photo" actions this photo's card offers.
 *
 * Pure and Android-free on purpose, unlike almost everything else this feature touches
 * ([android.speech.tts.TextToSpeech], [android.content.Intent], ML Kit): "does this photo
 * qualify for the card at all, and is Listen worth offering right now" is exactly the kind of
 * branching logic that is cheap to get wrong and expensive to get wrong silently. A flipped
 * condition here either hides the card for a photo that DOES have recognised text, or shows four
 * pills for a photo that has none -- and neither mistake fails loudly on a device, which is why
 * it is pinned in [LensActionsTest] instead of only ever being read off a real screen.
 */
object LensActions {

    /**
     * @param hasRecognizedText false for a photo the on-device OCR pass found no text in --
     *   [com.fotoxplorr.app.recognition.RecognitionIndex.textByMedia] has no entry for it, or the
     *   entry is blank. Google Photos' equivalent panel is always present; this one is not,
     *   because a card offering to act on text that does not exist is worse than no card at all
     *   -- the same call the owner already made for the "no location in this file" row in
     *   [com.fotoxplorr.app.viewer.PhotoDetailRoom], which explains itself rather than vanishing
     *   for exactly the same reason a bare warning triangle would have been worse than nothing.
     * @param ttsReadiness see [TtsReadiness].
     * @param translatorAvailable [TextTranslator.available] for this build -- true only in the
     *   `connect` flavor. Decides [TranslateMode], not whether the pill is shown at all:
     *   Translate is one of the four actions the reference always draws, and this card keeps
     *   that shape in every flavor and every state, only changing what a tap on it does.
     */
    fun plan(
        hasRecognizedText: Boolean,
        ttsReadiness: TtsReadiness,
        translatorAvailable: Boolean,
    ): LensCardPlan {
        if (!hasRecognizedText) return LensCardPlan.HIDDEN
        return LensCardPlan(
            visible = true,
            listenEnabled = ttsReadiness != TtsReadiness.UNAVAILABLE,
            translateMode = if (translatorAvailable) TranslateMode.ON_DEVICE else TranslateMode.HANDOFF,
        )
    }
}
