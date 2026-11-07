package com.fotoxplorr.app.gallery

import androidx.core.content.edit
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Persistence for what the viewer draws behind a transparent photo (owner, #17) -- see
 * [TransparencyBackground]. Robolectric for the same reason as
 * [GalleryPreferencesViewerPinchModeTest]: a fresh [GalleryPreferences] over the same context
 * stands in for a process restart, so the second can only read what reached disk.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GalleryPreferencesTransparencyBackgroundTest {

    private fun preferences() = GalleryPreferences(RuntimeEnvironment.getApplication())

    @Test
    fun `the transparency background defaults to the checkerboard`() {
        assertEquals(TransparencyBackground.CHECKERBOARD, preferences().observe().value.viewerTransparencyBackground)
    }

    @Test
    fun `each choice survives a process restart`() {
        TransparencyBackground.entries.forEach { choice ->
            preferences().setViewerTransparencyBackground(choice)

            assertEquals(choice, preferences().observe().value.viewerTransparencyBackground)
        }
    }

    @Test
    fun `setting it updates the live state at once`() {
        val live = preferences()
        live.setViewerTransparencyBackground(TransparencyBackground.BLACK)

        assertEquals(TransparencyBackground.BLACK, live.observe().value.viewerTransparencyBackground)
    }

    @Test
    fun `an unknown stored name falls back to the checkerboard`() {
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("foto_xplorr_gallery", android.content.Context.MODE_PRIVATE)
            .edit { putString("viewer_transparency_background", "NOT_A_BACKGROUND") }

        assertEquals(TransparencyBackground.CHECKERBOARD, preferences().observe().value.viewerTransparencyBackground)
    }

    @Test
    fun `next walks grid then white then black and round again`() {
        assertEquals(TransparencyBackground.WHITE, TransparencyBackground.CHECKERBOARD.next())
        assertEquals(TransparencyBackground.BLACK, TransparencyBackground.WHITE.next())
        assertEquals(TransparencyBackground.CHECKERBOARD, TransparencyBackground.BLACK.next())
    }
}
