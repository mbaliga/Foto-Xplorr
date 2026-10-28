package com.fotoxplorr.app.lens

import android.app.Activity
import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * What Copy, Share, Search, Listen and Translate do to the text they are given, in the one
 * implementation the details card and the Select text toolbar share ([LensTextActions]); and the
 * pure rules around it (which language, which note).
 *
 * The translator is a fake, so these run the same in both flavors: the connect build's ML Kit and
 * the offline build's hand-off-only stub are each other's business ([AppTextTranslator]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LensTextActionsTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    // An activity, as in the app: a bare application context may not start another task's activity.
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).create().get() }

    private class FakeTranslator(
        override val available: Boolean = true,
        private val outcome: CompletableDeferred<Result<Translation>>? = null,
        private val failWith: Throwable? = null,
    ) : TextTranslator {
        var calls = 0
        var lastText: String? = null
        var lastSource: String? = null
        var lastTarget: String? = null

        override suspend fun translate(text: String, targetLanguageTag: String, sourceLanguageTag: String): Result<Translation> {
            calls++
            lastText = text
            lastSource = sourceLanguageTag
            lastTarget = targetLanguageTag
            failWith?.let { return Result.failure(it) }
            return outcome?.await() ?: Result.success(Translation("translated: $text", sourceLanguageTag, targetLanguageTag))
        }
    }

    private class Kit(val actions: LensTextActions, val translator: FakeTranslator, val clipboard: MutableList<String>, val searches: MutableList<String>)

    private fun kit(translator: FakeTranslator = FakeTranslator(), withSearch: Boolean = true): Kit {
        val clipboard = mutableListOf<String>()
        val searches = mutableListOf<String>()
        val actions = LensTextActions(
            context = activity,
            putOnClipboard = { clipboard += it },
            scope = CoroutineScope(Dispatchers.Unconfined),
            speaker = LensSpeaker(app),
            translator = translator,
            deviceLanguageTag = "en-GB",
            searchLibrary = { if (withSearch) { text: String -> searches += text } else null },
        )
        return Kit(actions, translator, clipboard, searches)
    }

    private fun nextStarted(): Intent? = shadowOf(activity).nextStartedActivity

    // ── copy, share, search ────────────────────────────────────────────────────────────────────

    @Test
    fun `copy puts exactly the text it was given on the clipboard and says so`() {
        val k = kit()
        k.actions.copy("PLATFORM 9")
        assertEquals(listOf("PLATFORM 9"), k.clipboard)
        assertEquals(listOf(LensNote("Copied")), k.actions.notes)
    }

    @Test
    fun `share hands exactly the text it was given to the share sheet`() {
        val k = kit()
        k.actions.share("PLATFORM 9")
        val chooser = nextStarted()
        assertNotNull(chooser)
        assertEquals(Intent.ACTION_CHOOSER, chooser!!.action)
        val inner = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, inner.action)
        assertEquals("text/plain", inner.type)
        assertEquals("PLATFORM 9", inner.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun `the share intent is plain text`() {
        val intent = LensHandoff.shareTextIntent("hello")
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        assertEquals("hello", intent.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun `search sends the library the collapsed, capped query of the text`() {
        val k = kit()
        k.actions.search("PLATFORM 9\nNorthern   line")
        assertEquals(listOf("PLATFORM 9 Northern line"), k.searches)
    }

    @Test
    fun `without a library search the action is unavailable and does nothing`() {
        val k = kit(withSearch = false)
        assertTrue(!k.actions.searchAvailable)
        k.actions.search("anything")
        assertTrue(k.searches.isEmpty())
    }

    // ── translate ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `translate takes its source language from the text and its target from the device`() {
        val k = kit()
        k.actions.translate("Quai numero neuf", recognisedLanguage = "fr")
        assertEquals("fr", k.translator.lastSource)
        assertEquals("en-GB", k.translator.lastTarget)
        assertEquals("Quai numero neuf", k.translator.lastText)
        assertEquals(listOf(LensNote("translated: Quai numero neuf", emphasise = true)), k.actions.notes)
    }

    @Test
    fun `translate without a recognised language falls back to the default source`() {
        val k = kit()
        k.actions.translate("Hola")
        assertEquals(TextTranslator.DEFAULT_SOURCE_LANGUAGE_TAG, k.translator.lastSource)
        val und = kit()
        und.actions.translate("Hola", recognisedLanguage = "und")
        assertEquals(TextTranslator.DEFAULT_SOURCE_LANGUAGE_TAG, und.translator.lastSource)
    }

    @Test
    fun `text known to be in the device language is not sent to a translator`() {
        val k = kit()
        k.actions.translate("Platform nine", recognisedLanguage = "en")
        assertEquals(0, k.translator.calls)
        assertEquals(listOf(LensNote("This text is already in your language.")), k.actions.notes)
    }

    @Test
    fun `a failed on-device attempt hands the text to a translator app`() {
        val k = kit(FakeTranslator(failWith = TranslatorUnavailableException("no model")))
        k.actions.translate("Quai", recognisedLanguage = "fr")
        val chooser = nextStarted()!!
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertEquals("Translate with…", chooser.getStringExtra(Intent.EXTRA_TITLE))
        val inner = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        // Nothing here answers the more specific translate intent, so it is the plain send.
        assertEquals("Quai", inner.getStringExtra(Intent.EXTRA_TEXT))
        assertTrue("the hand-off started, so there is nothing to apologise for", k.actions.notes.isEmpty())
    }

    @Test
    fun `a build with no translator never asks one and hands the text over`() {
        val k = kit(FakeTranslator(available = false))
        k.actions.translate("Quai", recognisedLanguage = "fr")
        assertEquals(0, k.translator.calls)
        val chooser = nextStarted()!!
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertEquals(TranslateMode.HANDOFF, k.actions.plan.translateMode)
    }

    @Test
    fun `a translation that lands after the text changed is dropped`() {
        val gate = CompletableDeferred<Result<Translation>>()
        val k = kit(FakeTranslator(outcome = gate))
        k.actions.translate("Quai", recognisedLanguage = "fr")
        assertEquals(listOf(LensNote("Translating…")), k.actions.notes)

        k.actions.reset()
        gate.complete(Result.success(Translation("Platform", "fr", "en-GB")))
        assertTrue("the result belonged to words that are no longer selected", k.actions.notes.isEmpty())
    }

    @Test
    fun `reset forgets a copy confirmation and a translation`() {
        val k = kit()
        k.actions.translate("Quai", recognisedLanguage = "fr")
        k.actions.copy("Quai")
        k.actions.reset()
        assertTrue(k.actions.notes.isEmpty())
    }

    // ── the rules around it ────────────────────────────────────────────────────────────────────

    @Test
    fun `the source tag is the recognised language, else English`() {
        assertEquals("fr", TextTranslator.sourceTagFor("fr"))
        assertEquals("de", TextTranslator.sourceTagFor("  de "))
        assertEquals("en", TextTranslator.sourceTagFor(null))
        assertEquals("en", TextTranslator.sourceTagFor(""))
        assertEquals("en", TextTranslator.sourceTagFor("und"))
        assertEquals("en", TextTranslator.sourceTagFor("UND"))
    }

    @Test
    fun `languages compare without region or script`() {
        assertTrue(TextTranslator.sameLanguage("en", "en-GB"))
        assertTrue(TextTranslator.sameLanguage("en-US", "en-GB"))
        assertTrue(!TextTranslator.sameLanguage("fr", "en-GB"))
        assertTrue("an unparseable tag is never 'the same'", !TextTranslator.sameLanguage("", "en"))
    }

    @Test
    fun `the voice follows the recognised language, else the device`() {
        assertEquals("fr", LensTextActions.speechTagFor("fr", "en-GB"))
        assertEquals("en-GB", LensTextActions.speechTagFor(null, "en-GB"))
        assertEquals("en-GB", LensTextActions.speechTagFor("und", "en-GB"))
    }

    @Test
    fun `notes read speech trouble first, then the translation, then the status`() {
        val notes = LensTextActions.notesFor(
            speech = SpeechOutcome.Unavailable("No voice"),
            translate = TranslateUiState.Result("Platform"),
            status = "Copied",
        )
        assertEquals(listOf(LensNote("No voice"), LensNote("Platform", emphasise = true), LensNote("Copied")), notes)
        assertTrue(LensTextActions.notesFor(SpeechOutcome.Idle, TranslateUiState.Idle, null).isEmpty())
        assertEquals(
            listOf(LensNote("Translating…")),
            LensTextActions.notesFor(SpeechOutcome.Speaking, TranslateUiState.Loading, null),
        )
    }
}
