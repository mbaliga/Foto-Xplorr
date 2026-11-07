package com.fotoxplorr.app.shell

import com.fotoxplorr.app.editor.OverwriteException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Once the editor closes, the in-session warnings are the only thing that says WHICH photo may be
 * damaged, so each one names it; and an at-risk failure or copy asks for the recovery notice to be
 * recomputed, because that notice is otherwise built once at start.
 */
class OverwriteNamedWarningTest {

    @Test
    fun `the interrupted-save warning names the photo`() {
        val message = overwriteInterruptedMessage("IMG_0042.jpg")

        assertTrue(message, "\"IMG_0042.jpg\"" in message)
        assertTrue(message, "may be damaged" in message)
        assertTrue(message, "Save a copy" in message)
    }

    @Test
    fun `the copy-saved warning names the photo and does not promise a later start`() {
        val message = copySavedOriginalAtRiskMessage("IMG_0042.jpg")

        assertTrue(message, "\"IMG_0042.jpg\"" in message)
        assertTrue(message, "may still be damaged" in message)
        assertTrue(message, "safety copy" in message)
        assertFalse(message, "next time" in message)
    }

    @Test
    fun `a blank or missing name falls back to the generic wording`() {
        listOf(null, "", "  ", "\n\t").forEach { name ->
            val interrupted = overwriteInterruptedMessage(name)
            val copied = copySavedOriginalAtRiskMessage(name)
            assertTrue(interrupted, "The original photo may be damaged" in interrupted)
            assertTrue(copied, "The original may still be damaged" in copied)
            assertFalse(interrupted + copied, "\"\"" in interrupted + copied)
        }
    }

    @Test
    fun `control characters in a name do not reach the dialog`() {
        val message = copySavedOriginalAtRiskMessage("odd\nname\t.jpg")

        assertTrue(message, "\"odd name .jpg\"" in message)
        assertFalse(message, '\n' in message.substringBefore("may still"))
    }

    @Test
    fun `a failure that left the original at risk refreshes the recovery notice`() {
        val atRisk = overwriteFailureOf(
            OverwriteException.FailedAfterWrite(restored = false, recoveryFile = null, message = "could not be put back"),
        )

        assertTrue(shouldRefreshRecoveryNotice(failure = atRisk))
    }

    @Test
    fun `a failure that was safe does not refresh the recovery notice`() {
        val rolledBack = overwriteFailureOf(
            OverwriteException.FailedAfterWrite(restored = true, recoveryFile = null, message = "put back"),
        )
        val refused = overwriteFailureOf(
            OverwriteException.Refused(OverwriteException.Refused.Reason.BACKUP_FAILED, "no room"),
        )

        assertFalse(shouldRefreshRecoveryNotice(failure = rolledBack))
        assertFalse(shouldRefreshRecoveryNotice(failure = refused))
        assertFalse(shouldRefreshRecoveryNotice(failure = overwriteFailureOf(SecurityException("denied"))))
        assertFalse(shouldRefreshRecoveryNotice())
    }

    @Test
    fun `a copy saved while the original is still at risk refreshes the recovery notice`() {
        assertTrue(shouldRefreshRecoveryNotice(copySavedOriginalAtRisk = true))
        assertFalse(shouldRefreshRecoveryNotice(copySavedOriginalAtRisk = false))
    }

    @Test
    fun `an interrupted save is an at-risk failure that refreshes the notice`() {
        val interrupted = OverwriteFailure(overwriteInterruptedMessage("IMG_1.jpg"), originalAtRisk = true)

        assertEquals(true, shouldRefreshRecoveryNotice(failure = interrupted))
    }
}
