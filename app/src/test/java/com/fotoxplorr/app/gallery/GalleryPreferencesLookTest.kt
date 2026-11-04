package com.fotoxplorr.app.gallery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The two opt-in switches of the look-and-cull work: both off on a fresh install, both persist. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GalleryPreferencesLookTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun preferences() = GalleryPreferences(context)

    @Test
    fun `justified rows and culling sidecars are off by default`() {
        assertFalse(preferences().observe().value.justifiedLayout)
        assertFalse(preferences().observe().value.cullSidecars)
    }

    @Test
    fun `both survive a restart`() {
        preferences().setJustifiedLayout(true)
        preferences().setCullSidecars(true)
        assertTrue(preferences().observe().value.justifiedLayout)
        assertTrue(preferences().observe().value.cullSidecars)
        preferences().setJustifiedLayout(false)
        assertFalse(preferences().observe().value.justifiedLayout)
    }
}
