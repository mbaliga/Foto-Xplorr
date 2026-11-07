package com.fotoxplorr.app.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.render.ViewerFixtures
import com.fotoxplorr.app.ui.FotoXplorrTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The strip's echo guard remembers the last index it reported so the host following it is not
 * mistaken for a request to scroll. When the host moves on its own instead (page swipe, slideshow)
 * that memory is stale, and the host coming back to that index was taken for the strip's own echo:
 * the strip never scrolled to it and stayed on a different thumbnail from the photo shown.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class FilmstripSelfReportedTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun decodeSynchronously() {
        ViewerFixtures.installSynchronousImageLoader(context)
    }

    /** One thumbnail plus its gap, in pixels at the test's 2x density. */
    private val stepPx = (FilmstripGeometry.THUMB_WIDTH_DP + FilmstripGeometry.THUMB_GAP_DP) * 2f

    @Test
    fun `the strip follows the host back to the index it last reported`() {
        val roll = ViewerFixtures.roll(context)
        val reported = mutableListOf<MediaAsset>()
        var index by mutableStateOf(2)
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    FilmstripScrubber(
                        assets = roll,
                        currentIndex = index,
                        onSelect = { picked ->
                            reported += picked
                            index = roll.indexOf(picked)
                        },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
        compose.waitForIdle()

        // Scrub forward three thumbnails and let go: the strip reports and remembers the last one.
        compose.onRoot().performTouchInput {
            val y = bottom - 60f
            down(Offset(centerX, y))
            repeat(6) { moveBy(Offset(-stepPx / 2f, 0f)) }
            up()
        }
        compose.waitForIdle()
        val remembered = roll.indexOf(reported.last())
        assertTrue("setup: the scrub should have moved on, got $remembered", remembered > 2)

        // The host wanders off on its own (page swipes, the slideshow) and then comes back to the
        // remembered index. The strip has to follow it there; with a stale echo guard it took the
        // return for the strip's own report and stayed where it was.
        compose.runOnUiThread { index = 0 }
        compose.waitForIdle()
        compose.runOnUiThread { index = remembered }
        compose.waitForIdle()

        // Which thumbnail the strip is centred on: the first thing a slow touch on it reports.
        reported.clear()
        compose.onRoot().performTouchInput {
            down(Offset(centerX, bottom - 60f))
            // Past touch slop, well short of the next thumbnail, and slow enough not to fling.
            moveBy(Offset(-24f, 0f), delayMillis = 600)
            up()
        }
        compose.waitForIdle()
        assertEquals(
            "the strip should have followed the host back to index $remembered",
            remembered,
            reported.firstOrNull()?.let { roll.indexOf(it) },
        )
    }
}
