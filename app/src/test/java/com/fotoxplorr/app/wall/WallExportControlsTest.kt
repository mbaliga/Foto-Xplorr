package com.fotoxplorr.app.wall

import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import com.fotoxplorr.app.experimental.ExperimentEnv
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.hyle.ActivityKind
import com.fotoxplorr.app.jobs.JobOutcome
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.shell.AppStateViewModel
import com.fotoxplorr.app.spatial.GeoIndexState
import com.fotoxplorr.app.ui.FotoXplorrTheme
import com.fotoxplorr.core.model.MediaId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.FileOutputStream
import java.util.Collections
import kotlin.io.path.createTempFile

/**
 * The print controls in the hang panel, with real small JPEGs: that they show the file the export
 * will make (the same plan, not a number of the screen's own), that Print appears only where the
 * device can print, and that Save runs as a job on the app's job runner and reports its outcome
 * there. It does not prove how the controls look, the progress bar, or the system print dialog:
 * device checks.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h2400dp-xhdpi")
class WallExportControlsTest {

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
    private var storeOwner: ViewModelStoreOwner? = null
    private val outcomes: MutableList<JobOutcome<*>> = Collections.synchronizedList(mutableListOf())
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @After
    fun tearDown() = scope.cancel()

    private fun asset(id: Long, name: String, w: Int, h: Int): MediaAsset {
        val file = createTempFile("fx-wall-x-$id", ".jpg").toFile().also { it.deleteOnExit() }
        FileOutputStream(file).use { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 80, it) }
        return MediaAsset(
            id = MediaId(id), contentUriString = Uri.fromFile(file).toString(), displayName = name,
            mimeType = "image/jpeg", bucketName = null, dateTakenMillis = 0, dateModifiedSeconds = 0,
            width = w, height = h, sizeBytes = file.length(), relativePath = null, isFavorite = false, isTrashed = false,
        )
    }

    private val wallPhoto = asset(1, "wall.jpg", 800, 600)
    private val picture = asset(2, "picture.jpg", 600, 400)

    @androidx.compose.runtime.Composable
    private fun Host() {
        val assets = listOf(wallPhoto, picture)
        val env = ExperimentEnv(
            assets = assets,
            indexInput = assets,
            geoState = GeoIndexState(),
            onIndexLocations = {},
            onOpenAsset = { _, _ -> },
            context = ApplicationProvider.getApplicationContext(),
            currentBrowsableIds = { assets.mapTo(HashSet()) { it.id } },
        )
        storeOwner = LocalViewModelStoreOwner.current
        FotoXplorrTheme(GalleryPreferencesState()) {
            CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides owner) {
                WallExperience(env = env, onClose = {})
            }
        }
    }

    private fun waitForText(text: String, substring: Boolean = false) =
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForNoText(text: String) =
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isEmpty() }

    private fun hangOnePicture() {
        compose.onNodeWithContentDescription("wall.jpg").performClick()
        waitForNoText(WallCopy.LOADING)
        compose.onNode(hasText(WallCopy.DISTANCE) and hasSetTextAction()).performTextInput("200")
        waitForText("The photo shows about", substring = true)
        compose.onNodeWithText(WallCopy.NEXT).performClick()
        compose.onNodeWithContentDescription("picture.jpg").performClick()
        compose.onNodeWithText(WallCopy.HANG_THEM).performClick()
        compose.onNodeWithText(WallCopy.HANG_HINT).assertExists()
    }

    /** The size the single picture is hung at, from the pure maths: the 600 x 400 picture is wider than tall, so it lies down. */
    private fun hungSize(): PrintSize {
        val wallCm = WallScale.fromDistance(200f, WallScale.GUESSED_FOCAL_35MM, 800, 600)!!
        val preset = SetLayouts.biggestThatFits(
            SetKind.SINGLE, 1, wallCm, wallCm * 600 / 800, FrameStyle.THIN_BLACK.moldingCm, SetOrientation.LANDSCAPE,
        )
        val size = SetLayouts.cell(preset, SetOrientation.LANDSCAPE)
        return PrintSize(size.wCm.toDouble(), size.hCm.toDouble())
    }

    @Test
    fun `the panel says what file Save will make, from the export's own plan`() {
        compose.setContent { Host() }
        hangOnePicture()

        val size = hungSize()
        val plan = PrintPlanner.plan(600, 400, size)!!
        waitForText(WallCopy.fileWillBe(plan.outW, plan.outH, plan.dpi))
        compose.onNodeWithText(WallCopy.PRINT_HEADER).assertExists()
        compose.onNodeWithText(WallCopy.SAVE_PRINT).assertExists()
        compose.onNodeWithText(WallCopy.PLACE_LEFT_OUT).assertExists()
    }

    @Test
    fun `Print is not offered on a device that cannot print`() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>().packageManager).setSystemFeature(PackageManager.FEATURE_PRINTING, false)
        compose.setContent { Host() }
        hangOnePicture()
        waitForText(WallCopy.SAVE_PRINT)
        assertTrue(compose.onAllNodes(hasText(WallCopy.PRINT)).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodes(hasText(WallCopy.PRINT_REAL_SIZE)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `Print is offered where the device can print`() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>().packageManager).setSystemFeature(PackageManager.FEATURE_PRINTING, true)
        compose.setContent { Host() }
        hangOnePicture()
        waitForText(WallCopy.SAVE_PRINT)
        compose.onNodeWithText(WallCopy.PRINT).assertExists()
        compose.onNodeWithText(WallCopy.PRINT_REAL_SIZE).assertExists()
    }

    @Test
    fun `Save runs as a job and reports its outcome on the app's job runner`() {
        compose.setContent { Host() }
        hangOnePicture()
        val plan = PrintPlanner.plan(600, 400, hungSize())!!
        waitForText(WallCopy.fileWillBe(plan.outW, plan.outH, plan.dpi))

        val runner = ViewModelProvider(storeOwner!!)[AppStateViewModel::class.java].jobRunner
        scope.launch { runner.outcomes.collect { outcomes += it } }

        compose.onNodeWithText(WallCopy.SAVE_PRINT).performClick()
        // The job finishes on the main looper after its worker thread is done: let that run.
        compose.waitUntil(15_000) { shadowOf(Looper.getMainLooper()).idle(); outcomes.isNotEmpty() }

        val outcome = outcomes.single()
        assertEquals(WallCopy.PRINT_JOB_TITLE, outcome.title)
        // Whatever the test device's library does with the insert, the outcome is a sentence for the person.
        val message = outcome.result.fold({ it as String }, { it.message.orEmpty() })
        assertTrue(message, message == WallCopy.SAVED || message == WallCopy.CANT_CREATE_FILE)
    }

    @Test
    fun `while a file is made the panel shows progress and Cancel, and Cancel stops the job`() {
        compose.setContent { Host() }
        hangOnePicture()
        waitForText(WallCopy.SAVE_PRINT)
        val runner = ViewModelProvider(storeOwner!!)[AppStateViewModel::class.java].jobRunner
        scope.launch { runner.outcomes.collect { outcomes += it } }

        // A job of the export's own name that never finishes by itself, standing in for a long export.
        runner.launch<Unit>(WallCopy.PRINT_JOB_TITLE, ActivityKind.EXPORTING) { onProgress ->
            onProgress(0.4f)
            awaitCancellation()
        }
        waitForText(WallCopy.MAKING)
        compose.onNodeWithText(WallCopy.CANCEL).assertExists()
        assertTrue(compose.onAllNodes(hasText(WallCopy.SAVE_PRINT)).fetchSemanticsNodes().isEmpty())

        compose.onNodeWithText(WallCopy.CANCEL).performClick()
        compose.waitUntil(15_000) { shadowOf(Looper.getMainLooper()).idle(); outcomes.isNotEmpty() }
        assertTrue(outcomes.single().result.exceptionOrNull() is CancellationException)
        waitForText(WallCopy.SAVE_PRINT)
        assertTrue(runner.jobs.value.isEmpty())
    }

    @Test
    fun `a photo short of pixels is offered Enlarged, no new detail, and ticking it changes the file line`() {
        compose.setContent { Host() }
        hangOnePicture()
        val size = hungSize()
        val plain = PrintPlanner.plan(600, 400, size)!!
        val enlarged = PrintPlanner.plan(600, 400, size, enlarge = true)!!
        assertTrue(PrintPlanner.canEnlarge(600, 400, size))
        waitForText(WallCopy.fileWillBe(plain.outW, plain.outH, plain.dpi))
        compose.onNodeWithText(WallCopy.ENLARGE).assertExists()
        compose.onNodeWithText(WallCopy.ENLARGE_WHY).assertExists()

        compose.onNodeWithText(WallCopy.ENLARGE).performClick()
        waitForText(WallCopy.fileWillBe(enlarged.outW, enlarged.outH, enlarged.dpi))
        assertTrue(enlarged.outW > plain.outW)
        // The offer's reason is only for the unticked state.
        assertTrue(compose.onAllNodes(hasText(WallCopy.ENLARGE_WHY)).fetchSemanticsNodes().isEmpty())

        compose.onNodeWithText(WallCopy.ENLARGE).performClick()
        waitForText(WallCopy.fileWillBe(plain.outW, plain.outH, plain.dpi))
    }

    @Test
    fun `the label says what enlarging does not do`() {
        assertEquals("Enlarged, no new detail", WallCopy.ENLARGE)
        assertTrue(WallCopy.SAVED_ENLARGED.contains("no new detail"))
    }

    @Test
    fun `the saved sentences are one sentence each and name no folder, which is not the same on every Android`() {
        for (sentence in listOf(WallCopy.SAVED, WallCopy.SAVED_ENLARGED)) {
            assertEquals(sentence, 1, sentence.count { it == '.' })
            assertTrue(sentence, sentence.endsWith("."))
            assertTrue(sentence, !sentence.contains("/"))
        }
    }

    @Test
    fun `Save with Enlarge ticked runs as the enlarging job and reports what was done`() {
        compose.setContent { Host() }
        hangOnePicture()
        waitForText(WallCopy.ENLARGE)
        val runner = ViewModelProvider(storeOwner!!)[AppStateViewModel::class.java].jobRunner
        scope.launch { runner.outcomes.collect { outcomes += it } }

        compose.onNodeWithText(WallCopy.ENLARGE).performClick()
        compose.onNodeWithText(WallCopy.SAVE_PRINT).performClick()
        compose.waitUntil(30_000) { shadowOf(Looper.getMainLooper()).idle(); outcomes.isNotEmpty() }

        val outcome = outcomes.single()
        assertEquals(WallCopy.ENLARGE_JOB_TITLE, outcome.title)
        assertEquals(ActivityKind.EXPORTING, outcome.kind)
        val message = outcome.result.fold({ it as String }, { it.message.orEmpty() })
        assertTrue(message, message == WallCopy.SAVED_ENLARGED || message == WallCopy.CANT_CREATE_FILE)
    }

    @Test
    fun `while an enlargement runs the panel says so, shows Cancel, and hides the offer`() {
        compose.setContent { Host() }
        hangOnePicture()
        waitForText(WallCopy.ENLARGE)
        val runner = ViewModelProvider(storeOwner!!)[AppStateViewModel::class.java].jobRunner
        scope.launch { runner.outcomes.collect { outcomes += it } }

        runner.launch<Unit>(WallCopy.ENLARGE_JOB_TITLE, ActivityKind.EXPORTING) { onProgress ->
            onProgress(0.3f)
            awaitCancellation()
        }
        waitForText(WallCopy.ENLARGING)
        compose.onNodeWithText(WallCopy.CANCEL).assertExists()
        assertTrue(compose.onAllNodes(hasText(WallCopy.ENLARGE)).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodes(hasText(WallCopy.SAVE_PRINT)).fetchSemanticsNodes().isEmpty())

        compose.onNodeWithText(WallCopy.CANCEL).performClick()
        compose.waitUntil(15_000) { shadowOf(Looper.getMainLooper()).idle(); outcomes.isNotEmpty() }
        assertTrue(outcomes.single().result.exceptionOrNull() is CancellationException)
        waitForText(WallCopy.SAVE_PRINT)
        assertTrue(runner.jobs.value.isEmpty())
    }

    @Test
    fun `the privacy line no longer promises nothing is ever saved`() {
        compose.setContent { Host() }
        hangOnePicture()
        compose.onNodeWithText(WallCopy.PRIVACY).assertExists()
        assertTrue(WallCopy.PRIVACY.contains("unless you ask"))
    }
}
