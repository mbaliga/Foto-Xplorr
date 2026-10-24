package com.fotoxplorr.app.gallery

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.sp
import dev.aarso.cellshell.WheelItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The rail with a pane open over the destination it names, and the wheel turned by something other
 * than a finger. [DestinationWordRailComposeTest] holds the rest of the rail; the decisions are
 * [PaneInputTest]'s.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class DestinationWordRailPaneComposeTest {

    @get:Rule
    val compose = createComposeRule()

    private val items = HyleDestination.entries.map { WheelItem(it.name, it.label) }

    private class Host(initial: String, val follow: Boolean = false, paneOpen: Boolean = false) {
        var selectedId by mutableStateOf(initial)
        var paneOpen by mutableStateOf(paneOpen)
        val selections = mutableListOf<String>()
        var reselects = 0
        var leaves = 0
    }

    private fun mount(host: Host) {
        compose.setContent {
            DestinationRailPanel(
                items = items,
                selectedId = host.selectedId,
                onSelect = {
                    host.selections += it
                    if (host.follow) host.selectedId = it
                    // As GalleryScreen does: choosing a word drops whatever pane was over the last.
                    host.paneOpen = false
                },
                onReselect = { host.reselects++ },
                paneOpen = host.paneOpen,
                onLeavePane = {
                    host.leaves++
                    // As GalleryScreen does: the pane closes, which is what the rail then sees.
                    host.paneOpen = false
                },
                onOpenSettings = {},
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    private fun pitchPx(): Float = with(compose.density) { RAIL_ROW_PITCH_SP.sp.toPx() }

    private fun centreY(node: SemanticsNodeInteraction): Float {
        val bounds = node.fetchSemanticsNode().boundsInRoot
        return (bounds.top + bounds.bottom) / 2f
    }

    private fun markerY() = centreY(compose.onNodeWithTag(RAIL_MARKER_TAG))

    private fun wordY(destination: HyleDestination) = centreY(compose.onNodeWithText(destination.label))

    private fun advance(ms: Long) {
        var left = ms
        while (left > 0) {
            val step = minOf(16L, left)
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeBy(step)
            left -= step
        }
    }

    @Test
    fun `tapping the word under an open pane reselects it so the pane can be left`() {
        val host = Host(HyleDestination.PHOTOS.name, paneOpen = true)
        mount(host)
        compose.onNodeWithText("Photos").performClick()
        compose.runOnIdle {
            assertEquals(emptyList<String>(), host.selections)
            assertEquals(1, host.reselects)
        }
    }

    @Test
    fun `a scrub that comes back to the selected word under a pane leaves the pane once`() {
        val host = Host(HyleDestination.PHOTOS.name, follow = true, paneOpen = true)
        mount(host)
        compose.mainClock.autoAdvance = false
        advance(100)
        val pitch = pitchPx()

        compose.onRoot().performTouchInput {
            down(center)
            moveBy(Offset(0f, -2 * pitch))
        }
        advance(RAIL_COMMIT_DEBOUNCE_MS / 3)
        compose.onRoot().performTouchInput { moveBy(Offset(0f, 2 * pitch)) }
        advance(RAIL_COMMIT_DEBOUNCE_MS * 3)

        compose.runOnIdle {
            assertEquals("nothing was committed on the way", emptyList<String>(), host.selections)
            assertEquals(1, host.leaves)
            assertEquals("the room is the caller's to close", 0, host.reselects)
        }
        compose.onRoot().performTouchInput { up() }
        advance(1_500)
        compose.runOnIdle {
            assertEquals("the lift adds nothing once the pane is gone", 1, host.leaves)
            assertEquals(emptyList<String>(), host.selections)
        }
    }

    @Test
    fun `a scrub that goes to another word under a pane selects it and never reports leaving`() {
        val host = Host(HyleDestination.PHOTOS.name, follow = true, paneOpen = true)
        mount(host)
        compose.mainClock.autoAdvance = false
        advance(100)

        compose.onRoot().performTouchInput {
            down(center)
            moveBy(Offset(0f, -pitchPx()))
        }
        advance(RAIL_COMMIT_DEBOUNCE_MS * 3)
        compose.onRoot().performTouchInput { up() }
        advance(1_500)

        compose.runOnIdle {
            assertEquals(1, host.selections.size)
            assertEquals(0, host.leaves)
        }
    }

    @Test
    fun `a mouse wheel turn commits one word and settles the wheel on it`() {
        val host = Host(HyleDestination.PHOTOS.name, follow = true)
        mount(host)
        compose.mainClock.autoAdvance = false
        advance(100)
        val focusLineY = wordY(HyleDestination.PHOTOS)

        compose.onRoot().performMouseInput { moveTo(center); scroll(3f) }
        advance(300)
        advance(1_700)

        compose.runOnIdle {
            assertEquals("the turn committed exactly one word", 1, host.selections.size)
            assertTrue(host.selectedId != HyleDestination.PHOTOS.name)
        }
        val landed = HyleDestination.valueOf(host.selectedId)
        // Settled on a detent: the landed word is on the focus line, not left between two.
        assertEquals("the wheel rests between words", focusLineY, wordY(landed), 2f)
        assertEquals(wordY(landed), markerY(), 2f)
    }
}
