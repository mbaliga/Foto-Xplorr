package com.fotoxplorr.app.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.ui.FotoXplorrTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A tap on [MomentScrubber] belongs to the scrubber even before the duration is known. It used to
 * return from its pointer input without consuming, so the tap bubbled to the viewer's
 * tap-to-toggle-chrome detector and hid the very chrome the bar sits on.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class MomentScrubberUnknownDurationTest {

    @get:Rule
    val compose = createComposeRule()

    private fun scrubberUnderTapDetector(durationMs: Long, onSeek: (Long) -> Unit, onOuterTap: () -> Unit) {
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .pointerInput(Unit) { detectTapGestures(onTap = { onOuterTap() }) },
                ) {
                    MomentScrubber(
                        positionMs = 0L,
                        durationMs = durationMs,
                        moments = emptyList(),
                        onSeek = onSeek,
                        modifier = Modifier.align(Alignment.Center).testTag("scrubber"),
                    )
                }
            }
        }
    }

    @Test
    fun `a tap on the bar does not reach the outer tap detector while the duration is unknown`() {
        var outerTaps = 0
        var seeks = 0
        scrubberUnderTapDetector(durationMs = 0L, onSeek = { seeks++ }, onOuterTap = { outerTaps++ })
        compose.onNodeWithTag("scrubber").performTouchInput { click() }
        compose.waitForIdle()
        assertEquals("the tap bubbled up and would hide the chrome", 0, outerTaps)
        assertEquals("no seek against an unknown length", 0, seeks)
    }

    @Test
    fun `a tap on the bar seeks and stays consumed once the duration is known`() {
        var outerTaps = 0
        var seeks = 0
        scrubberUnderTapDetector(durationMs = 20_000L, onSeek = { seeks++ }, onOuterTap = { outerTaps++ })
        compose.onNodeWithTag("scrubber").performTouchInput { click() }
        compose.waitForIdle()
        assertEquals(0, outerTaps)
        assertEquals(1, seeks)
    }
}
