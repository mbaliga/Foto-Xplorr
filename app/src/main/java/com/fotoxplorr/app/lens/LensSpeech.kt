package com.fotoxplorr.app.lens

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.util.Locale
import java.util.UUID

/**
 * What the Listen pill should currently show, and why.
 *
 * See [LensSpeaker]'s own KDoc for the async round-trip that makes [Idle] the honest starting
 * state rather than a guess in either direction.
 */
sealed interface SpeechOutcome {
    /** Nothing is playing -- either nothing has been attempted yet, or the last attempt finished
     *  cleanly. The pill reads "Listen" in this state. */
    data object Idle : SpeechOutcome

    /** Reading the text aloud right now. The pill becomes "Stop" while this is true -- half of
     *  this feature's "provide stop/pause" requirement; [LensSpeaker.stop] is the other half. */
    data object Speaking : SpeechOutcome

    /**
     * A speak attempt was made and could not happen, with the reason in [message].
     *
     * Shown verbatim under the pill row: this feature's whole point in choosing this shape is
     * that "no TTS engine" or "no voice for this language" must be a VISIBLE, honest message
     * rather than a pill that looks live and silently does nothing when tapped.
     */
    data class Unavailable(val message: String) : SpeechOutcome
}

/**
 * One [TextToSpeech] engine, held for exactly as long as its card is on screen (see
 * [rememberLensSpeaker]) and speaking at most one utterance at a time.
 *
 * [TextToSpeech]'s constructor returns before the engine is actually usable -- binding to the
 * system TTS service happens off-thread, and the real answer arrives later, on
 * [TextToSpeech.OnInitListener.onInit]. Calling [TextToSpeech.speak] before that callback fires
 * is a DOCUMENTED no-op on the platform: nothing plays, nothing throws, nothing tells the caller
 * why. [speak] queues the request instead of forwarding it during that window, and plays it the
 * moment `onInit` reports success, so a tap that lands in the first instant after this card
 * appears is not silently swallowed the way it would be calling the platform API directly.
 *
 * Untested here on purpose, the same way [com.fotoxplorr.app.viewer.decodeSampledBitmap] and
 * `exifDetailsFrom` in [com.fotoxplorr.app.viewer.PhotoDetailRoom] are: this class is a thin,
 * literal wrapper over a platform API that only exists on a device. What has a right answer
 * independent of any device -- which action is available given the readiness this class reports
 * -- is pulled out into [LensActions.plan] and pinned there instead.
 */
class LensSpeaker(context: Context) {

    /** Moment-to-moment playback state -- see [SpeechOutcome]. */
    var outcome: SpeechOutcome by mutableStateOf(SpeechOutcome.Idle)
        private set

    /** Whether Listen is worth offering at all right now -- see [TtsReadiness]. Separate from
     *  [outcome]: readiness only ever moves UNKNOWN -> READY or (from either) -> UNAVAILABLE, while
     *  [outcome] cycles between Idle and Speaking on every single tap. */
    var readiness: TtsReadiness by mutableStateOf(TtsReadiness.UNKNOWN)
        private set

    private var ready = false
    private var pendingRequest: SpeakRequest? = null

    // One utterance id for the lifetime of this speaker: this card only ever reads out its own
    // whole text as a single utterance, never several queued at once, so there is nothing that
    // needs a fresh id per call the way a multi-utterance queue would.
    private val utteranceId = UUID.randomUUID().toString()

    private val engine: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status != TextToSpeech.SUCCESS) {
            readiness = TtsReadiness.UNAVAILABLE
            outcome = SpeechOutcome.Unavailable("No speech engine is installed on this device.")
            return@TextToSpeech
        }
        readiness = TtsReadiness.READY
        ready = true
        pendingRequest?.let { speakNow(it.text, it.languageTag) }
        pendingRequest = null
    }

    init {
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                outcome = SpeechOutcome.Speaking
            }

            override fun onDone(utteranceId: String?) {
                outcome = SpeechOutcome.Idle
            }

            // The single-arg overload is the one UtteranceProgressListener actually declares
            // abstract; the two-arg onError(String, Int) added later defaults to calling this
            // one, so overriding only this still catches both.
            override fun onError(utteranceId: String?) {
                outcome = SpeechOutcome.Unavailable("Speech playback failed.")
            }
        })
    }

    /**
     * Speak [text] in [languageTag] (BCP 47, e.g. `"en-US"`), replacing anything already queued
     * or playing -- there is only ever one utterance for this card's text, never several queued.
     */
    fun speak(text: String, languageTag: String) {
        if (!ready) {
            pendingRequest = SpeakRequest(text, languageTag)
            return
        }
        speakNow(text, languageTag)
    }

    private fun speakNow(text: String, languageTag: String) {
        val locale = Locale.forLanguageTag(languageTag)
        // Checked BEFORE speak(), not inferred from its return value: speak() itself reports
        // SUCCESS/ERROR synchronously for QUEUING the request, which is a different question
        // from "is there actually a voice for this language" -- a real device happily queues,
        // then produces no audio at all, for a language it has no voice data for.
        // isLanguageAvailable is the call that actually knows, which is why it runs first.
        val availability = engine.isLanguageAvailable(locale)
        if (availability == TextToSpeech.LANG_MISSING_DATA || availability == TextToSpeech.LANG_NOT_SUPPORTED) {
            readiness = TtsReadiness.UNAVAILABLE
            outcome = SpeechOutcome.Unavailable(
                "No speech voice is installed for \"${locale.displayLanguage}\" on this device.",
            )
            return
        }
        // setLanguage() returns an Int result code rather than Unit, which is exactly why this
        // is a method call and not `engine.language = locale` -- Kotlin only synthesises a
        // property from a Java getter/setter pair when the setter returns Unit.
        engine.setLanguage(locale)
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    /** Stops mid-sentence if currently speaking. The other half of "provide stop/pause". */
    fun stop() {
        engine.stop()
        outcome = SpeechOutcome.Idle
    }

    /** Releases the engine. Must be called exactly once, when the card leaves composition -- see
     *  [rememberLensSpeaker], which is the only intended caller. */
    fun shutdown() {
        engine.stop()
        engine.shutdown()
    }

    private data class SpeakRequest(val text: String, val languageTag: String)
}

/**
 * A [LensSpeaker] scoped to [key] (the photo it belongs to): a fresh engine for a fresh key,
 * shut down when that key changes or the card leaves the tree.
 *
 * Keyed on the CALLER's photo identity rather than only on [Context], because a
 * [LensCard] instance may or may not be recreated when the person swipes to another photo --
 * that depends on how the surrounding pager is built, which this package does not own or
 * control. Without this key, an engine left mid-sentence on photo A could keep reading photo
 * A's text out loud over photo B if the composition happened to be reused; keying on identity
 * guarantees a swipe always stops the old speaker (engines are torn down on
 * [androidx.compose.runtime.DisposableEffect]'s exit, which runs [LensSpeaker.shutdown], which
 * calls [LensSpeaker.stop] first) and hands photo B a clean, [TtsReadiness.UNKNOWN] one.
 */
@Composable
fun rememberLensSpeaker(key: Any): LensSpeaker {
    val context = LocalContext.current
    val speaker = remember(context, key) { LensSpeaker(context) }
    DisposableEffect(speaker) {
        onDispose { speaker.shutdown() }
    }
    return speaker
}
