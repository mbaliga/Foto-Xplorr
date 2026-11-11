package com.fotoxplorr.app.spatial

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import com.fotoxplorr.app.BuildConfig
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.ui.FotoXplorrTheme
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What the map pane does for itself: read locations when the map opens with nothing indexed, once.
 * Runs against the offline flavour's pane (the connect one is a MapLibre view with no JVM
 * renderer), through the same [PhotoMapPane] either flavour is reached by.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class PhotoMapPaneTest {

    @get:Rule
    val compose = createComposeRule()

    private var geo by mutableStateOf(GeoIndexState())
    private var asked = 0

    private fun mount() {
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                PhotoMapPane(
                    assets = emptyList(),
                    frameAssets = emptyList(),
                    geoState = geo,
                    onIndexLocations = { asked++ },
                    onOpenAsset = { _, _ -> },
                    settled = true,
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `an unindexed library is asked to read its locations when the map opens`() {
        assumeFalse(BuildConfig.NETWORK_FEATURES)
        mount()
        assertEquals(1, asked)
    }

    @Test
    fun `a pass that finds nothing does not start another`() {
        assumeFalse(BuildConfig.NETWORK_FEATURES)
        mount()
        compose.runOnIdle { geo = GeoIndexState(isIndexing = true, totalCount = 10) }
        compose.waitForIdle()
        compose.runOnIdle { geo = GeoIndexState(isIndexing = false, scannedCount = 0, totalCount = 10) }
        compose.waitForIdle()
        assertEquals("one request, however many times the index goes quiet", 1, asked)
    }

    @Test
    fun `an indexed library is not asked again`() {
        assumeFalse(BuildConfig.NETWORK_FEATURES)
        geo = GeoIndexState(scannedCount = 10, totalCount = 10)
        mount()
        assertEquals(0, asked)
    }
}
