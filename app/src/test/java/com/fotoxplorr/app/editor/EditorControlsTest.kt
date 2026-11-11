package com.fotoxplorr.app.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The editor's shared slider row and the Presets panel's button, under Robolectric.
 *
 * Proves the arithmetic and the sizes (what "reset" sets, that the row does not change height when
 * it appears); it does not prove the look.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h740dp-xhdpi")
class EditorControlsTest {

    @get:Rule
    val compose = createComposeRule()

    private val healRange = HealSpot.MIN_RADIUS..HealSpot.MAX_RADIUS

    @Test
    fun `reset on the Spot size slider goes to the default radius, which a HealSpot accepts`() {
        var radius = 0.1f
        compose.setContent {
            MaterialTheme {
                LabelledSlider("Spot size", radius, range = healRange, neutral = HealSpot.DEFAULT_RADIUS) { radius = it }
            }
        }
        compose.onNodeWithContentDescription("Reset Spot size").performClick()
        compose.waitForIdle()
        assertEquals(HealSpot.DEFAULT_RADIUS, radius, 0f)
        // The thing that used to crash on the next tap on the photo.
        HealSpot(0.5f, 0.5f, radius)
    }

    @Test
    fun `a slider whose range excludes zero never resets to zero, even when no neutral is named`() {
        var radius = 0.1f
        compose.setContent {
            MaterialTheme { LabelledSlider("Spot size", radius, range = healRange) { radius = it } }
        }
        compose.onNodeWithContentDescription("Reset Spot size").performClick()
        compose.waitForIdle()
        assertTrue("reset gave $radius", radius in healRange)
        HealSpot(0.5f, 0.5f, radius)
    }

    @Test
    fun `reset is offered only away from neutral`() {
        var radius by mutableStateOf(HealSpot.DEFAULT_RADIUS)
        var exposure by mutableStateOf(0f)
        compose.setContent {
            MaterialTheme {
                Box {
                    LabelledSlider("Spot size", radius, range = healRange, neutral = HealSpot.DEFAULT_RADIUS) { radius = it }
                }
                Box {
                    LabelledSlider("Exposure", exposure) { exposure = it }
                }
            }
        }
        assertEquals(0, compose.onAllNodesWithContentDescription("Reset Spot size").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithContentDescription("Reset Exposure").fetchSemanticsNodes().size)
        exposure = 0.5f
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Reset Exposure").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `the row is the same height with and without the reset link, so the photo above does not move`() {
        var value by mutableStateOf(0f)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.testTag("row")) { LabelledSlider("Exposure", value) { value = it } }
            }
        }
        val atNeutral = compose.onNodeWithTag("row").getUnclippedBoundsInRoot().height
        value = 0.5f
        compose.waitForIdle()
        val away = compose.onNodeWithTag("row").getUnclippedBoundsInRoot().height
        assertEquals(atNeutral.value, away.value, 0.01f)
        value = 0f
        compose.waitForIdle()
        assertEquals(atNeutral.value, compose.onNodeWithTag("row").getUnclippedBoundsInRoot().height.value, 0.01f)
    }

    @Test
    fun `the presets panel's save button is a 48 dp target`() {
        compose.setContent {
            MaterialTheme {
                PresetsPanel(
                    userPresets = emptyList(),
                    currentAdjustments = Adjustments(),
                    onApply = {}, onSaveCurrent = {}, onRename = { _, _ -> }, onDelete = {},
                )
            }
        }
        compose.onNodeWithText("Save current as preset…").assertHeightIsAtLeast(48.dp)
    }
}
