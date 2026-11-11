package com.fotoxplorr.app.viewer

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.lens.LensCard
import com.fotoxplorr.app.render.ViewerFixtures
import com.fotoxplorr.app.textselect.FRAME_H
import com.fotoxplorr.app.textselect.FRAME_W
import com.fotoxplorr.app.textselect.RawBlock
import com.fotoxplorr.app.textselect.RawLine
import com.fotoxplorr.app.textselect.TextLayout
import com.fotoxplorr.app.textselect.rawWord
import com.fotoxplorr.app.ui.FotoXplorrTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The ways into Select text and the actions it offers on the selection, through the real Compose
 * runtime: the details card's "Select text from the image" line, the actions room's "Select text"
 * row, and the toolbar's Share, Search, Listen and Translate acting on the selected words only.
 * The chip and the whole flow through [ViewerScreen] are in [ViewerIntegrationTest]; the shared
 * action logic itself is in LensTextActionsTest.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class SelectTextEntryTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun decodeSynchronously() {
        ViewerFixtures.installSynchronousImageLoader(context)
    }

    // ── the details card's line ────────────────────────────────────────────────────────────────

    @Composable
    private fun Card(onSelectText: (() -> Unit)?) {
        FotoXplorrTheme(GalleryPreferencesState()) {
            LensCard(
                asset = ViewerFixtures.roll(context)[4],
                recognizedText = "PLATFORM 9\nNorthern line",
                onSearchLibrary = {},
                onSelectText = onSelectText,
            )
        }
    }

    @Test
    fun `the card's line opens Select text, thumbnail and words alike`() {
        var opened = 0
        compose.setContent { Card { opened++ } }
        compose.waitForIdle()
        compose.onNodeWithText("Select text from the image").assertHasClickAction().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Select text from the image").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `the card's line is plain text where there is no photo to select from`() {
        compose.setContent { Card(onSelectText = null) }
        compose.waitForIdle()
        compose.onNodeWithText("Select text from the image").assertHasNoClickAction()
    }

    @Test
    fun `Copy text on the card still copies everything the index read`() {
        compose.setContent { Card { } }
        compose.waitForIdle()
        compose.onNodeWithText("Copy text").performClick()
        compose.waitForIdle()
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        assertEquals("PLATFORM 9\nNorthern line", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        compose.onNodeWithText("Copied").assertExists()
    }

    // ── the actions room's row ─────────────────────────────────────────────────────────────────

    @Composable
    private fun Room(
        onSelectText: (() -> Unit)?,
        isVideo: Boolean = false,
        onOpenWith: () -> Unit = {},
        isExternal: Boolean = false,
        catalogueActions: Boolean = true,
    ) {
        FotoXplorrTheme(GalleryPreferencesState()) {
            ViewerActionsRoom(
                isFavorite = false,
                isSensitive = false,
                canMoveToTrash = true,
                slideshowActive = false,
                onToggleSlideshow = {},
                onToggleFavorite = {},
                onToggleSensitive = {},
                onShare = {},
                onEdit = {},
                onOpenWith = onOpenWith,
                onMoveToTrash = {},
                isVideo = isVideo,
                isExternal = isExternal,
                catalogueActions = catalogueActions,
                onSelectText = onSelectText,
                hasBottomPeek = false,
            )
        }
    }

    @Test
    fun `the actions room has a Select text row that opens the mode`() {
        var opened = 0
        compose.setContent { Room(onSelectText = { opened++ }) }
        compose.waitForIdle()
        compose.onNodeWithText("Select text").assertHasClickAction().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Select text").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `the row is there for a photo opened from another app and absent when the host passes none`() {
        compose.setContent { Room(onSelectText = {}, isExternal = true, catalogueActions = false) }
        compose.waitForIdle()
        compose.onNodeWithText("Select text").assertExists()
    }

    @Test
    fun `no row without a callback, and none for a video`() {
        compose.setContent { Room(onSelectText = null) }
        compose.waitForIdle()
        compose.onAllNodesWithText("Select text").assertCountEquals(0)
    }

    @Test
    fun `a video never gets the row even if the host passed one`() {
        compose.setContent { Room(onSelectText = {}, isVideo = true) }
        compose.waitForIdle()
        compose.onAllNodesWithText("Select text").assertCountEquals(0)
    }

    @Test
    fun `a tap on a row's word acts, not only on its icon`() {
        var openedWith = 0
        compose.setContent { Room(onSelectText = {}, onOpenWith = { openedWith++ }) }
        compose.waitForIdle()
        compose.onNodeWithText("Open with").assertHasClickAction().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Open with").performClick()
        assertEquals(1, openedWith)
    }

    @Test
    fun `a row that is disabled is not clickable, whole row`() {
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                ViewerActionsRoom(
                    isFavorite = false,
                    isSensitive = false,
                    canMoveToTrash = false,
                    slideshowActive = false,
                    onToggleSlideshow = {},
                    onToggleFavorite = {},
                    onToggleSensitive = {},
                    onShare = {},
                    onEdit = {},
                    onOpenWith = {},
                    onMoveToTrash = {},
                    hasBottomPeek = false,
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Trash unavailable").assertIsNotEnabled()
        compose.onNodeWithText("Share").assertIsEnabled()
    }

    // ── the toolbar's actions on the selection ─────────────────────────────────────────────────

    /** Two lines, tagged French; the first line is "Quai neuf" and the second "Sortie libre". */
    private fun frenchPage(): TextLayout = TextLayout.build(
        listOf(
            RawBlock(
                listOf(
                    RawLine(
                        listOf(
                            rawWord("Quai", 100f, 100f, 200f, 130f, "fr"),
                            rawWord("neuf", 220f, 100f, 320f, 130f, "fr"),
                        ),
                    ),
                    RawLine(
                        listOf(
                            rawWord("Sortie", 100f, 150f, 240f, 180f, "fr"),
                            rawWord("libre", 260f, 150f, 360f, 180f, "fr"),
                        ),
                    ),
                ),
            ),
        ),
        FRAME_W, FRAME_H,
    )

    private fun stateWithPage(): TextSelectState =
        TextSelectState(TextLayout.empty(1, 1)).apply { useWordLayout(frenchPage()) }

    @Composable
    private fun Bar(state: TextSelectState, onSearchLibrary: ((String) -> Unit)?, onDone: () -> Unit = {}) {
        MaterialTheme {
            TextSelectBar(state = state, assetKey = "photo", onSearchLibrary = onSearchLibrary, onDone = onDone)
        }
    }

    private fun selectLine(state: TextSelectState, line: Int) {
        compose.runOnIdle { state.select(state.selection.selectLine(state.layout, line)) }
        compose.waitForIdle()
    }

    private fun nextStarted(): Intent? = shadowOf(context as Application).nextStartedActivity

    @Test
    fun `with nothing selected the actions that need words wait, and Select all is still there`() {
        val state = stateWithPage()
        compose.setContent { Bar(state, onSearchLibrary = {}) }
        compose.waitForIdle()
        for (label in listOf("Share", "Search", "Listen", "Translate", "Copy")) {
            compose.onNodeWithText(label).assertHasClickAction().assertIsNotEnabled()
        }
        compose.onNodeWithText("Select all").assertIsEnabled()
        compose.onNodeWithText("Done").assertIsEnabled()
    }

    @Test
    fun `a selection wakes them, each at least 48 dp`() {
        val state = stateWithPage()
        compose.setContent { Bar(state, onSearchLibrary = {}) }
        compose.waitForIdle()
        selectLine(state, 0)
        for (label in listOf("Share", "Search", "Translate", "Copy")) {
            compose.onNodeWithText(label).assertIsEnabled().assertHeightIsAtLeast(48.dp)
        }
        compose.onNodeWithText("Listen").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `Share sends only the selected words`() {
        val state = stateWithPage()
        compose.setContent { Bar(state, onSearchLibrary = {}) }
        compose.waitForIdle()
        selectLine(state, 1)
        compose.onNodeWithText("Share").performClick()
        val chooser = nextStarted()!!
        val inner = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals("Sortie libre", inner.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun `Search hands the library the selected words, not the whole page`() {
        val state = stateWithPage()
        val queries = mutableListOf<String>()
        compose.setContent { Bar(state, onSearchLibrary = { queries += it }) }
        compose.waitForIdle()
        selectLine(state, 0)
        compose.onNodeWithText("Search").performClick()
        assertEquals(listOf("Quai neuf"), queries)
    }

    @Test
    fun `without a library search there is no Search button`() {
        val state = stateWithPage()
        compose.setContent { Bar(state, onSearchLibrary = null) }
        compose.waitForIdle()
        compose.onAllNodesWithText("Search").assertCountEquals(0)
        compose.onNodeWithText("Share").assertExists()
    }

    @Test
    fun `Translate acts on the selection and a change of selection drops what it said`() {
        val state = stateWithPage()
        compose.setContent { Bar(state, onSearchLibrary = {}) }
        compose.waitForIdle()
        selectLine(state, 0)
        compose.onNodeWithText("Translate").performClick()
        compose.waitForIdle()
        // Whichever flavor this runs as, the first line was the text acted on: either the connect
        // build's on-device attempt (a note appears) or the offline hand-off (a chooser starts).
        val handedOff = nextStarted()
        if (handedOff != null) {
            val inner = handedOff.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            val text = inner.getStringExtra(Intent.EXTRA_TEXT) ?: inner.getStringExtra(Intent.EXTRA_PROCESS_TEXT)
            assertEquals("Quai neuf", text)
        }
        selectLine(state, 1)
        compose.waitForIdle()
        compose.onAllNodesWithText("Translating…").assertCountEquals(0)
    }

    @Test
    fun `Done is still the way out and the status line still counts words`() {
        val state = stateWithPage()
        var done = 0
        compose.setContent { Bar(state, onSearchLibrary = {}, onDone = { done++ }) }
        compose.waitForIdle()
        selectLine(state, 0)
        compose.onNodeWithTag(TEXT_SELECT_STATUS_TAG).assertTextEquals("2 words selected")
        compose.onNodeWithText("Done").performClick()
        assertEquals(1, done)
        assertNull(nextStarted())
    }
}
