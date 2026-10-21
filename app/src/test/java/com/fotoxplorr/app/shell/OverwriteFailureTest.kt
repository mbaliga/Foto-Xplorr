package com.fotoxplorr.app.shell

import com.fotoxplorr.app.editor.OverwriteException
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The editor's failure banner must say what actually happened, not blame Android for everything. */
class OverwriteFailureTest {

    @Test
    fun `a refusal shows its own reason and leaves the original safe`() {
        val failure = overwriteFailureOf(
            OverwriteException.Refused(
                OverwriteException.Refused.Reason.WOULD_DESTROY_CONTENT,
                "Replacing the original would destroy this photo's animation.",
            ),
        )
        assertEquals("Replacing the original would destroy this photo's animation.", failure.message)
        assertFalse(failure.originalAtRisk)
    }

    @Test
    fun `every refusal reason keeps its message`() {
        OverwriteException.Refused.Reason.entries.forEach { reason ->
            val failure = overwriteFailureOf(OverwriteException.Refused(reason, "because $reason"))
            assertEquals("because $reason", failure.message)
            assertFalse(failure.originalAtRisk)
        }
    }

    @Test
    fun `a failure that was rolled back says so and is not a risk`() {
        val failure = overwriteFailureOf(
            OverwriteException.FailedAfterWrite(
                restored = true,
                recoveryFile = null,
                message = "Replacing the photo failed partway, so the original was put back exactly as it was.",
            ),
        )
        assertTrue(failure.message.contains("put back"))
        assertFalse(failure.originalAtRisk)
    }

    @Test
    fun `a failure that could not be rolled back marks the original at risk`() {
        val failure = overwriteFailureOf(
            OverwriteException.FailedAfterWrite(
                restored = false,
                recoveryFile = File("overwrite-recovery/x.bak"),
                message = "Replacing the photo failed and the original could not be put back.",
            ),
        )
        assertEquals("Replacing the photo failed and the original could not be put back.", failure.message)
        assertTrue(failure.originalAtRisk)
    }

    @Test
    fun `only a security exception earns the Android did not allow wording`() {
        assertEquals(OVERWRITE_NOT_ALLOWED_MESSAGE, overwriteFailureOf(SecurityException("denied")).message)
        assertNotEquals(OVERWRITE_NOT_ALLOWED_MESSAGE, overwriteFailureOf(IOException("disk")).message)
        assertNotEquals(OVERWRITE_NOT_ALLOWED_MESSAGE, overwriteFailureOf(null).message)
        assertNotEquals(
            OVERWRITE_NOT_ALLOWED_MESSAGE,
            overwriteFailureOf(OverwriteException.Refused(OverwriteException.Refused.Reason.BACKUP_FAILED, "no room")).message,
        )
    }

    @Test
    fun `an unrelated error says the original was left alone`() {
        val failure = overwriteFailureOf(IOException("Could not open the original photo for writing"))
        assertTrue(failure.message.contains("left as it is"))
        assertFalse(failure.originalAtRisk)
    }

    @Test
    fun `a blank message falls back to the generic one`() {
        val failure = overwriteFailureOf(OverwriteException.Refused(OverwriteException.Refused.Reason.BACKUP_FAILED, ""))
        assertTrue(failure.message.isNotBlank())
    }
}
