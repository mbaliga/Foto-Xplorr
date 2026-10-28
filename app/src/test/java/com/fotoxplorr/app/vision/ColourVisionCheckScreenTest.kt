package com.fotoxplorr.app.vision

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.ui.FotoXplorrTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The check on screen: from the introduction through seven pictures to a result, Back, and a recreation. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h2400dp-xhdpi")
class ColourVisionCheckScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private class BackOwner : OnBackPressedDispatcherOwner {
        private val registry = LifecycleRegistry.createUnsafe(this).also { it.currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
        override val onBackPressedDispatcher = OnBackPressedDispatcher()
    }

    private val owner = BackOwner()
    private val seed = 99L
    private val plates = ColourCheck.plates(seed)
    private var closed = 0

    private fun content() {
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides owner) {
                    ColourVisionCheckScreen(seed, onClose = { closed++ })
                }
            }
        }
    }

    /** The pictures are made off the main thread; a test waits for them before it taps a digit. */
    private fun waitForPlates() = compose.waitUntil(15_000) {
        compose.onAllNodesWithText(VisionCopy.CHECK_WORKING).fetchSemanticsNodes().isEmpty()
    }

    private fun start() {
        compose.onNodeWithText(VisionCopy.CHECK_START).performClick()
        waitForPlates()
        compose.waitForIdle()
    }

    private fun digit(d: Int) = compose.onNodeWithContentDescription(VisionCopy.digitDescription(d)).performClick()

    private fun nothing() = compose.onNodeWithText(VisionCopy.PLATE_NOTHING).performClick()

    private fun pressBack() = compose.runOnUiThread { owner.onBackPressedDispatcher.onBackPressed() }

    private fun answerAll(answers: List<Int?>) {
        answers.forEach { if (it == null) nothing() else digit(it) }
    }

    @Test
    fun `it opens on the introduction that says it is a game`() {
        content()
        compose.onNodeWithText(VisionCopy.CHECK_TITLE).assertExists()
        compose.onNodeWithText(VisionCopy.CHECK_INTRO).assertExists()
        compose.onNodeWithText(VisionCopy.CHECK_SETUP).assertExists()
        compose.onNodeWithText(VisionCopy.PLATE_DISCLAIMER).assertExists()
        compose.onNodeWithText(VisionCopy.CHECK_START).assertExists()
        assertFalse("the host closes from here", owner.onBackPressedDispatcher.hasEnabledCallbacks())
    }

    @Test
    fun `starting shows the first picture with its question and every answer`() {
        content()
        start()
        compose.waitForIdle()
        compose.onNodeWithText(VisionCopy.plateProgress(1, 7)).assertExists()
        compose.onNodeWithText(VisionCopy.PLATE_QUESTION).assertExists()
        compose.onNodeWithContentDescription(VisionCopy.PLATE_DESCRIPTION).assertExists()
        for (d in 0..9) compose.onNodeWithContentDescription(VisionCopy.digitDescription(d)).assertExists()
        compose.onNodeWithText(VisionCopy.PLATE_NOTHING).assertExists()
    }

    @Test
    fun `the picture's description does not give the number away`() {
        content()
        start()
        compose.waitForIdle()
        val description = VisionCopy.PLATE_DESCRIPTION
        for (d in 0..9) assertFalse(description.contains("$d"))
    }

    @Test
    fun `every answer is a 48 dp target`() {
        content()
        start()
        compose.waitForIdle()
        for (d in 0..9) {
            compose.onNodeWithContentDescription(VisionCopy.digitDescription(d)).assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        }
        compose.onNodeWithText(VisionCopy.PLATE_NOTHING).assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `seeing every number reads as typical, with the not-a-diagnosis line`() {
        content()
        start()
        compose.waitForIdle()
        answerAll(plates.map { it.digit })
        compose.waitForIdle()
        compose.onNodeWithText(VisionCopy.RESULT_TYPICAL).assertExists()
        compose.onNodeWithText(VisionCopy.PLATE_DISCLAIMER).assertExists()
        compose.onNodeWithText(VisionCopy.RESULT_DOCTOR).assertExists()
        compose.onNodeWithText(VisionCopy.CHECK_AGAIN).assertExists()
    }

    @Test
    fun `missing the red-green pictures reads as a maybe and never names a condition`() {
        content()
        start()
        compose.waitForIdle()
        answerAll(plates.map<Plate, Int?> { if (!it.control && it.axis != Deficiency.TRITAN) null else it.digit })
        compose.waitForIdle()
        compose.onNodeWithText(VisionCopy.RESULT_RED_GREEN).assertExists()
        compose.onNodeWithText(VisionCopy.PLATE_DISCLAIMER).assertExists()
        compose.onNodeWithText(VisionCopy.RESULT_VIEWER).assertExists()
    }

    @Test
    fun `missing the control says to check the screen`() {
        content()
        start()
        compose.waitForIdle()
        answerAll(plates.map { null })
        compose.waitForIdle()
        compose.onNodeWithText(VisionCopy.PLATE_CHECK_SCREEN).assertExists()
        compose.onNodeWithText(VisionCopy.RESULT_RED_GREEN).assertDoesNotExist()
    }

    @Test
    fun `try again goes back to the introduction`() {
        content()
        start()
        compose.waitForIdle()
        answerAll(plates.map { it.digit })
        compose.waitForIdle()
        compose.onNodeWithText(VisionCopy.CHECK_AGAIN).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(VisionCopy.CHECK_START).assertExists()
    }

    @Test
    fun `close on the result closes the experiment`() {
        content()
        start()
        compose.waitForIdle()
        answerAll(plates.map { it.digit })
        compose.waitForIdle()
        compose.onNodeWithText(VisionCopy.CLOSE).performClick()
        assertEquals(1, closed)
    }

    @Test
    fun `Back steps one picture and the host's close only comes at the introduction`() {
        content()
        start()
        compose.waitForIdle()
        digit(plates[0].digit)
        compose.waitForIdle()
        compose.onNodeWithText(VisionCopy.plateProgress(2, 7)).assertExists()
        assertTrue(owner.onBackPressedDispatcher.hasEnabledCallbacks())
        pressBack()
        compose.waitForIdle()
        compose.onNodeWithText(VisionCopy.plateProgress(1, 7)).assertExists()
        pressBack()
        compose.waitForIdle()
        compose.onNodeWithText(VisionCopy.CHECK_START).assertExists()
        assertFalse("nothing of its own left, so the host closes", owner.onBackPressedDispatcher.hasEnabledCallbacks())
        assertEquals("the screen itself never closes on Back", 0, closed)
    }

    @Test
    fun `the arrow in the header steps back and then closes`() {
        content()
        start()
        compose.waitForIdle()
        compose.onNodeWithContentDescription(VisionCopy.BACK).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(VisionCopy.CHECK_START).assertExists()
        assertEquals(0, closed)
        compose.onNodeWithContentDescription(VisionCopy.BACK).performClick()
        assertEquals(1, closed)
    }

    @Test
    fun `the step and the answers survive the activity being recreated`() {
        val tester = StateRestorationTester(compose)
        tester.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides owner) {
                    ColourVisionCheckScreen(seed, onClose = {})
                }
            }
        }
        start()
        compose.waitForIdle()
        digit(plates[0].digit)
        compose.waitForIdle()
        digit(plates[1].digit)
        compose.waitForIdle()
        compose.onNodeWithText(VisionCopy.plateProgress(3, 7)).assertExists()

        tester.emulateSavedInstanceStateRestore()
        waitForPlates()
        compose.waitForIdle()

        compose.onNodeWithText(VisionCopy.plateProgress(3, 7)).assertExists()
        // And the answers came with it: finishing correctly still reads as typical.
        answerAll(plates.drop(2).map { it.digit })
        compose.waitForIdle()
        compose.onNodeWithText(VisionCopy.RESULT_TYPICAL).assertExists()
    }
}
