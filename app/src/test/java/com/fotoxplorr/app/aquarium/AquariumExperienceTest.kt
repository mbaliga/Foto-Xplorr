package com.fotoxplorr.app.aquarium

import android.content.Context
import android.provider.Settings
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.ui.FotoXplorrTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The two steps on screen, on real files. Proves that the flow composes, that Back belongs to this
 * entry only while the board is open, that a creature drawn from a ready shape is saved and shows,
 * that removing one deletes its files, and that the draft comes back after a recreation. It does
 * NOT prove how anything looks or that a finger stroke feels right: those are device checks.
 *
 * The animator scale is set to 0 so the tank opens paused: an endless frame loop would stop the
 * test clock from ever being idle. The loop itself is tested on [AquariumScreen] below.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h900dp-xhdpi")
class AquariumExperienceTest {

    @get:Rule
    val compose = createComposeRule()

    private class BackOwner : OnBackPressedDispatcherOwner {
        private val registry = LifecycleRegistry.createUnsafe(this).also { it.currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
        override val onBackPressedDispatcher = OnBackPressedDispatcher()
    }

    private val owner = BackOwner()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun animationsOff() {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        File(context.filesDir, CreatureStore.DIRECTORY_NAME).deleteRecursively()
    }

    @androidx.compose.runtime.Composable
    private fun Host(onClose: () -> Unit = {}) {
        FotoXplorrTheme(GalleryPreferencesState()) {
            CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides owner) {
                AquariumExperience(onClose = onClose)
            }
        }
    }

    private fun pressBack() {
        compose.runOnUiThread { owner.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun waitForDescription(text: String) =
        compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription(text)).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForNoDescription(text: String) =
        compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription(text)).fetchSemanticsNodes().isEmpty() }

    private fun waitForText(text: String) =
        compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    /** From the empty tank: Draw, a ready shape, Swim; ends in the tank with one creature. */
    private fun makeOne() {
        waitForText(AquariumCopy.EMPTY_PROMPT)
        compose.onNodeWithText(AquariumCopy.DRAW).performClick()
        compose.onNodeWithText(AquariumCopy.SHAPE_ROUND_FISH).performClick()
        compose.onNodeWithText(AquariumCopy.SWIM).performClick()
        waitForDescription(AquariumCopy.tank(1))
    }

    @Test
    fun `an empty tank says to draw and offers the Draw button`() {
        compose.setContent { Host() }
        waitForText(AquariumCopy.EMPTY_PROMPT)
        compose.onNodeWithText(AquariumCopy.DRAW).assertExists()
        compose.onNodeWithContentDescription(AquariumCopy.tank(0)).assertExists()
    }

    @Test
    fun `it opens paused when animations are off and says so, with a Play button`() {
        compose.setContent { Host() }
        waitForText(AquariumCopy.PAUSED)
        compose.onNodeWithContentDescription(AquariumCopy.PLAY).assertExists()
    }

    @Test
    fun `Back belongs to the host in the tank and to this entry while drawing`() {
        compose.setContent { Host() }
        waitForText(AquariumCopy.EMPTY_PROMPT)
        assertFalse("the tank leaves Back to the host", owner.onBackPressedDispatcher.hasEnabledCallbacks())
        compose.onNodeWithText(AquariumCopy.DRAW).performClick()
        compose.onNodeWithText(AquariumCopy.DRAW_TITLE).assertExists()
        assertTrue("the board handles its own Back", owner.onBackPressedDispatcher.hasEnabledCallbacks())
        pressBack()
        compose.onNodeWithText(AquariumCopy.EMPTY_PROMPT).assertExists()
        assertFalse(owner.onBackPressedDispatcher.hasEnabledCallbacks())
    }

    @Test
    fun `the back arrow on the board returns to the tank`() {
        compose.setContent { Host() }
        waitForText(AquariumCopy.EMPTY_PROMPT)
        compose.onNodeWithText(AquariumCopy.DRAW).performClick()
        compose.onNodeWithContentDescription(AquariumCopy.BACK).performClick()
        compose.onNodeWithText(AquariumCopy.EMPTY_PROMPT).assertExists()
    }

    @Test
    fun `Close calls the host's close`() {
        var closed = false
        compose.setContent { Host(onClose = { closed = true }) }
        waitForText(AquariumCopy.EMPTY_PROMPT)
        compose.onNodeWithContentDescription(AquariumCopy.CLOSE).performClick()
        assertTrue(closed)
    }

    @Test
    fun `Swim waits for something on the board`() {
        compose.setContent { Host() }
        waitForText(AquariumCopy.EMPTY_PROMPT)
        compose.onNodeWithText(AquariumCopy.DRAW).performClick()
        compose.onNodeWithText(AquariumCopy.SWIM).assertIsNotEnabled()
        compose.onNodeWithContentDescription(AquariumCopy.board(0)).assertExists()
        compose.onNodeWithText(AquariumCopy.BOARD_HINT).assertExists()
    }

    @Test
    fun `a ready shape fills the board and Swim puts a creature in the tank`() {
        compose.setContent { Host() }
        makeOne()
        compose.onNodeWithContentDescription(AquariumCopy.tank(1)).assertExists()
        // The files are there: one picture and one record.
        val names = File(context.filesDir, CreatureStore.DIRECTORY_NAME).list()!!.toList()
        assertEquals(2, names.size)
        assertTrue(names.any { it.endsWith(".png") } && names.any { it.endsWith(".json") })
    }

    @Test
    fun `the colour and brush choices are marked and named`() {
        compose.setContent { Host() }
        waitForText(AquariumCopy.EMPTY_PROMPT)
        compose.onNodeWithText(AquariumCopy.DRAW).performClick()
        for (s in SketchPalette.swatches) compose.onNodeWithContentDescription(s.name).assertExists()
        compose.onNodeWithContentDescription(AquariumCopy.COLOR_BLUE).performClick()
        compose.onNodeWithContentDescription(AquariumCopy.COLOR_BLUE).assertIsSelected()
        compose.onNodeWithContentDescription(AquariumCopy.COLOR_ORANGE).assertIsNotSelected()
        compose.onNodeWithText(AquariumCopy.BRUSH_THICK).performClick()
    }

    @Test
    fun `removing a creature deletes its files and shows the empty prompt again`() {
        compose.setContent { Host() }
        makeOne()
        compose.onNodeWithText(AquariumCopy.CREATURES).performClick()
        compose.onNodeWithContentDescription(AquariumCopy.remove(1)).performClick()
        waitForDescription(AquariumCopy.tank(0))
        waitForText(AquariumCopy.EMPTY_PROMPT)
        compose.waitUntil(10_000) { File(context.filesDir, CreatureStore.DIRECTORY_NAME).list()!!.isEmpty() }
    }

    @Test
    fun `creatures saved earlier are in the tank when it is opened again`() {
        val tester = StateRestorationTester(compose)
        tester.setContent { Host() }
        makeOne()
        // The recreation builds the screen again from nothing but the files.
        tester.emulateSavedInstanceStateRestore()
        waitForDescription(AquariumCopy.tank(1))
        compose.onNodeWithContentDescription(AquariumCopy.tank(1)).assertExists()
    }

    @Test
    fun `a damaged pair in the folder is ignored, not shown and not fatal`() {
        val dir = File(context.filesDir, CreatureStore.DIRECTORY_NAME).also { it.mkdirs() }
        val id = CreatureId.generate()
        File(dir, "$id.png").writeBytes(byteArrayOf(1, 2, 3))
        File(dir, "$id.json").writeText(CreatureCodec.encode(StoredCreature(id, 1L, 20, 10, false)))
        compose.setContent { Host() }
        waitForText(AquariumCopy.EMPTY_PROMPT)
        compose.onNodeWithContentDescription(AquariumCopy.tank(0)).assertExists()
        // Not shown, and not left behind to count toward the cap either.
        compose.waitUntil(10_000) { !File(dir, "$id.json").exists() }
        assertFalse(File(dir, "$id.png").exists())
    }

    @Test
    fun `the drawing comes back after the activity is recreated`() {
        val tester = StateRestorationTester(compose)
        tester.setContent { Host() }
        waitForText(AquariumCopy.EMPTY_PROMPT)
        compose.onNodeWithText(AquariumCopy.DRAW).performClick()
        compose.onNodeWithText(AquariumCopy.SHAPE_ROUND_FISH).performClick()
        compose.onNodeWithContentDescription(AquariumCopy.board(3)).assertExists()

        tester.emulateSavedInstanceStateRestore()

        compose.onNodeWithText(AquariumCopy.DRAW_TITLE).assertExists()
        compose.onNodeWithContentDescription(AquariumCopy.board(3)).assertExists()
    }

    @Test
    fun `start over asks first and then clears`() {
        compose.setContent { Host() }
        waitForText(AquariumCopy.EMPTY_PROMPT)
        compose.onNodeWithText(AquariumCopy.DRAW).performClick()
        compose.onNodeWithText(AquariumCopy.SHAPE_ROUND_FISH).performClick()
        compose.onNodeWithContentDescription(AquariumCopy.CLEAR).performClick()
        compose.onNodeWithText(AquariumCopy.CLEAR_QUESTION).assertExists()
        compose.onNodeWithText(AquariumCopy.CLEAR_KEEP).performClick()
        compose.onNodeWithContentDescription(AquariumCopy.board(3)).assertExists()
        compose.onNodeWithContentDescription(AquariumCopy.CLEAR).performClick()
        compose.onNodeWithText(AquariumCopy.CLEAR).performClick()
        compose.onNodeWithContentDescription(AquariumCopy.board(0)).assertExists()
    }

    @Test
    fun `a finger stroke on the board adds a stroke, and a dot adds one more`() {
        compose.setContent { Host() }
        waitForText(AquariumCopy.EMPTY_PROMPT)
        compose.onNodeWithText(AquariumCopy.DRAW).performClick()
        compose.onNodeWithContentDescription(AquariumCopy.board(0)).performTouchInput {
            swipe(Offset(width * 0.2f, height * 0.5f), Offset(width * 0.8f, height * 0.6f))
        }
        compose.onNodeWithContentDescription(AquariumCopy.board(1)).assertExists()
        compose.onNodeWithContentDescription(AquariumCopy.board(1)).performTouchInput { click(Offset(width * 0.5f, height * 0.3f)) }
        compose.onNodeWithContentDescription(AquariumCopy.board(2)).assertExists()
        compose.onNodeWithText(AquariumCopy.SWIM).assertIsEnabled()
    }

    @Test
    fun `undo and redo are buttons with names`() {
        compose.setContent { Host() }
        waitForText(AquariumCopy.EMPTY_PROMPT)
        compose.onNodeWithText(AquariumCopy.DRAW).performClick()
        compose.onNodeWithText(AquariumCopy.SHAPE_ROUND_FISH).performClick()
        compose.onNodeWithContentDescription(AquariumCopy.UNDO).performClick()
        compose.onNodeWithContentDescription(AquariumCopy.board(2)).assertExists()
        compose.onNodeWithContentDescription(AquariumCopy.REDO).performClick()
        compose.onNodeWithContentDescription(AquariumCopy.board(3)).assertExists()
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h900dp-xhdpi")
class AquariumFrameLoopTest {

    @get:Rule
    val compose = createComposeRule()

    private fun record(id: String = CreatureId.generate()) = StoredCreature(id, 1L, 100, 50, false)

    @Test
    fun `the loop steps the tank while it is resumed and not while paused`() {
        compose.mainClock.autoAdvance = false
        val sim = TankSimulation(seed = 1)
        val rec = record()
        sim.addCreature(rec.id, rec.params(), x = 0.5f, y = 0.5f, z = 0.5f)
        var paused by androidx.compose.runtime.mutableStateOf(false)
        compose.setContent {
            AquariumScreen(
                sim = sim, sprites = emptyMap(), creatures = listOf(rec), loaded = true,
                paused = paused, onPausedChange = { paused = it }, onDraw = {}, onDelete = {}, onClose = {},
            )
        }
        val start = sim.creatures.single().x to sim.creatures.single().y
        compose.mainClock.advanceTimeBy(1_000)
        val running = sim.creatures.single().x to sim.creatures.single().y
        assertTrue("swam", running != start)

        compose.runOnUiThread { paused = true }
        compose.mainClock.advanceTimeBy(200)
        val held = sim.creatures.single().x to sim.creatures.single().y
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals("paused means still", held, sim.creatures.single().x to sim.creatures.single().y)
    }

    @Test
    fun `pressing Pause stops it and the button then says Play`() {
        compose.mainClock.autoAdvance = false
        val sim = TankSimulation(seed = 1)
        var paused by androidx.compose.runtime.mutableStateOf(false)
        val rec = record()
        sim.addCreature(rec.id, rec.params(), x = 0.5f, y = 0.5f, z = 0.5f)
        compose.setContent {
            AquariumScreen(
                sim = sim, sprites = emptyMap(), creatures = listOf(rec), loaded = true,
                paused = paused, onPausedChange = { paused = it }, onDraw = {}, onDelete = {}, onClose = {},
            )
        }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithContentDescription(AquariumCopy.PAUSE).performClick()
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithContentDescription(AquariumCopy.PLAY).assertExists()
        assertTrue(paused)
    }

    @Test
    fun `a tank with creatures offers feed, the list and draw another, each by name`() {
        val sim = TankSimulation(seed = 1)
        val rec = record()
        compose.setContent {
            AquariumScreen(
                sim = sim, sprites = emptyMap(), creatures = listOf(rec), loaded = true,
                paused = true, onPausedChange = {}, onDraw = {}, onDelete = {}, onClose = {},
            )
        }
        compose.onNodeWithText(AquariumCopy.CREATURES).performClick()
        compose.onNodeWithContentDescription(AquariumCopy.remove(1)).assertExists()
        compose.onNodeWithText(AquariumCopy.DRAW_ANOTHER).assertExists()
        compose.onNodeWithText(AquariumCopy.FEED).assertIsNotEnabled()
    }

    private fun tank(paused: Boolean, sim: TankSimulation) {
        val rec = record()
        sim.addCreature(rec.id, rec.params(), x = 0.5f, y = 0.5f, z = 0.5f)
        compose.setContent {
            AquariumScreen(
                sim = sim, sprites = emptyMap(), creatures = listOf(rec), loaded = true,
                paused = paused, onPausedChange = {}, onDraw = {}, onDelete = {}, onClose = {},
            )
        }
    }

    @Test
    fun `a tap on the water drops food`() {
        compose.mainClock.autoAdvance = false
        val sim = TankSimulation(seed = 1)
        tank(paused = false, sim = sim)
        compose.onNodeWithContentDescription(AquariumCopy.tank(1)).performTouchInput { click(Offset(width * 0.5f, height * 0.4f)) }
        assertEquals(1, sim.activeFoodCount)
    }

    @Test
    fun `a hold scares instead of feeding`() {
        compose.mainClock.autoAdvance = false
        val sim = TankSimulation(seed = 1)
        tank(paused = false, sim = sim)
        compose.onNodeWithContentDescription(AquariumCopy.tank(1)).performTouchInput { longClick(Offset(width * 0.5f, height * 0.4f)) }
        assertEquals(0, sim.activeFoodCount)
    }

    @Test
    fun `a touch does nothing while the tank is paused`() {
        val sim = TankSimulation(seed = 1)
        tank(paused = true, sim = sim)
        compose.waitForIdle()
        val before = sim.creatures.single().x
        compose.onNodeWithContentDescription(AquariumCopy.tank(1)).performTouchInput { click(Offset(width * 0.5f, height * 0.4f)) }
        assertEquals(0, sim.activeFoodCount)
        assertEquals(before, sim.creatures.single().x, 0f)
    }
}
