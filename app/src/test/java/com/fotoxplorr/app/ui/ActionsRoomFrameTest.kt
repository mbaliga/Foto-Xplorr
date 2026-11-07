package com.fotoxplorr.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * On a window too short for the shell's peek the bottom room is single-stage, and the frame must
 * not draw the strip or the navigation-bar slice that belongs to it: both would sit above the rows
 * that repeat the strip's actions.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")
class ActionsRoomFrameTest {

    @get:Rule
    val compose = createComposeRule()

    private val items = listOf(QuickActionItem("Share", Icons.Outlined.Share, {}))

    private fun show(showStrip: Boolean) {
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    ActionsRoomFrame(quickActions = items, showStrip = showStrip) { Text("Row below") }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `with a peek the strip and its navigation spacer are drawn`() {
        show(showStrip = true)
        compose.onAllNodesWithTag(QUICK_ACTION_STRIP_TAG).assertCountEquals(1)
        compose.onAllNodesWithTag(ACTIONS_PEEK_NAV_SPACER_TAG).assertCountEquals(1)
        compose.onNodeWithText("Row below").assertExists()
    }

    @Test
    fun `without a peek neither the strip nor the spacer is drawn but the list is`() {
        show(showStrip = false)
        compose.onAllNodesWithTag(QUICK_ACTION_STRIP_TAG).assertCountEquals(0)
        compose.onAllNodesWithTag(ACTIONS_PEEK_NAV_SPACER_TAG).assertCountEquals(0)
        compose.onNodeWithText("Row below").assertExists()
    }

    @Test
    fun `visibleQuickActions is empty without a peek and the list itself with one`() {
        assertEquals(emptyList<QuickActionItem>(), visibleQuickActions(showStrip = false, items = items))
        assertSame(items, visibleQuickActions(showStrip = true, items = items))
    }
}
