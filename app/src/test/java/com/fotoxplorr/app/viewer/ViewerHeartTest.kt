package com.fotoxplorr.app.viewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.render.ViewerFixtures
import com.fotoxplorr.app.ui.FotoXplorrTheme
import com.github.takahirom.roborazzi.captureRoboImage
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
 * The favourite heart in the viewer's chrome row (owner, #18: "The favourite icon should be
 * visible when a photo is being viewed - doesn't need to hide in the right side menu"), through
 * the real [ViewerScreen]: when it is drawn, that it toggles, that its state is in the shape and
 * the semantics and not only the colour, and that its two glyphs really are different pictures.
 *
 * The gating rule itself is pinned without Compose in [ViewerChromePolicyTest]; this proves the
 * screen applies it. Playback is not asserted (a video cannot decode a frame under Robolectric).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class ViewerHeartTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun decodeSynchronously() {
        ViewerFixtures.installSynchronousImageLoader(context)
    }

    @Composable
    private fun Viewer(
        asset: MediaAsset,
        isFavorite: Boolean = false,
        onToggleFavorite: () -> Unit = {},
        catalogueActions: Boolean = true,
        isExternal: Boolean = false,
    ) {
        FotoXplorrTheme(GalleryPreferencesState()) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                ViewerScreen(
                    asset = asset,
                    position = 1,
                    total = 1,
                    isFavorite = isFavorite,
                    isSensitive = false,
                    hasPrevious = false,
                    hasNext = false,
                    canMoveToTrash = false,
                    slideshowActive = false,
                    slideshowIntervalSeconds = 5,
                    onToggleSlideshow = {},
                    onToggleFavorite = onToggleFavorite,
                    onToggleSensitive = {},
                    onShare = {},
                    onEdit = {},
                    onOpenWith = {},
                    onMoveToTrash = {},
                    onPrevious = {},
                    onNext = {},
                    onClose = {},
                    isExternal = isExternal,
                    catalogueActions = catalogueActions,
                    plateMapBackdrop = ViewerFixtures.mapStandIn,
                )
            }
        }
    }

    private fun photo(): MediaAsset = ViewerFixtures.referencePhoto(context)

    private fun video(): MediaAsset = photo().copy(
        displayName = "clip.mp4",
        mimeType = "video/mp4",
        durationMillis = 64_000L,
    )

    private fun showChrome() {
        compose.waitForIdle()
        compose.onRoot().performTouchInput { click(center) }
        // A single tap is only decided once the double-tap window has passed.
        compose.mainClock.advanceTimeBy(500L)
        compose.waitForIdle()
    }

    private fun heartCount(): Int = compose.onAllNodesWithTag(FAVOURITE_HEART_TAG).fetchSemanticsNodes().size

    private fun hasState(text: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text)

    // ── when it is drawn ───────────────────────────────────────────────────────────────────────

    @Test
    fun `a photo opens immersive with no heart and a tap brings it up`() {
        compose.setContent { Viewer(photo()) }
        compose.waitForIdle()
        assertEquals("immersive stays the default for stills", 0, heartCount())
        showChrome()
        compose.onNodeWithTag(FAVOURITE_HEART_TAG).assertIsDisplayed()
    }

    @Test
    fun `a video opens with the chrome up and the heart with it`() {
        compose.setContent { Viewer(video()) }
        compose.waitForIdle()
        compose.onNodeWithTag(FAVOURITE_HEART_TAG).assertIsDisplayed()
    }

    @Test
    fun `a second tap hides the heart with the rest of the chrome`() {
        compose.setContent { Viewer(photo()) }
        showChrome()
        assertEquals(1, heartCount())
        showChrome()
        assertEquals(0, heartCount())
    }

    @Test
    fun `no heart for an asset that is not in the catalogue`() {
        compose.setContent { Viewer(photo(), catalogueActions = false) }
        showChrome()
        assertEquals("catalogueActions off", 0, heartCount())
    }

    @Test
    fun `no heart for an external asset`() {
        compose.setContent { Viewer(photo(), isExternal = true) }
        showChrome()
        assertEquals("isExternal", 0, heartCount())
    }

    @Test
    fun `no heart when only the asset says it is external`() {
        compose.setContent { Viewer(photo().copy(isExternal = true)) }
        showChrome()
        assertEquals("asset.isExternal", 0, heartCount())
    }

    // ── what it does ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `the heart toggles and its state follows the favourite`() {
        var favourite by mutableStateOf(false)
        var toggles = 0
        compose.setContent {
            Viewer(photo(), isFavorite = favourite, onToggleFavorite = { toggles++; favourite = !favourite })
        }
        showChrome()
        val heart = compose.onNodeWithTag(FAVOURITE_HEART_TAG)
        heart.assert(hasState("Not a favourite"))

        heart.performClick()
        compose.waitForIdle()
        assertEquals(1, toggles)
        compose.onNodeWithTag(FAVOURITE_HEART_TAG).assert(hasState("Favourited"))
        // The row as drawn, for a look: Close, the position pill and the filled heart.
        compose.onRoot().captureRoboImage("build/renders/viewer-heart-chrome-on.png")

        compose.onNodeWithTag(FAVOURITE_HEART_TAG).performClick()
        compose.waitForIdle()
        assertEquals(2, toggles)
        compose.onNodeWithTag(FAVOURITE_HEART_TAG).assert(hasState("Not a favourite"))
    }

    @Test
    fun `the heart is a labelled button of at least 48dp`() {
        compose.setContent { Viewer(photo()) }
        showChrome()
        val heart = compose.onNodeWithTag(FAVOURITE_HEART_TAG)
        heart.assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        heart.assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
        // The label is the action's stable name; the state is described separately.
        heart.assert(
            SemanticsMatcher("is labelled Favourite") { node ->
                node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Favourite") == true
            },
        )
    }

    // ── the glyphs ─────────────────────────────────────────────────────────────────────────────

    @Composable
    private fun GlyphOn(glyph: ImageVector, tag: String) {
        Box(Modifier.size(48.dp).background(Color.Black).testTag(tag)) {
            Icon(imageVector = glyph, contentDescription = null, tint = Color.White, modifier = Modifier.size(48.dp))
        }
    }

    /** How many pixels of [node] are lit: ink on a black ground. */
    private fun litPixels(node: SemanticsNodeInteraction, name: String): Int {
        val path = "build/renders/$name.png"
        node.captureRoboImage(path)
        val image: Bitmap = checkNotNull(BitmapFactory.decodeFile(path)) { "could not read $path" }
        var lit = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val argb = image.getPixel(x, y)
            if (((argb shr 16) and 0xFF) > 127) lit++
        }
        return lit
    }

    @Composable
    private fun Glyphs(vararg glyphs: Pair<String, ImageVector>) {
        Row(Modifier.fillMaxSize().background(Color.Black)) {
            glyphs.forEach { (tag, glyph) -> GlyphOn(glyph, tag) }
        }
    }

    @Test
    fun `the on glyph is a solid heart and the off glyph an outline`() {
        compose.setContent {
            Glyphs("glyph-on" to favouriteGlyph(isFavorite = true), "glyph-off" to favouriteGlyph(isFavorite = false))
        }
        val solid = litPixels(compose.onNodeWithTag("glyph-on"), "viewer-heart-glyph-on")
        val outline = litPixels(compose.onNodeWithTag("glyph-off"), "viewer-heart-glyph-off")

        assertTrue("the solid heart has ink ($solid)", solid > 100)
        assertTrue("the outline has ink ($outline)", outline > 20)
        assertTrue(
            "the solid heart carries well over twice the ink of the outline ($solid vs $outline), so " +
                "the state does not rest on tint alone",
            solid > outline * 2,
        )
    }
}
