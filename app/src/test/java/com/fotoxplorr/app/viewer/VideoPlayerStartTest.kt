package com.fotoxplorr.app.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.core.app.ApplicationProvider
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.render.ViewerFixtures
import com.fotoxplorr.app.ui.FotoXplorrTheme
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [VideoPlayer]'s armed start and its catalogue gating, composed for real under Robolectric.
 *
 * What a JVM cannot show -- that frames render, that audio focus is taken, that the screen stays
 * on -- stays a device check. What it CAN show is the contract the filmstrip depends on: a player
 * that has just been composed does nothing until it has settled, and a finger on the strip holds
 * it back; and the controls that write to the catalogue are simply absent for a file this app does
 * not own.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class VideoPlayerStartTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun decodeSynchronously() {
        ViewerFixtures.installSynchronousImageLoader(context)
    }

    private fun video(external: Boolean = false): MediaAsset =
        ViewerFixtures.referencePhoto(context).copy(
            displayName = "clip.mp4",
            mimeType = "video/mp4",
            durationMillis = 64_000L,
            isExternal = external,
        )

    /** The centre glyph and the transport button both describe themselves as "Play". */
    private fun assertPlayOffered() {
        val nodes = compose.onAllNodesWithContentDescription("Play").fetchSemanticsNodes()
        assertTrue("expected the play glyph and the transport button, found ${nodes.size}", nodes.size >= 2)
    }

    @Test
    fun `a freshly composed video is only a poster until it has settled`() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    VideoPlayer(asset = video(), autoplayVideos = false, modifier = Modifier.fillMaxSize())
                }
            }
        }
        compose.mainClock.advanceTimeBy(ARMED_START_DELAY_MS / 2)
        compose.onAllNodesWithContentDescription("Play").assertCountEquals(0)

        compose.mainClock.advanceTimeBy(ARMED_START_DELAY_MS)
        compose.waitForIdle()
        assertPlayOffered()
    }

    @Test
    fun `a finger on the filmstrip holds the start back until it lifts`() {
        var scrubbing by mutableStateOf(true)
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    VideoPlayer(
                        asset = video(),
                        autoplayVideos = false,
                        scrubSuspended = scrubbing,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        // The test clock auto-advances through the arming delay while idle, so "still a poster"
        // here means the delay was never even started, not that it was too short to elapse.
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(ARMED_START_DELAY_MS * 3)
        compose.onAllNodesWithContentDescription("Play").assertCountEquals(0)

        compose.runOnIdle { scrubbing = false }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(ARMED_START_DELAY_MS * 2)
        compose.waitForIdle()
        assertPlayOffered()
    }

    @Test
    fun `a catalogued video offers to mark a key moment`() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    VideoPlayer(asset = video(external = false), autoplayVideos = false, modifier = Modifier.fillMaxSize())
                }
            }
        }
        compose.mainClock.advanceTimeBy(ARMED_START_DELAY_MS * 2)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Mark this as a key moment").assertExists()
    }

    @Test
    fun `an external video never offers a key moment even when catalogue actions are on`() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    // catalogueActions stays at its default (true), as FotoXplorrActivity passes it
                    // for an external asset opened inside the main activity.
                    VideoPlayer(asset = video(external = true), autoplayVideos = false, modifier = Modifier.fillMaxSize())
                }
            }
        }
        compose.mainClock.advanceTimeBy(ARMED_START_DELAY_MS * 2)
        compose.waitForIdle()
        assertPlayOffered()
        compose.onAllNodesWithContentDescription("Mark this as a key moment").assertCountEquals(0)
    }
}
