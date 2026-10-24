package com.fotoxplorr.app.pano

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import com.fotoxplorr.app.experimental.ExperimentalScreen
import com.fotoxplorr.app.experimental.builtInExperiments
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.jobs.JobRunner
import com.fotoxplorr.app.media.DecodeLimits
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.spatial.GeoIndexState
import com.fotoxplorr.app.ui.FotoXplorrTheme
import com.fotoxplorr.core.model.MediaId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
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
 * The panorama screen on screen: choosing, the order the strip starts in, the move and remove buttons (the
 * way to reorder with no drag), Back stepping inside the screen before the host closes it, and what
 * survives the activity being recreated or the process killed. The job behind it is covered in
 * [PanoramaJobTest] and, through this screen, in [PanoramaScreenJobTest]. Clicks are injected; how the
 * strip and its insets feel is a device check.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h2400dp-xhdpi")
class PanoramaScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private class BackOwner : OnBackPressedDispatcherOwner {
        private val registry = LifecycleRegistry.createUnsafe(this).also { it.currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
        override val onBackPressedDispatcher = OnBackPressedDispatcher()
    }

    private val owner = BackOwner()
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @After
    fun tearDown() = scope.cancel()

    private fun photo(id: Long, name: String, taken: Long) = MediaAsset(
        id = MediaId(id), contentUriString = "content://media/external/images/media/$id", displayName = name,
        mimeType = "image/jpeg", bucketName = null, dateTakenMillis = taken, dateModifiedSeconds = 0,
        width = 400, height = 300, sizeBytes = 1, relativePath = null, isFavorite = false, isTrashed = false,
    )

    // Listed newest first, as the gallery lists them; taken in the order a, b, c, d.
    private val a = photo(1, "a.jpg", 1000)
    private val b = photo(2, "b.jpg", 2000)
    private val c = photo(3, "c.jpg", 3000)
    private val d = photo(4, "d.jpg", 4000)
    private val library = listOf(d, c, b, a)

    private class NoHost : PanoramaHost {
        override fun mayExport(asset: MediaAsset) = true
        override suspend fun decode(asset: MediaAsset, limits: DecodeLimits): PanoDecoded? = null
        override suspend fun focal35mm(asset: MediaAsset): Int? = null
    }

    @Composable
    private fun Screen(
        assets: List<MediaAsset> = library,
        runner: JobRunner? = JobRunner(scope),
        onClose: () -> Unit = {},
    ) {
        FotoXplorrTheme(GalleryPreferencesState()) {
            CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides owner) {
                PanoramaScreen(
                    assets = assets,
                    newHost = { NoHost() },
                    newSink = { FakeSink() },
                    runner = runner,
                    onOpenAsset = { _, _ -> },
                    onClose = onClose,
                )
            }
        }
    }

    private fun pick(vararg names: String) = names.forEach { compose.onNodeWithContentDescription(it).performClick() }

    private fun next() = compose.onNodeWithText(PanoCopy.NEXT).performClick()

    /** The strip is a lazy row: a photo past the edge of the screen is only there once scrolled to. */
    private fun show(position: Int) {
        val prefix = PanoCopy.photoInStrip(position, "").trimEnd()
        compose.onNodeWithTag(STRIP_TAG).performScrollToNode(
            SemanticsMatcher("photo $position in the strip") { node ->
                node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith(prefix) } == true
            },
        )
    }

    private fun strip(position: Int, name: String): SemanticsNodeInteraction {
        show(position)
        return compose.onNodeWithContentDescription(PanoCopy.photoInStrip(position, name))
    }

    /** One of the buttons under photo [position]. */
    private fun button(description: String, position: Int): SemanticsNodeInteraction {
        show(position)
        return compose.onNodeWithContentDescription(description)
    }

    private fun pressBack() = compose.runOnUiThread { owner.onBackPressedDispatcher.onBackPressed() }

    // ---- choosing -------------------------------------------------------------------------------

    @Test
    fun `every still is offered, and Next waits for two`() {
        compose.setContent { Screen() }
        library.forEach { compose.onNodeWithContentDescription(it.displayName).assertExists() }
        compose.onNodeWithText(PanoCopy.chosen(0)).assertExists()
        compose.onNodeWithText(PanoCopy.NEXT).assertIsNotEnabled()

        pick("a.jpg")
        compose.onNodeWithText(PanoCopy.chosen(1)).assertExists()
        compose.onNodeWithText(PanoCopy.NEXT).assertIsNotEnabled()

        pick("b.jpg")
        compose.onNodeWithText(PanoCopy.chosen(2)).assertExists()
        compose.onNodeWithText(PanoCopy.NEXT).assertIsEnabled()

        // Tapping a chosen photo again takes it back out.
        pick("b.jpg")
        compose.onNodeWithText(PanoCopy.chosen(1)).assertExists()
        compose.onNodeWithText(PanoCopy.NEXT).assertIsNotEnabled()
    }

    @Test
    fun `a video and a trashed photo are not offered`() {
        val video = photo(9, "clip.mp4", 5).copy(mimeType = "video/mp4")
        val trashed = photo(8, "gone.jpg", 6).copy(isTrashed = true)
        compose.setContent { Screen(assets = library + video + trashed) }
        compose.onNodeWithContentDescription("clip.mp4").assertDoesNotExist()
        compose.onNodeWithContentDescription("gone.jpg").assertDoesNotExist()
        compose.onNodeWithContentDescription("a.jpg").assertExists()
    }

    @Test
    fun `an empty library says so instead of showing an empty grid`() {
        compose.setContent { Screen(assets = emptyList()) }
        compose.onNodeWithText(PanoCopy.NO_PHOTOS).assertExists()
        compose.onNodeWithText(PanoCopy.NEXT).assertIsNotEnabled()
    }

    // ---- the strip ------------------------------------------------------------------------------

    @Test
    fun `the strip starts in the order taken whatever order the photos were tapped`() {
        compose.setContent { Screen() }
        pick("c.jpg", "a.jpg", "b.jpg")
        next()
        strip(1, "a.jpg").assertExists()
        strip(2, "b.jpg").assertExists()
        strip(3, "c.jpg").assertExists()
        compose.onNodeWithText(PanoCopy.ORDER_HINT).assertExists()
    }

    @Test
    fun `Move right and Move left reorder the strip with no drag, and stop at the ends`() {
        compose.setContent { Screen() }
        pick("a.jpg", "b.jpg", "c.jpg")
        next()

        button(PanoCopy.moveLeft(1), 1).assertIsNotEnabled()
        button(PanoCopy.moveRight(3), 3).assertIsNotEnabled()

        button(PanoCopy.moveRight(1), 1).performClick()
        strip(1, "b.jpg").assertExists()
        strip(2, "a.jpg").assertExists()
        strip(3, "c.jpg").assertExists()

        button(PanoCopy.moveLeft(3), 3).performClick()
        strip(2, "c.jpg").assertExists()
        strip(3, "a.jpg").assertExists()
        // The new first and last still stop.
        button(PanoCopy.moveLeft(1), 1).assertIsNotEnabled()
        button(PanoCopy.moveRight(3), 3).assertIsNotEnabled()
    }

    @Test
    fun `an arranged order is kept when another photo is chosen, and Sort by date gives the default back`() {
        compose.setContent { Screen() }
        pick("a.jpg", "b.jpg")
        compose.onNodeWithText(PanoCopy.SORT_BY_DATE).assertDoesNotExist()
        next()
        button(PanoCopy.moveRight(1), 1).performClick()
        strip(1, "b.jpg").assertExists()

        // Back to choosing, add the earliest photo's neighbour: it goes last, not into date order.
        pressBack()
        compose.waitForIdle()
        pick("d.jpg")
        next()
        strip(1, "b.jpg").assertExists()
        strip(2, "a.jpg").assertExists()
        strip(3, "d.jpg").assertExists()

        compose.onNodeWithText(PanoCopy.SORT_BY_DATE).performClick()
        strip(1, "a.jpg").assertExists()
        strip(2, "b.jpg").assertExists()
        strip(3, "d.jpg").assertExists()
        compose.onNodeWithText(PanoCopy.SORT_BY_DATE).assertDoesNotExist()
    }

    @Test
    fun `Remove takes a photo out and closes the gap, and the last removal goes back to choosing`() {
        compose.setContent { Screen() }
        pick("a.jpg", "b.jpg", "c.jpg")
        next()

        button(PanoCopy.remove(2), 2).performClick()
        strip(1, "a.jpg").assertExists()
        strip(2, "c.jpg").assertExists()
        compose.onNodeWithContentDescription(PanoCopy.photoInStrip(3, "c.jpg")).assertDoesNotExist()

        button(PanoCopy.remove(1), 1).performClick()
        // One photo cannot be joined: Preview and Save wait, and the screen says what is needed.
        compose.onNodeWithText(PanoCopy.PREVIEW).assertIsNotEnabled()
        compose.onNodeWithText(PanoCopy.SAVE).assertIsNotEnabled()
        compose.onNodeWithText(PanoCopy.NEED_PHOTOS).assertExists()

        button(PanoCopy.remove(1), 1).performClick()
        compose.onNodeWithText(PanoCopy.PICK_HINT).assertExists()
    }

    @Test
    fun `a photo that has left the library since it was chosen drops out of the strip`() {
        var assets by mutableStateOf(library)
        compose.setContent { Screen(assets = assets) }
        pick("a.jpg", "b.jpg", "c.jpg")
        next()
        strip(2, "b.jpg").assertExists()

        // Locked, trashed or hidden since: the gallery's list no longer has it.
        compose.runOnUiThread { assets = library.filter { it.id != b.id } }
        compose.waitForIdle()

        strip(1, "a.jpg").assertExists()
        strip(2, "c.jpg").assertExists()
        compose.onNodeWithContentDescription(PanoCopy.photoInStrip(3, "c.jpg")).assertDoesNotExist()
    }

    // ---- accessibility ----------------------------------------------------------------------------

    @Test
    fun `every control is at least 48 dp and has a name`() {
        compose.setContent { Screen() }
        compose.onNodeWithContentDescription(PanoCopy.BACK).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText(PanoCopy.NEXT).assertHeightIsAtLeast(48.dp)
        pick("a.jpg", "b.jpg")
        next()
        listOf(PanoCopy.moveLeft(1), PanoCopy.moveRight(1), PanoCopy.remove(1), PanoCopy.moveLeft(2), PanoCopy.moveRight(2), PanoCopy.remove(2))
            .forEach { compose.onNodeWithContentDescription(it).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp) }
        compose.onNodeWithText(PanoCopy.PREVIEW).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText(PanoCopy.SAVE).assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `the size a save is capped at is said before anything is previewed`() {
        compose.setContent { Screen() }
        pick("a.jpg", "b.jpg")
        next()
        compose.onNodeWithText(PanoCopy.upToSize(PanoGeometry.DEFAULT_MAX_PIXELS)).assertExists()
    }

    @Test
    fun `without a job list there is no Save, and Preview is still offered`() {
        compose.setContent { Screen(runner = null) }
        pick("a.jpg", "b.jpg")
        next()
        compose.onNodeWithText(PanoCopy.SAVE).assertDoesNotExist()
        compose.onNodeWithText(PanoCopy.PREVIEW).assertIsEnabled()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w320dp-h2400dp-xhdpi")
    fun `a photo that was moved stays in view on a narrow phone, so it can be moved again`() {
        compose.setContent { Screen() }
        pick("a.jpg", "b.jpg", "c.jpg", "d.jpg")
        next()

        // The strip is inset on both sides, so about one photo shows at a time and the second is cut off. A
        // screen reader activates a button by its action, whether or not it is in view; nothing here scrolls the
        // strip by hand, so the moved photo has to be brought into view by the screen.
        fun activate(description: String) {
            val action = compose.onNodeWithContentDescription(description).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            compose.runOnUiThread { action() }
            compose.waitForIdle()
        }

        fun assertInView(position: Int, name: String) {
            val moved = compose.onNodeWithContentDescription(PanoCopy.photoInStrip(position, name))
            moved.assertExists()
            val strip = compose.onNodeWithTag(STRIP_TAG).fetchSemanticsNode().boundsInRoot
            val item = moved.fetchSemanticsNode().boundsInRoot
            assertTrue("$name at $position is inside the strip: $item in $strip", item.left >= strip.left - 0.5f && item.right <= strip.right + 0.5f)
        }

        activate(PanoCopy.moveRight(2)) // b goes past the edge of what shows
        assertInView(3, "b.jpg")
        activate(PanoCopy.moveRight(3))
        assertInView(4, "b.jpg")
        activate(PanoCopy.moveLeft(4)) // and back, one place at a time
        assertInView(3, "b.jpg")
        activate(PanoCopy.moveLeft(3))
        assertInView(2, "b.jpg")
        activate(PanoCopy.moveLeft(2)) // b is first, and a was the first photo showing: a would scroll b out to the left
        assertInView(1, "b.jpg")
    }

    @Test
    fun `the photo that was moved is announced with its new place, and only that one`() {
        compose.setContent { Screen() }
        pick("a.jpg", "b.jpg", "c.jpg")
        next()
        button(PanoCopy.moveRight(1), 1).performClick()
        compose.waitForIdle()

        val polite = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
        strip(2, "a.jpg").assert(polite)
        strip(1, "b.jpg").assert(!polite)
        strip(3, "c.jpg").assert(!polite)
    }

    @Test
    fun `the number on a chosen photo still reads at the largest font, even with two digits`() {
        val many = List(12) { photo(it + 1L, "p%02d.jpg".format(it + 1), 1000L * (it + 1)) }
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale = 2f)) { Screen(assets = many) }
        }
        many.forEach { compose.onNodeWithContentDescription(it.displayName).performClick() }
        // Photos 10, 11 and 12 are the ones whose number is two digits wide.
        listOf("10", "11", "12").forEach { digits ->
            val node = compose.onAllNodesWithText(digits, useUnmergedTree = true).fetchSemanticsNodes().single()
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            val layout = layouts.single()
            assertEquals("'$digits' is on one line", 1, layout.lineCount)
            assertEquals("'$digits' is not cut off", digits.length, layout.getLineEnd(0, visibleEnd = true))
        }
    }

    // ---- Back ---------------------------------------------------------------------------------------

    @Test
    fun `Back steps from arranging to choosing with the choice kept, then is the host's`() {
        var closed = 0
        compose.setContent { Screen(onClose = { closed++ }) }
        assertFalse("on the first step the screen takes no Back of its own", owner.onBackPressedDispatcher.hasEnabledCallbacks())

        pick("a.jpg", "b.jpg")
        next()
        compose.waitForIdle()
        assertTrue("arranging has a step to go back to", owner.onBackPressedDispatcher.hasEnabledCallbacks())

        pressBack()
        compose.waitForIdle()
        compose.onNodeWithText(PanoCopy.PICK_HINT).assertExists()
        compose.onNodeWithText(PanoCopy.chosen(2)).assertExists()
        assertFalse(owner.onBackPressedDispatcher.hasEnabledCallbacks())
        assertEquals("the screen did not close itself", 0, closed)
    }

    @Test
    fun `the on-screen back arrow does what Back does`() {
        var closed = 0
        compose.setContent { Screen(onClose = { closed++ }) }
        pick("a.jpg", "b.jpg")
        next()
        compose.onNodeWithContentDescription(PanoCopy.BACK).performClick()
        compose.onNodeWithText(PanoCopy.PICK_HINT).assertExists()
        assertEquals(0, closed)
        compose.onNodeWithContentDescription(PanoCopy.BACK).performClick()
        assertEquals("from the first step it closes", 1, closed)
    }

    // ---- inside the Experimental host -----------------------------------------------------------------

    @Composable
    private fun Host(processId: String = "this-process") {
        FotoXplorrTheme(GalleryPreferencesState()) {
            CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides owner) {
                ExperimentalScreen(
                    assets = library,
                    indexInput = emptyList(),
                    geoState = GeoIndexState(),
                    onIndexLocations = {},
                    onOpenAsset = { _, _ -> },
                    entries = builtInExperiments.filter { it.id == "panorama" },
                    processId = processId,
                )
            }
        }
    }

    @Test
    fun `in the host, Back goes arranging then choosing then the hub`() {
        compose.setContent { Host() }
        compose.onNodeWithText(PanoCopy.TITLE).performClick()
        compose.onNodeWithText(PanoCopy.PICK_HINT).assertExists()
        pick("a.jpg", "b.jpg")
        next()
        compose.onNodeWithText(PanoCopy.ORDER_HINT).assertExists()

        pressBack()
        compose.waitForIdle()
        compose.onNodeWithText(PanoCopy.PICK_HINT).assertExists()
        compose.onNodeWithText(PanoCopy.BLURB).assertDoesNotExist()

        pressBack()
        compose.waitForIdle()
        compose.onNodeWithText(PanoCopy.PICK_HINT).assertDoesNotExist()
        compose.onNodeWithText(PanoCopy.BLURB).assertExists()
        assertFalse(owner.onBackPressedDispatcher.hasEnabledCallbacks())
    }

    // ---- what survives -------------------------------------------------------------------------------

    @Test
    fun `the step, the photos and their order survive the activity being recreated`() {
        val tester = StateRestorationTester(compose)
        tester.setContent { Screen() }
        pick("a.jpg", "b.jpg", "c.jpg")
        next()
        button(PanoCopy.moveRight(1), 1).performClick()

        tester.emulateSavedInstanceStateRestore()

        strip(1, "b.jpg").assertExists()
        strip(2, "a.jpg").assertExists()
        strip(3, "c.jpg").assertExists()
        assertTrue("and Back still has its step", owner.onBackPressedDispatcher.hasEnabledCallbacks())
    }

    @Test
    fun `after the process was killed the experiment reopens on the same step with the same photos`() {
        val tester = StateRestorationTester(compose)
        var process = "before"
        tester.setContent { Host(processId = process) }
        compose.onNodeWithText(PanoCopy.TITLE).performClick()
        pick("a.jpg", "c.jpg")
        next()
        button(PanoCopy.moveRight(1), 1).performClick()

        process = "after"
        tester.emulateSavedInstanceStateRestore()

        strip(1, "c.jpg").assertExists()
        strip(2, "a.jpg").assertExists()
        // The preview was not saved: the person gets the "up to" line again, not a size.
        compose.onNodeWithText(PanoCopy.upToSize(PanoGeometry.DEFAULT_MAX_PIXELS)).assertExists()
    }
}
