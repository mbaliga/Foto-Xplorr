package com.fotoxplorr.app.viewer

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import android.content.ClipboardManager
import com.fotoxplorr.app.media.MediaAsset
import androidx.test.core.app.ApplicationProvider
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.palette.PaletteMemo
import com.fotoxplorr.app.palette.PaletteSwatch
import com.fotoxplorr.app.render.ViewerFixtures
import com.fotoxplorr.app.ui.FotoXplorrTheme
import com.fotoxplorr.core.search.ColourSearch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The details room's palette strip against the stored palette it receives: a swatch is a button
 * that searches the library for its colour, the stored palette feeds the map's dot colour through
 * [PaletteMemo], and a room without a search target survives a tap. The real [PhotoDetailRoom]
 * through the real Compose runtime, as the edge-case suite does.
 *
 * The strip is under the camera card whatever Information is doing (#11): the first three tests
 * open Information anyway, the rest leave it at its real default, collapsed -- the case CI never
 * exercised while the palette lived inside the expanded block.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class PhotoDetailRoomPaletteTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val stored = listOf(
        PaletteSwatch(argb = 0xFF1A2B3C.toInt(), proportion = 0.6f),
        PaletteSwatch(argb = 0xFFC03020.toInt(), proportion = 0.4f),
    )

    @Before
    fun decodeSynchronously() {
        ViewerFixtures.installSynchronousImageLoader(context)
        PaletteMemo.clear()
    }

    @After
    fun forgetPalettes() = PaletteMemo.clear()

    @Test
    fun `a stored swatch searches the library for its colour`() {
        val asset = ViewerFixtures.referencePhoto(context)
        var searched: String? = null
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                PhotoDetailRoom(
                    asset = asset,
                    exif = ImageExifDetails(),
                    reveal = { 1f },
                    settled = true,
                    palette = stored,
                    onSearchLibrary = { searched = it },
                    informationInitiallyExpanded = true,
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Search photos coloured ${stored[1].hex}", substring = true)
            .performScrollTo()
            .performClick()
        compose.waitForIdle()

        assertEquals(ColourSearch.colourQuery(stored[1].argb), searched)
        assertTrue("a swatch tap opens a colour: query, got $searched", searched!!.startsWith("colour:#", ignoreCase = true))
        // The stored palette is what the expanded map colours this photo's dot with -- no decode.
        assertEquals(stored[0].argb, PaletteMemo.dominantArgb(asset.id))
    }

    @Test
    fun `a swatch tap with no search target is harmless`() {
        val asset = ViewerFixtures.referencePhoto(context)
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                PhotoDetailRoom(
                    asset = asset,
                    exif = ImageExifDetails(),
                    reveal = { 1f },
                    settled = true,
                    palette = stored,
                    onSearchLibrary = null,
                    informationInitiallyExpanded = true,
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Search photos coloured ${stored[0].hex}", substring = true)
            .performScrollTo()
            .performClick()
        compose.waitForIdle()
        assertEquals(stored[0].argb, PaletteMemo.dominantArgb(asset.id))
    }

    @Test
    fun `an empty stored palette falls back to the room's own decode rather than crashing`() {
        val asset = ViewerFixtures.referencePhoto(context)
        assertNull(PaletteMemo.get(asset.id))
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                PhotoDetailRoom(
                    asset = asset,
                    exif = ImageExifDetails(),
                    reveal = { 1f },
                    settled = true,
                    palette = emptyList(),
                    informationInitiallyExpanded = true,
                )
            }
        }
        compose.waitForIdle()
        // Whatever the fixture decodes to, the memo now holds it or the strip said it could not
        // read the colours; either way composition, layout, draw and the effect ran without throwing.
    }

    @Test
    fun `the strip is on screen with Information collapsed, which is the real default`() {
        val asset = ViewerFixtures.referencePhoto(context)
        room(asset, palette = stored, expanded = false)
        compose.onAllNodesWithContentDescription("Search photos coloured", substring = true).assertCountEquals(stored.size)
        compose.onNodeWithContentDescription("Search photos coloured ${stored[0].hex}", substring = true).assertExists()
    }

    @Test
    fun `expanding Information does not draw the swatches a second time`() {
        val asset = ViewerFixtures.referencePhoto(context)
        room(asset, palette = stored, expanded = true)
        // One strip, not one in the card's panel and one in the Information block.
        compose.onAllNodesWithContentDescription("Search photos coloured", substring = true).assertCountEquals(stored.size)
        compose.onAllNodesWithText("Palette").assertCountEquals(0)
    }

    @Test
    fun `a photo without a stored palette reads its own and shows it with Information collapsed`() {
        val asset = ViewerFixtures.referencePhoto(context)
        room(asset, palette = emptyList(), expanded = false)
        compose.waitUntil(READ_TIMEOUT_MS) {
            compose.onAllNodesWithContentDescription("Search photos coloured", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue("the room's own read went into the memo", PaletteMemo.get(asset.id).orEmpty().isNotEmpty())
    }

    @Test
    fun `a photo that cannot be read says so instead of showing a dash`() {
        val broken = ViewerFixtures.referencePhoto(context).copy(
            contentUriString = "file:///definitely/not/here.jpg",
        )
        room(broken, palette = emptyList(), expanded = false)
        compose.waitUntil(READ_TIMEOUT_MS) {
            compose.onAllNodesWithText("Couldn't read colours").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithContentDescription("Search photos coloured", substring = true).assertCountEquals(0)
        assertNull(PaletteMemo.get(broken.id))
    }

    @Test
    fun `a video gets a strip too, from its stored palette`() {
        val video = ViewerFixtures.referencePhoto(context).copy(
            displayName = "clip.mp4",
            mimeType = "video/mp4",
            durationMillis = 64_000L,
        )
        room(video, palette = stored, expanded = false)
        compose.onAllNodesWithContentDescription("Search photos coloured", substring = true).assertCountEquals(stored.size)
    }

    @Test
    fun `a video whose frame cannot be read says so`() {
        val video = ViewerFixtures.referencePhoto(context).copy(
            displayName = "clip.mp4",
            mimeType = "video/mp4",
            durationMillis = 64_000L,
            contentUriString = "file:///definitely/not/here.mp4",
        )
        room(video, palette = emptyList(), expanded = false)
        compose.waitUntil(READ_TIMEOUT_MS) {
            compose.onAllNodesWithText("Couldn't read colours").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `the swatches are described with their hex and how to copy it`() {
        room(ViewerFixtures.referencePhoto(context), palette = stored, expanded = false)
        compose.onNodeWithContentDescription(
            "Search photos coloured ${stored[0].hex}, long press to copy",
        ).assertExists()
    }

    @Test
    fun `a long press copies the hex and says so`() {
        room(ViewerFixtures.referencePhoto(context), palette = stored, expanded = false)
        compose.onNodeWithContentDescription("Search photos coloured ${stored[1].hex}", substring = true)
            .performTouchInput { longClick() }
        compose.waitForIdle()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(stored[1].hex, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        compose.onAllNodesWithText("Copied ${stored[1].hex}").assertCountEquals(1)
    }

    private fun room(asset: MediaAsset, palette: List<PaletteSwatch>, expanded: Boolean) {
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                PhotoDetailRoom(
                    asset = asset,
                    exif = ImageExifDetails(readOk = true),
                    reveal = { 1f },
                    settled = true,
                    palette = palette,
                    informationInitiallyExpanded = expanded,
                )
            }
        }
        compose.waitForIdle()
    }

    private companion object {
        const val READ_TIMEOUT_MS = 10_000L
    }
}
