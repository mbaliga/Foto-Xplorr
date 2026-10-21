package com.fotoxplorr.app.hyle

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The shade composable under every shape of input it can be handed, on a real Compose runtime
 * with real drawing. [ActivityShadeTest] pins the arithmetic; this pins that the surface built on
 * it survives — nothing here asserts a look, only that each state composes, lays out, draws and
 * keeps the content beneath it.
 *
 * NATIVE graphics so the Canvas lambdas actually run: in legacy mode nothing draws, and a divide
 * by a zero-width track or a negative inset would pass unseen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class ActivityShadeSurfaceTest {

    @get:Rule
    val compose = createComposeRule()

    /** The host's own state, mutated under the test so one composition sees every transition. */
    private class Host {
        var activities by mutableStateOf(emptyList<BackgroundActivity>())
        var state by mutableStateOf(ShadeState.COLLAPSED)
    }

    private fun mount(host: Host) {
        compose.setContent {
            ActivityShade(
                activities = host.activities,
                state = host.state,
                onStateChange = { host.state = it },
            ) {
                Box(Modifier.fillMaxSize().testTag("content"))
            }
        }
    }

    @Test
    fun `nothing running draws only the content`() {
        val host = Host()
        mount(host)
        compose.onNodeWithTag("content").assertIsDisplayed()
        ActivityKind.entries.forEach { compose.onNodeWithText(it.label).assertDoesNotExist() }
    }

    @Test
    fun `one three and eight jobs in every state`() {
        val host = Host()
        mount(host)
        for (count in listOf(1, 3, 8)) {
            for (state in ShadeState.entries) {
                compose.runOnIdle {
                    host.activities = jobs(count)
                    host.state = state
                }
                compose.waitForIdle()
                compose.onNodeWithTag("content").assertIsDisplayed()
            }
        }
        // Eight in the notification state: four rows and the overflow line, never eight rows.
        compose.runOnIdle {
            host.activities = jobs(8)
            host.state = ShadeState.NOTIFICATION
        }
        compose.onNodeWithText("and 4 more").assertIsDisplayed()
    }

    @Test
    fun `a failed job and one with no total in every state`() {
        val host = Host()
        mount(host)
        val mixed = listOf(
            BackgroundActivity("f", ActivityKind.MOVING, 10, 100, error = "Could not read the library"),
            BackgroundActivity("u", ActivityKind.SCANNING, 12, 0),
            BackgroundActivity("fu", ActivityKind.COPYING, 0, 0, error = "Nothing to copy"),
            BackgroundActivity("z", ActivityKind.EXPORTING, 0, 100),
            BackgroundActivity("d", ActivityKind.BACKING_UP, 100, 100),
        )
        for (state in ShadeState.entries) {
            compose.runOnIdle {
                host.activities = mixed
                host.state = state
            }
            compose.waitForIdle()
            compose.onNodeWithTag("content").assertIsDisplayed()
        }
        // The failure's message stands in for the kind's name in the rows.
        compose.onNodeWithText("Could not read the library").assertIsDisplayed()
    }

    @Test
    fun `a job finishing mid-animation`() {
        compose.mainClock.autoAdvance = false
        val host = Host()
        mount(host)
        compose.runOnIdle { host.activities = jobs(3) }
        // Part way through the bars easing in, the first job finishes and the list re-indexes.
        compose.mainClock.advanceTimeBy(120)
        compose.runOnIdle { host.activities = host.activities.drop(1) }
        compose.mainClock.advanceTimeBy(120)
        // Then the rest complete at once, then there is nothing.
        compose.runOnIdle { host.activities = host.activities.map { it.copy(completed = it.total) } }
        compose.mainClock.advanceTimeBy(120)
        compose.runOnIdle { host.activities = emptyList() }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("content").assertIsDisplayed()
    }

    @Test
    fun `rapid state changes`() {
        compose.mainClock.autoAdvance = false
        val host = Host()
        mount(host)
        compose.runOnIdle { host.activities = jobs(3) }
        // Faster than the 320ms settle, so every change lands on an animation in flight.
        repeat(24) { step ->
            compose.runOnIdle { host.state = ShadeState.entries[step % ShadeState.entries.size] }
            compose.mainClock.advanceTimeBy(16)
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("content").assertIsDisplayed()
    }

    @Test
    fun `tapping the shade cycles its state`() {
        val host = Host()
        mount(host)
        compose.runOnIdle { host.activities = jobs(2) }
        compose.onNode(hasClickAction()).performClick()
        compose.runOnIdle { assertEquals(ShadeState.NOTIFICATION, host.state) }
        compose.onNode(hasClickAction()).performClick()
        compose.runOnIdle { assertEquals(ShadeState.EXPANDED, host.state) }
        compose.onNode(hasClickAction()).performClick()
        compose.runOnIdle { assertEquals(ShadeState.COLLAPSED, host.state) }
    }

    /** [count] jobs across every kind, at a spread of fractions, each with a stable id. */
    private fun jobs(count: Int): List<BackgroundActivity> = List(count) { index ->
        val kind = ActivityKind.entries[index % ActivityKind.entries.size]
        BackgroundActivity("job-$index", kind, completed = 1_000 * (index + 1), total = 12_366)
    }
}
