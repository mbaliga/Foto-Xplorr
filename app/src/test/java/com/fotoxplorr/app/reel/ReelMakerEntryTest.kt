package com.fotoxplorr.app.reel

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import com.fotoxplorr.app.experimental.ExperimentEnv
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.reel.ReelScreenKit.photo
import com.fotoxplorr.app.spatial.GeoIndexState
import com.fotoxplorr.app.ui.FotoXplorrTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The public entry, [ReelMaker], over the real Experimental environment and the real store in the app's files
 * folder: that a reel is kept in `filesDir/reels` as the recipe and not a pixel, that the picker lists only the
 * photos the host lists, and that without the app's job list the screen says it cannot make a video. The export is
 * covered by [ReelScreenTest] and the job's own tests; here nothing is a real file.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h2400dp-xhdpi")
class ReelMakerEntryTest {

    @get:Rule
    val compose = createComposeRule()

    private class BackOwner : OnBackPressedDispatcherOwner {
        private val registry = LifecycleRegistry.createUnsafe(this).also { it.currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
        override val onBackPressedDispatcher = OnBackPressedDispatcher()
    }

    private val owner = BackOwner()
    private val context = RuntimeEnvironment.getApplication()
    private val reelsDir = File(context.filesDir, ReelProjectStore.DIRECTORY_NAME)

    private val a = photo(1)
    private val b = photo(2)
    private val browsable = mutableSetOf(a.id, b.id)
    private var closed = false

    private var listed by mutableStateOf(listOf(b, a))

    @Composable
    private fun Host(assets: List<MediaAsset> = listed) {
        val env = ExperimentEnv(
            assets = assets, indexInput = assets, geoState = GeoIndexState(), onIndexLocations = {},
            onOpenAsset = { _, _ -> }, context = context, currentBrowsableIds = { browsable.toSet() },
        )
        FotoXplorrTheme(GalleryPreferencesState()) {
            CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides owner) {
                ReelMaker(env = env, onClose = { closed = true })
            }
        }
    }

    private fun tap(text: String) {
        compose.onNodeWithText(text).performClick()
        compose.waitForIdle()
    }

    @Test
    fun `the entry opens on its list, makes a reel, and keeps only the recipe in the app's own folder`() {
        reelsDir.deleteRecursively()
        compose.setContent { Host() }
        compose.onNodeWithText(ReelCopy.NO_REELS).assertExists()
        tap(ReelCopy.START_BLANK)
        tap(ReelCopy.TOOL_ADD)
        tap(ReelCopy.ADD_CARD)
        compose.waitUntil(30_000) { reelsDir.listFiles().orEmpty().any { it.name.endsWith(".json") } }
        val file = reelsDir.listFiles()!!.single { it.name.endsWith(".json") }
        assertTrue(ReelProjectStore.isValidId(file.name.removeSuffix(".json")))
        val decoded = ReelCodec.decode(file.readText())!!
        assertEquals(1, decoded.project.items.size)
        assertTrue(decoded.project.items[0] is TitleItem)
        assertFalse("no pixel is kept, only the recipe", file.readText().length > 5_000)
    }

    @Test
    fun `the picker lists only what the host lists`() {
        reelsDir.deleteRecursively()
        compose.setContent { Host(assets = listOf(a)) }
        tap(ReelCopy.START_DRAFT)
        tap(ReelCopy.PICK_BY_HAND)
        compose.onNodeWithContentDescription(a.displayName).assertExists()
        compose.onNodeWithContentDescription(b.displayName).assertDoesNotExist()
    }

    @Test
    fun `with the app's job list a reel of photos from the library can be made`() {
        reelsDir.deleteRecursively()
        compose.setContent { Host() }
        tap(ReelCopy.START_BLANK)
        tap(ReelCopy.TOOL_ADD)
        tap(ReelCopy.ADD_ITEMS)
        compose.onNodeWithContentDescription(a.displayName).performClick()
        tap(ReelCopy.addItems(1))
        tap(ReelCopy.TOOL_MAKE)
        compose.onNodeWithText(ReelCopy.CANNOT_MAKE_HERE).assertDoesNotExist()
        compose.onNodeWithText(ReelCopy.MAKE).assertIsEnabled()
    }

    @Test
    fun `a photo the host stops allowing is marked and the video waits`() {
        reelsDir.deleteRecursively()
        compose.setContent { Host() }
        tap(ReelCopy.START_BLANK)
        tap(ReelCopy.TOOL_ADD)
        tap(ReelCopy.ADD_ITEMS)
        compose.onNodeWithContentDescription(a.displayName).performClick()
        tap(ReelCopy.addItems(1))
        tap(ReelCopy.TOOL_MAKE)
        compose.onNodeWithText(ReelCopy.MAKE).assertIsEnabled()
        // The photo's folder is locked again while the reel is open: the host's next list no longer holds it.
        compose.runOnUiThread { listed = listOf(b) }
        compose.waitForIdle()
        compose.onNodeWithText(ReelCopy.missingItems(1)).assertExists()
        compose.onNodeWithText(ReelCopy.MAKE).assertIsNotEnabled()
    }

    @Test
    fun `Back on the list closes the experiment and a reel opened again is the one that was saved`() {
        reelsDir.deleteRecursively()
        compose.setContent { Host() }
        tap(ReelCopy.START_BLANK)
        tap(ReelCopy.TOOL_ADD)
        tap(ReelCopy.ADD_CARD)
        compose.waitUntil(30_000) { reelsDir.listFiles().orEmpty().any { it.name.endsWith(".json") } }
        // The card was added from its panel, so the first Back closes that and the second leaves the reel.
        compose.onNodeWithContentDescription(ReelCopy.BACK).performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription(ReelCopy.BACK).performClick()
        compose.waitForIdle()
        compose.waitUntil(30_000) { compose.onAllNodesWithTextCount(ReelCopy.reelSummary(1, ReelLimits.TITLE_DEFAULT_MS)) > 0 }
        tap(ReelCopy.DEFAULT_NAME)
        compose.waitUntil(30_000) { compose.onAllNodesWithTextCount(ReelCopy.PREVIEW_NOTE) > 0 }
        assertNotNull(compose.onNodeWithContentDescription(ReelCopy.PREVIEW).fetchSemanticsNode())
        assertFalse(closed)
        compose.onNodeWithContentDescription(ReelCopy.BACK).performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription(ReelCopy.BACK).performClick()
        assertTrue(closed)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String): Int =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
}
