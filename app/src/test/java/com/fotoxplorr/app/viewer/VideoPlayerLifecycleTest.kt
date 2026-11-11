package com.fotoxplorr.app.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.render.ViewerFixtures
import com.fotoxplorr.app.ui.FotoXplorrTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A [VideoPlayer] never arms -- never builds an ExoPlayer, never takes audio focus -- while the
 * host is stopped. The lifecycle is a hand-driven owner so "Home was pressed" is exact: the ON_STOP
 * pause in [VideoPlayer] only acts on a player that already exists, so the gate has to be on the
 * arming itself. The armed player's presence is observed through the key-moment button, which only
 * the armed player draws.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class VideoPlayerLifecycleTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun decodeSynchronously() {
        ViewerFixtures.installSynchronousImageLoader(context)
    }

    private class TestOwner(initial: Lifecycle.State) : LifecycleOwner {
        private val registry = LifecycleRegistry(this).also { it.currentState = initial }
        override val lifecycle: Lifecycle get() = registry
        fun moveTo(state: Lifecycle.State) { registry.currentState = state }
    }

    private fun video(): MediaAsset =
        ViewerFixtures.referencePhoto(context).copy(
            displayName = "clip.mp4",
            mimeType = "video/mp4",
            durationMillis = 64_000L,
        )

    private fun setContentIn(owner: LifecycleOwner) {
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                FotoXplorrTheme(GalleryPreferencesState()) {
                    Box(Modifier.fillMaxSize().background(Color.Black)) {
                        VideoPlayer(asset = video(), autoplayVideos = true, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
    }

    private fun assertArmed(armed: Boolean) {
        val nodes = compose.onAllNodesWithContentDescription("Mark this as a key moment")
        if (armed) compose.onNodeWithContentDescription("Mark this as a key moment").assertExists()
        else nodes.assertCountEquals(0)
    }

    @Test
    fun `a video composed into a stopped host stays a poster until the host starts`() {
        val owner = TestOwner(Lifecycle.State.CREATED)
        setContentIn(owner)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(ARMED_START_DELAY_MS * 4)
        compose.waitForIdle()
        assertArmed(false)

        compose.runOnUiThread { owner.moveTo(Lifecycle.State.RESUMED) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(ARMED_START_DELAY_MS * 2)
        compose.waitForIdle()
        assertArmed(true)
    }

    /**
     * Runs the clock frame by frame, the way a device does, so a recomposition (here: the arming
     * effect being cancelled or restarted by a lifecycle change) lands BEFORE the delay it
     * concerns elapses. One big `advanceTimeBy` would fire the delay first.
     */
    private fun runFrames(ms: Long) {
        compose.waitForIdle()
        repeat((ms / 16L).toInt() + 1) { compose.mainClock.advanceTimeByFrame() }
        compose.waitForIdle()
    }

    @Test
    fun `Home pressed inside the arming delay cancels the arming`() {
        val owner = TestOwner(Lifecycle.State.RESUMED)
        compose.mainClock.autoAdvance = false
        setContentIn(owner)
        runFrames(ARMED_START_DELAY_MS / 2)
        compose.runOnUiThread { owner.moveTo(Lifecycle.State.CREATED) }
        runFrames(ARMED_START_DELAY_MS * 4)
        assertArmed(false)

        compose.runOnUiThread { owner.moveTo(Lifecycle.State.RESUMED) }
        runFrames(ARMED_START_DELAY_MS * 2)
        assertArmed(true)
    }
}
