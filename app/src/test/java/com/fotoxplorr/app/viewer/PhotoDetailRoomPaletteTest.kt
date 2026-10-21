package com.fotoxplorr.app.viewer

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
 * The details room's palette row against the stored palette it now receives: a swatch is a
 * button that searches the library for its colour, the stored palette feeds the map's dot colour
 * through [PaletteMemo], and a room without a search target survives a tap. The real
 * [PhotoDetailRoom] through the real Compose runtime, as the edge-case suite does.
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
        compose.onNodeWithContentDescription("Search photos coloured ${stored[1].hex}")
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
        compose.onNodeWithContentDescription("Search photos coloured ${stored[0].hex}")
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
        // Whatever the fixture decodes to, the memo now holds it or the row showed the dash; either
        // way composition, layout, draw and the effect all ran without throwing.
    }
}
