package com.fotoxplorr.app.composite

import android.content.Context
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.fotoxplorr.app.composite.CompositeTestFiles.asset
import com.fotoxplorr.core.formats.composite.MotionPhotoLayout
import com.fotoxplorr.core.formats.composite.StackBadge
import com.fotoxplorr.core.formats.composite.StereoMode
import com.fotoxplorr.core.model.MediaId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import android.os.Looper
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The Android seam on real files: probing, the clip cache, both eyes of an MPO, the badge and the 3D screen. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class CompositeSeamTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun fileAsset(id: Long, name: String, bytes: ByteArray): com.fotoxplorr.app.media.MediaAsset {
        val file = File(folder.root, name).apply { writeBytes(bytes) }
        return asset(id, name, uri = Uri.fromFile(file).toString())
    }

    @Test
    fun `a motion photo on disk is found and its clip copied byte for byte`() {
        val (bytes, clip) = CompositeTestFiles.microVideo()
        val photo = fileAsset(1, "PXL_1.MP.jpg", bytes)
        val facts = CompositeProbe.facts(context, photo)
        val motion = facts.motion!!
        assertEquals(MotionPhotoLayout.GOOGLE_MICRO_VIDEO, motion.layout)
        val file = MotionClipCache.clipFile(context, photo, motion)!!
        assertArrayEquals(clip, file.readBytes())
        assertTrue(file.path.startsWith(context.cacheDir.path))
        // The original is untouched.
        assertArrayEquals(bytes, File(folder.root, "PXL_1.MP.jpg").readBytes())
    }

    @Test
    fun `an ordinary photo has no composite facts`() {
        val photo = fileAsset(2, "plain.jpg", CompositeTestFiles.jpeg(0xFF00FF00.toInt()))
        assertTrue(CompositeProbe.facts(context, photo).isEmpty)
    }

    @Test
    fun `both eyes of an MPO decode upright and the anaglyph mixes them`() {
        val red = CompositeTestFiles.jpeg(0xFFFF0000.toInt())
        val blue = CompositeTestFiles.jpeg(0xFF0000FF.toInt())
        val photo = fileAsset(3, "stereo.mpo", CompositeTestFiles.mpo(red, blue))
        val pair = CompositeProbe.facts(context, photo).stereo!!
        val eyes = StereoDecoding.decode(context, photo.contentUri, pair)!!
        assertEquals(64, eyes.left.width)
        assertEquals(eyes.left.width, eyes.right.width)
        val leftPixel = eyes.left.getPixel(32, 24)
        val rightPixel = eyes.right.getPixel(32, 24)
        assertTrue((leftPixel shr 16 and 0xFF) > 200 && (leftPixel and 0xFF) < 60)
        assertTrue((rightPixel and 0xFF) > 200 && (rightPixel shr 16 and 0xFF) < 60)
        val blended = StereoDecoding.anaglyph(eyes).getPixel(32, 24)
        assertTrue((blended and 0xFF) > 200)
    }

    @Test
    fun `the badge on a wide tile is a 48 dp button that toggles the stack`() {
        var toggles = 0
        val badge = StackBadge(MediaId(7), 5, expanded = false, members = emptyList())
        compose.setContent {
            Box(Modifier.size(120.dp)) { BurstBadge(badge, onToggle = { toggles++ }, modifier = Modifier.size(120.dp)) }
        }
        compose.onNodeWithTag(burstBadgeTag(MediaId(7))).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, toggles)
    }

    @Test
    fun `the badge on a tiny tile opens through the screen reader action`() {
        var toggles = 0
        val badge = StackBadge(MediaId(8), 3, expanded = true, members = emptyList())
        compose.setContent {
            Box(Modifier.size(40.dp)) { BurstBadge(badge, onToggle = { toggles++ }, modifier = Modifier.size(40.dp)) }
        }
        val node = compose.onNodeWithTag(burstBadgeTag(MediaId(8))).fetchSemanticsNode()
        assertTrue(SemanticsActions.OnClick !in node.config)
        assertEquals("Open", node.config[SemanticsProperties.StateDescription])
        compose.runOnUiThread { node.config[SemanticsActions.CustomActions].single().action() }
        assertEquals(1, toggles)
    }

    @Test
    fun `the badge on a mid tile takes keyboard focus but no touches`() {
        var toggles = 0
        val badge = StackBadge(MediaId(11), 4, expanded = false, members = emptyList())
        compose.setContent {
            Box(Modifier.size(56.dp)) { BurstBadge(badge, onToggle = { toggles++ }, modifier = Modifier.size(56.dp)) }
        }
        val node = compose.onNodeWithTag(burstBadgeTag(MediaId(11)))
        assertTrue(SemanticsActions.OnClick !in node.fetchSemanticsNode().config)
        node.performSemanticsAction(SemanticsActions.RequestFocus)
        node.assertIsFocused()
        node.performKeyInput { pressKey(Key.Enter) }
        node.performKeyInput { pressKey(Key.Spacebar) }
        compose.waitForIdle()
        assertEquals(2, toggles)
    }

    @Test
    fun `the 3D view offers its modes as 48 dp choices and closes`() {
        val photo = fileAsset(4, "stereo.mpo", CompositeTestFiles.mpo(CompositeTestFiles.jpeg(-1), CompositeTestFiles.jpeg(-1)))
        val pair = CompositeProbe.facts(context, photo).stereo!!
        var closed = false
        compose.setContent { StereoScreen(asset = photo, pair = pair, onClose = { closed = true }) }
        waitFor(10_000) {
            compose.onAllNodesWithText(CompositeCopy.STEREO_LOADING).fetchSemanticsNodes().isEmpty()
        }
        for (mode in StereoMode.entries) {
            compose.onNodeWithTag(StereoTags.mode(mode)).assertHeightIsAtLeast(48.dp)
        }
        compose.onNodeWithTag(StereoTags.mode(StereoMode.ANAGLYPH)).performClick().assertIsSelected()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(CompositeCopy.CLOSE_STEREO))).performClick()
        assertTrue(closed)
    }

    @Test
    fun `wiggle is not offered while the system animations are off`() {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        try {
            val photo = fileAsset(5, "stereo.mpo", CompositeTestFiles.mpo(CompositeTestFiles.jpeg(-1), CompositeTestFiles.jpeg(-1)))
            val pair = CompositeProbe.facts(context, photo).stereo!!
            compose.setContent { StereoScreen(asset = photo, pair = pair, onClose = {}) }
            compose.onNodeWithTag(StereoTags.mode(StereoMode.SIDE_BY_SIDE)).assertIsSelected()
            assertEquals(0, compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, StereoTags.mode(StereoMode.WIGGLE))).fetchSemanticsNodes().size)
        } finally {
            Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }
    }

    @Test
    fun `the viewer layer offers play and export for a motion photo and nothing for a plain one`() {
        val (bytes, _) = CompositeTestFiles.microVideo()
        val photo = fileAsset(6, "PXL_6.MP.jpg", bytes)
        compose.setContent {
            Box(Modifier.size(400.dp)) {
                CompositeViewerLayer(asset = photo, chromeShown = true, mayExport = { true }, onOpenStereo = {}, bottomPaddingDp = 0)
            }
        }
        waitFor(10_000) {
            compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, CompositeTags.PLAY)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(CompositeTags.PLAY).assertWidthIsAtLeast(48.dp)
        assertNotNull(compose.onNodeWithTag(CompositeTags.EXPORT_STILL).fetchSemanticsNode())
        assertNotNull(compose.onNodeWithTag(CompositeTags.EXPORT_VIDEO).fetchSemanticsNode())
    }

    @Test
    fun `export buttons are not offered when the photo may not be copied`() {
        val (bytes, _) = CompositeTestFiles.microVideo()
        val photo = fileAsset(7, "PXL_7.MP.jpg", bytes)
        compose.setContent {
            Box(Modifier.size(400.dp)) {
                CompositeViewerLayer(asset = photo, chromeShown = true, mayExport = { false }, onOpenStereo = {}, bottomPaddingDp = 0)
            }
        }
        waitFor(10_000) {
            compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, CompositeTags.PLAY)).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(0, compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, CompositeTags.EXPORT_STILL)).fetchSemanticsNodes().size)
    }

    @Test
    fun `closing the viewer clears every copied clip`() {
        val (bytes, _) = CompositeTestFiles.microVideo()
        val photo = fileAsset(8, "PXL_8.MP.jpg", bytes)
        val clip = MotionClipCache.clipFile(context, photo, CompositeProbe.facts(context, photo).motion!!)!!
        assertTrue(clip.isFile)
        MotionClipCache.clear(context)
        assertTrue(!clip.exists())
        assertEquals(0, File(context.cacheDir, "motion-clips").listFiles()?.size ?: 0)
    }

    @Test
    fun `the viewer's clear runs off the calling thread and outlives it`() {
        val (bytes, _) = CompositeTestFiles.microVideo()
        val photo = fileAsset(9, "PXL_9.MP.jpg", bytes)
        val clip = MotionClipCache.clipFile(context, photo, CompositeProbe.facts(context, photo).motion!!)!!
        val caller = Thread.currentThread()
        var clearedOn: Thread? = null
        val watching = object : android.content.ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getCacheDir(): File = super.getCacheDir().also { clearedOn = Thread.currentThread() }
        }
        val job = MotionClipCache.clearInBackground(watching)
        kotlinx.coroutines.runBlocking { job.join() }
        assertTrue(!clip.exists())
        assertNotNull(clearedOn)
        assertTrue(clearedOn !== caller)
    }

    @Test
    fun `the first probe of a process clears clips an earlier process left`() {
        val stale = File(context.cacheDir, "motion-clips").apply { mkdirs() }.resolve("clip-stale.mp4").apply { writeBytes(ByteArray(10)) }
        MotionClipCache.sweptThisProcess = false
        CompositeProbe.facts(context, fileAsset(10, "plain10.jpg", CompositeTestFiles.jpeg(-1)))
        assertTrue(!stale.exists())
        // Only once: a clip copied afterwards is not swept by the next probe.
        val (bytes, _) = CompositeTestFiles.microVideo()
        val photo = fileAsset(11, "PXL_11.MP.jpg", bytes)
        val clip = MotionClipCache.clipFile(context, photo, CompositeProbe.facts(context, photo).motion!!)!!
        CompositeProbe.facts(context, fileAsset(12, "plain12.jpg", CompositeTestFiles.jpeg(-1)))
        assertTrue(clip.isFile)
    }

    @Test
    fun `a clip still copying when the cache is cleared is thrown away`() {
        val (bytes, _) = CompositeTestFiles.microVideo()
        val photo = fileAsset(13, "PXL_13.MP.jpg", bytes)
        val motion = CompositeProbe.facts(context, photo).motion!!
        MotionClipCache.clear(context)
        assertEquals(null, MotionClipCache.clipFile(context, photo, motion, afterCopy = { MotionClipCache.clear(context) }))
        assertEquals(0, File(context.cacheDir, "motion-clips").listFiles()?.size ?: 0)
        assertTrue(MotionClipCache.becameProtected(before = false, now = true))
        assertTrue(!MotionClipCache.becameProtected(before = true, now = true))
        assertTrue(!MotionClipCache.becameProtected(before = false, now = false))
    }

    @Test
    fun `coming back to the grid draws the last groups in its very first composition`() {
        CompositeGroupsMemory.last = null
        val burst = (1L..3L).map { asset(100 + it, "0000${it}IMG_0000${it}_BURST20240101101010123${if (it == 1L) "_COVER" else ""}.jpg", taken = 1_000L + it) }
        val list = burst + asset(110, "IMG_1.jpg", taken = 90_000L)
        val views = mutableListOf<CompositeView>()
        var shown by androidx.compose.runtime.mutableStateOf(true)
        compose.setContent {
            if (shown) {
                val view = rememberCompositeView(list, list, pairLive = true, groupBursts = true, expanded = emptySet())
                androidx.compose.runtime.SideEffect { views += view }
            }
        }
        waitFor(5_000) { views.lastOrNull()?.visible?.size == 2 }

        shown = false
        compose.waitForIdle()
        views.clear()
        shown = true
        compose.waitForIdle()
        // Not the ungrouped list first and the stack a pass later: the grid's scroll index counts
        // the stacked list, so a first frame of four would land the restored position elsewhere.
        assertEquals(listOf(MediaId(101), MediaId(110)), views.first().visible.map { it.id })
    }

    @Test
    fun `the play button takes keyboard focus and Enter or Space toggles it`() {
        var taps = 0
        compose.setContent {
            MotionButton(playing = false, label = CompositeCopy.PLAY_MOTION, onTap = { taps++ }, onHold = {}, onHover = {})
        }
        val button = compose.onNodeWithTag(CompositeTags.PLAY)
        button.performSemanticsAction(SemanticsActions.RequestFocus)
        button.assertIsFocused()
        button.performKeyInput { pressKey(Key.Enter) }
        button.performKeyInput { pressKey(Key.Spacebar) }
        compose.waitForIdle()
        assertEquals(2, taps)
    }

    // Robolectric's main looper is paused: drain it while waiting, or work posted from a background
    // thread (the composite probe, the clip copy) lands at an arbitrary point and can touch views from
    // the wrong thread. Same helper as the other screen tests.
    private fun waitFor(timeoutMillis: Long, condition: () -> Boolean) =
        compose.waitUntil(timeoutMillis) { shadowOf(Looper.getMainLooper()).idle(); condition() }
}
