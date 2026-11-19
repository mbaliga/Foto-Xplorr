package com.fotoxplorr.app.sources.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.ui.FotoXplorrTheme
import com.fotoxplorr.core.index.sources.SourceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class SourcesScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val calls = ArrayList<String>()
    private val actions = SourcesActions(
        onBack = { calls += "back" },
        onAdd = { calls += "add" },
        onRescan = { calls += "rescan $it" },
        onStop = { calls += "stop $it" },
        onInclude = { r, on -> calls += "include $r $on" },
        onIndexOnConnect = { r, on -> calls += "connect $r $on" },
        onRemove = { calls += "remove $it" },
        onDismissMessage = { calls += "dismiss" },
    )

    private fun card(
        row: Long = 1,
        name: String = "Camera",
        state: SourceState = SourceState.ONLINE,
        busy: Boolean = false,
        problems: List<String> = emptyList(),
        included: Boolean = true,
    ) = SourceCard(
        row = row, name = name, state = state, stateLabel = SourcesCopy.stateLabel(state), stateNote = SourcesCopy.stateNote(state),
        filesLabel = "1200 files", bytesLabel = "3.4 GB", lastRead = "Last read today.", busy = busy,
        progressText = if (busy) "Reading folders. 40 files found so far." else null, progressFraction = if (busy) 0.4f else null,
        problems = problems, sizesPending = false, included = included, indexOnConnect = false, canRescan = !busy,
    )

    private fun show(cards: List<SourceCard>, message: String? = null) {
        compose.setContent { FotoXplorrTheme(GalleryPreferencesState()) { SourcesScreen(cards, message, actions) } }
    }

    @Test
    fun `a fresh install shows one explanation and the add button and no list`() {
        show(emptyList())
        compose.onNodeWithText(SourcesCopy.EMPTY_TITLE).assertIsDisplayed()
        compose.onNodeWithText(SourcesCopy.EMPTY_BODY).assertIsDisplayed()
        compose.onNodeWithText(SourcesCopy.ADD).assertIsDisplayed().assertHasClickAction()
        compose.onAllNodesWithText(SourcesCopy.RESCAN).assertCountEquals(0)
        compose.onNodeWithText(SourcesCopy.ADD).performClick()
        assertEquals(listOf("add"), calls)
    }

    @Test
    fun `an online source shows its words and every control is a labelled 48 dp target`() {
        show(listOf(card()))
        compose.onNodeWithText("Camera").assertIsDisplayed()
        compose.onNodeWithText("1200 files, 3.4 GB").assertIsDisplayed()
        compose.onNodeWithText("Last read today.").assertIsDisplayed()
        compose.onNodeWithContentDescription("State: Online").assertIsDisplayed()
        compose.onAllNodesWithText(SourcesCopy.EMPTY_TITLE).assertCountEquals(0)
        listOf(SourcesCopy.ADD, SourcesCopy.rescanA11y("Camera"), SourcesCopy.removeA11y("Camera")).forEach {
            compose.onNodeWithContentDescription(it).performScrollTo().assertHasClickAction().assertHeightIsAtLeast(48.dp)
        }
        compose.onNodeWithText(SourcesCopy.INCLUDE).performScrollTo().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText(SourcesCopy.ON_CONNECT).performScrollTo().assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `the state is a word and a glyph for every state`() {
        show(SourceState.entries.mapIndexed { i, s -> card(row = i + 1L, name = "S$i", state = s) })
        listOf("Online", "Offline", "Revoked", "Error").forEach {
            compose.onNodeWithContentDescription("State: $it").performScrollTo().assertIsDisplayed()
            compose.onNode(hasText(it) and hasAnyAncestor(hasContentDescription("State: $it")), useUnmergedTree = true).assertExists()
        }
        compose.onNodeWithText("Fotoz lost access to this folder, so pick it again to bring it back.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("This folder is not available right now, maybe because the drive is not connected.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `rescan and the switches report the row and the new value`() {
        show(listOf(card(row = 7, included = true)))
        compose.onNodeWithContentDescription(SourcesCopy.rescanA11y("Camera")).performScrollTo().performClick()
        compose.onNodeWithText(SourcesCopy.INCLUDE).performScrollTo().performClick()
        compose.onNodeWithText(SourcesCopy.ON_CONNECT).performScrollTo().performClick()
        assertEquals(listOf("rescan 7", "include 7 false", "connect 7 true"), calls)
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch) and hasText(SourcesCopy.INCLUDE, substring = true)).assertExists()
    }

    @Test
    fun `a reading source shows progress as words and offers stop and not rescan`() {
        show(listOf(card(row = 2, busy = true)))
        compose.onNodeWithText("Reading folders. 40 files found so far.").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText(SourcesCopy.RESCAN).assertCountEquals(0)
        compose.onNodeWithContentDescription(SourcesCopy.stopA11y("Camera")).performScrollTo().performClick()
        assertEquals(listOf("stop 2"), calls)
    }

    @Test
    fun `problems are listed in words`() {
        show(listOf(card(state = SourceState.ERROR, problems = listOf("3 files could not be read."))))
        compose.onNodeWithText("3 files could not be read.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `removing asks first and says no file is touched and cancel changes nothing`() {
        show(listOf(card(row = 4)))
        compose.onNodeWithContentDescription(SourcesCopy.removeA11y("Camera")).performScrollTo().performClick()
        compose.onNodeWithText(SourcesCopy.REMOVE_TITLE).assertIsDisplayed()
        compose.onNodeWithText("Camera. ${SourcesCopy.REMOVE_BODY}").assertIsDisplayed()
        assertTrue("nothing happened before the answer", calls.isEmpty())
        compose.onNodeWithText(SourcesCopy.CANCEL).performClick()
        assertTrue(calls.isEmpty())
        compose.onNodeWithContentDescription(SourcesCopy.removeA11y("Camera")).performScrollTo().performClick()
        compose.onNode(hasText(SourcesCopy.REMOVE_CONFIRM) and hasAnyAncestor(isDialog())).performClick()
        assertEquals(listOf("remove 4"), calls)
    }

    @Test
    fun `a message shows and can be dismissed and back is reachable`() {
        show(listOf(card()), message = SourcesCopy.ADDED)
        compose.onNodeWithText(SourcesCopy.ADDED).assertIsDisplayed()
        compose.onNodeWithText(SourcesCopy.DISMISS).performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(listOf("dismiss", "back"), calls)
    }
}
