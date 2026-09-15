package com.fotoxplorr.app.lens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LensActionsTest {

    @Test
    fun `no recognised text hides the card regardless of tts or translator state`() {
        val plan = LensActions.plan(
            hasRecognizedText = false,
            ttsReadiness = TtsReadiness.READY,
            translatorAvailable = true,
        )
        assertEquals(LensCardPlan.HIDDEN, plan)
        assertFalse(plan.visible)
    }

    @Test
    fun `text with an unknown tts state is shown as listenable, not pre-emptively disabled`() {
        val plan = LensActions.plan(
            hasRecognizedText = true,
            ttsReadiness = TtsReadiness.UNKNOWN,
            translatorAvailable = false,
        )
        assertTrue(plan.visible)
        assertTrue(plan.listenEnabled)
    }

    @Test
    fun `text with a ready tts engine enables listen`() {
        val plan = LensActions.plan(
            hasRecognizedText = true,
            ttsReadiness = TtsReadiness.READY,
            translatorAvailable = false,
        )
        assertTrue(plan.listenEnabled)
    }

    @Test
    fun `a tts engine proven unavailable disables listen but keeps the card visible`() {
        val plan = LensActions.plan(
            hasRecognizedText = true,
            ttsReadiness = TtsReadiness.UNAVAILABLE,
            translatorAvailable = false,
        )
        assertTrue(plan.visible)
        assertFalse(plan.listenEnabled)
    }

    @Test
    fun `an available translator runs on device`() {
        val plan = LensActions.plan(
            hasRecognizedText = true,
            ttsReadiness = TtsReadiness.READY,
            translatorAvailable = true,
        )
        assertEquals(TranslateMode.ON_DEVICE, plan.translateMode)
    }

    @Test
    fun `no translator falls back to a hand-off, never hiding the translate pill`() {
        val plan = LensActions.plan(
            hasRecognizedText = true,
            ttsReadiness = TtsReadiness.READY,
            translatorAvailable = false,
        )
        assertTrue(plan.visible)
        assertEquals(TranslateMode.HANDOFF, plan.translateMode)
    }

    @Test
    fun `listen and translate are decided independently of one another`() {
        // A photo can have text with no translator (offline) and no TTS voice at the same
        // time -- the two must not accidentally share one flag internally.
        val plan = LensActions.plan(
            hasRecognizedText = true,
            ttsReadiness = TtsReadiness.UNAVAILABLE,
            translatorAvailable = false,
        )
        assertTrue(plan.visible)
        assertFalse(plan.listenEnabled)
        assertEquals(TranslateMode.HANDOFF, plan.translateMode)
    }
}
