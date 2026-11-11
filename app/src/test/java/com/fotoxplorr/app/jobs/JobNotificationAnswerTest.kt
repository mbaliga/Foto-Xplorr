package com.fotoxplorr.app.jobs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JobNotificationAnswerTest {

    @Test
    fun `a grant is an answer`() {
        assertTrue(notificationPromptWasAnswered(granted = true, shouldShowRationale = false))
    }

    @Test
    fun `a refusal after the prompt was shown is an answer`() {
        assertTrue(notificationPromptWasAnswered(granted = false, shouldShowRationale = true))
    }

    @Test
    fun `an empty result for a request that showed nothing is not an answer`() {
        assertFalse(notificationPromptWasAnswered(granted = false, shouldShowRationale = false))
    }
}
