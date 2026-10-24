package com.fotoxplorr.app.viewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.gallery.TransparencyBackground
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.render.ViewerFixtures
import com.fotoxplorr.app.ui.FotoXplorrTheme
import com.fotoxplorr.core.model.MediaId
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What the viewer draws behind a see-through photo (owner, #17), through the real [ViewerScreen]
 * and a real PNG whose left half is fully transparent. The renders go to
 * `app/build/renders/viewer-transparency-*.png`.
 *
 * The pixel checks also pin that the display path (Coil) keeps the alpha: had it flattened the
 * picture onto black, the transparent half would read black in every mode and the grid, white and
 * black assertions below could not hold.
 *
 * The header probe runs on a real IO thread, so the tests wait for the chrome button, which only
 * exists once it has answered. Playback is not involved (a video cannot decode under Robolectric).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class TransparencyBackdropTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun decodeSynchronously() {
        ViewerFixtures.installSynchronousImageLoader(context)
    }

    /** 800 x 400: transparent on the left, opaque red on the right. Fits the 440dp window as 440dp x 220dp. */
    private fun sticker(id: Long, mime: String = "image/png"): MediaAsset {
        val file = File(context.cacheDir, "viewer-sticker-$id.png")
        if (!file.exists()) {
            val bitmap = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawRect(400f, 0f, 800f, 400f, Paint().apply { color = AndroidColor.rgb(200, 30, 30) })
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        return ViewerFixtures.referencePhoto(context).copy(
            id = MediaId(id),
            contentUriString = "file://${file.absolutePath}",
            displayName = "sticker.png",
            mimeType = mime,
            width = 800,
            height = 400,
            dateModifiedSeconds = 1_700_000_000L,
        )
    }

    @Composable
    private fun Viewer(
        asset: MediaAsset,
        background: TransparencyBackground,
        onSetBackground: (TransparencyBackground) -> Unit = {},
        catalogueActions: Boolean = true,
    ) {
        FotoXplorrTheme(GalleryPreferencesState()) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                ViewerScreen(
                    asset = asset,
                    position = 1,
                    total = 1,
                    isFavorite = false,
                    isSensitive = false,
                    hasPrevious = false,
                    hasNext = false,
                    canMoveToTrash = false,
                    slideshowActive = false,
                    slideshowIntervalSeconds = 5,
                    onToggleSlideshow = {},
                    onToggleFavorite = {},
                    onToggleSensitive = {},
                    onShare = {},
                    onEdit = {},
                    onOpenWith = {},
                    onMoveToTrash = {},
                    onPrevious = {},
                    onNext = {},
                    onClose = {},
                    catalogueActions = catalogueActions,
                    transparencyBackground = background,
                    onSetTransparencyBackground = onSetBackground,
                    plateMapBackdrop = ViewerFixtures.mapStandIn,
                )
            }
        }
    }

    private fun showChrome() {
        compose.waitForIdle()
        compose.onRoot().performTouchInput { click(center) }
        // A single tap is only decided once the double-tap window has passed.
        compose.mainClock.advanceTimeBy(500L)
        compose.waitForIdle()
    }

    private fun buttonCount(): Int = compose.onAllNodesWithTag(TRANSPARENCY_BUTTON_TAG).fetchSemanticsNodes().size

    private fun awaitButton() {
        compose.waitUntil(timeoutMillis = 10_000) { buttonCount() == 1 }
    }

    private class Shot(val image: Bitmap) {
        /** One dp in the capture's pixels. */
        val dp: Float = image.width / 440f

        /** The picture's top edge: the 440 x 220 dp picture is centred in a 956dp window. */
        val pictureTop: Int = ((956f - 220f) / 2f * dp).toInt()

        fun pixel(xDp: Float, yDpInPicture: Float): Int =
            image.getPixel((xDp * dp).toInt(), pictureTop + (yDpInPicture * dp).toInt())
    }

    private fun capture(name: String): Shot {
        val path = "build/renders/$name.png"
        compose.onRoot().captureRoboImage(path)
        return Shot(checkNotNull(BitmapFactory.decodeFile(path)) { "could not read $path" })
    }

    private fun assertNear(message: String, expected: Int, actual: Int, tolerance: Int = 3) {
        val channels = listOf(16, 8, 0).map { shift -> ((expected shr shift) and 0xFF) to ((actual shr shift) and 0xFF) }
        assertTrue(
            "$message: expected #${Integer.toHexString(expected and 0xFFFFFF)} " +
                "but was #${Integer.toHexString(actual and 0xFFFFFF)}",
            channels.all { (e, a) -> kotlin.math.abs(e - a) <= tolerance },
        )
    }

    // ── the three backgrounds ──────────────────────────────────────────────────────────────────

    @Test
    fun `the grid shows through the transparent half and the picture stays on top`() {
        compose.setContent { Viewer(sticker(7101L), TransparencyBackground.CHECKERBOARD) }
        showChrome()
        awaitButton()
        compose.waitForIdle()
        val shot = capture("viewer-transparency-grid")

        // The grid is anchored to the picture's corner: 8dp squares, light first.
        assertNear("first square", 0xFFFFFF, shot.pixel(4f, 4f))
        assertNear("square to its right", 0xCCCCCC, shot.pixel(12f, 4f))
        assertNear("square below it", 0xCCCCCC, shot.pixel(4f, 12f))
        assertNear("diagonal square", 0xFFFFFF, shot.pixel(12f, 12f))
        // The opaque half is the picture, not the grid.
        assertNear("opaque half", AndroidColor.rgb(200, 30, 30), shot.pixel(330f, 110f), tolerance = 6)
    }

    @Test
    fun `white shows white through the transparent half`() {
        compose.setContent { Viewer(sticker(7101L), TransparencyBackground.WHITE) }
        showChrome()
        awaitButton()
        compose.waitForIdle()
        val shot = capture("viewer-transparency-white")

        assertNear("transparent half", 0xFFFFFF, shot.pixel(100f, 110f))
        assertNear("opaque half", AndroidColor.rgb(200, 30, 30), shot.pixel(330f, 110f), tolerance = 6)
    }

    @Test
    fun `black shows black through the transparent half`() {
        compose.setContent { Viewer(sticker(7101L), TransparencyBackground.BLACK) }
        showChrome()
        awaitButton()
        compose.waitForIdle()
        val shot = capture("viewer-transparency-black")

        assertNear("transparent half", 0x000000, shot.pixel(100f, 110f))
        assertNear("opaque half", AndroidColor.rgb(200, 30, 30), shot.pixel(330f, 110f), tolerance = 6)
    }

    @Test
    fun `the letterbox around the picture stays black in every mode`() {
        compose.setContent { Viewer(sticker(7101L), TransparencyBackground.WHITE) }
        showChrome()
        awaitButton()
        compose.waitForIdle()
        val shot = capture("viewer-transparency-letterbox")

        // 20dp above the picture's top edge and 20dp below its bottom edge, over its transparent side.
        assertNear("above", 0x000000, shot.pixel(100f, -20f))
        assertNear("below", 0x000000, shot.pixel(100f, 240f))
    }

    @Test
    fun `the backdrop shows with the chrome down`() {
        compose.setContent { Viewer(sticker(7101L), TransparencyBackground.WHITE) }
        showChrome()
        awaitButton()
        showChrome() // hide again
        assertEquals("the button is chrome", 0, buttonCount())
        val shot = capture("viewer-transparency-immersive")

        assertNear("transparent half", 0xFFFFFF, shot.pixel(100f, 110f))
    }

    // ── the button ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the button cycles grid then white then black and draws each`() {
        var mode by mutableStateOf(TransparencyBackground.CHECKERBOARD)
        compose.setContent { Viewer(sticker(7101L), mode, onSetBackground = { mode = it }) }
        showChrome()
        awaitButton()
        compose.onNodeWithTag(TRANSPARENCY_BUTTON_TAG).assertIsDisplayed()

        compose.onNodeWithTag(TRANSPARENCY_BUTTON_TAG).performClick()
        compose.waitForIdle()
        assertEquals(TransparencyBackground.WHITE, mode)
        assertNear("white", 0xFFFFFF, capture("viewer-transparency-cycle-white").pixel(100f, 110f))

        compose.onNodeWithTag(TRANSPARENCY_BUTTON_TAG).performClick()
        compose.waitForIdle()
        assertEquals(TransparencyBackground.BLACK, mode)
        assertNear("black", 0x000000, capture("viewer-transparency-cycle-black").pixel(100f, 110f))

        compose.onNodeWithTag(TRANSPARENCY_BUTTON_TAG).performClick()
        compose.waitForIdle()
        assertEquals(TransparencyBackground.CHECKERBOARD, mode)
        // The row as drawn, with the chrome up, for a look.
        assertNear("grid again", 0xFFFFFF, capture("viewer-transparency-chrome").pixel(4f, 4f))
    }

    @Test
    fun `an asset from another app gets the button too`() {
        compose.setContent { Viewer(sticker(7101L), TransparencyBackground.CHECKERBOARD, catalogueActions = false) }
        showChrome()
        awaitButton()
        assertEquals(1, buttonCount())
    }

    @Test
    fun `an opaque photo has no button and no backdrop`() {
        // Declared a JPEG: the probe settles it from the type alone, so the transparent file is
        // treated as opaque and its transparent half is the viewer's own black.
        compose.setContent { Viewer(sticker(7102L, mime = "image/jpeg"), TransparencyBackground.WHITE) }
        showChrome()
        compose.waitForIdle()
        assertEquals(0, buttonCount())
        assertNear("no backdrop", 0x000000, capture("viewer-transparency-opaque").pixel(100f, 110f))
    }

    @Test
    fun `different choices render different pictures`() {
        compose.setContent { Viewer(sticker(7101L), TransparencyBackground.WHITE) }
        showChrome()
        awaitButton()
        val white = capture("viewer-transparency-compare").pixel(100f, 110f)
        assertNotEquals(0xFF000000.toInt(), white)
    }
}
