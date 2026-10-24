package com.fotoxplorr.app.wall

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import com.fotoxplorr.app.experimental.ExperimentEnv
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.spatial.GeoIndexState
import com.fotoxplorr.app.ui.FotoXplorrTheme
import com.fotoxplorr.core.model.MediaId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import kotlin.io.path.createTempFile

/**
 * The four steps on screen, with real small JPEGs on disk. What this proves is that the flow
 * composes, that every number on the screen is the pure maths's, that Back walks the steps, that
 * a restored draft comes back and that a photo that leaves the browsable list leaves the draft.
 * It does NOT prove how it looks or that a drag feels right: those are device checks.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h2400dp-xhdpi")
class WallExperienceTest {

    @get:Rule
    val compose = createComposeRule()

    @org.junit.Before
    fun warmUp() = warmUpNativeImageDecoder()

    private class BackOwner : OnBackPressedDispatcherOwner {
        private val registry = LifecycleRegistry.createUnsafe(this).also { it.currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
        override val onBackPressedDispatcher = OnBackPressedDispatcher()
    }

    private val owner = BackOwner()

    private fun asset(id: Long, name: String, w: Int, h: Int): MediaAsset {
        val file = createTempFile("fx-wall-$id", ".jpg").toFile().also { it.deleteOnExit() }
        FileOutputStream(file).use { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 80, it) }
        return MediaAsset(
            id = MediaId(id), contentUriString = Uri.fromFile(file).toString(), displayName = name,
            mimeType = "image/jpeg", bucketName = null, dateTakenMillis = 0, dateModifiedSeconds = 0,
            width = w, height = h, sizeBytes = file.length(), relativePath = null, isFavorite = false, isTrashed = false,
        )
    }

    private val wallPhoto = asset(1, "wall.jpg", 800, 600)
    private val picture = asset(2, "picture.jpg", 600, 400)

    private var shown by mutableStateOf(listOf(wallPhoto, picture))

    @androidx.compose.runtime.Composable
    private fun Host() {
        val assets = shown
        val env = ExperimentEnv(
            assets = assets,
            indexInput = assets,
            geoState = GeoIndexState(),
            onIndexLocations = {},
            onOpenAsset = { _, _ -> },
            context = ApplicationProvider.getApplicationContext(),
            currentBrowsableIds = { assets.mapTo(HashSet()) { it.id } },
        )
        FotoXplorrTheme(GalleryPreferencesState()) {
            CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides owner) {
                WallExperience(env = env, onClose = {})
            }
        }
    }

    private fun pressBack() {
        compose.runOnUiThread { owner.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun waitForText(text: String, substring: Boolean = false) =
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForNoText(text: String) =
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isEmpty() }

    /** The wall photo chosen, a distance typed, one picture hung. */
    private fun hangOnePicture() {
        compose.onNodeWithContentDescription("wall.jpg").performClick()
        compose.onNodeWithText(WallCopy.SCALE_HEADER).assertExists()
        waitForNoText(WallCopy.LOADING)
        compose.onNode(hasText(WallCopy.DISTANCE) and hasSetTextAction()).performTextInput("200")
        waitForText("The photo shows about", substring = true)
        compose.onNodeWithText(WallCopy.NEXT).performClick()
        compose.onNodeWithContentDescription("picture.jpg").performClick()
        compose.onNodeWithText(WallCopy.HANG_THEM).performClick()
        compose.onNodeWithText(WallCopy.HANG_HINT).assertExists()
    }

    /** The size "Hang them" gives one landscape picture on a 4:3 wall photo [wallCm] wide, as (width, height). */
    private fun lyingSingle(wallCm: Float): FrameSize {
        val preset = SetLayouts.biggestThatFits(
            SetKind.SINGLE, 1, wallCm, wallCm * 600 / 800, FrameStyle.THIN_BLACK.moldingCm, SetOrientation.LANDSCAPE,
        )
        return SetLayouts.cell(preset, SetOrientation.LANDSCAPE)
    }

    @Test
    fun `with no photos it says so`() {
        shown = emptyList()
        compose.setContent { Host() }
        compose.onNodeWithText(WallCopy.NO_PHOTOS).assertExists()
    }

    @Test
    fun `the distance sets the wall width the maths gives, and the picture is hung at a size that fits`() {
        compose.setContent { Host() }
        hangOnePicture()

        // 4:3 wall photo, 200 cm away, the guessed 26 mm lens.
        val wallCm = WallScale.fromDistance(200f, WallScale.GUESSED_FOCAL_35MM, 800, 600)!!
        // The 600 x 400 picture is wider than tall, so it hangs lying down.
        val size = lyingSingle(wallCm)
        val label = FramePresets.sizeLabel(size.wCm, size.hCm, inches = false)
        waitForText(label)
        // The picture is 600 x 400: dpi at this size is the pure figure, not a number of the screen's own.
        val dpi = PrintMath.effectiveDpi(600, 400, size.wCm.toDouble(), size.hCm.toDouble())
        waitForText(WallCopy.thisSize(dpi, PrintQuality.of(dpi)))
        compose.onNodeWithText(WallCopy.CROPPED).assertExists()
    }

    @Test
    fun `bigger and tilt work with buttons alone`() {
        compose.setContent { Host() }
        hangOnePicture()
        val wallCm = WallScale.fromDistance(200f, WallScale.GUESSED_FOCAL_35MM, 800, 600)!!
        val size = lyingSingle(wallCm)
        waitForText(FramePresets.sizeLabel(size.wCm, size.hCm, false))

        compose.onNodeWithContentDescription(WallCopy.BIGGER).performClick()
        val (w, h) = FramePresets.scaled(size.wCm, size.hCm, (size.wCm + 2f) / size.wCm)
        waitForText(FramePresets.sizeLabel(w, h, false))

        compose.onNodeWithContentDescription(WallCopy.TILT_RIGHT).performClick()
        compose.onNodeWithText(WallCopy.tilt(1f)).assertExists()
        compose.onNodeWithText(WallCopy.LEVEL).performClick()
        compose.onNodeWithText(WallCopy.tilt(0f)).assertExists()
    }

    @Test
    fun `Back walks the steps and then belongs to the host`() {
        compose.setContent { Host() }
        hangOnePicture()
        pressBack()
        compose.onNodeWithText(WallCopy.PICK_PICTURES).assertExists()
        pressBack()
        compose.onNodeWithText(WallCopy.SCALE_HEADER).assertExists()
        pressBack()
        compose.onNodeWithText(WallCopy.PICK_WALL).assertExists()
        assertFalse("the first step leaves Back to the host", owner.onBackPressedDispatcher.hasEnabledCallbacks())
    }

    @Test
    fun `a draft comes back after the activity is recreated`() {
        val tester = StateRestorationTester(compose)
        tester.setContent { Host() }
        hangOnePicture()

        tester.emulateSavedInstanceStateRestore()

        compose.onNodeWithText(WallCopy.HANG_HINT).assertExists()
        compose.onNodeWithText(WallCopy.picture(1)).assertExists()
    }

    @Test
    fun `a wall photo that leaves the browsable list sends the person back to choose another`() {
        compose.setContent { Host() }
        hangOnePicture()

        compose.runOnUiThread { shown = listOf(picture) }
        compose.waitForIdle()

        compose.onNodeWithText(WallCopy.PICK_WALL).assertExists()
    }

    @Test
    fun `a hung picture that leaves the browsable list leaves the wall`() {
        compose.setContent { Host() }
        hangOnePicture()

        compose.runOnUiThread { shown = listOf(wallPhoto) }
        compose.waitForIdle()

        // With no frame left the hanging step has nothing to hang and falls back to the pictures.
        compose.onNodeWithText(WallCopy.PICK_PICTURES).assertExists()
        assertTrue(compose.onAllNodes(hasText(WallCopy.picture(1))).fetchSemanticsNodes().isEmpty())
        assertEquals(0, compose.onAllNodes(hasText("This size:", substring = true)).fetchSemanticsNodes().size)
    }
}
