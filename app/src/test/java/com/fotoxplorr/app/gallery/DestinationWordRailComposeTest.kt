package com.fotoxplorr.app.gallery

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
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
import kotlin.math.abs

/**
 * The word rail on a real Compose runtime: every destination, the selection at either end of the
 * run, a selection change mid-flight, the scrub (the square follows the finger, the hovered word
 * is committed after a pause, and lifting the finger only settles), and the inputs that must not
 * crash it. The fade curve and the pure decisions are [DestinationWordRailTest]'s; this is the
 * composable built on them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class DestinationWordRailComposeTest {

    @get:Rule
    val compose = createComposeRule()

    private val items = HyleDestination.entries.map { WheelItem(it.name, it.label) }

    /**
     * [follow] makes the host behave like GalleryScreen: a reported selection becomes the
     * selection. The tests that only watch what the rail reports leave it off.
     */
    private class Host(initial: String, val follow: Boolean = false) {
        var selectedId by mutableStateOf(initial)
        val selections = mutableListOf<String>()
        var reselects = 0
    }

    private fun mount(host: Host, items: List<WheelItem> = this.items) {
        compose.setContent {
            DestinationRailPanel(
                items = items,
                selectedId = host.selectedId,
                onSelect = {
                    host.selections += it
                    if (host.follow) host.selectedId = it
                },
                onReselect = { host.reselects++ },
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
            // Writes made by the test's own gestures are applied on the next frame; make that
            // explicit so a gesture's state change is seen by this frame, not the one after.
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeBy(step)
            left -= step
        }
    }

    @Test
    fun `every destination is drawn with the middle one selected`() {
        val host = Host(HyleDestination.PHOTOS.name)
        mount(host)
        HyleDestination.entries.forEach { compose.onNodeWithText(it.label).assertExists() }
        compose.onNodeWithText("Photos").assertIsSelected()
        compose.onNodeWithText("Pets").assertIsNotSelected()
        compose.onNodeWithText("Settings").assertIsDisplayed()
    }

    @Test
    fun `selection at the first row`() {
        val host = Host(HyleDestination.PETS.name)
        mount(host)
        compose.onNodeWithText("Pets").assertIsSelected()
        HyleDestination.entries.forEach { compose.onNodeWithText(it.label).assertExists() }
    }

    @Test
    fun `selection at the last row`() {
        val host = Host(HyleDestination.PROTECTED.name)
        mount(host)
        compose.onNodeWithText("Protected").assertIsSelected()
        HyleDestination.entries.forEach { compose.onNodeWithText(it.label).assertExists() }
    }

    @Test
    fun `an unknown selection draws the run with nothing selected`() {
        val host = Host("NOT_A_DESTINATION")
        mount(host)
        HyleDestination.entries.forEach { compose.onNodeWithText(it.label).assertExists() }
        compose.onNodeWithText("Pets").assertIsNotSelected()
    }

    @Test
    fun `no destinations draws only the settings row`() {
        val host = Host(HyleDestination.PHOTOS.name)
        mount(host, items = emptyList())
        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onNodeWithText("Photos").assertDoesNotExist()
    }

    @Test
    fun `a tap on another row selects it`() {
        val host = Host(HyleDestination.PHOTOS.name)
        mount(host)
        compose.onNodeWithText("Videos").performClick()
        compose.runOnIdle {
            assertEquals(listOf(HyleDestination.VIDEOS.name), host.selections)
            assertEquals(0, host.reselects)
        }
    }

    @Test
    fun `a tap on the selected row reselects instead of selecting`() {
        // Release no longer closes the room, so this tap is how the user closes it.
        val host = Host(HyleDestination.PHOTOS.name)
        mount(host)
        compose.onNodeWithText("Photos").performClick()
        compose.runOnIdle {
            assertEquals(emptyList<String>(), host.selections)
            assertEquals(1, host.reselects)
        }
    }

    @Test
    fun `following a tap commits nothing but the tap`() {
        // The tap's scroll animation passes through every row between; none of them may be read
        // as a hover, or the debounce would commit one and undo the tap.
        val host = Host(HyleDestination.PROTECTED.name, follow = true)
        mount(host)
        compose.mainClock.autoAdvance = false
        advance(100)
        compose.onNodeWithText("Videos").performClick()
        advance(1_500)
        compose.runOnIdle { assertEquals(listOf(HyleDestination.VIDEOS.name), host.selections) }
        compose.onNodeWithText("Videos").assertIsSelected()
        assertEquals(wordY(HyleDestination.VIDEOS), markerY(), 2f)
    }

    @Test
    fun `the square sits beside the selected word at rest`() {
        val host = Host(HyleDestination.VIDEOS.name)
        mount(host)
        compose.mainClock.autoAdvance = false
        advance(500)
        assertEquals(wordY(HyleDestination.VIDEOS), markerY(), 2f)
    }

    @Test
    fun `the square follows the finger and the pane follows the square`() {
        val host = Host(HyleDestination.PHOTOS.name, follow = true)
        mount(host)
        compose.mainClock.autoAdvance = false
        advance(100)
        val pitch = pitchPx()

        // Three rows up: Photos -> Places. Finger stays down.
        compose.onRoot().performTouchInput {
            down(center)
            moveBy(Offset(0f, -3 * pitch))
        }
        // Still inside the debounce: the square has left Photos for the word under the focus
        // line, but nothing has been committed yet.
        advance(RAIL_COMMIT_DEBOUNCE_MS / 2)
        compose.runOnIdle { assertEquals(emptyList<String>(), host.selections) }
        // Mid-hop, but already nearer the hovered word than the one it left.
        val marker = markerY()
        assertTrue(abs(marker - wordY(HyleDestination.PLACES)) < abs(marker - wordY(HyleDestination.PHOTOS)))

        // After the pause the hovered word is committed, with the finger still down.
        advance(RAIL_COMMIT_DEBOUNCE_MS * 3)
        compose.runOnIdle { assertEquals(listOf(HyleDestination.PLACES.name), host.selections) }
        compose.onNodeWithText("Places").assertIsSelected()
        compose.onNodeWithText("Photos").assertIsNotSelected()
        assertEquals(wordY(HyleDestination.PLACES), markerY(), 2f)

        // Lifting the finger only settles: no further selection, no reselect, square on the word.
        compose.onRoot().performTouchInput { up() }
        advance(1_500)
        compose.runOnIdle {
            assertEquals(listOf(HyleDestination.PLACES.name), host.selections)
            assertEquals(0, host.reselects)
        }
        assertEquals(wordY(HyleDestination.PLACES), markerY(), 2f)
    }

    @Test
    fun `rows passed without pausing are never committed`() {
        val host = Host(HyleDestination.PETS.name, follow = true)
        mount(host)
        compose.mainClock.autoAdvance = false
        advance(100)
        val pitch = pitchPx()

        compose.onRoot().performTouchInput { down(center) }
        // Four rows, a row at a time, never resting on one for the debounce.
        repeat(4) {
            compose.onRoot().performTouchInput { moveBy(Offset(0f, -pitch)) }
            advance(RAIL_COMMIT_DEBOUNCE_MS / 3)
        }
        compose.runOnIdle { assertEquals(emptyList<String>(), host.selections) }

        advance(RAIL_COMMIT_DEBOUNCE_MS * 3)
        compose.runOnIdle { assertEquals(listOf(HyleDestination.PHOTOS.name), host.selections) }

        compose.onRoot().performTouchInput { up() }
        advance(1_500)
        compose.runOnIdle { assertEquals(listOf(HyleDestination.PHOTOS.name), host.selections) }
    }

    @Test
    fun `a scrub brought back to the selected word commits nothing`() {
        val host = Host(HyleDestination.PHOTOS.name, follow = true)
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
        compose.runOnIdle { assertEquals(emptyList<String>(), host.selections) }
        compose.onRoot().performTouchInput { up() }
        advance(1_500)
        compose.runOnIdle { assertEquals(emptyList<String>(), host.selections) }
        compose.onNodeWithText("Photos").assertIsSelected()
        assertEquals(wordY(HyleDestination.PHOTOS), markerY(), 2f)
    }

    @Test
    fun `a turn released before the pause still lands on one word and commits it once`() {
        val host = Host(HyleDestination.PHOTOS.name, follow = true)
        mount(host)
        compose.mainClock.autoAdvance = false
        advance(100)
        val pitch = pitchPx()

        compose.onRoot().performTouchInput {
            down(center)
            moveBy(Offset(0f, -pitch))
        }
        advance(RAIL_COMMIT_DEBOUNCE_MS / 4)
        compose.onRoot().performTouchInput { up() }
        advance(2_000)

        compose.runOnIdle {
            assertEquals(1, host.selections.size)
            assertEquals(0, host.reselects)
            assertEquals(host.selections.single(), host.selectedId)
        }
        val landed = HyleDestination.valueOf(host.selectedId)
        compose.onNodeWithText(landed.label).assertIsSelected()
        assertEquals(wordY(landed), markerY(), 2f)
    }

    @Test
    fun `a selection change from one end to the other animates through`() {
        compose.mainClock.autoAdvance = false
        val host = Host(HyleDestination.PETS.name)
        mount(host)
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle { host.selectedId = HyleDestination.PROTECTED.name }
        // Frame by frame through the 320ms settle and the marker's hops, with a second change
        // landing while the first is still in flight.
        repeat(12) { compose.mainClock.advanceTimeBy(16) }
        compose.runOnIdle { host.selectedId = HyleDestination.PETS.name }
        repeat(40) { compose.mainClock.advanceTimeBy(16) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Pets").assertIsSelected()
        compose.onNodeWithText("Protected").assertIsNotSelected()
        HyleDestination.entries.forEach { compose.onNodeWithText(it.label).assertExists() }
        compose.onRoot().assertExists()
        // The rail followed two outside changes and reported neither back.
        compose.runOnIdle { assertEquals(emptyList<String>(), host.selections) }
        assertEquals(wordY(HyleDestination.PETS), markerY(), 2f)
    }
}
