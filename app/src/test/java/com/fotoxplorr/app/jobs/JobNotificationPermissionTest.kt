package com.fotoxplorr.app.jobs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JobNotificationPermissionTest {

    @Test
    fun `asks on Android 13 when not granted and never asked`() {
        assertTrue(shouldAskForJobNotifications(sdkInt = 33, granted = false, alreadyAsked = false))
        assertTrue(shouldAskForJobNotifications(sdkInt = 35, granted = false, alreadyAsked = false))
    }

    @Test
    fun `does not ask below Android 13, where the permission does not exist`() {
        assertFalse(shouldAskForJobNotifications(sdkInt = 32, granted = false, alreadyAsked = false))
        assertFalse(shouldAskForJobNotifications(sdkInt = 26, granted = false, alreadyAsked = false))
    }

    @Test
    fun `does not ask when already granted`() {
        assertFalse(shouldAskForJobNotifications(sdkInt = 34, granted = true, alreadyAsked = false))
    }

    @Test
    fun `asks only once, even if the answer was no`() {
        assertFalse(shouldAskForJobNotifications(sdkInt = 34, granted = false, alreadyAsked = true))
    }
}
