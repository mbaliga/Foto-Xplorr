package com.fotoxplorr.app.editor

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The hub on screen ([HubRingPanel] over a real [HubSession]), with a stand-in for the controls of
 * the open tool: a line naming the tool and one button that plays "the person changed something".
 * The real controls are the editor's and are not drawn here (they need a decoded photo).
 *
 * This proves the wiring: what the centre and ring show, that every tool is reachable by taps
 * alone, that a used tool is promoted after the hysteresis margin, that Back steps inside the hub
 * before it leaves, the target sizes under Robolectric's measuring, and the large-font fallback. It
 * does not prove the look (the arc, the fills, thumb reach) or TalkBack; those are device checks.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h740dp-xhdpi")
class HubRingScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private class BackOwner : OnBackPressedDispatcherOwner {
        private val registry = LifecycleRegistry.createUnsafe(this).also { it.currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
        override val onBackPressedDispatcher = OnBackPressedDispatcher()
    }

    private val owner = BackOwner()
    private val store = HubRingSessionTest.FakeStore()
    private var left = 0
    private var session: HubSession? = null

    /** The host's Back is registered first, as the activity's is; the hub's comes after, so it is asked first. */
    @Composable
    private fun Host(fontScale: Float? = null) {
        val density = LocalDensity.current
        val provided = if (fontScale == null) density else Density(density.density, fontScale)
        CompositionLocalProvider(
            LocalOnBackPressedDispatcherOwner provides owner,
            LocalDensity provides provided,
        ) {
            MaterialTheme {
                BackHandler { left++ }
                val hub = rememberHubSession(store).also { session = it }
                Column {
                    HubRingPanel(hub, controlsMaxHeight = 300.dp, modifier = Modifier) { spec ->
                        Text("Editing ${spec.title}")
                        Text(
                            "Change ${spec.title}",
                            modifier = Modifier.clickable { hub.applied() },
                        )
                    }
                }
            }
        }
    }

    private fun back() {
        compose.runOnIdle { owner.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun ring(group: ToolGroup, deeper: Int = deeperOf(group)) =
        compose.onNodeWithContentDescription(HubCopy.groupDescription(group, deeper))

    private fun deeperOf(group: ToolGroup) =
        HubModel.groupPage(group, session?.state?.memory?.usage ?: ToolUsage.EMPTY, deeperShown = false).deeperCount

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun atHome() = shown(HubCopy.CENTRE_LABEL)

    private fun backToHome() {
        var guard = 0
        while (!atHome() && guard++ < 6) compose.onNodeWithText(HubCopy.BACK).performClick()
        assertTrue("home", atHome())
    }

    @Test
    fun `the centre shows the cold start tools, the ring shows five groups, and nothing else`() {
        compose.setContent { Host() }
        ToolCatalog.defaultCentre().forEach { compose.onNodeWithText(it.title).assertExists() }
        assertEquals(5, ToolCatalog.defaultCentre().size)
        // A tool from outside the centre is a tap deeper, not on the home page.
        assertFalse(shown(HubCopy.TOOL_HIGHLIGHTS))
        assertFalse(shown(HubCopy.TOOL_CURVES))
        ToolGroup.entries.forEach { ring(it).assertExists() }
        assertFalse(shown(HubCopy.BACK))
    }

    @Test
    fun `a ring button says its group and what is inside, and a ring tap shows the group's page`() {
        compose.setContent { Host() }
        compose.onNodeWithContentDescription("Light, 3 more inside").assertExists()
        ring(ToolGroup.LIGHT).performClick()
        compose.onNodeWithText(HubCopy.TOOL_HIGHLIGHTS).assertExists()
        compose.onNodeWithText(HubCopy.TOOL_SHADOWS).assertExists()
        // Rarely used tools are one tap deeper.
        assertFalse(shown(HubCopy.TOOL_CURVES))
        compose.onNodeWithContentDescription(HubRingCopy.moreDescription(3, shown = false)).performClick()
        compose.onNodeWithText(HubCopy.TOOL_CURVES).assertExists()
        compose.onNodeWithText(HubCopy.TOOL_WHITES).assertExists()
        compose.onNodeWithContentDescription(HubRingCopy.moreDescription(3, shown = true)).assertExists()
    }

    @Test
    fun `the current group is marked as selected, the others are not`() {
        compose.setContent { Host() }
        ToolGroup.entries.forEach { ring(it).assertIsNotSelected() }
        ring(ToolGroup.COLOUR).performClick()
        ring(ToolGroup.COLOUR).assertIsSelected()
        ring(ToolGroup.LIGHT).assertIsNotSelected()
        compose.onNodeWithText(HubCopy.TOOL_SATURATION).performClick()
        // With a tool open the ring is compact but still says where you are.
        ring(ToolGroup.COLOUR).assertIsSelected()
    }

    @Test
    fun `every tool is reachable by taps alone`() {
        compose.setContent { Host() }
        for (spec in ToolCatalog.all) {
            backToHome()
            val centre = session!!.view.centre.any { it.id == spec.id }
            if (!centre) {
                ring(spec.group).performClick()
                val page = session!!.view.groupPage!!
                if (spec !in page.front) {
                    compose.onNodeWithContentDescription(HubRingCopy.moreDescription(page.deeperCount, shown = false)).performClick()
                }
            }
            compose.onNodeWithText(spec.title).performClick()
            compose.onNodeWithText("Editing ${spec.title}").assertExists()
            assertEquals(spec.id, session!!.view.openTool?.id)
        }
        // The hub never counted a visit as a use.
        assertTrue(store.memory.usage.isEmpty)
    }

    @Test
    fun `a tool used enough takes the weakest centre slot, but not on one use`() {
        compose.setContent { Host() }
        val cold = session!!.view.centre.map { it.id }

        fun use() {
            backToHome()
            ring(ToolGroup.LIGHT).performClick()
            compose.onNodeWithText(HubCopy.TOOL_HIGHLIGHTS).performClick()
            compose.onNodeWithText("Change ${HubCopy.TOOL_HIGHLIGHTS}").performClick()
            // Several changes in one opening are one use.
            compose.onNodeWithText("Change ${HubCopy.TOOL_HIGHLIGHTS}").performClick()
            compose.onNodeWithText("Change ${HubCopy.TOOL_HIGHLIGHTS}").performClick()
        }

        use()
        backToHome()
        assertEquals("one use is not enough to move anything", cold, session!!.view.centre.map { it.id })
        compose.onNodeWithText(HubCopy.TOOL_STRAIGHTEN).assertExists()

        use()
        use()
        backToHome()
        val now = session!!.view.centre.map { it.id }
        assertTrue("used three times: $now", ToolIds.HIGHLIGHTS in now)
        compose.onNodeWithText(HubCopy.TOOL_HIGHLIGHTS).assertExists()
        // It took the slot of the weakest, and everything else stayed where it was.
        val slot = now.indexOf(ToolIds.HIGHLIGHTS)
        assertEquals(cold.size, now.size)
        cold.forEachIndexed { i, id -> if (i != slot) assertEquals(id, now[i]) }
        // And it went to disk.
        assertEquals(now, store.memory.centre)
    }

    @Test
    fun `Back from a group returns to the ring before the editor is left`() {
        compose.setContent { Host() }
        ring(ToolGroup.LIGHT).performClick()
        compose.onNodeWithText(HubCopy.TOOL_HIGHLIGHTS).assertExists()
        assertFalse(atHome())

        back()
        assertTrue("the hub's home", atHome())
        assertEquals("the editor was not left", 0, left)
        ToolGroup.entries.forEach { ring(it).assertExists() }

        back()
        assertEquals("with nothing of the hub's left, Back is the host's", 1, left)
    }

    @Test
    fun `Back from a tool closes the tool, then the group page, then the editor`() {
        compose.setContent { Host() }
        ring(ToolGroup.COLOUR).performClick()
        compose.onNodeWithText(HubCopy.TOOL_VIBRANCE).performClick()
        compose.onNodeWithText("Editing ${HubCopy.TOOL_VIBRANCE}").assertExists()

        back()
        compose.onNodeWithText("Editing ${HubCopy.TOOL_VIBRANCE}").assertDoesNotExist()
        compose.onNodeWithText(HubCopy.TOOL_TEMPERATURE).assertExists() // the Colour page again
        back()
        assertTrue(atHome())
        assertEquals(0, left)
        back()
        assertEquals(1, left)
    }

    @Test
    fun `the visible Back button does what the system Back does`() {
        compose.setContent { Host() }
        ring(ToolGroup.EFFECTS).performClick()
        compose.onNodeWithText(HubCopy.TOOL_HEAL).performClick()
        compose.onNodeWithText(HubCopy.BACK).performClick()
        compose.onNodeWithText(HubCopy.TOOL_PRESETS).assertExists()
        compose.onNodeWithText(HubCopy.BACK).performClick()
        assertTrue(atHome())
    }

    @Test
    fun `the labelled Back of a group page leaves the page even with More shown`() {
        compose.setContent { Host() }
        ring(ToolGroup.LIGHT).performClick()
        compose.onNodeWithContentDescription(HubRingCopy.moreDescription(3, shown = false)).performClick()
        compose.onNodeWithText(HubCopy.TOOL_CURVES).assertExists()
        compose.onNodeWithText(HubCopy.BACK).performClick()
        assertTrue("one tap on Back is home", atHome())
        // The system Back still steps: More first, then the page.
        ring(ToolGroup.LIGHT).performClick()
        compose.onNodeWithContentDescription(HubRingCopy.moreDescription(3, shown = false)).performClick()
        back()
        assertFalse(shown(HubCopy.TOOL_CURVES))
        compose.onNodeWithText(HubCopy.TOOL_HIGHLIGHTS).assertExists()
    }

    @Test
    fun `the list keeps its scroll place when a tool is opened and closed`() {
        compose.setContent { Host(fontScale = 2f) }
        // Vignette is the last tool of the last group: far below the first screenful.
        compose.onNodeWithText(HubCopy.TOOL_VIGNETTE).performScrollTo().performClick()
        compose.onNodeWithText("Editing ${HubCopy.TOOL_VIGNETTE}").assertExists()
        compose.onNodeWithText(HubCopy.BACK).performClick()
        compose.onNodeWithText(HubCopy.TOOL_VIGNETTE).assertIsDisplayed()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h500dp-xhdpi")
    fun `a short upright window gets the list, so the photo is not squeezed`() {
        compose.setContent { Host() }
        ToolGroup.entries.forEach {
            assertEquals(0, compose.onAllNodesWithContentDescription(HubCopy.groupDescription(it, deeperOf(it))).fetchSemanticsNodes().size)
        }
        ToolCatalog.all.forEach { assertTrue(it.title, shown(it.title)) }
    }

    @Test
    fun `a tool opened from the centre goes back to the home, not to a group`() {
        compose.setContent { Host() }
        compose.onNodeWithText(HubCopy.TOOL_EXPOSURE).performClick()
        compose.onNodeWithText("Editing ${HubCopy.TOOL_EXPOSURE}").assertExists()
        back()
        assertTrue(atHome())
    }

    @Test
    fun `every button is at least 48 dp each way`() {
        compose.setContent { Host() }
        ToolCatalog.defaultCentre().forEach {
            compose.onNodeWithText(it.title).assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        }
        ToolGroup.entries.forEach { ring(it).assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp) }
        ring(ToolGroup.LIGHT).performClick()
        compose.onNodeWithText(HubCopy.BACK).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription(HubRingCopy.moreDescription(3, shown = false))
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        compose.onNodeWithText(HubCopy.TOOL_EXPOSURE).performClick()
        // The compact ring keeps its targets while a tool is open.
        ToolGroup.entries.forEach { ring(it).assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp) }
    }

    @Test
    fun `a screen reader meets the centre first, then the ring`() {
        compose.setContent { Host() }
        val labels = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().map { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
                ?: node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text
        }
        assertEquals(
            ToolCatalog.defaultCentre().map { it.title } +
                ToolGroup.entries.map { HubCopy.groupDescription(it, deeperOf(it)) },
            labels,
        )
    }

    @Test
    fun `a large font gets the list - every tool under its group, no ring, still all taps`() {
        compose.setContent { Host(fontScale = 2f) }
        ToolGroup.entries.forEach {
            assertEquals(0, compose.onAllNodesWithContentDescription(HubCopy.groupDescription(it, deeperOf(it))).fetchSemanticsNodes().size)
            compose.onNodeWithText(HubCopy.groupTitle(it)).assertExists()
        }
        // A deep tool is one scroll and one tap, with no "More" to find first.
        compose.onNodeWithText(HubCopy.TOOL_CURVES).performScrollTo().performClick()
        compose.onNodeWithText("Editing ${HubCopy.TOOL_CURVES}").assertExists()
        assertFalse("no ring in the list layout", shown(HubCopy.CENTRE_LABEL))
        back()
        assertTrue(atHome())
        assertEquals(0, left)
    }

    @Test
    fun `every tool is in the list layout too`() {
        compose.setContent { Host(fontScale = 2f) }
        ToolCatalog.all.forEach { assertTrue(it.title, shown(it.title)) }
    }

    @Test
    fun `the page and the used counts survive a recreate, and the centre is not recomputed under an open tool`() {
        val tester = StateRestorationTester(compose)
        tester.setContent { Host() }
        ring(ToolGroup.LIGHT).performClick()
        compose.onNodeWithText(HubCopy.TOOL_HIGHLIGHTS).performClick()
        compose.onNodeWithText("Change ${HubCopy.TOOL_HIGHLIGHTS}").performClick()
        val centreBefore = session!!.view.centre.map { it.id }

        tester.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("Editing ${HubCopy.TOOL_HIGHLIGHTS}").assertExists()
        assertEquals(centreBefore, session!!.view.centre.map { it.id })
        assertEquals("the use was kept", ToolUsage.ONE_USE, session!!.state.memory.usage.score(ToolIds.HIGHLIGHTS))
        assertTrue("and Back still has its step", session!!.canGoBack)
        // The opening was already counted; more changes after the recreate do not count again.
        compose.onNodeWithText("Change ${HubCopy.TOOL_HIGHLIGHTS}").performClick()
        assertEquals(ToolUsage.ONE_USE, session!!.state.memory.usage.score(ToolIds.HIGHLIGHTS))
    }
}
