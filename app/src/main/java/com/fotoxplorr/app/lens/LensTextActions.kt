package com.fotoxplorr.app.lens

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Locale

/** One line of explanation under a set of text actions: a status, a translation, or why not. */
internal data class LensNote(val text: String, val emphasise: Boolean = false)

/** What the Translate action is showing: nothing, a run in flight, a result, or a message. */
internal sealed interface TranslateUiState {
    data object Idle : TranslateUiState
    data object Loading : TranslateUiState
    data class Result(val text: String) : TranslateUiState
    data class Message(val text: String) : TranslateUiState
}

/**
 * What Copy, Share, Search, Listen and Translate do to a piece of text, and the notes they leave
 * behind -- ONE implementation behind two surfaces: the details room's "Search inside this photo"
 * card ([LensCard], which acts on every word the index read) and the Select text toolbar (which
 * acts on the words the person selected). Both pass the text to act on with each call, so the
 * actions cannot disagree about what a button does; only the text differs.
 *
 * Built by [rememberLensTextActions], which also owns the text-to-speech engine and the
 * translator for as long as the surface that asked for it is on screen.
 *
 * Notes ([notes]) are kept apart per source -- speech, translation, status -- rather than sharing
 * one slot, because one action's explanation can be true at the same time as another's (Listen
 * fails, then Translate runs) and must not evict it.
 */
@Stable
internal class LensTextActions internal constructor(
    private val context: Context,
    private val putOnClipboard: (String) -> Unit,
    private val scope: CoroutineScope,
    val speaker: LensSpeaker,
    private val translator: TextTranslator,
    private val deviceLanguageTag: String,
    /** Where Search sends the query, read at each use; null leaves Search without one ([searchAvailable]). */
    private val searchLibrary: () -> ((String) -> Unit)?,
) {
    private var statusMessage by mutableStateOf<String?>(null)
    private var translateState by mutableStateOf<TranslateUiState>(TranslateUiState.Idle)

    // Bumped by [reset]: a translation that finishes after the text it was for has gone must not
    // post its result against the text now on screen.
    private var generation = 0

    /** Which of the card's actions are on offer right now (see [LensActions.plan]). */
    val plan: LensCardPlan
        get() = LensActions.plan(
            hasRecognizedText = true,
            ttsReadiness = speaker.readiness,
            translatorAvailable = translator.available,
        )

    val searchAvailable: Boolean get() = searchLibrary() != null

    val speaking: Boolean get() = speaker.outcome == SpeechOutcome.Speaking

    /** The lines to show under the actions, in the order they should read. */
    val notes: List<LensNote>
        get() = notesFor(speaker.outcome, translateState, statusMessage)

    /** Forget any translation, status or failure shown for the text before; speech is left running. */
    fun reset() {
        generation++
        translateState = TranslateUiState.Idle
        statusMessage = null
    }

    fun copy(text: String) {
        putOnClipboard(text)
        translateState = TranslateUiState.Idle
        statusMessage = "Copied"
    }

    /** Hand [text] to the system share sheet. */
    fun share(text: String) {
        statusMessage = null
        if (!LensHandoff.launch(context, LensHandoff.shareTextIntent(text), "Share text")) {
            statusMessage = "No app on this device can take shared text."
        }
    }

    /** Search the person's own library for [text] (see [LensSearchQuery.buildQuery]). */
    fun search(text: String) {
        statusMessage = null
        searchLibrary()?.invoke(LensSearchQuery.buildQuery(text))
    }

    /** Search the web for [text]: the one action here that leaves the device, labelled as such where it is drawn. */
    fun searchWeb(text: String) {
        statusMessage = null
        val launched = LensHandoff.launch(context, LensHandoff.webSearchIntent(LensSearchQuery.buildQuery(text)), null)
        if (!launched) statusMessage = "No app on this device can search the web."
    }

    /**
     * Read [text] aloud, or stop if it is already being read. [recognisedLanguage] is the
     * language the recogniser tagged the text with, when it did; the device's language is the
     * best-effort fallback otherwise (see [LensSpeaker.speak]).
     */
    fun listen(text: String, recognisedLanguage: String? = null) {
        statusMessage = null
        if (speaking) {
            speaker.stop()
        } else {
            speaker.speak(text, speechTagFor(recognisedLanguage, deviceLanguageTag))
        }
    }

    /**
     * Translate [text] into the device's language: on the device where the build can, otherwise
     * (or when that attempt fails) by handing the text to a translator app.
     *
     * [recognisedLanguage] is the source language the recogniser tagged the text with; without it
     * the source falls back to [TextTranslator.DEFAULT_SOURCE_LANGUAGE_TAG]. Text that is known to
     * be in the device's language already says so instead of asking a translator for a no-op.
     */
    fun translate(text: String, recognisedLanguage: String? = null) {
        statusMessage = null
        val recognised = TextTranslator.recognisedTag(recognisedLanguage)
        val source = recognised ?: TextTranslator.DEFAULT_SOURCE_LANGUAGE_TAG
        if (plan.translateMode != TranslateMode.ON_DEVICE) {
            if (!launchTranslatorApp(text)) translateState = TranslateUiState.Message("No translator app is installed.")
            return
        }
        if (recognised != null && TextTranslator.sameLanguage(recognised, deviceLanguageTag)) {
            translateState = TranslateUiState.Message("This text is already in your language.")
            return
        }
        val mine = ++generation
        translateState = TranslateUiState.Loading
        scope.launch {
            val result = translator.translate(text, deviceLanguageTag, source)
            if (mine != generation) return@launch
            translateState = result.fold(
                onSuccess = { TranslateUiState.Result(it.text) },
                onFailure = { error ->
                    // The on-device attempt genuinely failed (timed out, no network to fetch a
                    // model, unsupported pair) -- fall back to the same hand-off the offline
                    // flavor always uses, rather than just reporting failure and stranding the
                    // person with nothing to do.
                    if (launchTranslatorApp(text)) {
                        TranslateUiState.Idle
                    } else {
                        TranslateUiState.Message(error.message ?: "Couldn't translate this text.")
                    }
                },
            )
        }
    }

    private fun launchTranslatorApp(text: String): Boolean =
        LensHandoff.launch(context, LensHandoff.translateIntent(context, text), "Translate with…")

    internal companion object {
        /**
         * The notes for the three sources, in reading order: speech trouble first (it is the
         * newest thing to have gone wrong, whatever else is up), then the translation, then the
         * status line.
         */
        fun notesFor(speech: SpeechOutcome, translate: TranslateUiState, status: String?): List<LensNote> = buildList {
            if (speech is SpeechOutcome.Unavailable) add(LensNote(speech.message))
            when (translate) {
                TranslateUiState.Loading -> add(LensNote("Translating…"))
                is TranslateUiState.Result -> add(LensNote(translate.text, emphasise = true))
                is TranslateUiState.Message -> add(LensNote(translate.text))
                TranslateUiState.Idle -> Unit
            }
            if (status != null) add(LensNote(status))
        }

        /** The language to speak [text] in: what the recogniser tagged it with, else the device's. */
        fun speechTagFor(recognised: String?, deviceTag: String): String =
            TextTranslator.recognisedTag(recognised) ?: deviceTag
    }
}

/**
 * A [LensTextActions] for [key] (the photo it is for): a fresh speech engine and a clean slate for
 * a fresh key, the engine shut down when the key changes or the surface leaves the tree (see
 * [rememberLensSpeaker]). [onSearchLibrary] is read on every call, so a surface that rebuilds its
 * callback does not have to rebuild the actions.
 */
@Composable
internal fun rememberLensTextActions(key: Any, onSearchLibrary: ((String) -> Unit)?): LensTextActions {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    // Keyed on the photo, not just the Context: see rememberLensSpeaker's own KDoc for why a swipe
    // to another photo must always get a fresh engine rather than inheriting one mid-sentence.
    val speaker = rememberLensSpeaker(key = key)
    val translator = remember { AppTextTranslator() }
    // Best-effort, not detected -- see TextTranslator.DEFAULT_SOURCE_LANGUAGE_TAG and
    // LensSpeaker.speak's own KDoc for the identical limitation on the Listen side.
    val deviceLanguageTag = remember { Locale.getDefault().toLanguageTag() }
    // Reset per photo (keyed on key) for the same reason the speaker is: without it, a composable
    // slot reused across a swipe could show "Copied" or a translated RESULT that actually belongs
    // to the photo the person just navigated away from, silently misattributed to the new one.
    val search by rememberUpdatedState(onSearchLibrary)
    return remember(key, speaker, clipboard, scope) {
        LensTextActions(
            context = context,
            putOnClipboard = { clipboard.setText(AnnotatedString(it)) },
            scope = scope,
            speaker = speaker,
            translator = translator,
            deviceLanguageTag = deviceLanguageTag,
            searchLibrary = { search },
        )
    }
}
