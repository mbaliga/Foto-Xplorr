package com.fotoxplorr.app.animate

import android.os.Looper
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.fotoxplorr.app.editor.EditRecipe
import com.fotoxplorr.app.experimental.ExperimentEntry
import com.fotoxplorr.app.experimental.ExperimentEnv
import com.fotoxplorr.app.experimental.ExperimentGroup
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.jobs.JobRunner
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.spatial.GeoIndexState
import com.fotoxplorr.app.ui.FotoXplorrTheme
import com.fotoxplorr.core.model.MediaId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * "Make a GIF or video" on screen, over fakes for everything outside it: the three steps, every button of the
 * arrange step (none is a drag), the make step's choices and estimate, saving as a job with progress, cancel and
 * open, the preview player, Back, and what survives a recreation. Clicks are injected; how it looks and feels is
 * a device check.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h2400dp-xhdpi")
class AnimateScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private class BackOwner : OnBackPressedDispatcherOwner {
        private val registry = LifecycleRegistry.createUnsafe(this).also { it.currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
        override val onBackPressedDispatcher = OnBackPressedDispatcher()
    }

    private val owner = BackOwner()

    // The app's job list runs on the view model's scope, which is Main.immediate.
    //
    // JobRunner.launch adds a job to its list AFTER it has started it (TRAPS #97), so a job that finishes before launch
    // returns is kept in the list for ever and the screen shows it as running. In the app that cannot happen (a job's
    // result comes back to the main thread, which is still inside launch). Under Robolectric it does happen, even with
    // Main.immediate: a scratch test of 2000 jobs that do nothing left 1882 in the list, 182 with Unconfined. So every
    // job here takes a few tens of milliseconds before it can finish: the sink waits in stagingDirectory (every job that
    // passes the first refusals asks for it) and, for the test of a refusal, the host in mayExport.
    private val scope = CoroutineScope(Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val photos = (1L..12L).map { AnimateKit.asset(it) }
    private val video = AnimateKit.video(20, durationMs = 12_000)
    private val gif = AnimateKit.asset(30, "party.gif", mime = "image/gif")
    private val saved = AnimateKit.asset(900, "IMG_0001-animation.gif", mime = "image/gif")
    private val library = photos.reversed() + video + gif

    private val host = FakeAnimateHost().apply {
        videos[20L] = FakeVideoReader(VideoInfo(1280, 720, 12_000))
        files[30L] = GifEncoder.encode(AnimTestKit.photoSequence(60, 40, 5, delayMs = 100, noise = 0), GifOptions(ditherStrength = 0f))
    }

    /** Returns the library row of [saved] for whatever it is given, as MediaStore would. */
    private class ResultSink(val inner: FakeAnimateSink = FakeAnimateSink()) : AnimateSink by inner {
        override fun stagingDirectory(): File {
            Thread.sleep(JOB_STARTS_MS)
            return inner.stagingDirectory()
        }

        override suspend fun publish(
            staged: File,
            output: AnimOutput,
            dateTakenMillis: Long,
            sourceName: String,
            stillAllowed: () -> Boolean,
            onProgress: (Float) -> Unit,
        ): String {
            inner.publish(staged, output, dateTakenMillis, sourceName, stillAllowed, onProgress)
            return "content://media/external/images/media/900"
        }
    }

    private val sink = ResultSink()

    /** When set, the host the screen is given instead of [host]. */
    private var hostOverride: AnimateHost? = null
    private val maker = FakeVideoMaker()
    private val runner = JobRunner(scope)
    private var closed = 0
    private val opened = ArrayList<MediaAsset>()
    private var edit: (MediaAsset) -> EditRecipe? = { null }

    @Composable
    private fun Screen(assets: List<MediaAsset> = library, runner: JobRunner? = this.runner, animatorScale: Float = 1f) {
        FotoXplorrTheme(GalleryPreferencesState()) {
            CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides owner) {
                AnimatedImagesScreen(
                    assets = assets,
                    newHost = { hostOverride ?: host },
                    newSink = { sink },
                    newVideoMaker = { maker },
                    savedEdit = { edit(it) },
                    animatorScale = animatorScale,
                    runner = runner,
                    onOpenAsset = { asset, _ -> opened += asset },
                    onClose = { closed++ },
                )
            }
        }
    }

    private companion object {
        /** How long a job is held before it can finish (see the scope above). */
        const val JOB_STARTS_MS = 40L
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private fun waitFor(timeoutMillis: Long = 60_000, condition: () -> Boolean) =
        compose.waitUntil(timeoutMillis) { shadowOf(Looper.getMainLooper()).idle(); condition() }

    private fun exists(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun waitForText(text: String) = waitFor { exists(text) }

    private fun click(text: String) {
        compose.onNodeWithText(text).performClick()
        compose.waitForIdle()
    }

    private fun clickDescription(description: String) {
        compose.onNodeWithContentDescription(description).performClick()
        compose.waitForIdle()
    }

    private fun pressBack() {
        compose.runOnUiThread { owner.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun name(id: Long) = "IMG_%04d.jpg".format(id)

    /** Tick the photos in this order on the first step. */
    private fun tick(vararg ids: Long) = ids.forEach { clickDescription(name(it)) }

    private fun stepIs(step: Int, name: String) = compose.onNodeWithText(AnimateScreenCopy.stepTitle(step, name)).assertExists()

    private fun toArrange(vararg ids: Long) {
        tick(*ids)
        click(AnimateScreenCopy.NEXT)
        stepIs(2, AnimateScreenCopy.STEP_ARRANGE)
    }

    private fun toOutput(vararg ids: Long) {
        toArrange(*ids)
        click(AnimateScreenCopy.NEXT)
        stepIs(3, AnimateScreenCopy.STEP_OUTPUT)
    }

    private fun cellMatcher(position: Int, count: Int) =
        SemanticsMatcher("photo $position of $count") { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith("Photo $position of $count,") } == true
        }

    private fun cell(position: Int, count: Int): SemanticsNodeInteraction = compose.onNode(cellMatcher(position, count))

    private fun describe(position: Int, count: Int): String =
        cell(position, count).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].first()

    /** The photo names in strip order. */
    private fun order(count: Int): List<String> = (1..count).map { Regex("IMG_\\d{4}\\.jpg").find(describe(it, count))!!.value }

    private fun hold(position: Int, count: Int): String = describe(position, count).substringAfterLast(", ")

    private fun textOf(part: String): String =
        compose.onAllNodes(hasText(part, substring = true)).fetchSemanticsNodes().first().config[SemanticsProperties.Text].first().text

    private fun stepButton(description: String) = compose.onNodeWithContentDescription(description)

    /** Press a stepper button until it is off (it must get there in a few presses). */
    private fun pressWhileEnabled(description: String) {
        var presses = 0
        while (runCatching { stepButton(description).assertIsEnabled() }.isSuccess) {
            check(presses++ < 80) { "$description never reached its end" }
            stepButton(description).performClick()
        }
    }

    // ---- the entry ---------------------------------------------------------------------------------

    @Test
    fun `the public entry fits what the Experimental host gives an experiment`() {
        // Compile-time and construction: an ExperimentEntry takes exactly this composable as its content.
        val entry = ExperimentEntry(
            id = "animate-test",
            title = AnimateScreenCopy.TITLE,
            blurb = AnimateScreenCopy.BLURB,
            group = ExperimentGroup.MAKE,
            icon = Icons.Outlined.Check,
            content = { env, close -> AnimatedImages(env, close) },
        )
        assertEquals("Make a GIF or video", entry.title)
        assertTrue(entry.restorable)
    }

    @Test
    fun `the entry opens on the first step over a real env, and Back closes it when nothing is picked`() {
        val context = RuntimeEnvironment.getApplication()
        val env = ExperimentEnv(
            assets = library, indexInput = library, geoState = GeoIndexState(), onIndexLocations = {},
            onOpenAsset = { _, _ -> }, context = context, currentBrowsableIds = { library.map { it.id }.toSet() },
        )
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                // No view-model owner that can make the app's job list, so saving is not offered; choosing is.
                val noModels = object : ViewModelStoreOwner {
                    override val viewModelStore = ViewModelStore()
                }
                CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides owner, LocalViewModelStoreOwner provides noModels) {
                    AnimatedImages(env, onClose = { closed++ })
                }
            }
        }
        compose.onNodeWithText(AnimateScreenCopy.TITLE).assertExists()
        stepIs(1, AnimateScreenCopy.STEP_SOURCE)
        compose.onNodeWithText(AnimateScreenCopy.SRC_PHOTOS).assertExists()
        clickDescription(AnimateScreenCopy.BACK)
        assertEquals(1, closed)
    }

    // ---- step 1: choose -----------------------------------------------------------------------------

    @Test
    fun `step one offers photos, a video part and a GIF, with photos chosen and its hint`() {
        compose.setContent { Screen() }
        stepIs(1, AnimateScreenCopy.STEP_SOURCE)
        compose.onNodeWithText(AnimateScreenCopy.SRC_PHOTOS).assertIsSelected()
        compose.onNodeWithText(AnimateScreenCopy.SRC_CLIP).assertIsNotSelected()
        compose.onNodeWithText(AnimateScreenCopy.SRC_FILE).assertIsNotSelected()
        compose.onNodeWithText(AnimateScreenCopy.PHOTOS_HINT).assertExists()
        compose.onNodeWithText(AnimateScreenCopy.NEED_TWO).assertExists()
        compose.onNodeWithText(AnimateScreenCopy.NEXT).assertIsNotEnabled()
        compose.onNodeWithTag(ANIM_GRID_TAG).assertExists()
    }

    @Test
    fun `ticking photos numbers them in order and Next waits for two`() {
        compose.setContent { Screen() }
        tick(3)
        compose.onNodeWithText(AnimateScreenCopy.NEXT).assertIsNotEnabled()
        compose.onNodeWithText(AnimateScreenCopy.NEED_TWO).assertExists()
        tick(1)
        compose.onNodeWithText(AnimateScreenCopy.chosen(2)).assertExists()
        compose.onNodeWithText(AnimateScreenCopy.NEXT).assertIsEnabled()
        // The number is the order ticked, 3 first and then 1.
        compose.onAllNodes(hasText("1")).fetchSemanticsNodes().isNotEmpty().let { assertTrue(it) }
        compose.onAllNodes(hasText("2")).fetchSemanticsNodes().isNotEmpty().let { assertTrue(it) }
        // Unticking takes one out.
        tick(3)
        compose.onNodeWithText(AnimateScreenCopy.NEED_TWO).assertExists()
        compose.onNodeWithText(AnimateScreenCopy.NEXT).assertIsNotEnabled()
    }

    @Test
    fun `a ticked photo says so to a screen reader, and the grid is a check box list`() {
        compose.setContent { Screen() }
        tick(2)
        compose.onNodeWithContentDescription(name(2)).assertIsOn()
        compose.onNodeWithContentDescription(name(3)).assertIsOff()
    }

    @Test
    fun `the video list picks one video, shows its length, and Next waits for it`() {
        compose.setContent { Screen() }
        click(AnimateScreenCopy.SRC_CLIP)
        compose.onNodeWithText(AnimateScreenCopy.SRC_CLIP).assertIsSelected()
        compose.onNodeWithText(AnimateScreenCopy.CLIP_HINT).assertExists()
        compose.onNodeWithText(AnimateScreenCopy.NEXT).assertIsNotEnabled()
        val row = AnimateScreenCopy.clipRow("VID_0020.mp4", 12_000)
        compose.onNodeWithContentDescription(row).assertIsNotSelected()
        clickDescription(row)
        compose.onNodeWithContentDescription(row).assertIsSelected()
        compose.onNodeWithText(AnimateScreenCopy.NEXT).assertIsEnabled()
    }

    @Test
    fun `the file list offers GIFs only`() {
        compose.setContent { Screen() }
        click(AnimateScreenCopy.SRC_FILE)
        compose.onNodeWithContentDescription("party.gif").assertExists()
        compose.onNodeWithContentDescription("VID_0020.mp4").assertDoesNotExist()
        compose.onAllNodes(hasContentDescription(name(1))).fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
    }

    @Test
    fun `a library with no videos or GIFs says so in a sentence`() {
        compose.setContent { Screen(assets = photos) }
        click(AnimateScreenCopy.SRC_CLIP)
        compose.onNodeWithText(AnimateScreenCopy.NO_VIDEOS).assertExists()
        click(AnimateScreenCopy.SRC_FILE)
        compose.onNodeWithText(AnimateScreenCopy.NO_FILES).assertExists()
    }

    @Test
    fun `changing the source and back keeps the photos that were ticked`() {
        compose.setContent { Screen() }
        tick(4, 5)
        click(AnimateScreenCopy.SRC_CLIP)
        click(AnimateScreenCopy.SRC_PHOTOS)
        compose.onNodeWithText(AnimateScreenCopy.chosen(2)).assertExists()
    }

    // ---- step 2: arrange photos -----------------------------------------------------------------------

    @Test
    fun `step two shows the strip in the order ticked, each with its place and time, the first selected`() {
        compose.setContent { Screen() }
        toArrange(3, 1, 2)
        compose.onNodeWithTag(ANIM_STRIP_TAG).assertExists()
        assertEquals(listOf(name(3), name(1), name(2)), order(3))
        assertEquals("1.0 s", hold(1, 3))
        cell(1, 3).assertIsSelected()
        cell(2, 3).assertIsNotSelected()
        assertTrue(describe(1, 3).startsWith("Photo 1 of 3,"))
        compose.onNodeWithText(AnimateScreenCopy.frameTitle(1, 3)).assertExists()
    }

    @Test
    fun `the selected photo is told by a state and a title, not by colour`() {
        compose.setContent { Screen() }
        toArrange(1, 2, 3)
        cell(2, 3).performClick()
        cell(2, 3).assertIsSelected()
        cell(1, 3).assertIsNotSelected()
        compose.onNodeWithText(AnimateScreenCopy.frameTitle(2, 3)).assertExists()
        val state = cell(2, 3).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)
        assertEquals(AnimateScreenCopy.SELECTED, state)
        assertEquals(AnimateScreenCopy.NOT_SELECTED, cell(1, 3).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
    }

    @Test
    fun `Move left and Move right reorder the strip with no drag, the selection follows, and the ends stop`() {
        compose.setContent { Screen() }
        toArrange(1, 2, 3)
        compose.onNodeWithText(AnimateScreenCopy.MOVE_LEFT).assertIsNotEnabled()
        click(AnimateScreenCopy.MOVE_RIGHT)
        assertEquals(listOf(name(2), name(1), name(3)), order(3))
        cell(2, 3).assertIsSelected()
        click(AnimateScreenCopy.MOVE_RIGHT)
        assertEquals(listOf(name(2), name(3), name(1)), order(3))
        compose.onNodeWithText(AnimateScreenCopy.MOVE_RIGHT).assertIsNotEnabled()
        click(AnimateScreenCopy.MOVE_LEFT)
        assertEquals(listOf(name(2), name(1), name(3)), order(3))
    }

    @Test
    fun `Remove takes out the selected photo and stops at two, with a line saying why`() {
        compose.setContent { Screen() }
        toArrange(1, 2, 3)
        compose.onNodeWithText(AnimateScreenCopy.KEEP_TWO).assertDoesNotExist()
        cell(2, 3).performClick()
        click(AnimateScreenCopy.REMOVE)
        assertEquals(listOf(name(1), name(3)), order(2))
        compose.onNodeWithText(AnimateScreenCopy.REMOVE).assertIsNotEnabled()
        compose.onNodeWithText(AnimateScreenCopy.KEEP_TWO).assertExists()
    }

    @Test
    fun `the time on screen steps in two sizes with words for a screen reader, and stops at its ends`() {
        compose.setContent { Screen() }
        toArrange(1, 2)
        val fine = AnimateDraft.HOLD_FINE_MS
        val coarse = AnimateDraft.HOLD_COARSE_MS
        stepButton(AnimateScreenCopy.longerWords(coarse)).performClick()
        assertEquals("1.5 s", hold(1, 2))
        assertEquals("1.0 s", hold(2, 2))
        stepButton(AnimateScreenCopy.shorterWords(fine)).performClick()
        assertEquals("1.4 s", hold(1, 2))
        pressWhileEnabled(AnimateScreenCopy.shorterWords(coarse))
        assertEquals("0.1 s", hold(1, 2))
        stepButton(AnimateScreenCopy.shorterWords(fine)).assertIsNotEnabled()
        stepButton(AnimateScreenCopy.shorterWords(coarse)).assertIsNotEnabled()
        stepButton(AnimateScreenCopy.longerWords(fine)).assertIsEnabled()
        pressWhileEnabled(AnimateScreenCopy.longerWords(coarse))
        assertEquals("10.0 s", hold(1, 2))
        stepButton(AnimateScreenCopy.longerWords(fine)).assertIsNotEnabled()
        assertEquals("1.0 s", hold(2, 2))
    }

    @Test
    fun `Same for all gives every photo the selected one's time`() {
        compose.setContent { Screen() }
        toArrange(1, 2, 3)
        compose.onNodeWithText(AnimateScreenCopy.SAME_FOR_ALL).assertIsNotEnabled()
        cell(2, 3).performClick()
        stepButton(AnimateScreenCopy.longerWords(AnimateDraft.HOLD_COARSE_MS)).performClick()
        stepButton(AnimateScreenCopy.longerWords(AnimateDraft.HOLD_COARSE_MS)).performClick()
        assertEquals(listOf("1.0 s", "2.0 s", "1.0 s"), (1..3).map { hold(it, 3) })
        click(AnimateScreenCopy.SAME_FOR_ALL)
        assertEquals(listOf("2.0 s", "2.0 s", "2.0 s"), (1..3).map { hold(it, 3) })
        compose.onNodeWithText(AnimateScreenCopy.SAME_FOR_ALL).assertIsNotEnabled()
    }

    @Test
    fun `speed, repeat and fade are buttons and switches that say their state in words`() {
        compose.setContent { Screen() }
        toArrange(1, 2)
        assertTrue(exists(AnimateScreenCopy.speedValue(100)))
        stepButton(AnimateScreenCopy.FASTER).performClick()
        assertTrue(exists(AnimateScreenCopy.speedValue(125)))
        stepButton(AnimateScreenCopy.SLOWER).performClick()
        stepButton(AnimateScreenCopy.SLOWER).performClick()
        assertTrue(exists(AnimateScreenCopy.speedValue(75)))
        compose.onNodeWithText(AnimateScreenCopy.LOOP).assertIsOn()
        compose.onNodeWithText(AnimateScreenCopy.LOOP).performClick()
        compose.onNodeWithText(AnimateScreenCopy.LOOP).assertIsOff()
        compose.onNodeWithText(AnimateScreenCopy.FADE).assertIsOff()
        compose.onNodeWithText(AnimateScreenCopy.FADE).performClick()
        compose.onNodeWithText(AnimateScreenCopy.FADE).assertIsOn()
        val role = compose.onNodeWithText(AnimateScreenCopy.FADE).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role)
        assertEquals(Role.Switch, role)
        // Words as well as the switch: "On" and "Off" are text.
        assertTrue(exists(AnimateScreenCopy.ON) && exists(AnimateScreenCopy.OFF))
    }

    @Test
    fun `speed changes the time each photo shows in the estimate, not the times chosen`() {
        compose.setContent { Screen() }
        toOutput(1, 2, 3, 4)
        assertTrue(exists(AnimateScreenCopy.playInfo(4, 480, 360, 4_000)))
        clickDescription(AnimateScreenCopy.BACK)
        repeat(4) { stepButton(AnimateScreenCopy.FASTER).performClick() }
        click(AnimateScreenCopy.NEXT)
        assertTrue(exists(AnimateScreenCopy.playInfo(4, 480, 360, 2_000)))
    }

    @Test
    fun `Use my edits appears only when a chosen photo has an edit saved`() {
        compose.setContent { Screen() }
        toArrange(1, 2)
        compose.waitForIdle()
        compose.onNodeWithText(AnimateScreenCopy.USE_EDITS).assertDoesNotExist()
        pressBack()
        edit = { if (it.id.value == 2L) EditRecipe(quarterTurns = 1) else null }
        tick(3)
        click(AnimateScreenCopy.NEXT)
        compose.waitForIdle()
        waitFor { exists(AnimateScreenCopy.USE_EDITS) }
        compose.onNodeWithText(AnimateScreenCopy.USE_EDITS).assertIsOff()
        compose.onNodeWithText(AnimateScreenCopy.USE_EDITS).performClick()
        compose.onNodeWithText(AnimateScreenCopy.USE_EDITS).assertIsOn()
    }

    // ---- step 2: a part of a video and a file ------------------------------------------------------------

    private fun toClip() {
        click(AnimateScreenCopy.SRC_CLIP)
        clickDescription(AnimateScreenCopy.clipRow("VID_0020.mp4", 12_000))
        click(AnimateScreenCopy.NEXT)
        stepIs(2, AnimateScreenCopy.STEP_ARRANGE)
    }

    @Test
    fun `a video part has its start, end and rate as numbers with buttons, and shows its length and frames`() {
        compose.setContent { Screen() }
        toClip()
        assertTrue(exists(AnimateScreenCopy.seconds(0)))
        assertTrue(exists(AnimateScreenCopy.seconds(5_000)))
        assertTrue(exists(AnimateScreenCopy.clipLength(5_000)))
        assertTrue(exists(AnimateScreenCopy.clipFrames(60)))
        stepButton(AnimateScreenCopy.laterWords(AnimateScreenCopy.CLIP_START, 1_000)).performClick()
        assertTrue(exists(AnimateScreenCopy.seconds(1_000)))
        assertTrue(exists(AnimateScreenCopy.clipLength(4_000)))
        stepButton(AnimateScreenCopy.laterWords(AnimateScreenCopy.CLIP_END, 100)).performClick()
        assertTrue(exists(AnimateScreenCopy.seconds(5_100)))
        stepButton(AnimateScreenCopy.moreWords(5)).performClick()
        assertTrue(exists(AnimateScreenCopy.fpsValue(17)))
        stepButton(AnimateScreenCopy.fewerWords(1)).performClick()
        assertTrue(exists(AnimateScreenCopy.fpsValue(16)))
    }

    @Test
    fun `the end stops at the end of the video and the start stops short of the end`() {
        compose.setContent { Screen() }
        toClip()
        pressWhileEnabled(AnimateScreenCopy.laterWords(AnimateScreenCopy.CLIP_END, 1_000))
        assertTrue(exists(AnimateScreenCopy.seconds(12_000)))
        stepButton(AnimateScreenCopy.laterWords(AnimateScreenCopy.CLIP_END, 100)).assertIsNotEnabled()
        pressWhileEnabled(AnimateScreenCopy.laterWords(AnimateScreenCopy.CLIP_START, 1_000))
        assertTrue(exists(AnimateScreenCopy.seconds(11_900)))
        stepButton(AnimateScreenCopy.laterWords(AnimateScreenCopy.CLIP_START, 100)).assertIsNotEnabled()
        stepButton(AnimateScreenCopy.earlierWords(AnimateScreenCopy.CLIP_START, 100)).assertIsEnabled()
    }

    @Test
    fun `a file shows its name, whether to keep see-through parts, and repeat`() {
        compose.setContent { Screen() }
        click(AnimateScreenCopy.SRC_FILE)
        clickDescription("party.gif")
        click(AnimateScreenCopy.NEXT)
        stepIs(2, AnimateScreenCopy.STEP_ARRANGE)
        compose.onNodeWithText(AnimateScreenCopy.FILE_PICKED).assertExists()
        compose.onNodeWithText(AnimateScreenCopy.KEEP_SEE_THROUGH).assertIsOn()
        compose.onNodeWithText(AnimateScreenCopy.KEEP_SEE_THROUGH).performClick()
        compose.onNodeWithText(AnimateScreenCopy.KEEP_SEE_THROUGH).assertIsOff()
        compose.onNodeWithText(AnimateScreenCopy.LOOP).assertIsOn()
        // Nothing to order or time: those controls are for photos.
        compose.onNodeWithText(AnimateScreenCopy.MOVE_LEFT).assertDoesNotExist()
    }

    // ---- targets and words ----------------------------------------------------------------------------------

    @Test
    fun `every button on steps one and two is at least 48 dp each way`() {
        compose.setContent { Screen() }
        compose.onNodeWithText(AnimateScreenCopy.NEXT).assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        tick(1, 2, 3)
        click(AnimateScreenCopy.NEXT)
        listOf(AnimateScreenCopy.MOVE_LEFT, AnimateScreenCopy.MOVE_RIGHT, AnimateScreenCopy.REMOVE, AnimateScreenCopy.SAME_FOR_ALL, AnimateScreenCopy.NEXT, AnimateScreenCopy.PREVIEW_MAKE)
            .forEach { compose.onNodeWithText(it).assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp) }
        listOf(
            AnimateScreenCopy.longerWords(500), AnimateScreenCopy.longerWords(100), AnimateScreenCopy.shorterWords(100), AnimateScreenCopy.shorterWords(500),
            AnimateScreenCopy.FASTER, AnimateScreenCopy.SLOWER,
        ).forEach { stepButton(it).assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp) }
        listOf(AnimateScreenCopy.LOOP, AnimateScreenCopy.FADE).forEach { compose.onNodeWithText(it).assertHeightIsAtLeast(48.dp) }
        cell(1, 3).assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
    }

    // ---- step 3: make -----------------------------------------------------------------------------------------

    @Test
    fun `step three offers the three file types with GIF chosen, and the size is a guess it says so`() {
        compose.setContent { Screen() }
        toOutput(1, 2, 3)
        compose.onNodeWithText(AnimateScreenCopy.OUT_GIF).assertIsSelected()
        compose.onNodeWithText(AnimateScreenCopy.OUT_VIDEO).assertIsNotSelected()
        compose.onNodeWithText(AnimateScreenCopy.OUT_APNG).assertIsNotSelected()
        assertTrue(textOf("About ").endsWith("."))
        assertTrue(exists(AnimateCopy.SIZE_IS_A_GUESS))
        assertTrue(exists(AnimateScreenCopy.playInfo(3, 480, 360, 3_000)))
        compose.onAllNodes(hasText("Could be ", substring = true)).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
        // What the planner gave up is told: the photos are bigger than the GIF edge.
        assertTrue(exists(AnimateCopy.planStep(PlanStep.SHRUNK)))
        compose.onNodeWithText(AnimateScreenCopy.BIG_GIF).assertDoesNotExist()
    }

    @Test
    fun `choosing another file type changes the guess, a video is bigger than its GIF edge, and says it plays once`() {
        compose.setContent { Screen() }
        toOutput(1, 2, 3)
        val gifInfo = AnimateScreenCopy.playInfo(3, 480, 360, 3_000)
        assertTrue(exists(gifInfo))
        click(AnimateScreenCopy.OUT_VIDEO)
        compose.onNodeWithText(AnimateScreenCopy.OUT_VIDEO).assertIsSelected()
        compose.onNodeWithText(AnimateScreenCopy.OUT_GIF).assertIsNotSelected()
        assertFalse(exists(gifInfo))
        assertTrue(exists(AnimateScreenCopy.playInfo(3, 640, 480, 3_000)))
        assertTrue(exists(AnimateScreenCopy.VIDEO_PLAYS_ONCE))
        click(AnimateScreenCopy.OUT_APNG)
        assertTrue(exists(gifInfo))
        assertFalse(exists(AnimateScreenCopy.VIDEO_PLAYS_ONCE))
    }

    @Test
    fun `a long GIF carries a warning, and the same frames as a video or APNG do not`() {
        compose.setContent { Screen() }
        toArrange(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        compose.onNodeWithText(AnimateScreenCopy.FADE).performClick()
        click(AnimateScreenCopy.NEXT)
        compose.onNodeWithText(AnimateScreenCopy.BIG_GIF).assertExists()
        click(AnimateScreenCopy.OUT_VIDEO)
        compose.onNodeWithText(AnimateScreenCopy.BIG_GIF).assertDoesNotExist()
        click(AnimateScreenCopy.OUT_GIF)
        compose.onNodeWithText(AnimateScreenCopy.BIG_GIF).assertExists()
    }

    @Test
    fun `an existing file has no size guess, only what it is now`() {
        compose.setContent { Screen(assets = library.map { if (it.id.value == 30L) it.copy(sizeBytes = 2_500_000) else it }) }
        click(AnimateScreenCopy.SRC_FILE)
        clickDescription("party.gif")
        click(AnimateScreenCopy.NEXT)
        click(AnimateScreenCopy.NEXT)
        compose.onNodeWithText(AnimateScreenCopy.SIZE_UNKNOWN).assertExists()
        compose.onNodeWithText(AnimateScreenCopy.sourceNow(2_500_000)).assertExists()
    }

    @Test
    fun `without a video encoder the video choice is off and says why`() {
        host.encoder = false
        compose.setContent { Screen() }
        toOutput(1, 2)
        waitFor { runCatching { compose.onNodeWithText(AnimateScreenCopy.OUT_VIDEO).assertIsNotEnabled() }.isSuccess }
        compose.onNodeWithText(AnimateCopy.NO_VIDEO_ENCODER).assertExists()
        compose.onNodeWithText(AnimateScreenCopy.SAVE).assertIsEnabled()
    }

    @Test
    fun `Save and the file type choices are at least 48 dp`() {
        compose.setContent { Screen() }
        toOutput(1, 2)
        listOf(AnimateScreenCopy.SAVE, AnimateScreenCopy.OUT_GIF, AnimateScreenCopy.OUT_VIDEO, AnimateScreenCopy.OUT_APNG, AnimateScreenCopy.PREVIEW_MAKE)
            .forEach { compose.onNodeWithText(it).assertHeightIsAtLeast(48.dp) }
    }

    // ---- saving ------------------------------------------------------------------------------------------------------

    @Test
    fun `Save makes the file as a job, says it is saved, and Open shows the new picture`() {
        compose.setContent { Screen(assets = library + saved) }
        toOutput(1, 2, 3)
        click(AnimateScreenCopy.SAVE)
        waitForText(AnimateCopy.SAVED)
        assertEquals(1, sink.inner.published.size)
        assertEquals(AnimOutput.GIF, sink.inner.published.single().output)
        assertEquals(name(1), sink.inner.published.single().sourceName)
        compose.onNodeWithText(AnimateScreenCopy.SAVED_NOTE).assertExists()
        click(AnimateScreenCopy.OPEN)
        assertEquals(listOf(900L), opened.map { it.id.value })
        // The job is gone and Save is free again.
        waitFor { runner.jobs.value.isEmpty() }
        compose.onNodeWithText(AnimateScreenCopy.SAVE).assertIsEnabled()
    }

    @Test
    fun `a saved file the library does not list yet says it will, and has no Open`() {
        compose.setContent { Screen() }
        toOutput(1, 2)
        click(AnimateScreenCopy.SAVE)
        waitForText(AnimateCopy.SAVED)
        compose.onNodeWithText(AnimateScreenCopy.NOT_LISTED).assertExists()
        compose.onNodeWithText(AnimateScreenCopy.OPEN).assertDoesNotExist()
    }

    @Test
    fun `the chosen type, the times and the order reach the job`() {
        compose.setContent { Screen(assets = library + saved) }
        toArrange(3, 1)
        cell(2, 2).performClick()
        stepButton(AnimateScreenCopy.longerWords(AnimateDraft.HOLD_COARSE_MS)).performClick()
        click(AnimateScreenCopy.NEXT)
        click(AnimateScreenCopy.OUT_APNG)
        click(AnimateScreenCopy.SAVE)
        waitForText(AnimateCopy.SAVED)
        val out = sink.inner.published.single()
        assertEquals(AnimOutput.APNG, out.output)
        assertEquals(name(3), out.sourceName)
        val decoded = ApngReader.decodeAll(out.bytes).sequence!!
        assertEquals(listOf(1000, 1500), decoded.frames.map { it.delayMs })
    }

    @Test
    fun `a video is made through the video maker`() {
        compose.setContent { Screen(assets = library + saved) }
        toOutput(1, 2)
        click(AnimateScreenCopy.OUT_VIDEO)
        click(AnimateScreenCopy.SAVE)
        waitForText(AnimateCopy.SAVED)
        assertEquals(1, maker.made.size)
        assertEquals(AnimOutput.VIDEO, sink.inner.published.single().output)
    }

    @Test
    fun `a photo locked before Save is refused with the job's sentence and nothing is saved`() {
        // The refusal comes before any photo is read, so this host takes its time over the question (see the scope above).
        hostOverride = object : AnimateHost by host {
            override fun mayExport(asset: MediaAsset): Boolean {
                Thread.sleep(JOB_STARTS_MS)
                return host.mayExport(asset)
            }
        }
        compose.setContent { Screen() }
        toOutput(1, 2, 3)
        host.protectedIds += 2L
        click(AnimateScreenCopy.SAVE)
        waitForText(AnimateCopy.PROTECTED)
        assertTrue(sink.inner.published.isEmpty())
        assertTrue(host.stillCalls.isEmpty())
        compose.onNodeWithText(AnimateScreenCopy.SAVE).assertIsEnabled()
    }

    @Test
    fun `a photo locked while the file is made stops it and nothing is saved`() {
        compose.setContent { Screen() }
        toOutput(1, 2, 3)
        host.onStill = { asset, _ -> if (asset.id.value == 1L) host.protectedIds += 3L }
        click(AnimateScreenCopy.SAVE)
        waitForText(AnimateCopy.PROTECTED)
        assertTrue(sink.inner.published.isEmpty())
        assertTrue(sink.inner.leftovers().isEmpty())
    }

    @Test
    fun `progress and Cancel show while it is made, and Cancel leaves nothing`() {
        val gate = CompletableDeferred<Unit>()
        host.onStill = { _, _ -> runBlocking { gate.await() } }
        compose.setContent { Screen() }
        toOutput(1, 2, 3)
        click(AnimateScreenCopy.SAVE)
        waitFor { exists(AnimateScreenCopy.MAKING) }
        compose.onNodeWithText(AnimateScreenCopy.SAVE).assertIsNotEnabled()
        compose.onNodeWithText(AnimateScreenCopy.LEAVING_CANCELS).assertExists()
        click(AnimateScreenCopy.CANCEL)
        gate.complete(Unit)
        waitForText(AnimateScreenCopy.CANCELLED)
        waitFor { runner.jobs.value.isEmpty() }
        assertTrue(sink.inner.published.isEmpty())
        assertTrue(sink.inner.leftovers().isEmpty())
        compose.onNodeWithText(AnimateScreenCopy.SAVE).assertIsEnabled()
    }

    @Test
    fun `leaving the screen stops a save in progress`() {
        val gate = CompletableDeferred<Unit>()
        host.onStill = { _, _ -> runBlocking { gate.await() } }
        var show by mutableStateOf(true)
        compose.setContent { if (show) Screen() }
        toOutput(1, 2)
        click(AnimateScreenCopy.SAVE)
        waitFor { runner.jobs.value.isNotEmpty() }
        compose.runOnUiThread { show = false }
        gate.complete(Unit)
        waitFor { runner.jobs.value.isEmpty() }
        assertTrue(sink.inner.published.isEmpty())
    }

    @Test
    fun `saving is not offered where there is no job list`() {
        compose.setContent { Screen(runner = null) }
        toOutput(1, 2)
        compose.onNodeWithText(AnimateScreenCopy.SAVE).assertIsNotEnabled()
    }

    @Test
    fun `a failure is the job's sentence`() {
        sink.inner.failWith = java.io.IOException("disk")
        compose.setContent { Screen() }
        toOutput(1, 2)
        click(AnimateScreenCopy.SAVE)
        waitForText(AnimateCopy.SAVE_FAILED)
        assertTrue(sink.inner.published.isEmpty())
    }

    // ---- the preview -----------------------------------------------------------------------------------------------

    /** With the test clock held, wait for the preview's player by stepping frames, as a frame loop is driven only by the clock. */
    private fun waitForPlayerByFrames() {
        val end = System.currentTimeMillis() + 60_000
        while (compose.onAllNodes(hasTestTag(ANIM_PLAYER_TAG)).fetchSemanticsNodes().isEmpty()) {
            check(System.currentTimeMillis() < end) { "the preview never appeared" }
            compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    private fun playerState(): String? = compose.onNodeWithTag(ANIM_PLAYER_TAG).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    @Test
    fun `a preview is made on request, plays in place, and Pause stops it on a frame it names`() {
        compose.setContent { Screen() }
        toArrange(1, 2, 3)
        // Held from here: the player's frame loop moves only when the test says time has passed.
        compose.mainClock.autoAdvance = false
        click(AnimateScreenCopy.PREVIEW_MAKE)
        waitForPlayerByFrames()
        compose.onNodeWithText(AnimateScreenCopy.PREVIEW_ROUGH).assertExists()
        compose.onNodeWithText(AnimateScreenCopy.PREVIEW_PAUSE).assertExists()
        compose.onNodeWithContentDescription(AnimateScreenCopy.PREVIEW_DESCRIPTION).assertExists()
        assertEquals("a playing preview names no frame, so a screen reader is not read each one", null, playerState())
        // A second and a half in, with a second to a photo: the second photo is up.
        compose.mainClock.advanceTimeBy(1_500)
        click(AnimateScreenCopy.PREVIEW_PAUSE)
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText(AnimateScreenCopy.PREVIEW_PLAY).assertExists()
        assertEquals(AnimateScreenCopy.previewFrame(2, 3), playerState())
        // Paused, time passing changes nothing.
        compose.mainClock.advanceTimeBy(5_000)
        assertEquals(AnimateScreenCopy.previewFrame(2, 3), playerState())
        click(AnimateScreenCopy.PREVIEW_PLAY)
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText(AnimateScreenCopy.PREVIEW_PAUSE).assertExists()
        // And it loops: at about 3.2 seconds, past the end of the third photo, the first is up again.
        compose.mainClock.advanceTimeBy(1_700)
        click(AnimateScreenCopy.PREVIEW_PAUSE)
        compose.mainClock.advanceTimeByFrame()
        assertEquals(AnimateScreenCopy.previewFrame(1, 3), playerState())
    }

    @Test
    fun `with animations turned off the preview starts paused on its first frame and says why`() {
        compose.setContent { Screen(animatorScale = 0f) }
        toArrange(1, 2, 3)
        click(AnimateScreenCopy.PREVIEW_MAKE)
        waitFor { compose.onAllNodes(hasTestTag(ANIM_PLAYER_TAG)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(AnimateScreenCopy.PREVIEW_PLAY).assertExists()
        compose.onNodeWithText(AnimateScreenCopy.PREVIEW_MOTION_OFF).assertExists()
        assertEquals(AnimateScreenCopy.previewFrame(1, 3), compose.onNodeWithTag(ANIM_PLAYER_TAG).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
        compose.mainClock.advanceTimeBy(5_000)
        assertEquals(AnimateScreenCopy.previewFrame(1, 3), compose.onNodeWithTag(ANIM_PLAYER_TAG).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
    }

    @Test
    fun `changing a choice takes the preview away instead of showing a stale one`() {
        compose.setContent { Screen(animatorScale = 0f) }
        toArrange(1, 2, 3)
        click(AnimateScreenCopy.PREVIEW_MAKE)
        waitFor { compose.onAllNodes(hasTestTag(ANIM_PLAYER_TAG)).fetchSemanticsNodes().isNotEmpty() }
        stepButton(AnimateScreenCopy.longerWords(AnimateDraft.HOLD_FINE_MS)).performClick()
        compose.onAllNodes(hasTestTag(ANIM_PLAYER_TAG)).fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
        compose.onNodeWithText(AnimateScreenCopy.PREVIEW_MAKE).assertExists()
    }

    @Test
    fun `a choice that does not change the picture keeps the preview`() {
        compose.setContent { Screen(animatorScale = 0f) }
        toArrange(1, 2, 3)
        click(AnimateScreenCopy.PREVIEW_MAKE)
        waitFor { compose.onAllNodes(hasTestTag(ANIM_PLAYER_TAG)).fetchSemanticsNodes().isNotEmpty() }
        cell(2, 3).performClick()
        compose.onNodeWithText(AnimateScreenCopy.LOOP).performClick()
        compose.onNodeWithTag(ANIM_PLAYER_TAG).assertExists()
    }

    @Test
    fun `a preview of a protected photo gives the sentence and no player`() {
        compose.setContent { Screen(animatorScale = 0f) }
        toArrange(1, 2, 3)
        host.protectedIds += 3L
        click(AnimateScreenCopy.PREVIEW_MAKE)
        waitForText(AnimateCopy.PROTECTED)
        compose.onAllNodes(hasTestTag(ANIM_PLAYER_TAG)).fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
        compose.onNodeWithText(AnimateScreenCopy.PREVIEW_MAKE).assertExists()
    }

    @Test
    fun `a video part and a GIF can be previewed`() {
        compose.setContent { Screen(animatorScale = 0f) }
        toClip()
        click(AnimateScreenCopy.PREVIEW_MAKE)
        waitFor { compose.onAllNodes(hasTestTag(ANIM_PLAYER_TAG)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(host.videos.getValue(20L).times.isNotEmpty())
    }

    // ---- Back --------------------------------------------------------------------------------------------------------------

    @Test
    fun `Back steps from make to arrange to choose, keeping the choices`() {
        compose.setContent { Screen() }
        toOutput(2, 4)
        pressBack()
        stepIs(2, AnimateScreenCopy.STEP_ARRANGE)
        pressBack()
        stepIs(1, AnimateScreenCopy.STEP_SOURCE)
        compose.onNodeWithText(AnimateScreenCopy.chosen(2)).assertExists()
        assertEquals(0, closed)
    }

    @Test
    fun `the header's Back does the same as the system Back`() {
        compose.setContent { Screen() }
        toOutput(2, 4)
        clickDescription(AnimateScreenCopy.BACK)
        stepIs(2, AnimateScreenCopy.STEP_ARRANGE)
        clickDescription(AnimateScreenCopy.BACK)
        stepIs(1, AnimateScreenCopy.STEP_SOURCE)
    }

    @Test
    fun `with nothing picked Back leaves at once, and the screen asks the host to`() {
        compose.setContent { Screen() }
        assertFalse(owner.onBackPressedDispatcher.hasEnabledCallbacks())
        clickDescription(AnimateScreenCopy.BACK)
        assertEquals(1, closed)
    }

    @Test
    fun `with photos picked the first Back that would leave is held and the next leaves`() {
        compose.setContent { Screen() }
        tick(1)
        assertTrue(owner.onBackPressedDispatcher.hasEnabledCallbacks())
        pressBack()
        assertEquals(0, closed)
        compose.onNodeWithText(AnimateScreenCopy.LEAVE_FORGETS).assertExists()
        pressBack()
        assertEquals(1, closed)
    }

    @Test
    fun `a pick after the held Back clears the line`() {
        compose.setContent { Screen() }
        tick(1)
        pressBack()
        compose.onNodeWithText(AnimateScreenCopy.LEAVE_FORGETS).assertExists()
        tick(2)
        compose.onNodeWithText(AnimateScreenCopy.LEAVE_FORGETS).assertDoesNotExist()
    }

    @Test
    fun `while a save runs the first Back that would leave is held with a line saying what leaving does`() {
        val gate = CompletableDeferred<Unit>()
        host.onStill = { _, _ -> runBlocking { gate.await() } }
        compose.setContent { Screen() }
        toOutput(1, 2)
        click(AnimateScreenCopy.SAVE)
        waitFor { runner.jobs.value.isNotEmpty() }
        pressBack()
        pressBack()
        stepIs(1, AnimateScreenCopy.STEP_SOURCE)
        pressBack()
        assertEquals(0, closed)
        pressBack()
        assertEquals(1, closed)
        gate.complete(Unit)
        waitFor { runner.jobs.value.isEmpty() }
    }

    // ---- the library changing under it -----------------------------------------------------------------------------------

    @Test
    fun `a photo locked while the screen is open drops out of the choice`() {
        var assets by mutableStateOf(library)
        compose.setContent { Screen(assets = assets) }
        tick(1, 2, 3)
        compose.onNodeWithText(AnimateScreenCopy.chosen(3)).assertExists()
        compose.runOnUiThread { assets = assets.filter { it.id.value != 2L } }
        compose.waitForIdle()
        compose.onNodeWithText(AnimateScreenCopy.chosen(2)).assertExists()
        assertTrue(compose.onAllNodes(hasContentDescription(name(2))).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `an empty list while the library loads does not forget the choice`() {
        var assets by mutableStateOf(library)
        compose.setContent { Screen(assets = assets) }
        tick(1, 2)
        compose.runOnUiThread { assets = emptyList() }
        compose.waitForIdle()
        compose.runOnUiThread { assets = library }
        compose.waitForIdle()
        compose.onNodeWithText(AnimateScreenCopy.chosen(2)).assertExists()
    }

    @Test
    fun `if the strip falls below two on step two the screen goes back to choosing`() {
        var assets by mutableStateOf(library)
        compose.setContent { Screen(assets = assets) }
        toArrange(1, 2)
        compose.runOnUiThread { assets = assets.filter { it.id.value != 2L } }
        compose.waitForIdle()
        stepIs(1, AnimateScreenCopy.STEP_SOURCE)
        compose.onNodeWithText(AnimateScreenCopy.NEED_TWO).assertExists()
    }

    // ---- process death --------------------------------------------------------------------------------------------------------

    @Test
    fun `the step, the order, the times and the switches survive the activity being recreated`() {
        val tester = StateRestorationTester(compose)
        tester.setContent { Screen() }
        toArrange(3, 1, 2)
        cell(2, 3).performClick()
        stepButton(AnimateScreenCopy.longerWords(AnimateDraft.HOLD_COARSE_MS)).performClick()
        click(AnimateScreenCopy.MOVE_RIGHT)
        compose.onNodeWithText(AnimateScreenCopy.FADE).performClick()
        stepButton(AnimateScreenCopy.FASTER).performClick()
        val before = order(3)
        val holds = (1..3).map { hold(it, 3) }

        tester.emulateSavedInstanceStateRestore()

        stepIs(2, AnimateScreenCopy.STEP_ARRANGE)
        assertEquals(before, order(3))
        assertEquals(holds, (1..3).map { hold(it, 3) })
        cell(3, 3).assertIsSelected()
        compose.onNodeWithText(AnimateScreenCopy.FADE).assertIsOn()
        assertTrue(exists(AnimateScreenCopy.speedValue(125)))
        assertTrue("and Back still has its step", owner.onBackPressedDispatcher.hasEnabledCallbacks())
    }

    @Test
    fun `a video part and the make step survive too, and a preview or result does not`() {
        val tester = StateRestorationTester(compose)
        tester.setContent { Screen(animatorScale = 0f) }
        toClip()
        stepButton(AnimateScreenCopy.laterWords(AnimateScreenCopy.CLIP_START, 1_000)).performClick()
        click(AnimateScreenCopy.PREVIEW_MAKE)
        waitFor { compose.onAllNodes(hasTestTag(ANIM_PLAYER_TAG)).fetchSemanticsNodes().isNotEmpty() }
        click(AnimateScreenCopy.NEXT)
        click(AnimateScreenCopy.OUT_VIDEO)
        click(AnimateScreenCopy.SAVE)
        waitForText(AnimateCopy.SAVED)

        tester.emulateSavedInstanceStateRestore()

        stepIs(3, AnimateScreenCopy.STEP_OUTPUT)
        compose.onNodeWithText(AnimateScreenCopy.OUT_VIDEO).assertIsSelected()
        compose.onNodeWithText(AnimateCopy.SAVED).assertDoesNotExist()
        pressBack()
        assertTrue(exists(AnimateScreenCopy.seconds(1_000)))
        assertTrue("the preview is not kept", compose.onAllNodes(hasTestTag(ANIM_PLAYER_TAG)).fetchSemanticsNodes().isEmpty())
    }
}
