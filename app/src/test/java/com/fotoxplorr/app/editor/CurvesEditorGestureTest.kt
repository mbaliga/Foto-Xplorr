package com.fotoxplorr.app.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.center
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.up
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
 * The gesture loop in [CurvesEditor], driven with synthetic touches under Robolectric.
 *
 * This proves the wiring -- touch-down inserts or grabs, a drag moves by the finger's delta, the
 * three ways of removing a point all remove it, tabs switch and report -- not the FEEL (slop,
 * radius, long-press timing on a real thumb), which is device-checked.
 *
 * The graph is 200dp square at xhdpi (400px) with a 16dp (32px) handle margin, so the plotted area
 * is 336px and a point at (x, y) sits at (32 + 336x, 32 + 336(1-y)) in the node's own pixels.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class CurvesEditorGestureTest {

    @get:Rule
    val compose = createComposeRule()

    private var curve by mutableStateOf(ToneCurve.IDENTITY)
    private var channel by mutableStateOf(CurveChannel.RGB)
    private val dragStates = mutableListOf<Boolean>()

    private fun host(initial: ToneCurve = ToneCurve.IDENTITY) {
        curve = initial
        channel = CurveChannel.RGB
        dragStates.clear()
        compose.setContent {
            Column(Modifier.fillMaxWidth()) {
                CurvesEditor(
                    curveFor = { if (it == CurveChannel.RGB) curve else ToneCurve.IDENTITY },
                    onCurveChange = { which, next -> if (which == CurveChannel.RGB) curve = next },
                    channel = channel,
                    onChannelChange = { channel = it },
                    histogram = ToneHistogram.of(IntArray(64) { (0xFF shl 24) or (it * 4 shl 16) }),
                    onDragActive = { dragStates += it },
                )
            }
        }
    }

    private fun graph() = compose.onNode(
        SemanticsMatcher("tone curve graph") { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith("Tone curve") } == true
        },
    )

    private fun at(x: Float, y: Float) = Offset(32f + 336f * x, 32f + 336f * (1f - y))

    @Test
    fun `touching the line adds a point and dragging moves it by the finger's delta`() {
        host()
        graph().performTouchInput {
            down(at(0.5f, 0.5f))
            moveTo(at(0.5f, 0.5f) + Offset(0f, -80f)) // up 80px = +0.238
            up()
        }
        compose.waitForIdle()
        assertEquals(3, curve.points.size)
        val p = curve.points[1]
        assertEquals(0.5f, p.x, 0.01f)
        assertEquals(0.5f + 80f / 336f, p.y, 0.02f)
        assertEquals(listOf(true, false), dragStates)
    }

    @Test
    fun `an endpoint handle can be grabbed from the margin ring outside the plot`() {
        host(ToneCurve(listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.7f), CurvePoint(1f, 1f))))
        // 12px left of the plot edge and 8px below the bottom edge: inside the 32px ring, so only
        // the outer half of the handle's touch target. Drag it up 100px.
        val start = at(0f, 0f) + Offset(-12f, 8f)
        graph().performTouchInput {
            down(start)
            moveTo(start + Offset(0f, -100f))
            up()
        }
        compose.waitForIdle()
        assertEquals("grabbed the endpoint, did not add a point", 3, curve.points.size)
        assertEquals(0f, curve.points[0].x, 0.001f)
        assertEquals(100f / 336f, curve.points[0].y, 0.02f)
    }

    @Test
    fun `the right-hand endpoint is grabbable from the outer ring as well`() {
        host(ToneCurve(listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.7f), CurvePoint(1f, 1f))))
        val start = at(1f, 1f) + Offset(12f, -8f)
        graph().performTouchInput {
            down(start)
            moveTo(start + Offset(0f, 60f))
            up()
        }
        compose.waitForIdle()
        assertEquals(3, curve.points.size)
        assertEquals(1f, curve.points[2].x, 0.001f)
        assertEquals(1f - 60f / 336f, curve.points[2].y, 0.02f)
    }

    @Test
    fun `a fast drag across the graph leaves exactly one added point`() {
        host()
        graph().performTouchInput {
            down(at(0.3f, 0.3f))
            moveTo(at(0.5f, 0.6f))
            moveTo(at(0.7f, 0.4f))
            moveTo(at(0.85f, 0.9f))
            up()
        }
        compose.waitForIdle()
        assertEquals("one touch, one point", 3, curve.points.size)
        assertEquals(0.85f, curve.points[1].x, 0.03f)
    }

    @Test
    fun `touching an existing point grabs it rather than adding another`() {
        host(ToneCurve(listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.7f), CurvePoint(1f, 1f))))
        graph().performTouchInput {
            down(at(0.5f, 0.7f))
            moveTo(at(0.5f, 0.7f) + Offset(0f, 40f))
            up()
        }
        compose.waitForIdle()
        assertEquals(3, curve.points.size)
        assertTrue(curve.points[1].y < 0.7f)
    }

    @Test
    fun `dragging a point off the graph and letting go removes it`() {
        host(ToneCurve(listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.7f), CurvePoint(1f, 1f))))
        graph().performTouchInput {
            down(at(0.5f, 0.7f))
            moveTo(Offset(center.x, 460f)) // well past the bottom edge plus the margin
            up()
        }
        compose.waitForIdle()
        assertEquals(2, curve.points.size)
    }

    @Test
    fun `dragging off the graph and back in again does not remove the point`() {
        host(ToneCurve(listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.7f), CurvePoint(1f, 1f))))
        graph().performTouchInput {
            down(at(0.5f, 0.7f))
            moveTo(Offset(center.x, 460f))
            moveTo(at(0.5f, 0.4f))
            up()
        }
        compose.waitForIdle()
        assertEquals(3, curve.points.size)
        assertEquals(0.4f, curve.points[1].y, 0.03f)
    }

    @Test
    fun `an end point cannot be dragged out of existence`() {
        host()
        graph().performTouchInput {
            down(at(1f, 1f))
            moveTo(Offset(900f, -200f))
            up()
        }
        compose.waitForIdle()
        assertEquals(2, curve.points.size)
    }

    @Test
    fun `double-tapping a point removes it`() {
        host(ToneCurve(listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.7f), CurvePoint(1f, 1f))))
        graph().performTouchInput {
            down(at(0.5f, 0.7f)); up()
            advanceEventTime(120)
            down(at(0.5f, 0.7f)); up()
        }
        compose.waitForIdle()
        assertEquals(2, curve.points.size)
    }

    @Test
    fun `a single tap on a point leaves it alone`() {
        host(ToneCurve(listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.7f), CurvePoint(1f, 1f))))
        graph().performTouchInput { down(at(0.5f, 0.7f)); up() }
        compose.waitForIdle()
        assertEquals(3, curve.points.size)
    }

    @Test
    fun `long-pressing a point removes it`() {
        host(ToneCurve(listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.7f), CurvePoint(1f, 1f))))
        graph().performTouchInput { down(at(0.5f, 0.7f)) }
        compose.mainClock.advanceTimeBy(1_000)
        graph().performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(2, curve.points.size)
    }

    @Test
    fun `the channel tabs switch the channel and the reset chip clears the curve`() {
        host(CurvePreset.STRONG_CONTRAST.curve)
        assertFalse(curve.isIdentity)
        compose.onNodeWithText("Reset").performClick()
        compose.waitForIdle()
        assertTrue(curve.isIdentity)
        compose.onNodeWithText("Luma").performClick()
        compose.waitForIdle()
        assertEquals(CurveChannel.LUMA, channel)
    }

    @Test
    fun `a preset chip loads its points into the curve`() {
        host()
        compose.onNodeWithText("Soft contrast").performClick()
        compose.waitForIdle()
        assertEquals(CurvePreset.SOFT_CONTRAST.curve, curve)
    }
}
