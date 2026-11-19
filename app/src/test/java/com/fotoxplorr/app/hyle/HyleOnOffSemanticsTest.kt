package com.fotoxplorr.app.hyle

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.ui.FotoXplorrTheme
import com.fotoxplorr.app.ui.RoomToggle
import dev.aarso.hyle.component.HyleOnOffToggle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class HyleOnOffSemanticsTest {

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
        onBlack { HyleOnOffToggle(checked = true, onCheckedChange = null, description = "Picture only") }
        val node = compose.onNodeWithContentDescription("Picture only").fetchSemanticsNode()
        assertFalse(SemanticsProperties.Role in node.config)
        assertFalse(SemanticsActions.OnClick in node.config)
        assertFalse(SemanticsProperties.ToggleableState in node.config)
    }

    @Test
    fun `the touch target is the shared 52dp control height`() {
        onBlack { RoomToggle(checked = false, onCheckedChange = {}) }
        compose.onNode(isToggleable()).assertTouchHeightIsEqualTo(52.dp)
    }
}
