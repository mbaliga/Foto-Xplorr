package com.fotoxplorr.app.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
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
 * The press-and-hold of [FrameStepButton]: a slide off the button cancels the hold without stepping
 * (it used to step once per move event, a runaway scan), while a hold that stays on the button repeats.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class FrameStepButtonTest {
    @get:Rule
    val compose = createComposeRule()

    private var steps = 0

    private fun setUp() {
        compose.setContent {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                FrameStepButton(
                    icon = Icons.Outlined.KeyboardArrowRight,
                    description = "Next frame",
                    enabled = true,
                    onStep = { steps++ },
                    tag = FRAME_FORWARD_TAG,
                )
            }
        }
    }

    @Test
    fun `only a timeout repeats`() {
        assertTrue(FrameStep.holdRepeats(FrameStep.HoldWait.TIMED_OUT))
        assertFalse(FrameStep.holdRepeats(FrameStep.HoldWait.RELEASED))
        assertFalse(FrameStep.holdRepeats(FrameStep.HoldWait.CANCELLED))
    }

    @Test
    fun `a tap is one step`() {
        setUp()
        compose.onNodeWithTag(FRAME_FORWARD_TAG).performTouchInput { down(center); up() }
        compose.waitForIdle()
        assertEquals(1, steps)
    }

    @Test
    fun `sliding off the button cancels the hold without stepping again`() {
        setUp()
        compose.onNodeWithTag(FRAME_FORWARD_TAG).performTouchInput {
            down(center)
            // Off the 48 dp button and on, a move every frame, well inside the first repeat delay.
            repeat(12) { moveBy(Offset(0f, 40f)) }
            up()
        }
        compose.waitForIdle()
        assertEquals(1, steps)
    }

    @Test
    fun `a hold that stays on the button repeats`() {
        setUp()
        compose.onNodeWithTag(FRAME_FORWARD_TAG).performTouchInput {
            down(center)
            advanceEventTime(FrameStep.HOLD_BEFORE_REPEAT_MS + 400L)
            up()
        }
        compose.waitForIdle()
        assertTrue("steps $steps", steps > 1)
    }
}
