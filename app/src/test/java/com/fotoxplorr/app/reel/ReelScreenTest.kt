package com.fotoxplorr.app.reel

import android.net.Uri
import android.os.Looper
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.jobs.JobRunner
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.reel.ReelScreenKit.photo
import com.fotoxplorr.app.reel.ReelScreenKit.stills
import com.fotoxplorr.app.reel.ReelScreenKit.video
import com.fotoxplorr.app.ui.FotoXplorrTheme
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The reel screen on screen, over an in-memory library, fake export seams and library items that are not real
 * files: the three pages, the draft, the picker, every edit by button (reorder, remove, copy, time, move, words,
 * sound), autosave, what survives the activity being recreated, and making the video as a job with its refusals,
 * its question about sound and its Cancel. Clicks are injected; nothing here says how the preview looks or moves,
 * or how the strip feels under a thumb. Those are device checks.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h2400dp-xhdpi")
class ReelScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private class BackOwner : OnBackPressedDispatcherOwner {
        private val registry = LifecycleRegistry.createUnsafe(this).also { it.currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
        override val onBackPressedDispatcher = OnBackPressedDispatcher()
    }

    private val owner = BackOwner()
    // The job list runs on the main thread, as the app's does (its view model scope): a job then cannot finish
    // before the thread that started it has noted it, which is what the screen relies on.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val library = MemoryReelLibrary()
    private val sounds = FakeSoundFiles()
    private lateinit var work: File
    private lateinit var host: ScreenHost
    private val encoder = ScreenEncoder()
    private lateinit var sink: ScreenSink
    private var state: ReelScreenState? = null
    private val project: ReelProject? get() = state?.open?.project
    private var closed = false
    private val watched = mutableListOf<String>()
    private var canWatch = true

    /** What the system's file picker answers, when the screen launches it. */
    private var picked: Uri? = null

    private val a = photo(1)
    private val b = photo(2)
    private val c = photo(3)
    private val clip = video(4, 12_000L)
    private val gallery = listOf(clip, c, b, a)

    @Before
    fun setUp() {
        work = File.createTempFile("reel-screen-", "").apply { delete(); mkdirs() }
        sink = ScreenSink(work)
        host = ScreenHost(gallery.associateBy { it.id.value })
    }

    @After
    fun tearDown() {
        scope.cancel()
        work.deleteRecursively()
    }

    private val registryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                @Suppress("UNCHECKED_CAST")
                dispatchResult(requestCode, picked as O)
            }
        }
    }

    @Composable
    private fun Screen(
        assets: List<MediaAsset> = gallery,
        runner: JobRunner? = JobRunner(scope),
        animatorScale: Float = 0f,
    ) {
        FotoXplorrTheme(GalleryPreferencesState()) {
            CompositionLocalProvider(
                LocalOnBackPressedDispatcherOwner provides owner,
                LocalActivityResultRegistryOwner provides registryOwner,
            ) {
                ReelScreen(
                    assets = assets,
                    library = library,
                    newHost = { host },
                    newEncoder = { encoder },
                    newSink = { sink },
                    sounds = sounds,
                    runner = runner,
                    onWatch = { uri -> watched += uri; canWatch },
                    animatorScale = animatorScale,
                    onClose = { closed = true },
                    autosaveMillis = 50L,
                    zone = java.time.ZoneOffset.UTC,
                    onState = { state = it },
                )
            }
        }
    }

    private fun act(node: SemanticsNodeInteraction): SemanticsNodeInteraction {
        runCatching { node.performScrollTo() }
        node.performClick()
        compose.waitForIdle()
        return node
    }

    // The saved-reels list is read off the main thread, so a row can appear a moment after the screen does: wait for
    // the text before pressing it, or a busy machine finds nothing there yet.
    private fun tapText(text: String): SemanticsNodeInteraction {
        waitFor { textShown(text) }
        return act(compose.onNodeWithText(text))
    }

    private fun press(description: String) = act(compose.onNodeWithContentDescription(description))

    private fun pressBack() = compose.runOnUiThread { owner.onBackPressedDispatcher.onBackPressed() }

    private fun waitFor(timeoutMillis: Long = 60_000, condition: () -> Boolean) =
        compose.waitUntil(timeoutMillis) { shadowOf(Looper.getMainLooper()).idle(); condition() }

    private fun textShown(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun descriptionShown(text: String) = compose.onAllNodes(hasContentDescription(text)).fetchSemanticsNodes().isNotEmpty()

    /** Opens a saved reel of [p] from the list and waits for the editor. */
    private fun openSaved(p: ReelProject, name: String = "") {
        library.put(p.copy(name = name))
        compose.setContent { Screen() }
        tapText(name.ifBlank { ReelCopy.DEFAULT_NAME })
        waitFor { project != null }
    }

    private fun newBlank() {
        compose.setContent { Screen() }
        tapText(ReelCopy.START_BLANK)
    }

    private fun strip(number: Int, kind: String, seconds: String) = "$kind $number, $seconds"

    private fun ids() = project!!.items.map { it.id }

    // ---- the pages ----

    @Test
    fun `it opens on the list, and Start with nothing opens an empty editor that writes nothing`() {
        compose.setContent { Screen() }
        compose.onNodeWithText(ReelCopy.START_DRAFT).assertIsEnabled()
        compose.onNodeWithText(ReelCopy.START_BLANK).assertIsEnabled()
        compose.onNodeWithText(ReelCopy.NO_REELS).assertExists()
        compose.onNodeWithText(ReelCopy.SAVES_BY_ITSELF).assertExists()
        tapText(ReelCopy.START_BLANK)
        compose.onNodeWithText(ReelCopy.EMPTY_REEL).assertExists()
        assertEquals(0, project!!.items.size)
        pressBack()
        compose.onNodeWithText(ReelCopy.START_BLANK).assertExists()
        Thread.sleep(200)
        assertTrue("an empty reel is never written", library.saves.isEmpty())
    }

    @Test
    fun `Back on the list is left to the host, and the screen takes none of its own`() {
        compose.setContent { Screen() }
        compose.waitForIdle()
        assertFalse(owner.onBackPressedDispatcher.hasEnabledCallbacks())
        tapText(ReelCopy.START_BLANK)
        assertTrue(owner.onBackPressedDispatcher.hasEnabledCallbacks())
        press(ReelCopy.BACK)
        compose.onNodeWithText(ReelCopy.START_BLANK).assertExists()
        press(ReelCopy.BACK)
        assertTrue("the header's Back on the list closes the experiment", closed)
    }

    @Test
    fun `Back closes a panel first and then goes back to the list`() {
        newBlank()
        tapText(ReelCopy.TOOL_ADD)
        compose.onNodeWithText(ReelCopy.ADD_CARD).assertExists()
        pressBack()
        compose.onNodeWithText(ReelCopy.ADD_CARD).assertDoesNotExist()
        compose.onNodeWithText(ReelCopy.EMPTY_REEL).assertExists()
        pressBack()
        compose.onNodeWithText(ReelCopy.START_BLANK).assertExists()
    }

    @Test
    fun `saved reels are listed with their count and length, a damaged one can only be deleted`() {
        library.put(stills(1, 2, 3).copy(name = "Beach"))
        library.put(ReelProject(), readable = false)
        compose.setContent { Screen() }
        compose.onNodeWithText("Beach").assertExists()
        compose.onNodeWithText(ReelCopy.reelSummary(3, 9_000L)).assertExists()
        compose.onNodeWithText(ReelCopy.DAMAGED_REEL).assertExists()
        compose.onNodeWithContentDescription(ReelCopy.deleteReel(ReelCopy.DAMAGED_REEL)).assertIsEnabled()
    }

    @Test
    fun `deleting asks first and Keep it keeps the reel`() {
        library.put(stills(1).copy(name = "Beach"))
        compose.setContent { Screen() }
        press(ReelCopy.deleteReel("Beach"))
        compose.onNodeWithText(ReelCopy.DELETE_ASK).assertExists()
        tapText(ReelCopy.DELETE_KEEP)
        assertEquals(1, library.entries.size)
        press(ReelCopy.deleteReel("Beach"))
        tapText(ReelCopy.DELETE)
        waitFor { library.entries.isEmpty() }
        waitFor { textShown(ReelCopy.NO_REELS) }
    }

    @Test
    fun `a reel that cannot be opened says so and stays in the list`() {
        val id = library.put(stills(1).copy(name = "Beach"))
        compose.setContent { Screen() }
        library.entries.remove(id)
        tapText("Beach")
        waitFor { textShown(ReelCopy.COULD_NOT_OPEN) }
        assertNull(project)
    }

    @Test
    fun `a reel from a newer version opens with a line saying some may be missing`() {
        library.put(stills(1, 2), decoded = ReelDecoded(stills(1, 2), skippedItems = 0, fromNewerVersion = true))
        compose.setContent { Screen() }
        tapText(ReelCopy.DEFAULT_NAME)
        waitFor { textShown(ReelCopy.NEWER_VERSION) }
    }

    // ---- the draft and the picker ----

    @Test
    fun `a draft is made from the months and length chosen and opens as a reel that can be made`() {
        compose.setContent { Screen() }
        tapText(ReelCopy.START_DRAFT)
        compose.onNodeWithText(ReelCopy.DRAFT_TITLE).assertExists()
        compose.onNodeWithText(ReelCopy.ALL_DATES).assertIsSelected()
        compose.onNodeWithText("Jun 2025").assertExists()
        tapText("Jun 2025")
        compose.onNodeWithText(ReelCopy.ALL_DATES).assertIsNotSelected()
        tapText(ReelCopy.lengthChoice(15_000L))
        compose.onNodeWithText(ReelCopy.lengthChoice(15_000L)).assertIsSelected()
        tapText(ReelCopy.aspect(ReelAspect.TALL))
        tapText(ReelCopy.MAKE_DRAFT)
        waitFor { project != null }
        val p = project!!
        assertTrue(p.items.isNotEmpty())
        assertEquals(ReelAspect.TALL, p.aspect)
        assertTrue(ReelPlan.canRender(p))
        assertTrue(p.totalDurationMs <= 15_000L)
        waitFor { textShown(ReelCopy.picked(p.items.count { it !is TitleItem }, 4)) }
    }

    @Test
    fun `a draft over a month with nothing in it says so and stays on the page`() {
        compose.setContent { Screen(assets = emptyList()) }
        tapText(ReelCopy.START_DRAFT)
        tapText(ReelCopy.MAKE_DRAFT)
        waitFor { textShown(ReelCopy.NOTHING_TO_USE) }
        assertNull(project)
        pressBack()
        compose.onNodeWithText(ReelCopy.START_BLANK).assertExists()
    }

    @Test
    fun `choosing by hand numbers the choice, adds in that order and opens the reel`() {
        compose.setContent { Screen() }
        tapText(ReelCopy.START_DRAFT)
        tapText(ReelCopy.PICK_BY_HAND)
        compose.onNodeWithText(ReelCopy.PICK_ITEMS).assertExists()
        compose.onNodeWithText(ReelCopy.NONE_CHOSEN).assertExists()
        compose.onNodeWithText(ReelCopy.addItems(0)).assertIsNotEnabled()
        press(a.displayName.let { it })
        press("VID_4.mp4, ${ReelCopy.ITEM_CLIP}, 0:12")
        compose.onNodeWithText(ReelCopy.itemsChosen(2)).assertExists()
        press(a.displayName) // un-choose
        compose.onNodeWithText(ReelCopy.itemsChosen(1)).assertExists()
        press(c.displayName)
        tapText(ReelCopy.addItems(2))
        waitFor { project != null }
        assertEquals(listOf(4L, 3L), project!!.items.map { (it as? ClipItem)?.mediaId ?: (it as StillItem).mediaId })
        assertTrue(project!!.items[0] is ClipItem)
    }

    @Test
    fun `the picker offers only the library's list and Back leaves it without adding`() {
        compose.setContent { Screen(assets = listOf(a)) }
        tapText(ReelCopy.START_DRAFT)
        tapText(ReelCopy.PICK_BY_HAND)
        compose.onNodeWithContentDescription(a.displayName).assertExists()
        compose.onNodeWithContentDescription(b.displayName).assertDoesNotExist()
        pressBack()
        compose.onNodeWithText(ReelCopy.DRAFT_TITLE).assertExists()
        assertNull(project)
    }

    @Test
    fun `an empty library says there is nothing to add`() {
        compose.setContent { Screen(assets = emptyList()) }
        tapText(ReelCopy.START_DRAFT)
        tapText(ReelCopy.PICK_BY_HAND)
        compose.onNodeWithText(ReelCopy.NO_ITEMS_HERE).assertExists()
    }

    @Test
    fun `adding from the editor puts the new items after the picked one and picks the first of them`() {
        openSaved(stills(1, 2))
        press(strip(1, ReelCopy.ITEM_STILL, "3 s"))
        tapText(ReelCopy.TOOL_ADD)
        tapText(ReelCopy.ADD_ITEMS)
        press(c.displayName)
        tapText(ReelCopy.addItems(1))
        waitFor { project!!.items.size == 3 }
        assertEquals(listOf(1L, 3L, 2L), project!!.items.map { (it as StillItem).mediaId })
        assertEquals(project!!.items[1].id, state!!.selectedId)
        compose.onNodeWithText(ReelCopy.itemsAdded(1, 0)).assertExists()
    }

    // ---- editing ----

    @Test
    fun `an item is picked by tapping it, with a selected state and not by colour alone`() {
        openSaved(stills(1, 2, 3))
        val first = compose.onNodeWithContentDescription(strip(1, ReelCopy.ITEM_STILL, "3 s"))
        first.assertIsNotSelected()
        act(first)
        first.assertIsSelected()
        compose.onNodeWithContentDescription(strip(2, ReelCopy.ITEM_STILL, "3 s")).assertIsNotSelected()
        compose.onNodeWithText(ReelCopy.itemPosition(1, 3)).assertExists()
        assertEquals(ReelPanel.ITEM, state!!.panel)
    }

    @Test
    fun `move earlier, move later, copy and remove do what they say, with no drag`() {
        openSaved(stills(1, 2, 3))
        press(strip(2, ReelCopy.ITEM_STILL, "3 s"))
        assertEquals(listOf("r1", "r2", "r3"), ids())

        press(ReelCopy.MOVE_EARLIER)
        assertEquals(listOf("r2", "r1", "r3"), ids())
        compose.onNodeWithContentDescription(ReelCopy.MOVE_EARLIER).assertIsNotEnabled()
        compose.onNodeWithText(ReelCopy.itemPosition(1, 3)).assertExists()

        press(ReelCopy.MOVE_LATER)
        press(ReelCopy.MOVE_LATER)
        assertEquals(listOf("r1", "r3", "r2"), ids())
        compose.onNodeWithContentDescription(ReelCopy.MOVE_LATER).assertIsNotEnabled()

        press(ReelCopy.DUPLICATE)
        assertEquals(4, project!!.items.size)
        assertEquals(4, ids().toSet().size)
        assertEquals((project!!.items[2] as StillItem).mediaId, (project!!.items[3] as StillItem).mediaId)
        assertEquals("the copy is the one picked", project!!.items[3].id, state!!.selectedId)

        press(ReelCopy.REMOVE)
        assertEquals(3, project!!.items.size)
        assertEquals(listOf("r1", "r3", "r2"), ids())
        assertEquals("the one before is picked", "r2", state!!.selectedId)
    }

    @Test
    fun `removing the only item leaves an empty reel and closes the item panel`() {
        openSaved(stills(1))
        press(strip(1, ReelCopy.ITEM_STILL, "3 s"))
        press(ReelCopy.REMOVE)
        assertEquals(0, project!!.items.size)
        assertNull(state!!.selectedId)
        compose.onNodeWithText(ReelCopy.EMPTY_REEL).assertExists()
        assertEquals(ReelPanel.NONE, state!!.panel)
    }

    @Test
    fun `previous and next item reach every item without the strip`() {
        openSaved(stills(1, 2, 3))
        press(ReelCopy.NEXT_ITEM)
        assertEquals("r1", state!!.selectedId)
        compose.onNodeWithContentDescription(ReelCopy.PREVIOUS_ITEM).assertIsNotEnabled()
        press(ReelCopy.NEXT_ITEM)
        press(ReelCopy.NEXT_ITEM)
        assertEquals("r3", state!!.selectedId)
        compose.onNodeWithContentDescription(ReelCopy.NEXT_ITEM).assertIsNotEnabled()
        press(ReelCopy.PREVIOUS_ITEM)
        assertEquals("r2", state!!.selectedId)
    }

    @Test
    fun `a photo's time, move and hand-over change from its panel`() {
        openSaved(stills(1, 2, 3))
        press(strip(1, ReelCopy.ITEM_STILL, "3 s"))
        press(ReelCopy.LONGER)
        assertEquals(3_500L, project!!.items[0].durationMs)
        compose.onNodeWithText(ReelCopy.lastsFor(3_500L)).assertExists()
        press(ReelCopy.SHORTER)
        press(ReelCopy.SHORTER)
        assertEquals(2_500L, project!!.items[0].durationMs)

        tapText(ReelCopy.motionChoice(MotionChoice.ZOOM_IN))
        assertEquals(MotionChoice.ZOOM_IN, ReelMoves.choiceOf((project!!.items[0] as StillItem).motion))
        compose.onNodeWithText(ReelCopy.motionChoice(MotionChoice.ZOOM_IN)).assertIsSelected()
        tapText(ReelCopy.motionChoice(MotionChoice.PAN_LEFT))
        assertEquals(MotionChoice.PAN_LEFT, ReelMoves.choiceOf((project!!.items[0] as StillItem).motion))
        assertTrue(ReelPlan.canRender(project!!))

        tapText(ReelCopy.transition(ReelTransition.CROSS_FADE))
        assertEquals(ReelTransition.CROSS_FADE, project!!.items[0].transition)
        compose.onNodeWithText(ReelCopy.transition(ReelTransition.CROSS_FADE)).assertIsSelected()
    }

    @Test
    fun `the last item cannot hand over, and says why`() {
        openSaved(stills(1, 2))
        press(strip(2, ReelCopy.ITEM_STILL, "3 s"))
        compose.onNodeWithText(ReelCopy.transition(ReelTransition.SLIDE)).assertIsNotEnabled()
        compose.onNodeWithText(ReelCopy.LAST_ITEM).assertExists()
    }

    @Test
    fun `in the original shape photos stay still and the move buttons are off`() {
        openSaved(stills(1, 2).copy(aspect = ReelAspect.ORIGINAL))
        press(strip(1, ReelCopy.ITEM_STILL, "3 s"))
        compose.onNodeWithText(ReelCopy.motionChoice(MotionChoice.ZOOM_IN)).assertIsNotEnabled()
        compose.onNodeWithText(ReelCopy.MOVE_STAYS_STILL).assertExists()
    }

    @Test
    fun `a clip is trimmed with start and end buttons inside its video`() {
        val p = ReelProject(items = listOf(ClipItem("r1", 4, 1_000, 4_000, 12_000), StillItem("r2", 1)))
        openSaved(p)
        press(strip(1, ReelCopy.ITEM_CLIP, "3 s"))
        compose.onNodeWithText(ReelCopy.clipStarts(1_000)).assertExists()
        press(ReelCopy.START_LATER)
        press(ReelCopy.END_LATER)
        val clipNow = project!!.items[0] as ClipItem
        assertEquals(1_500L, clipNow.inMs)
        assertEquals(4_500L, clipNow.outMs)
        press(ReelCopy.START_EARLIER)
        press(ReelCopy.START_EARLIER)
        press(ReelCopy.START_EARLIER)
        assertEquals(0L, (project!!.items[0] as ClipItem).inMs)
        compose.onNodeWithContentDescription(ReelCopy.START_EARLIER).assertIsNotEnabled()
        compose.onNodeWithText(ReelCopy.lastsFor(4_500L)).assertExists()
    }

    @Test
    fun `a title card is added after the picked item, picked, and its words and colour are changed`() {
        openSaved(stills(1, 2))
        press(strip(1, ReelCopy.ITEM_STILL, "3 s"))
        tapText(ReelCopy.TOOL_ADD)
        tapText(ReelCopy.ADD_CARD)
        assertEquals(3, project!!.items.size)
        val card = project!!.items[1] as TitleItem
        assertEquals(ReelCopy.NEW_CARD_WORDS, card.text)
        assertEquals(card.id, state!!.selectedId)
        assertEquals(ReelPanel.ITEM, state!!.panel)

        compose.onNode(hasSetTextAction() and hasText(ReelCopy.NEW_CARD_WORDS)).performTextReplacement("Our trip")
        compose.waitForIdle()
        assertEquals("Our trip", (project!!.items[1] as TitleItem).text)

        press("Cream")
        assertEquals(ReelEditing.CARD_COLOURS.first { it.name == "Cream" }.argb, (project!!.items[1] as TitleItem).backgroundArgb)
        compose.onNodeWithContentDescription("Cream").assertIsSelected()
        press(ReelCopy.LONGER)
        assertEquals(ReelLimits.TITLE_DEFAULT_MS + 500L, project!!.items[1].durationMs)
    }

    @Test
    fun `the reel's name, shape and every hand-over are set from the Reel tool`() {
        openSaved(stills(1, 2, 3))
        tapText(ReelCopy.TOOL_REEL)
        compose.onNode(hasSetTextAction()).performTextReplacement("Beach day")
        compose.waitForIdle()
        assertEquals("Beach day", project!!.name)
        compose.onAllNodesWithText("Beach day").assertCountEquals(2) // the title bar and the field

        tapText(ReelCopy.aspect(ReelAspect.SQUARE))
        assertEquals(ReelAspect.SQUARE, project!!.aspect)
        compose.onNodeWithText(ReelCopy.aspect(ReelAspect.SQUARE)).assertIsSelected()

        tapText(ReelCopy.transition(ReelTransition.SLIDE))
        assertTrue(project!!.items.all { it.transition == ReelTransition.SLIDE })
        compose.onNodeWithText(ReelCopy.reelLength(project!!.totalDurationMs)).assertExists()
    }

    @Test
    fun `an item whose photo is not in the library is marked and the video waits`() {
        openSaved(stills(1, 99))
        press("${ReelCopy.ITEM_STILL} 2, 3 s, ${ReelCopy.NOT_IN_LIBRARY}")
        tapText(ReelCopy.TOOL_MAKE)
        compose.onNodeWithText(ReelCopy.missingItems(1)).assertExists()
        compose.onNodeWithText(ReelCopy.MAKE).assertIsNotEnabled()
    }

    @Test
    fun `a library that is still loading does not mark every item gone`() {
        library.put(stills(1, 2))
        compose.setContent { Screen(assets = emptyList()) }
        tapText(ReelCopy.DEFAULT_NAME)
        waitFor { project != null }
        compose.onNodeWithContentDescription(strip(1, ReelCopy.ITEM_STILL, "3 s")).assertExists()
        tapText(ReelCopy.TOOL_MAKE)
        compose.onNodeWithText(ReelCopy.missingItems(2)).assertDoesNotExist()
    }

    @Test
    fun `a problem in the plan is marked on the item and told in a sentence`() {
        val tooShort = ReelProject(items = listOf(StillItem("r1", 1, durationMs = 100L), StillItem("r2", 2)))
        openSaved(tooShort)
        compose.onNodeWithContentDescription("${ReelCopy.ITEM_STILL} 1, 0.1 s, ${ReelCopy.NEEDS_ATTENTION}").assertExists()
        tapText(ReelCopy.TOOL_MAKE)
        compose.onNodeWithText(ReelCopy.issue(ReelIssue(ReelIssueKind.ITEM_TOO_SHORT, 0))).assertExists()
        compose.onNodeWithText(ReelCopy.MAKE).assertIsNotEnabled()
    }

    // ---- the preview ----

    @Test
    fun `the preview starts paused when the system removes animations, and plays otherwise`() {
        library.put(stills(1, 2))
        compose.setContent { Screen(animatorScale = 0f) }
        tapText(ReelCopy.DEFAULT_NAME)
        waitFor { project != null }
        compose.onNodeWithContentDescription(ReelCopy.PLAY).assertExists()
        compose.onNodeWithContentDescription(ReelCopy.PREVIEW).assertExists()
        press(ReelCopy.PLAY)
        compose.onNodeWithContentDescription(ReelCopy.PAUSE).assertExists()
        press(ReelCopy.PAUSE)
        compose.onNodeWithContentDescription(ReelCopy.PLAY).assertExists()
    }

    @Test
    fun `with animations on the preview plays from the start`() {
        library.put(stills(1, 2))
        compose.setContent { Screen(animatorScale = 1f) }
        tapText(ReelCopy.DEFAULT_NAME)
        waitFor { project != null }
        compose.onNodeWithContentDescription(ReelCopy.PAUSE).assertExists()
        press(ReelCopy.PAUSE)
        compose.onNodeWithContentDescription(ReelCopy.PLAY).assertExists()
    }

    @Test
    fun `an empty reel has no preview and the play buttons are off`() {
        newBlank()
        compose.onNodeWithContentDescription(ReelCopy.PREVIEW).assertDoesNotExist()
        compose.onNodeWithContentDescription(ReelCopy.PLAY).assertIsNotEnabled()
        compose.onNodeWithContentDescription(ReelCopy.NEXT_ITEM).assertIsNotEnabled()
    }

    // ---- sound ----

    @Test
    fun `the sound tool offers a sound file, and picking one lays it under the reel`() {
        openSaved(stills(1, 2))
        tapText(ReelCopy.TOOL_SOUND)
        compose.onNodeWithText(ReelCopy.SOUND_PICK).assertExists()
        compose.onNodeWithText(ReelCopy.SOUND_NONE).assertExists()
        compose.onNodeWithText(ReelCopy.MUSIC_NOTE).assertExists()
        compose.onNodeWithText(ReelCopy.CLIP_SOUND).assertDoesNotExist()

        picked = Uri.parse("content://docs/song-1")
        sounds.names["content://docs/song-1"] = "holiday.mp3"
        tapText(ReelCopy.SOUND_PICK)
        waitFor { project!!.soundtrack != null }
        assertEquals("content://docs/song-1", project!!.soundtrack!!.uri)
        assertEquals(listOf("content://docs/song-1"), sounds.kept)
        waitFor { textShown(ReelCopy.soundFile("holiday.mp3")) }
        compose.onNodeWithText(ReelCopy.SOUND_MAY_STOP).assertDoesNotExist()

        press(ReelCopy.LONGER_FADE)
        assertEquals(3_000L, project!!.soundtrack!!.fadeOutMs)
        press(ReelCopy.LATER_IN_SONG)
        assertEquals(5_000L, project!!.soundtrack!!.trimStartMs)
        compose.onNodeWithText(ReelCopy.clock(5_000)).assertExists()

        tapText(ReelCopy.SOUND_REMOVE)
        assertNull(project!!.soundtrack)
        compose.onNodeWithText(ReelCopy.SOUND_NONE).assertExists()
    }

    @Test
    fun `backing out of the file picker changes nothing, and a file that cannot be kept is said so`() {
        openSaved(stills(1, 2))
        tapText(ReelCopy.TOOL_SOUND)
        picked = null
        tapText(ReelCopy.SOUND_PICK)
        assertNull(project!!.soundtrack)

        picked = Uri.parse("content://docs/song-2")
        sounds.keeps = false
        tapText(ReelCopy.SOUND_PICK)
        waitFor { project!!.soundtrack != null }
        compose.onNodeWithText(ReelCopy.SOUND_MAY_STOP).assertExists()
    }

    @Test
    fun `the clips' own sound is a switch that is only offered when there is a clip`() {
        openSaved(ReelProject(items = listOf(ClipItem("r1", 4, 0, 3_000, 12_000), StillItem("r2", 1))))
        tapText(ReelCopy.TOOL_SOUND)
        compose.onNodeWithText(ReelCopy.CLIP_SOUND).assertExists()
        assertFalse(state!!.keepClipSound)
        tapText(ReelCopy.CLIP_SOUND)
        assertTrue(state!!.keepClipSound)
    }

    @Test
    fun `a sound file's name comes back after the screen is reopened`() {
        sounds.names["content://docs/song-3"] = "kept.m4a"
        openSaved(stills(1).copy(soundtrack = ReelSoundtrack("content://docs/song-3")))
        tapText(ReelCopy.TOOL_SOUND)
        waitFor { textShown(ReelCopy.soundFile("kept.m4a")) }
    }

    // ---- saving as the person works ----

    @Test
    fun `a change is saved by itself and leaving saves it again`() {
        openSaved(stills(1, 2, 3))
        press(strip(1, ReelCopy.ITEM_STILL, "3 s"))
        assertTrue("opening a reel and looking at it writes nothing", library.saves.isEmpty())
        press(ReelCopy.LONGER)
        waitFor { library.saves.isNotEmpty() }
        assertEquals(3_500L, library.entries.values.single().project.items[0].durationMs)
        assertEquals("the reel kept using its own file", 1, library.entries.size)
        press(ReelCopy.LONGER)
        pressBack() // the panel
        pressBack() // leaves
        compose.onNodeWithText(ReelCopy.START_BLANK).assertExists()
        waitFor { library.entries.values.single().project.items[0].durationMs == 4_000L }
    }

    @Test
    fun `a new reel gets one file once it has something in it`() {
        newBlank()
        tapText(ReelCopy.TOOL_ADD)
        tapText(ReelCopy.ADD_CARD)
        waitFor { library.entries.size == 1 }
        press(ReelCopy.LONGER)
        waitFor { library.entries.values.single().project.items[0].durationMs == ReelLimits.TITLE_DEFAULT_MS + 500L }
        assertEquals(1, library.entries.size)
    }

    @Test
    fun `a save that fails is said in words and a full list is said too`() {
        library.result = { ReelSave.Failed }
        newBlank()
        tapText(ReelCopy.TOOL_ADD)
        tapText(ReelCopy.ADD_CARD)
        waitFor { textShown(ReelCopy.COULD_NOT_SAVE) }
        library.result = { ReelSave.Full }
        press(ReelCopy.LONGER)
        waitFor { textShown(ReelCopy.LIST_FULL) }
    }

    @Test
    fun `the open reel, its pick and its tool survive the activity being recreated`() {
        val tester = StateRestorationTester(compose)
        library.put(stills(1, 2, 3))
        tester.setContent { Screen() }
        tapText(ReelCopy.DEFAULT_NAME)
        waitFor { project != null }
        press(strip(2, ReelCopy.ITEM_STILL, "3 s"))
        press(ReelCopy.LONGER)
        tapText(ReelCopy.TOOL_SOUND)
        val before = project
        val selected = state!!.selectedId

        tester.emulateSavedInstanceStateRestore()

        assertEquals(before, project)
        assertEquals(selected, state!!.selectedId)
        compose.onNodeWithContentDescription(strip(2, ReelCopy.ITEM_STILL, "3.5 s")).assertIsSelected()
        compose.onNodeWithText(ReelCopy.MUSIC_NOTE).assertExists()
        assertTrue("and Back still has its steps", owner.onBackPressedDispatcher.hasEnabledCallbacks())
    }

    @Test
    fun `the draft page's choices survive too`() {
        val tester = StateRestorationTester(compose)
        tester.setContent { Screen() }
        tapText(ReelCopy.START_DRAFT)
        tapText("Jun 2025")
        tapText(ReelCopy.lengthChoice(60_000L))
        tester.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(ReelCopy.DRAFT_TITLE).assertExists()
        compose.onNodeWithText(ReelCopy.lengthChoice(60_000L)).assertIsSelected()
        compose.onNodeWithText("Jun 2025").assertExists()
        compose.onNodeWithText(ReelCopy.ALL_DATES).assertIsNotSelected()
    }

    // ---- making the video ----

    private fun openMake() = tapText(ReelCopy.TOOL_MAKE)

    @Test
    fun `opening the make panel sweeps what a killed export left, once, and not before`() {
        openSaved(stills(1, 2), name = "Beach")
        compose.waitForIdle()
        assertEquals("nothing is swept for opening a reel", 0, sink.sweeps)
        openMake()
        waitFor { sink.sweeps >= 1 }
        assertEquals(1, sink.sweeps)
    }

    @Test
    fun `making the video runs the job with the choices and shows how it ended`() {
        openSaved(stills(1, 2, 3).withTransitions(ReelTransition.CROSS_FADE), name = "Beach")
        openMake()
        tapText(ReelCopy.sizeChoice(720))
        compose.onNodeWithText(ReelCopy.sizeChoice(720)).assertIsSelected()
        tapText(ReelCopy.MAKE)
        waitFor { textShown(ReelCopy.SAVED) }
        assertEquals(1, encoder.plans.size)
        assertEquals(1, sink.published.size)
        assertEquals(720, encoder.plans[0].attempt.shortSide)
        assertTrue(encoder.plans[0].isLayered)
        compose.onNodeWithText(ReelCopy.madeNote(1280, 720, project!!.totalDurationMs)).assertExists()
        tapText(ReelCopy.WATCH_IT)
        assertEquals(1, watched.size)
        assertTrue(watched[0].startsWith("content://media/external/video/media/"))
        compose.onNodeWithText(ReelCopy.NO_PLAYER).assertDoesNotExist()
    }

    @Test
    fun `plain cuts are honoured, and Watch it says when nothing can play the video`() {
        canWatch = false
        openSaved(stills(1, 2, 3).withTransitions(ReelTransition.SLIDE))
        openMake()
        tapText(ReelCopy.PLAIN_CUTS)
        compose.onNodeWithText(ReelCopy.PLAIN_CUTS).assertIsOn()
        tapText(ReelCopy.MAKE)
        waitFor { textShown(ReelCopy.SAVED) }
        assertFalse(encoder.plans.single().isLayered)
        tapText(ReelCopy.WATCH_IT)
        compose.onNodeWithText(ReelCopy.NO_PLAYER).assertExists()
    }

    @Test
    fun `the work runs under the app's job list while it runs, with progress and a Cancel`() {
        val gate = CompletableDeferred<Unit>()
        encoder.gate = { gate.await() }
        val runner = JobRunner(scope)
        library.put(stills(1, 2))
        compose.setContent { Screen(runner = runner) }
        tapText(ReelCopy.DEFAULT_NAME)
        waitFor { project != null }
        openMake()
        tapText(ReelCopy.MAKE)
        waitFor { textShown(ReelCopy.MAKING) }
        assertEquals(listOf(ReelCopy.JOB_TITLE), runner.jobs.value.map { it.title })
        compose.onNodeWithText(ReelCopy.MAKE).assertIsNotEnabled()
        tapText(ReelCopy.CANCEL)
        waitFor { textShown(ReelCopy.CANCELLED) }
        assertTrue(sink.published.isEmpty())
        compose.onNodeWithText(ReelCopy.MAKE).assertIsEnabled()
        gate.complete(Unit)
    }

    @Test
    fun `leaving while the video is made is held once and then stops it`() {
        val gate = CompletableDeferred<Unit>()
        encoder.gate = { gate.await() }
        openSaved(stills(1, 2))
        openMake()
        tapText(ReelCopy.MAKE)
        waitFor { textShown(ReelCopy.MAKING) }
        pressBack() // the panel
        pressBack() // held
        compose.onNodeWithText(ReelCopy.LEAVING_CANCELS).assertExists()
        compose.onNodeWithText(ReelCopy.MAKING).assertExists()
        pressBack() // leaves
        compose.onNodeWithText(ReelCopy.START_BLANK).assertExists()
        waitFor { sink.published.isEmpty() }
        gate.complete(Unit)
        // The stopped video's late "Cancelled." belongs to the reel that was left, not to the next one.
        tapText(ReelCopy.DEFAULT_NAME)
        waitFor { project != null }
        Thread.sleep(300)
        compose.waitForIdle()
        compose.onNodeWithText(ReelCopy.CANCELLED).assertDoesNotExist()
        assertTrue(sink.published.isEmpty())
    }

    @Test
    fun `a protected photo is refused in words`() {
        host.blocked += 2L
        openSaved(stills(1, 2))
        openMake()
        tapText(ReelCopy.MAKE)
        waitFor { textShown(ReelCopy.PROTECTED) }
        assertTrue(encoder.plans.isEmpty())
        assertTrue(sink.published.isEmpty())
    }

    @Test
    fun `a phone that cannot make a video says so`() {
        encoder.can = false
        openSaved(stills(1))
        openMake()
        tapText(ReelCopy.MAKE)
        waitFor { textShown(ReelCopy.NO_ENCODER) }
    }

    @Test
    fun `a video that is refused at once is still reported, however the threads fall`() {
        // The job answers on another thread and comes back to the main one; many in a row, none lost, none left running.
        encoder.can = false
        openSaved(stills(1))
        openMake()
        repeat(25) { round ->
            tapText(ReelCopy.MAKE)
            waitFor { textShown(ReelCopy.NO_ENCODER) }
            compose.runOnUiThread { state!!.result = null }
            compose.waitForIdle()
            assertFalse("round $round", textShown(ReelCopy.NO_ENCODER))
            compose.onNodeWithText(ReelCopy.MAKE).assertIsEnabled()
        }
    }

    @Test
    fun `a failed save is told in the job's own sentence`() {
        sink.failWith = java.io.IOException("x")
        openSaved(stills(1))
        openMake()
        tapText(ReelCopy.MAKE)
        waitFor { textShown(ReelCopy.SAVE_FAILED) }
    }

    @Test
    fun `a sound file that cannot be read asks, and only a yes makes a silent reel`() {
        host.probe = SoundtrackProbe.Unusable
        openSaved(stills(1, 2).copy(soundtrack = ReelSoundtrack("content://docs/bad")))
        openMake()
        tapText(ReelCopy.MAKE)
        waitFor { textShown(ReelCopy.SOUND_ASK) }
        compose.onNodeWithText(ReelCopy.SOUND_UNREADABLE).assertExists()
        assertTrue("nothing was made without being asked", encoder.plans.isEmpty())
        tapText(ReelCopy.SOUND_ASK_YES)
        waitFor { textShown(ReelCopy.SAVED) }
        assertFalse(encoder.plans.single().hasAudio)
        compose.onNodeWithText(ReelCopy.SAVED_SILENT).assertExists()
        assertEquals(1, sink.published.size)
    }

    @Test
    fun `answering no to the sound question leaves the reel as it was`() {
        host.probe = SoundtrackProbe.Unusable
        openSaved(stills(1, 2).copy(soundtrack = ReelSoundtrack("content://docs/bad")))
        openMake()
        tapText(ReelCopy.MAKE)
        waitFor { textShown(ReelCopy.SOUND_ASK) }
        tapText(ReelCopy.SOUND_ASK_NO)
        compose.onNodeWithText(ReelCopy.SOUND_ASK).assertDoesNotExist()
        assertNotNull(project!!.soundtrack)
        assertTrue(encoder.plans.isEmpty())
    }

    @Test
    fun `without the app's job list there is no Make and the screen says so`() {
        library.put(stills(1))
        compose.setContent { Screen(runner = null) }
        tapText(ReelCopy.DEFAULT_NAME)
        waitFor { project != null }
        openMake()
        compose.onNodeWithText(ReelCopy.CANNOT_MAKE_HERE).assertExists()
        compose.onNodeWithText(ReelCopy.MAKE).assertIsNotEnabled()
    }

    @Test
    fun `an empty reel cannot be made and the first thing to do is said`() {
        newBlank()
        openMake()
        compose.onNodeWithText(ReelCopy.issue(ReelIssue(ReelIssueKind.NO_ITEMS))).assertExists()
        compose.onNodeWithText(ReelCopy.MAKE).assertIsNotEnabled()
    }

    // ---- reaching everything ----

    @Test
    fun `every button is at least 48 dp and the pick is a selected state`() {
        openSaved(stills(1, 2, 3))
        press(strip(2, ReelCopy.ITEM_STILL, "3 s"))
        for (description in listOf(
            ReelCopy.BACK, ReelCopy.PREVIOUS_ITEM, ReelCopy.PLAY, ReelCopy.NEXT_ITEM, ReelCopy.MOVE_EARLIER,
            ReelCopy.MOVE_LATER, ReelCopy.DUPLICATE, ReelCopy.REMOVE, ReelCopy.SHORTER, ReelCopy.LONGER,
        )) {
            compose.onNodeWithContentDescription(description).assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        }
        for (text in listOf(ReelCopy.TOOL_ITEM, ReelCopy.TOOL_ADD, ReelCopy.TOOL_REEL, ReelCopy.TOOL_SOUND, ReelCopy.TOOL_MAKE, ReelCopy.motionChoice(MotionChoice.ZOOM_IN))) {
            compose.onNodeWithText(text).assertHeightIsAtLeast(48.dp)
        }
        compose.onNodeWithText(ReelCopy.TOOL_ITEM).assertIsSelected()
        compose.onNodeWithText(ReelCopy.TOOL_SOUND).assertIsNotSelected()
    }
}
