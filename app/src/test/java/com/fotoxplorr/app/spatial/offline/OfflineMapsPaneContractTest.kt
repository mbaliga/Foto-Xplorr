package com.fotoxplorr.app.spatial.offline

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.fotoxplorr.app.BuildConfig
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.jobs.JobRunner
import com.fotoxplorr.app.ui.FotoXplorrTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The pane exists in both builds under one name and one signature (this file compiles against
 * whichever the flavour provides, which is the contract), opening it starts nothing, and the
 * Offline build says plainly why there is nothing to download with.
 *
 * Composition only: Robolectric proves the pane composes, not how it looks.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class OfflineMapsPaneContractTest {

    @get:Rule
    val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun mount(closed: () -> Unit = {}): JobRunner {
        val runner = JobRunner(scope)
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                OfflineMapsPane(
                    placePoints = listOf(GeoPoint(48.86, 2.35)),
                    currentView = GeoBox.clamped(2.30, 48.83, 2.38, 48.89),
                    jobRunner = runner,
                    onClose = closed,
                )
            }
        }
        compose.waitForIdle()
        return runner
    }

    @Test
    fun `opening the pane runs no job`() {
        val runner = mount()
        assertEquals(0, runner.jobs.value.size)
        scope.cancel()
    }

    @Test
    fun `the Offline build says it needs the Connect build`() {
        assumeFalse(BuildConfig.NETWORK_FEATURES)
        mount()
        compose.onNodeWithText("Offline maps need the Connect build.").assertIsDisplayed()
        scope.cancel()
    }
}
