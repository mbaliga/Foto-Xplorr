package com.fotoxplorr.app.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class KeyMaskingTest {
    @Test fun longKeyShowsLastFourOnly() {
        assertEquals("wxyz", KeyMasking.tail("sk-abcdefghijklmnwxyz".toCharArray()))
    }

    @Test fun shortOrMissingKeyShowsNothing() {
        assertEquals("", KeyMasking.tail("short".toCharArray()))
        assertEquals("", KeyMasking.tail(CharArray(0)))
        assertEquals("", KeyMasking.tail(null))
    }

    @Test fun labelNeverContainsMoreThanTheTail() {
        val key = "sk-abcdefghijklmnwxyz"
        val label = KeyMasking.savedLabel(KeyMasking.tail(key.toCharArray()))
        assertEquals("Key saved, ending wxyz", label)
        assertFalse(label.contains("abcdef"))
        assertEquals("Key saved", KeyMasking.savedLabel(""))
    }

    @Test fun privacyLineMakesOnlyTrueClaims() {
        // The line promises: no photo is sent. The only network call made with a key is the Test
        // button, whose prompt is fixed text (AppConnectivityBindings.testConnection).
        assertFalse(ServicesCopy.KEYS_PRIVACY.contains("when you ask", ignoreCase = true))
    }
}
