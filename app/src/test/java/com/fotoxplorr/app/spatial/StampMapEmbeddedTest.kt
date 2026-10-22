package com.fotoxplorr.app.spatial

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.ui.FotoXplorrTheme
import com.fotoxplorr.core.model.MediaId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The stamp map as a pane of the browser: it must not move when the photos it is handed are
 * narrowed, and it must not take the Back gesture or draw a screen's chrome.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class StampMapEmbeddedTest {

    @get:Rule
    val compose = createComposeRule()

    private fun asset(id: Long) = MediaAsset(
        id = MediaId(id),
        contentUriString = "content://media/$id",
        displayName = "PIN_$id.jpg",
        mimeType = "image/jpeg",
        bucketName = "Camera",
        dateTakenMillis = 1_700_000_000_000L,
        dateModifiedSeconds = 1_700_000_000L,
        width = 100,
        height = 100,
        sizeBytes = 1_000L,
        relativePath = "DCIM/Camera/",
        isFavorite = false,
        isTrashed = false,
    )

    // Spread far enough apart that the map's thinning keeps every one of them, and so that
    // dropping 2 and 4 narrows the longitude the located photos span (60 -> 30).
    private val places = mapOf(
        1L to (10.0 to 10.0),
        2L to (10.0 to 60.0),
        3L to (50.0 to 10.0),
        4L to (50.0 to 60.0),
        5L to (30.0 to 30.0),
    )
    private val all = places.keys.map(::asset)
    private val geo = GeoIndexState(
        metadataById = places.mapKeys { MediaId(it.key) }.mapValues { (id, at) ->
            GeoMetadata(id, at.first, at.second, null, null)
        },
        scannedCount = 5,
        totalCount = 5,
    )

    private var shown by mutableStateOf(all)

    /** A dispatcher the test can ask whether anything is registered to take Back. */
    private class BackOwner : OnBackPressedDispatcherOwner {
        private val registry = LifecycleRegistry.createUnsafe(this).also {
            it.currentState = Lifecycle.State.RESUMED
        }
        override val lifecycle: Lifecycle get() = registry
        override val onBackPressedDispatcher = OnBackPressedDispatcher()
    }

    private fun mount(embedded: Boolean, frame: List<MediaAsset>?, owner: BackOwner = BackOwner()) {
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides owner) {
                    StampMapScreen(
                        assets = shown,
                        geoState = geo,
                        onIndexLocations = {},
                        onOpenAsset = { _, _ -> },
                        onClose = {},
                        embedded = embedded,
                        frameAssets = frame ?: shown,
                    )
                }
            }
        }
    }

    private fun stamp(id: Long): Rect =
        compose.onNode(hasContentDescription("PIN_$id.jpg") and hasClickAction())
            .fetchSemanticsNode().boundsInRoot

    @Test
    fun `narrowing the photos does not move the stamps that stay when the field is framed to all of them`() {
        mount(embedded = true, frame = all)
        val before = stamp(5)
        compose.runOnIdle { shown = all.filter { it.id.value in setOf(1L, 3L, 5L) } }
        compose.waitForIdle()
        assertEquals(before, stamp(5))
        compose.onNode(hasContentDescription("PIN_2.jpg") and hasClickAction()).assertDoesNotExist()
        compose.onNode(hasContentDescription("PIN_4.jpg") and hasClickAction()).assertDoesNotExist()
    }

    @Test
    fun `without a frame list narrowing re-fits the field and the stamps jump`() {
        // The control for the test above: proves the position check can tell the two apart.
        mount(embedded = true, frame = null)
        val before = stamp(5)
        compose.runOnIdle { shown = all.filter { it.id.value in setOf(1L, 3L, 5L) } }
        compose.waitForIdle()
        assertNotEquals(before, stamp(5))
    }

    @Test
    fun `a view with no located photo in a library that has some says so and stays a map`() {
        mount(embedded = true, frame = all)
        compose.runOnIdle { shown = emptyList() }
        compose.waitForIdle()
        compose.onNodeWithText("Nothing in this view has a location").assertExists()
    }

    @Test
    fun `embedded registers no Back handler and draws no header or close glyph`() {
        val owner = BackOwner()
        mount(embedded = true, frame = all, owner = owner)
        assertFalse(owner.onBackPressedDispatcher.hasEnabledCallbacks())
        compose.onNodeWithText("MAP").assertDoesNotExist()
        compose.onNodeWithText("✕").assertDoesNotExist()
    }

    @Test
    fun `as a screen it still takes Back and draws its header`() {
        val owner = BackOwner()
        mount(embedded = false, frame = null, owner = owner)
        assertTrue(owner.onBackPressedDispatcher.hasEnabledCallbacks())
        compose.onNodeWithText("MAP").assertExists()
        compose.onNodeWithText("✕").assertExists()
    }
}
