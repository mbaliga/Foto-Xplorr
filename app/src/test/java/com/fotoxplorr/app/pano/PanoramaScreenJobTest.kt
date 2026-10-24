package com.fotoxplorr.app.pano

import android.os.Looper
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.jobs.JobRunner
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.ui.FotoXplorrTheme
import com.fotoxplorr.core.model.MediaId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.IOException
import kotlin.math.abs

/**
 * The panorama screen with the REAL job behind it (aligner, layout, exposure, compositor, preview
 * canvas) over procedural photos: Preview, the size it shows, Save as a job on the app's runner, a photo
 * that does not join, a protected photo, Cancel, and leaving the screen mid-save. Only the phone is
 * faked ([FakeHost], [FakeSink]); nothing here is a photograph, and nothing says how it looks.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h2400dp-xhdpi")
class PanoramaScreenJobTest {

    @get:Rule
    val compose = createComposeRule()

    private class BackOwner : OnBackPressedDispatcherOwner {
        private val registry = LifecycleRegistry.createUnsafe(this).also { it.currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
        override val onBackPressedDispatcher = OnBackPressedDispatcher()
    }

    private val owner = BackOwner()
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val runner = JobRunner(scope)

    @After
    fun tearDown() = scope.cancel()

    @Composable
    private fun Screen(
        assets: List<MediaAsset>,
        host: FakeHost,
        sink: FakeSink,
        opened: MutableList<MediaAsset>? = null,
    ) {
        FotoXplorrTheme(GalleryPreferencesState()) {
            CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides owner) {
                PanoramaScreen(
                    assets = assets,
                    newHost = { host },
                    newSink = { sink },
                    runner = runner,
                    onOpenAsset = { asset, _ -> opened?.add(asset) },
                    onClose = {},
                )
            }
        }
    }

    private fun waitFor(timeoutMillis: Long = 120_000, condition: () -> Boolean) =
        compose.waitUntil(timeoutMillis) { shadowOf(Looper.getMainLooper()).idle(); condition() }

    private fun waitForText(text: String) =
        waitFor { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    private fun pickPhotos(rig: CylinderRig, vararg indices: Int) {
        indices.forEach { compose.onNodeWithContentDescription(rig.asset(it).displayName).performClick() }
        compose.onNodeWithText(PanoCopy.NEXT).performClick()
    }

    private fun textOf(prefix: String): String {
        val nodes = compose.onAllNodes(hasText(prefix, substring = true)).fetchSemanticsNodes()
        return nodes.first().config[SemanticsProperties.Text].first().text
    }

    // ---- Preview -----------------------------------------------------------------------------------

    @Test
    fun `Preview joins the photos on a small canvas, shows the picture, and saves nothing`() {
        val rig = CylinderRig(count = 3)
        val host = rigHost(rig)
        val sink = FakeSink()
        compose.setContent { Screen(rig.assets(), host, sink) }
        pickPhotos(rig, 0, 1, 2)

        compose.onNodeWithText(PanoCopy.PREVIEW).performClick()
        waitFor { compose.onAllNodesWithContentDescription(PanoCopy.PREVIEW_DESCRIPTION).fetchSemanticsNodes().isNotEmpty() }

        assertTrue("nothing was written", sink.published.isEmpty() && sink.canvases.isEmpty())
        assertTrue("and no job was put on the list", runner.jobs.value.isEmpty())
        compose.onNodeWithText(PanoCopy.PREVIEW).assertExists()
        // The size line now says "about", not "up to".
        assertTrue(textOf("About").endsWith("pixels."))
        compose.onAllNodes(hasText(PanoCopy.upToSize(PanoGeometry.DEFAULT_MAX_PIXELS))).fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
    }

    @Test
    fun `changing the order throws the preview away, because it was made for the old order`() {
        val rig = CylinderRig(count = 3)
        compose.setContent { Screen(rig.assets(), rigHost(rig), FakeSink()) }
        pickPhotos(rig, 0, 1, 2)
        compose.onNodeWithText(PanoCopy.PREVIEW).performClick()
        waitFor { compose.onAllNodesWithContentDescription(PanoCopy.PREVIEW_DESCRIPTION).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithContentDescription(PanoCopy.moveRight(1)).performClick()

        assertTrue(compose.onAllNodesWithContentDescription(PanoCopy.PREVIEW_DESCRIPTION).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText(PanoCopy.upToSize(PanoGeometry.DEFAULT_MAX_PIXELS)).assertExists()
    }

    @Test
    fun `a photo that does not overlap is named in plain words and can be removed`() {
        // Photos 1 and 2 are one scene; 3 is another place altogether, so the break is before it.
        val a = CylinderRig(count = 2, seed = 5)
        val b = CylinderRig(count = 2, seed = 6)
        val host = FakeHost(List(3) { a.asset(it) }, { PanoSize(640, 480) }, { 28 }) { i, w, h ->
            if (i < 2) a.render(i, w, h) else b.render(0, w, h)
        }
        compose.setContent { Screen(a.assets() + a.asset(2), host, FakeSink()) }
        pickPhotos(a, 0, 1, 2)

        compose.onNodeWithText(PanoCopy.PREVIEW).performClick()
        waitForText("Photo 3 doesn't overlap enough.")

        compose.onNodeWithText(PanoCopy.remove(3)).performClick()

        assertTrue(compose.onAllNodes(hasText("Photo 3 doesn't overlap enough.")).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithContentDescription(PanoCopy.photoInStrip(2, a.asset(1).displayName)).assertExists()
        compose.onNodeWithContentDescription(PanoCopy.photoInStrip(3, a.asset(2).displayName)).assertDoesNotExist()
        // Two photos are left, so Preview works again and joins them.
        compose.onNodeWithText(PanoCopy.PREVIEW).performClick()
        waitFor { compose.onAllNodesWithContentDescription(PanoCopy.PREVIEW_DESCRIPTION).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `a protected photo is refused before a pixel is read`() {
        val rig = CylinderRig(count = 3)
        val host = rigHost(rig)
        host.protectedIds += rig.asset(1).id
        compose.setContent { Screen(rig.assets(), host, FakeSink()) }
        pickPhotos(rig, 0, 1, 2)

        compose.onNodeWithText(PanoCopy.PREVIEW).performClick()
        waitForText(PanoCopy.PROTECTED)

        assertTrue("no pixel and no tag was read", host.calls.isEmpty() && host.focalReads.get() == 0)
        assertTrue(compose.onAllNodesWithContentDescription(PanoCopy.PREVIEW_DESCRIPTION).fetchSemanticsNodes().isEmpty())
    }

    // ---- Save --------------------------------------------------------------------------------------

    @Test
    fun `the size shown after Preview is about the size Save then makes`() {
        // 1000 x 750 photos spread over about 2240 x 770: more than the preview's budget, so the preview
        // is drawn at a fraction of full size and the figure has to be scaled back up.
        val rig = CylinderRig(photoW = 1000, photoH = 750, count = 3, step = 620)
        val host = rigHost(rig)
        val sink = FakeSink()
        compose.setContent { Screen(rig.assets(), host, sink) }
        pickPhotos(rig, 0, 1, 2)

        compose.onNodeWithText(PanoCopy.PREVIEW).performClick()
        waitFor { compose.onAllNodesWithContentDescription(PanoCopy.PREVIEW_DESCRIPTION).fetchSemanticsNodes().isNotEmpty() }
        val previewedAt = host.calls.last { it.index == 0 }.limits.maxLongEdge
        assertTrue("the preview really was drawn smaller than the photo ($previewedAt)", previewedAt < 1000)
        val numbers = Regex("\\d+").findAll(textOf("About")).map { it.value.toInt() }.toList()
        assertEquals(2, numbers.size)

        compose.onNodeWithText(PanoCopy.SAVE).performClick()
        waitForText(PanoCopy.SAVED)

        val crop = sink.published.single().crop
        assertTrue("width ${numbers[0]} vs ${crop.width}", abs(numbers[0] - crop.width) <= crop.width * 0.03)
        assertTrue("height ${numbers[1]} vs ${crop.height}", abs(numbers[1] - crop.height) <= crop.height * 0.03)
    }

    @Test
    fun `Save runs as a job on the app's list, says it is saved, and opens the new picture in the gallery`() {
        val rig = CylinderRig(count = 3)
        val host = rigHost(rig)
        val sink = FakeSink()
        // The gallery already lists the file the fake sink will "create": row id 1.
        val saved = rig.asset(0).copy(id = MediaId(1), displayName = "IMG_0001-panorama.jpg")
        val opened = mutableListOf<MediaAsset>()
        val seen = mutableListOf<String>()
        scope.launch { runner.outcomes.collect { seen += it.title } }
        compose.setContent { Screen(rig.assets() + saved, host, sink, opened) }
        pickPhotos(rig, 0, 1, 2)

        compose.onNodeWithText(PanoCopy.SAVE).performClick()
        waitForText(PanoCopy.SAVED)

        assertEquals(listOf(PanoCopy.JOB_TITLE), seen)
        assertEquals("saved once", 1, sink.published.size)
        assertEquals("dated and named after the first photo", rig.asset(0).dateTakenMillis, sink.published.single().dateTakenMillis)
        assertEquals(rig.asset(0).displayName, sink.published.single().sourceName)
        assertTrue("the list is empty again", runner.jobs.value.isEmpty())

        compose.onNodeWithText(PanoCopy.OPEN).performClick()
        assertEquals(listOf(saved), opened)
    }

    @Test
    fun `until the gallery lists the new picture the screen says so instead of offering a dead button`() {
        val rig = CylinderRig(count = 3)
        compose.setContent { Screen(rig.assets(), rigHost(rig), FakeSink()) }
        pickPhotos(rig, 0, 1, 2)
        compose.onNodeWithText(PanoCopy.SAVE).performClick()
        waitForText(PanoCopy.SAVED)
        compose.onNodeWithText(PanoCopy.NOT_LISTED_YET).assertExists()
        compose.onNodeWithText(PanoCopy.OPEN).assertDoesNotExist()
    }

    @Test
    fun `a failure of the save is said in the job's own sentence`() {
        val rig = CylinderRig(count = 3)
        val sink = FakeSink().also { it.failWith = IOException("disk full") }
        compose.setContent { Screen(rig.assets(), rigHost(rig), sink) }
        pickPhotos(rig, 0, 1, 2)
        compose.onNodeWithText(PanoCopy.SAVE).performClick()
        waitForText(PanoCopy.SAVE_FAILED)
        assertTrue("none of the exception's text reaches the person", compose.onAllNodes(hasText("disk full", substring = true)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `Cancel stops a save that is running and leaves nothing`() {
        val rig = CylinderRig(count = 3)
        val host = rigHost(rig)
        host.onDecode = { _, _ -> Thread.sleep(150) }
        val sink = FakeSink()
        compose.setContent { Screen(rig.assets(), host, sink) }
        pickPhotos(rig, 0, 1, 2)
        compose.onNodeWithText(PanoCopy.SAVE).performClick()
        waitFor(20_000) { runner.jobs.value.isNotEmpty() }
        waitForText(PanoCopy.SAVING)

        compose.onNodeWithText(PanoCopy.CANCEL).performClick()
        waitForText(PanoCopy.CANCELLED)

        assertTrue(runner.jobs.value.isEmpty())
        assertTrue("nothing was published", sink.published.isEmpty())
        assertTrue("and every canvas was handed back", sink.canvases.all { it.released })
    }

    @Test
    fun `leaving the screen stops a save that is running`() {
        val rig = CylinderRig(count = 3)
        val host = rigHost(rig)
        host.onDecode = { _, _ -> Thread.sleep(150) }
        val sink = FakeSink()
        var shown by mutableStateOf(true)
        compose.setContent { if (shown) Screen(rig.assets(), host, sink) }
        pickPhotos(rig, 0, 1, 2)
        compose.onNodeWithText(PanoCopy.SAVE).performClick()
        waitFor(20_000) { runner.jobs.value.isNotEmpty() }

        compose.runOnUiThread { shown = false }
        compose.waitForIdle()
        waitFor(20_000) { runner.jobs.value.isEmpty() }

        assertTrue("nothing was published", sink.published.isEmpty())
        assertNull(runner.jobs.value.firstOrNull())
    }

    @Test
    fun `Save and Preview wait for each other, so two jobs never hold the photos at once`() {
        val rig = CylinderRig(count = 3)
        val host = rigHost(rig)
        host.onDecode = { _, _ -> Thread.sleep(150) }
        compose.setContent { Screen(rig.assets(), host, FakeSink()) }
        pickPhotos(rig, 0, 1, 2)
        compose.onNodeWithText(PanoCopy.SAVE).performClick()
        waitFor(20_000) { runner.jobs.value.isNotEmpty() }
        waitForText(PanoCopy.SAVING)

        compose.onNodeWithText(PanoCopy.PREVIEW).assertIsNotEnabled()
        compose.onNodeWithText(PanoCopy.SAVE).assertIsNotEnabled()
        compose.onNodeWithText(PanoCopy.CANCEL).performClick()
        waitForText(PanoCopy.CANCELLED)
    }
}
