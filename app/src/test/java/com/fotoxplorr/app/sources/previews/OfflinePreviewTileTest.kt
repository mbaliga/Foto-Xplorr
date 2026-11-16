package com.fotoxplorr.app.sources.previews

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.ui.layout.ContentScale
import androidx.test.core.app.ApplicationProvider
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.media.MediaImage
import com.fotoxplorr.app.sources.gallery.SourceAssetMapper
import com.fotoxplorr.app.sources.gallery.entry
import com.fotoxplorr.app.sources.gallery.libraryAsset
import com.fotoxplorr.app.sources.gallery.sourceRow
import com.fotoxplorr.app.ui.FotoXplorrTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class OfflinePreviewTileTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val settings = InMemoryPreviewSettings()
    private val store by lazy { SourcePreviewStore(File(tmp.root, "p"), capOf = settings::capBytes, io = Dispatchers.Unconfined) }
    private val previews by lazy { SourcePreviews(store, settings, SourcePreviewsFixtures.writer(store, settings) { null }) }

    private val source = SourceAssetMapper.toMediaAsset(entry(sourceRow(7, sourceRow = 3, name = "Trip.jpg")))!!

    private fun storePreview() = runBlocking {
        val bmp = Bitmap.createBitmap(256, 170, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(20, 120, 220)) }
        store.write(3, 7, source.dateModifiedSeconds, PreviewCodec.encode(bmp)!!)
    }

    private fun show(asset: com.fotoxplorr.app.media.MediaAsset, size: Int = 160, offline: Boolean = false) {
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                CompositionLocalProvider(LocalSourcePreviews provides previews) {
                    MediaImage(asset, Modifier.size(size.dp), ContentScale.Crop, offline = offline)
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `an offline source asset shows its stored preview greyed with the offline label and the cue`() {
        storePreview()
        previews.setOffline(setOf(3L))
        show(source)
        compose.onNodeWithContentDescription(PreviewsCopy.offlineA11y("Trip.jpg")).assertIsDisplayed()
        compose.onNodeWithText(PreviewsCopy.PLUG_IN).assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Trip.jpg").assertCountEquals(0)
    }

    @Test
    fun `an offline asset with no stored preview is a labelled tile not a blank one`() {
        previews.setOffline(setOf(3L))
        show(source)
        compose.onNodeWithContentDescription(PreviewsCopy.offlineA11y("Trip.jpg")).assertIsDisplayed()
        compose.onNodeWithText(PreviewsCopy.NO_PREVIEW).assertIsDisplayed()
    }

    @Test
    fun `a small offline tile keeps the glyph and the label without the sentence`() {
        storePreview()
        show(source, size = 56, offline = true)
        compose.onNodeWithContentDescription(PreviewsCopy.offlineA11y("Trip.jpg")).assertIsDisplayed()
        compose.onAllNodesWithText(PreviewsCopy.PLUG_IN).assertCountEquals(0)
    }

    @Test
    fun `a library asset is unchanged even when every source is offline`() {
        previews.setOffline(setOf(3L))
        val lib = libraryAsset(5, name = "Beach.jpg")
        show(lib)
        compose.onAllNodesWithContentDescription(PreviewsCopy.offlineA11y("Beach.jpg")).assertCountEquals(0)
        compose.onAllNodesWithText(PreviewsCopy.PLUG_IN).assertCountEquals(0)
        compose.onAllNodesWithText(PreviewsCopy.NO_PREVIEW).assertCountEquals(0)
    }

    @Test
    fun `an online source asset with nothing stored is never drawn as offline`() {
        show(source)
        compose.onAllNodesWithContentDescription(PreviewsCopy.offlineA11y("Trip.jpg")).assertCountEquals(0)
    }

    @Test
    fun `a failed read of an online source falls back to its stored preview`() {
        // The test provider does not serve this uri, so the live read fails like a drive pulled mid scroll.
        storePreview()
        show(source)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription(PreviewsCopy.offlineA11y("Trip.jpg")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `a stored file that does not decode is dropped so it is made again`() {
        runBlocking { store.write(3, 7, source.dateModifiedSeconds, ByteArray(64) { 1 }) }
        previews.setOffline(setOf(3L))
        show(source)
        compose.onNodeWithText(PreviewsCopy.NO_PREVIEW).assertIsDisplayed()
        // The unreadable file is dropped off the main thread, so it can go a moment after the
        // placeholder shows: wait for it instead of reading the count once.
        compose.waitUntil(5_000) { runBlocking { store.count(3) } == 0 }
        assertEquals(0, runBlocking { store.count(3) })
    }

    @Test
    fun `the sources screen line shows the exact size and clear empties it`() {
        storePreview()
        val bytes = runBlocking { store.sizeBytes(3) }
        assertTrue(bytes > 0)
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                CompositionLocalProvider(LocalSourcePreviews provides previews) { PreviewsCardSection(3, "Backup") }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText(PreviewsCopy.size(bytes)).assertIsDisplayed()
        compose.onNodeWithContentDescription(PreviewsCopy.clearA11y("Backup")).performClickAndWait()
        assertEquals(0L, runBlocking { store.sizeBytes(3) })
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.performClickAndWait() {
        this.performClick()
        // clearAsync runs on the service's own scope.
        val deadline = System.currentTimeMillis() + 5_000
        while (runBlocking { store.sizeBytes(3) } != 0L && System.currentTimeMillis() < deadline) Thread.sleep(20)
        compose.waitForIdle()
    }
}
