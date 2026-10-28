package com.fotoxplorr.app.vision

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.ui.FotoXplorrTheme
import com.fotoxplorr.app.viewer.ViewerSettingsRoom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The "Colour vision" group inside the viewer's real view-options room: the choice rows, the strength
 * stepper (the non-drag way), the toggles and what they say when they cannot be used. Clicks are
 * injected; how the group scrolls and feels on a phone is a device check.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h4000dp-xhdpi")
class ColourVisionRoomTest {

    @get:Rule
    val compose = createComposeRule()

    private val viewer = ColourVisionViewer(ColourVisionChoice())

    private fun show(
        asset: MediaAsset = ColourVisionKit.asset(),
        support: VisionSupport = VisionSupport.FULL,
        sensitive: Boolean = false,
        canSave: Boolean = true,
        withGroup: Boolean = true,
    ) {
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                ViewerSettingsRoom(
                    slideshowIntervalSeconds = 4,
                    blurSensitive = true,
                    showFilmstrip = true,
                    keepScreenOn = false,
                    slideshowShuffle = false,
                    loopAnimations = false,
                    autoplayVideos = true,
                    onSetSlideshowInterval = {},
                    onSetBlurSensitive = {},
                    onSetShowFilmstrip = {},
                    onSetKeepScreenOn = {},
                    onSetSlideshowShuffle = {},
                    onSetLoopAnimations = {},
                    onSetAutoplayVideos = {},
                    colourVisionSection = if (withGroup) {
                        { ColourVisionRoomSection(viewer, asset, support, sensitive, canSave) { true } }
                    } else {
                        null
                    },
                )
            }
        }
    }

    @Test
    fun `without the group the room is as it was`() {
        show(withGroup = false)
        compose.onNodeWithText(VisionCopy.ROOM_EYEBROW).assertDoesNotExist()
        compose.onNodeWithText("Filmstrip").assertExists()
    }

    @Test
    fun `the group sits in the room and starts with Off selected`() {
        show()
        compose.onNodeWithText(VisionCopy.ROOM_EYEBROW).assertExists()
        compose.onNodeWithTag("colour-vision-off").assertIsSelected()
        for (d in Deficiency.entries) compose.onNodeWithTag("colour-vision-$d").assertIsNotSelected()
        // Nothing else to set until a type is picked.
        compose.onNodeWithText(VisionCopy.KIND_LABEL).assertDoesNotExist()
        compose.onNodeWithTag("colour-vision-strength").assertDoesNotExist()
    }

    @Test
    fun `picking a type selects it by state and brings up the rest`() {
        show()
        compose.onNodeWithText(VisionCopy.DEUTAN).performClick()
        compose.waitForIdle()
        assertEquals(Deficiency.DEUTAN, viewer.choice.deficiency)
        compose.onNodeWithTag("colour-vision-DEUTAN").assertIsSelected()
        compose.onNodeWithTag("colour-vision-off").assertIsNotSelected()
        compose.onNodeWithTag("colour-vision-simulate").assertIsSelected()
        compose.onNodeWithTag("colour-vision-help").assertIsNotSelected()
        compose.onNodeWithTag("colour-vision-strength").assertExists()
    }

    @Test
    fun `Off turns it off again`() {
        show()
        compose.onNodeWithText(VisionCopy.PROTAN).performClick()
        compose.onNodeWithText(VisionCopy.OFF).performClick()
        compose.waitForIdle()
        assertNull(viewer.choice.deficiency)
        compose.onNodeWithTag("colour-vision-off").assertIsSelected()
    }

    @Test
    fun `help me see shows its note and simulate does not`() {
        show()
        compose.onNodeWithText(VisionCopy.TRITAN).performClick()
        compose.onNodeWithText(VisionCopy.DALTONIZE_NOTE).assertDoesNotExist()
        compose.onNodeWithText(VisionCopy.DALTONIZE).performClick()
        compose.waitForIdle()
        assertEquals(VisionKind.HELP, viewer.choice.kind)
        compose.onNodeWithTag("colour-vision-help").assertIsSelected()
        compose.onNodeWithText(VisionCopy.DALTONIZE_NOTE).assertExists()
        compose.onNodeWithText(VisionCopy.SIMULATE).performClick()
        compose.waitForIdle()
        assertEquals(VisionKind.SIMULATE, viewer.choice.kind)
        compose.onNodeWithText(VisionCopy.DALTONIZE_NOTE).assertDoesNotExist()
    }

    @Test
    fun `the stepper changes the strength a tenth at a time with no dragging`() {
        show()
        compose.onNodeWithText(VisionCopy.DEUTAN).performClick()
        compose.onNodeWithContentDescription("How strong, 100%").assertExists()
        compose.onNodeWithContentDescription("More, How strong").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Fewer, How strong").performClick()
        compose.onNodeWithContentDescription("Fewer, How strong").performClick()
        compose.waitForIdle()
        assertEquals(8, viewer.choice.severityStep)
        compose.onNodeWithContentDescription("How strong, 80%").assertExists()
        compose.onNodeWithContentDescription("More, How strong").assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(9, viewer.choice.severityStep)
    }

    @Test
    fun `the stepper stops at ten percent`() {
        show()
        compose.onNodeWithText(VisionCopy.DEUTAN).performClick()
        repeat(12) { compose.onNodeWithContentDescription("Fewer, How strong").performClickIfEnabled() }
        compose.waitForIdle()
        assertEquals(1, viewer.choice.severityStep)
        compose.onNodeWithContentDescription("Fewer, How strong").assertIsNotEnabled()
    }

    @Test
    fun `the slider has a spoken name and value`() {
        show()
        compose.onNodeWithText(VisionCopy.PROTAN).performClick()
        compose.waitForIdle()
        val node = compose.onNodeWithTag("colour-vision-strength").fetchSemanticsNode()
        val description = node.config[SemanticsProperties.ContentDescription]
        assertTrue(description.contains(VisionCopy.SEVERITY_SLIDER))
        assertEquals("100%", node.config[SemanticsProperties.StateDescription])
    }

    @Test
    fun `every choice row is at least 48 dp tall`() {
        show()
        compose.onNodeWithText(VisionCopy.PROTAN).performClick()
        for (tag in listOf("colour-vision-off", "colour-vision-PROTAN", "colour-vision-DEUTAN", "colour-vision-TRITAN", "colour-vision-simulate", "colour-vision-help")) {
            compose.onNodeWithTag(tag).assertHeightIsAtLeast(48.dp)
        }
    }

    @Test
    fun `names can be switched on from the row`() {
        show()
        compose.onNodeWithText(VisionCopy.NAME_COLOURS).performClick()
        compose.waitForIdle()
        assertTrue(viewer.choice.naming)
        compose.onNodeWithText(VisionCopy.NAME_A_COLOUR).assertExists()
        compose.onNodeWithText(VisionCopy.NAME_COLOURS).performClick()
        compose.waitForIdle()
        assertFalse(viewer.choice.naming)
    }

    @Test
    fun `patterns need a type first and say so`() {
        show()
        compose.onAllNodesWithText(VisionCopy.PATTERNS_NEED_TYPE).assertCountEquals(2)
        compose.onNodeWithText(VisionCopy.PATTERNS_LABEL).performClick()
        compose.waitForIdle()
        assertFalse("a disabled row does nothing", viewer.choice.patterns)
    }

    @Test
    fun `patterns switch on once a type is chosen`() {
        show()
        compose.onNodeWithText(VisionCopy.DEUTAN).performClick()
        compose.onNodeWithText(VisionCopy.PATTERNS).assertExists()
        compose.onNodeWithText(VisionCopy.PATTERNS_LABEL).performClick()
        compose.waitForIdle()
        assertTrue(viewer.choice.hatching)
    }

    @Test
    fun `patterns say when nothing looks alike`() {
        show()
        compose.onNodeWithText(VisionCopy.DEUTAN).performClick()
        compose.onNodeWithText(VisionCopy.PATTERNS_LABEL).performClick()
        compose.runOnIdle { viewer.putHatch(HatchState.NoneAlike) }
        compose.onNodeWithText(VisionCopy.NO_CONFUSION).assertExists()
        compose.runOnIdle { viewer.putHatch(HatchState.Working) }
        compose.onNodeWithText(VisionCopy.WORKING).assertExists()
    }

    @Test
    fun `saving a copy needs a type, then is open`() {
        show()
        compose.onNodeWithText(VisionCopy.SAVE_SIMULATED).assertExists()
        compose.onAllNodesWithText(VisionCopy.SAVE_NEED_TYPE).assertCountEquals(2) // patterns and save say the same
        compose.onNodeWithText(VisionCopy.PROTAN).performClick()
        compose.waitForIdle()
        // Open as far as this group goes; with no job list in a test it says why it cannot run.
        compose.onNodeWithText(VisionCopy.SAVE_NO_JOBS).assertExists()
    }

    @Test
    fun `the save row is named for what it saves`() {
        show()
        compose.onNodeWithText(VisionCopy.DEUTAN).performClick()
        compose.onNodeWithText(VisionCopy.SAVE_SIMULATED).assertExists()
        compose.onNodeWithText(VisionCopy.DALTONIZE).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(VisionCopy.SAVE_HELP).assertExists()
    }

    @Test
    fun `a sensitive photo says it is not copied`() {
        show(sensitive = true)
        compose.onNodeWithText(VisionCopy.DEUTAN).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(VisionCopy.SAVE_SENSITIVE).assertExists()
    }

    @Test
    fun `a file whose pixels cannot be read gets the view only and says so`() {
        show(support = VisionSupport.VIEW_ONLY)
        compose.onNodeWithText(VisionCopy.DEUTAN).performClick()
        compose.waitForIdle()
        // Said under the kind, the names, the patterns and the save row.
        compose.onAllNodesWithText(VisionCopy.FILTER_ONLY).assertCountEquals(4)
        compose.onNodeWithText(VisionCopy.NAME_COLOURS).performClick()
        compose.waitForIdle()
        assertFalse("names need the pixels", viewer.choice.naming)
    }

    @Test
    fun `a photo that could not be read says it is shown as it is`() {
        val asset = ColourVisionKit.asset()
        viewer.update { it.withDeficiency(Deficiency.DEUTAN) }
        viewer.markUnreadable(PhotoKey(asset))
        show(asset = asset)
        compose.onNodeWithText(VisionCopy.UNREADABLE).assertExists()
    }

    @Test
    fun `a readable photo does not say that`() {
        viewer.update { it.withDeficiency(Deficiency.DEUTAN) }
        show()
        compose.onNodeWithText(VisionCopy.UNREADABLE).assertDoesNotExist()
    }

    @Test
    fun `with names on the main colours are listed as text, the way in that needs no touch on the photo`() {
        val names = listOf("dark green", "sand", "grey")
        viewer.update { it.withNaming(true) }
        viewer.putOriginal(PhotoKey(ColourVisionKit.asset()), ColourVisionKit.bitmap(4, 4) { _, _ -> 0xFF808080.toInt() })
        viewer.putMainColours(names)
        show()
        compose.onNodeWithText(VisionCopy.mainColours(names)).assertExists()
    }

    @Test
    fun `with names off the main colours are not listed`() {
        val names = listOf("sand")
        viewer.putMainColours(names)
        show()
        compose.onNodeWithText(VisionCopy.mainColours(names)).assertDoesNotExist()
    }

    @Test
    fun `for a video the group is one sentence and no controls`() {
        show(asset = ColourVisionKit.asset(mime = "video/mp4", name = "v.mp4"), support = VisionSupport.NONE)
        compose.onNodeWithText(VisionCopy.ROOM_EYEBROW).assertExists()
        compose.onNodeWithText(VisionCopy.NOT_FOR_VIDEO).assertExists()
        compose.onNodeWithTag("colour-vision-off").assertDoesNotExist()
    }
}
