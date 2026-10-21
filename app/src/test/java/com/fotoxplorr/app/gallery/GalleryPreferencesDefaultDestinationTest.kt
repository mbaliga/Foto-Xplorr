package com.fotoxplorr.app.gallery

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The upgrade path for the removed Audio destination: an install that had picked Audio as its
 * default view still has the string `AUDIO` on disk, which no longer names a [HyleDestination],
 * and must open on Photos rather than crash or draw an empty pane.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GalleryPreferencesDefaultDestinationTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun storeDefault(name: String) {
        context.getSharedPreferences("foto_xplorr_gallery", Context.MODE_PRIVATE)
            .edit().putString("default_destination", name).commit()
    }

    @Test
    fun `a saved default of the removed Audio destination falls back to Photos`() {
        storeDefault("AUDIO")
        assertEquals(HyleDestination.PHOTOS, GalleryPreferences(context).observe().value.defaultDestination)
    }

    @Test
    fun `a saved default of a surviving destination is kept`() {
        storeDefault(HyleDestination.VIDEOS.name)
        assertEquals(HyleDestination.VIDEOS, GalleryPreferences(context).observe().value.defaultDestination)
    }
}
