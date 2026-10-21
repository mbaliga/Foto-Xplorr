package com.fotoxplorr.app.gallery

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import dev.aarso.cellshell.WheelItem
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The word rail on a real Compose runtime: every destination, the selection at either end of the
 * run, a selection change mid-flight, and the inputs that must not crash it. The fade curve is
 * [DestinationWordRailTest]'s; this is the composable built on it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class DestinationWordRailComposeTest {

    @get:Rule
    val compose = createComposeRule()

    private val items = HyleDestination.entries.map { WheelItem(it.name, it.label) }

    private class Host(initial: String) {
        var selectedId by mutableStateOf(initial)
        val selections = mutableListOf<String>()
    }

    private fun mount(host: Host, items: List<WheelItem> = this.items) {
        compose.setContent {
            DestinationRailPanel(
                items = items,
                selectedId = host.selectedId,
                onSelect = { host.selections += it },
                onOpenSettings = {},
                modifier = Modifier.fillMaxSize(),
            )
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
    fun `a tap on another row selects it and the selected row is inert`() {
        val host = Host(HyleDestination.PHOTOS.name)
        mount(host)
        compose.onNodeWithText("Videos").performClick()
        compose.onNodeWithText("Photos").performClick()
        compose.runOnIdle { assertEquals(listOf(HyleDestination.VIDEOS.name), host.selections) }
    }

    @Test
    fun `a selection change from one end to the other animates through`() {
        compose.mainClock.autoAdvance = false
        val host = Host(HyleDestination.PETS.name)
        mount(host)
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle { host.selectedId = HyleDestination.PROTECTED.name }
        // Frame by frame through the 320ms settle and the marker's 420ms travel, with a second
        // change landing while the first is still in flight.
        repeat(12) { compose.mainClock.advanceTimeBy(16) }
        compose.runOnIdle { host.selectedId = HyleDestination.PETS.name }
        repeat(40) { compose.mainClock.advanceTimeBy(16) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Pets").assertIsSelected()
        compose.onNodeWithText("Protected").assertIsNotSelected()
        HyleDestination.entries.forEach { compose.onNodeWithText(it.label).assertExists() }
        compose.onRoot().assertExists()
    }
}
