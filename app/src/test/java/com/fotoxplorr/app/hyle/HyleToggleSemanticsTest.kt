package com.fotoxplorr.app.hyle

import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.assertTouchWidthIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.ui.FotoXplorrTheme
import com.fotoxplorr.app.ui.RoomToggle
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
 * What a screen reader and a thumb get from the room switch, and the two things the old one got
 * wrong that a picture cannot show: it had no switch semantics at all, and a disabled switch was
 * *lighter* than a live one (which on the black rooms reads as the live one).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class HyleToggleSemanticsTest {

    @get:Rule
    val compose = createComposeRule()

    private fun onBlack(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                    content()
                }
            }
        }
    }

    private val switchRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)

    @Test
    fun `a room toggle is a switch that reports on and off`() {
        onBlack { RoomToggle(checked = true, onCheckedChange = {}, description = "Filmstrip") }
        compose.onNode(switchRole).assertExists()
        compose.onNodeWithContentDescription("Filmstrip").assertIsOn()
        compose.onNode(switchRole).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "On"),
        )
    }

    @Test
    fun `an unchecked room toggle reports off`() {
        onBlack { RoomToggle(checked = false, onCheckedChange = {}) }
        compose.onNode(isToggleable()).assertIsOff()
        compose.onNode(switchRole).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Off"),
        )
    }

    @Test
    fun `a tap asks for the opposite state`() {
        val seen = mutableListOf<Boolean>()
        onBlack { RoomToggle(checked = false, onCheckedChange = { seen += it }) }
        compose.onNode(isToggleable()).performClick()
        assertEquals(listOf(true), seen)
    }

    @Test
    fun `a disabled room toggle is disabled and does not fire`() {
        var fired = false
        onBlack { RoomToggle(checked = true, onCheckedChange = { fired = true }, enabled = false) }
        compose.onNode(isToggleable()).assertIsNotEnabled()
        compose.onNode(isToggleable()).performClick()
        assertFalse(fired)
    }

    @Test
    fun `a live room toggle is enabled`() {
        onBlack { RoomToggle(checked = true, onCheckedChange = {}) }
        compose.onNode(isToggleable()).assertIsEnabled()
    }

    /**
     * A display-only switch is the picture inside a row that is itself the one switch. If it
     * claimed the role and a click as well, the row would announce two controls for one setting.
     */
    @Test
    fun `a display only toggle is neither a switch nor clickable`() {
        onBlack { HyleToggle(checked = true, onCheckedChange = null, description = "Picture only") }
        val node = compose.onNodeWithContentDescription("Picture only").fetchSemanticsNode()
        assertFalse(SemanticsProperties.Role in node.config)
        assertFalse(SemanticsActions.OnClick in node.config)
        assertFalse(SemanticsProperties.ToggleableState in node.config)
    }

    @Test
    fun `the touch target is at least 48dp though the track is 32dp tall`() {
        onBlack { RoomToggle(checked = false, onCheckedChange = {}) }
        compose.onNode(isToggleable()).assertTouchHeightIsEqualTo(48.dp)
        compose.onNode(isToggleable()).assertTouchWidthIsEqualTo(64.dp)
    }

    /** The drawn track is 8dp short of the target on each side; a tap there must still land. */
    @Test
    fun `a tap just above the drawn track still toggles`() {
        val seen = mutableListOf<Boolean>()
        onBlack { RoomToggle(checked = false, onCheckedChange = { seen += it }) }
        compose.onNode(isToggleable()).performTouchInput {
            click(Offset(centerX, -(5.dp.toPx())))
        }
        assertEquals(listOf(true), seen)
    }

    // ---- the colours ----

    private fun lum(c: Color, ground: Color) = c.compositeOver(ground).luminance()

    /**
     * The control as drawn is its track with the knob over it. Measured on the ground it sits on:
     * how far the track stands off that ground is how loudly the control speaks.
     */
    private fun loudness(enabled: Boolean, ground: Color): Float {
        val track = toggleTrackColor(enabled).compositeOver(ground)
        return kotlin.math.abs(track.luminance() - ground.luminance())
    }

    @Test
    fun `a disabled toggle is dimmer than a live one on black`() {
        val black = Color.Black
        assertTrue(lum(toggleTrackColor(false), black) < lum(toggleTrackColor(true), black))
        assertTrue(loudness(false, black) < loudness(true, black))
        // And clearly so, not by a rounding error.
        assertTrue(lum(toggleTrackColor(false), black) < 0.6f * lum(toggleTrackColor(true), black))
    }

    @Test
    fun `a disabled toggle is also quieter than a live one on the light card`() {
        val card = Color(0xFFF4F4F5)
        assertTrue(loudness(false, card) < loudness(true, card))
    }

    @Test
    fun `a disabled knob still stands off its disabled track`() {
        for (ground in listOf(Color.Black, Color(0xFFF4F4F5))) {
            val track = toggleTrackColor(false).compositeOver(ground)
            val knob = toggleKnobColor(false).compositeOver(track)
            assertTrue("knob must differ from track on $ground", knob.luminance() < track.luminance())
        }
    }

    // ---- the motion ----

    private fun peak(spec: androidx.compose.animation.core.AnimationSpec<Float>): Float {
        val converter: TwoWayConverter<Float, androidx.compose.animation.core.AnimationVector1D> =
            Float.VectorConverter
        val vec = spec.vectorize(converter)
        val from = converter.convertToVector(0f)
        val to = converter.convertToVector(1f)
        val zero = converter.convertToVector(0f)
        var max = 0f
        var ms = 0L
        while (ms <= 1000L) {
            val v = vec.getValueFromNanos(ms * 1_000_000L, from, to, zero).value
            if (v > max) max = v
            ms += 4
        }
        return max
    }

    @Test
    fun `the knob arrives and does not overshoot`() {
        assertTrue("glide must not pass its target", peak(ToggleGlideSpec) <= 1.0001f)
        assertEquals(1f, peak(ToggleGlideSpec), 0.0001f)
    }

    /** Proves the check above can fail: the spring the toggle used to use does overshoot. */
    @Test
    fun `the spring it replaced would have overshot`() {
        val old = spring<Float>(stiffness = 900f, dampingRatio = 0.72f)
        assertTrue(peak(old) > 1.02f)
    }
}
