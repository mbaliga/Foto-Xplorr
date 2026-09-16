package com.fotoxplorr.app.jobs

import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Test

class HumanMessageTest {

    @Test
    fun `a cancelled job reads as cancelled, not as a failure`() {
        assertEquals("Cancelled.", humanMessage(CancellationException("job was cancelled"), "fallback"))
    }

    @Test
    fun `a security exception never repeats a permission string`() {
        val message = humanMessage(SecurityException("Permission Denial: android.permission.SOMETHING"), "fallback")
        assertEquals("Android would not allow this — the permission may have been withdrawn.", message)
    }

    @Test
    fun `an IO exception with a written fallback uses it`() {
        assertEquals("Could not export metadata.", humanMessage(IOException("ENOENT"), "Could not export metadata."))
    }

    @Test
    fun `an IO exception with a blank fallback still says something concrete`() {
        val message = humanMessage(IOException("ENOENT"), "")
        assertEquals("A storage error stopped this from completing.", message)
    }

    @Test
    fun `a written, sentence-shaped exception message is trusted`() {
        val error = IllegalStateException("Android did not allow this file to be renamed")
        assertEquals("Android did not allow this file to be renamed", humanMessage(error, "fallback"))
    }

    @Test
    fun `a bare identifier or code is not mistaken for prose`() {
        assertEquals("fallback", humanMessage(IllegalStateException("ENOENT"), "fallback"))
        assertEquals("fallback", humanMessage(IllegalStateException("E_PERMISSION_DENIED"), "fallback"))
    }

    @Test
    fun `no message at all falls back`() {
        assertEquals("fallback", humanMessage(RuntimeException(), "fallback"))
    }

    @Test
    fun `a blank message falls back`() {
        assertEquals("fallback", humanMessage(RuntimeException("   "), "fallback"))
    }
}
