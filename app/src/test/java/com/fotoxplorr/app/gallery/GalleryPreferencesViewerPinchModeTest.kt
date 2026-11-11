package com.fotoxplorr.app.gallery

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Persistence for the viewer's pinch mode -- see [ViewerPinchMode]'s own doc.
 *
 * Robolectric rather than a pure-JVM fake, same reasoning as
 * [com.fotoxplorr.app.organize.LibraryStoreCurationMemoryTest]: the thing under test IS the
 * `SharedPreferences` round-trip, not an approximation of it. A fresh [GalleryPreferences]
 * instance over the SAME [android.content.Context] is how each test stands in for a process
 * restart -- the first instance's in-memory `StateFlow` is gone, so the second can only be
 * reading back what actually reached disk.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GalleryPreferencesViewerPinchModeTest {

    private fun preferences() = GalleryPreferences(RuntimeEnvironment.getApplication())

    @Test
    fun `viewer pinch mode defaults to optical zoom -- the screen's original behaviour`() {
        assertEquals(ViewerPinchMode.OPTICAL_ZOOM, preferences().observe().value.viewerPinchMode)
    }

    @Test
    fun `viewer pinch mode survives a process restart`() {
        preferences().setViewerPinchMode(ViewerPinchMode.PANEL_SHORTCUTS)

        val reloaded = preferences()
        assertEquals(ViewerPinchMode.PANEL_SHORTCUTS, reloaded.observe().value.viewerPinchMode)
    }
}
