package com.fotoxplorr.app.board

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import com.fotoxplorr.app.experimental.ExperimentEnv
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.spatial.GeoIndexState
import com.fotoxplorr.app.ui.FotoXplorrTheme
import com.fotoxplorr.core.model.MediaId
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The public entry, [MoodBoard], over the real Experimental environment and the real store in the app's
 * files folder: that it lists only the photos the host lists, that a board is kept in `filesDir/boards`,
 * and that a photo the host stops allowing is shown as missing and not drawn from. The host's own
 * `decode` is covered by its tests; here it is never reached because the photos are not files.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h2400dp-xhdpi")
class MoodBoardEntryTest {

    @get:Rule
    val compose = createComposeRule()

    private class BackOwner : OnBackPressedDispatcherOwner {
        private val registry = LifecycleRegistry.createUnsafe(this).also { it.currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
        override val onBackPressedDispatcher = OnBackPressedDispatcher()
    }

    private val owner = BackOwner()
    private val context = RuntimeEnvironment.getApplication()
    private val boardsDir = File(context.filesDir, BoardStore.DIRECTORY_NAME)

    private fun photo(id: Long, name: String) = MediaAsset(
        id = MediaId(id), contentUriString = "content://media/external/images/media/$id", displayName = name,
        mimeType = "image/jpeg", bucketName = null, dateTakenMillis = id * 1000, dateModifiedSeconds = 0,
        width = 400, height = 300, sizeBytes = id * 100, relativePath = "DCIM/Camera/", isFavorite = false, isTrashed = false,
    )

    private val a = photo(1, "a.jpg")
    private val b = photo(2, "b.jpg")
    private val browsable = mutableSetOf(a.id, b.id)

    @Composable
    private fun Host() {
        val assets = listOf(a, b)
        val env = ExperimentEnv(
            assets = assets, indexInput = assets, geoState = GeoIndexState(), onIndexLocations = {},
            onOpenAsset = { _, _ -> }, context = context, currentBrowsableIds = { browsable.toSet() },
        )
        FotoXplorrTheme(GalleryPreferencesState()) {
            CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides owner) {
                MoodBoard(env = env, onClose = {})
            }
        }
    }

    private fun tap(text: String) {
        compose.onNodeWithText(text).performClick()
        compose.waitForIdle()
    }

    @Test
    fun `the entry opens on its list, makes a board, and keeps it in the app's own folder`() {
        boardsDir.deleteRecursively()
        compose.setContent { Host() }
        compose.onNodeWithText(BoardCopy.NO_BOARDS).assertExists()
        tap(BoardCopy.NEW_BOARD)
        tap(BoardCopy.TOOL_ADD)
        tap(BoardCopy.ADD_COLOUR)
        compose.waitUntil(30_000) { boardsDir.listFiles().orEmpty().any { it.name.endsWith(".json") } }
        val file = boardsDir.listFiles()!!.single { it.name.endsWith(".json") }
        assertTrue(BoardStore.isValidId(file.name.removeSuffix(".json")))
        val decoded = BoardJson.decode(file.readText())!!
        assertEquals(1, decoded.board.layers.size)
        assertFalse("no pixel is kept, only the layout", file.readText().length > 5_000)
    }

    @Test
    fun `a photo the host stops allowing is shown as missing`() {
        boardsDir.deleteRecursively()
        compose.setContent { Host() }
        tap(BoardCopy.NEW_BOARD)
        tap(BoardCopy.TOOL_ADD)
        tap(BoardCopy.ADD_PHOTO)
        compose.onNodeWithContentDescription("a.jpg").performClick()
        tap(BoardCopy.addPhotos(1))
        // The photos' sizes are read first (off the main thread), then the picker closes.
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(BoardCopy.TOOL_LAYERS)).fetchSemanticsNodes().isNotEmpty() }
        // Locked again while the board is open: the host's live list no longer holds it.
        browsable.remove(a.id)
        tap(BoardCopy.TOOL_LAYERS)
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(BoardCopy.MISSING_PHOTO, substring = true)).fetchSemanticsNodes().isNotEmpty() }
        tap(BoardCopy.EXPORT)
        compose.onNodeWithText(BoardCopy.MISSING_PHOTOS_NOTE).assertExists()
    }
}
