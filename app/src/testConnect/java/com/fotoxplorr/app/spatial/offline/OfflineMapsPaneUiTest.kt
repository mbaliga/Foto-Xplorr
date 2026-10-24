package com.fotoxplorr.app.spatial.offline

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.jobs.JobRunner
import com.fotoxplorr.app.ui.FotoXplorrTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Connect pane mounted over the fake engine: what it says, which buttons are on, and that only
 * a tap starts anything. Composition and wiring only: Robolectric proves the pane composes and
 * responds, not how it looks (the screenshot runner is in record mode).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class OfflineMapsPaneUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val paris = GeoBox.clamped(2.30, 48.83, 2.38, 48.89)!!
    private val switzerland = GeoBox.clamped(5.96, 45.82, 10.49, 47.81)!!
    private val parisBytes = (RegionBudget.plan(paris) as BudgetVerdict.Fits).estimate.bytes

    @After
    fun tearDown() = scope.cancel()

    private fun mount(
        engine: FakeOfflineRegionEngine,
        view: GeoBox? = paris,
        points: List<GeoPoint> = emptyList(),
        metered: Boolean = false,
    ): JobRunner {
        val runner = JobRunner(scope)
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                OfflineMapsPaneBody(
                    engine = engine,
                    network = ConnectionCheck { if (metered) Connection.METERED else Connection.UNMETERED },
                    nowMillis = { 777L },
                    placePoints = points,
                    currentView = view,
                    jobRunner = runner,
                    onClose = {},
                )
            }
        }
        compose.waitForIdle()
        return runner
    }

    private fun storedRecord(name: String) = RegionRecord(
        name = name, box = paris, minZoom = 0, maxZoom = 14, styleId = "liberty",
        createdAtMillis = 1_792_670_400_000L, lastUsedMillis = 1L, bytes = 12_000_000L, tileCount = 100L,
    )

    @Test
    fun `opening shows this view with its size on the button, and starts nothing`() {
        val engine = FakeOfflineRegionEngine()
        val runner = mount(engine)

        compose.onNodeWithText(OfflineMapsCopy.downloadWithSize(parisBytes)).assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText(OfflineMapsCopy.THIS_VIEW).assertIsDisplayed()
        assertEquals(listOf("list"), engine.calls)
        assertEquals(0, runner.jobs.value.size)
    }

    @Test
    fun `the privacy sentence sits directly under the first Download button, once, and the attribution is on the pane`() {
        mount(FakeOfflineRegionEngine(), points = listOf(GeoPoint(48.86, 2.35)))
        compose.onNodeWithText(OfflineMapsCopy.PRIVACY).assertIsDisplayed()
        compose.onNodeWithText(OfflineMapsCopy.ATTRIBUTION).assertIsDisplayed()

        // The first card is This view; the place below it must not repeat the sentence.
        // The place below is the same size, so its button reads the same: take the first.
        val button = compose.onAllNodesWithText(OfflineMapsCopy.downloadWithSize(parisBytes))[0].getBoundsInRoot()
        val privacy = compose.onNodeWithText(OfflineMapsCopy.PRIVACY).getBoundsInRoot()
        val places = compose.onNodeWithText(OfflineMapsCopy.HEADING_PLACES).getBoundsInRoot()
        assertTrue("under the button", privacy.top >= button.bottom)
        assertTrue("before the next section", privacy.bottom <= places.top)
        compose.onAllNodesWithText(OfflineMapsCopy.PRIVACY).assertCountEquals(1)
    }

    @Test
    fun `with only places the sentence sits under the first place's button`() {
        mount(FakeOfflineRegionEngine(), view = null, points = listOf(GeoPoint(48.86, 2.35)))
        val privacy = compose.onNodeWithText(OfflineMapsCopy.PRIVACY).getBoundsInRoot()
        val heading = compose.onNodeWithText(OfflineMapsCopy.HEADING_PLACES).getBoundsInRoot()
        assertTrue(privacy.top > heading.bottom)
        compose.onAllNodesWithText(OfflineMapsCopy.PRIVACY).assertCountEquals(1)
    }

    @Test
    fun `without the request limit Download is off and the reason is on screen`() {
        val engine = FakeOfflineRegionEngine(throttleInstalled = false)
        mount(engine)

        compose.onNodeWithText(OfflineMapsCopy.DOWNLOAD).assertIsNotEnabled()
        compose.onNodeWithText(OfflineMapsCopy.UNAVAILABLE).assertIsDisplayed()
        compose.onNodeWithText(OfflineMapsCopy.DOWNLOAD).performClick()
        compose.waitForIdle()
        assertFalse(engine.calls.any { it.startsWith("create") || it.startsWith("resume") })
    }

    @Test
    fun `an area that is too big is off with its reason`() {
        mount(FakeOfflineRegionEngine(), view = switzerland)
        compose.onNodeWithText(OfflineMapsCopy.DOWNLOAD).assertIsNotEnabled()
        compose.onNodeWithText("This area would take too much space.").assertIsDisplayed()
    }

    @Test
    fun `a tap on Download on Wi-Fi starts it`() {
        val engine = FakeOfflineRegionEngine()
        val runner = mount(engine)

        compose.onNodeWithText(OfflineMapsCopy.downloadWithSize(parisBytes)).performClick()
        compose.waitForIdle()

        assertTrue(engine.calls.contains("resume:1"))
        assertEquals(1, runner.jobs.value.size)
        compose.onNodeWithContentDescription("${OfflineMapsCopy.PAUSE} ${storedNameOf(engine)}").assertIsDisplayed()
    }

    private fun storedNameOf(engine: FakeOfflineRegionEngine): String =
        ((runBlocking { engine.list() }) as EngineResult.Ok).value.single().record.name

    @Test
    fun `on mobile data a tap asks first and Not now starts nothing`() {
        val engine = FakeOfflineRegionEngine()
        val runner = mount(engine, metered = true)

        compose.onNodeWithText(OfflineMapsCopy.downloadWithSize(parisBytes)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(OfflineMapsCopy.METERED).assertIsDisplayed()
        // The dialog repeats the privacy sentence next to its own Download button.
        compose.onAllNodesWithText(OfflineMapsCopy.PRIVACY).assertCountEquals(2)
        assertFalse(engine.calls.any { it.startsWith("create") || it.startsWith("resume") })

        compose.onNodeWithText(OfflineMapsCopy.NOT_NOW).performClick()
        compose.waitForIdle()
        assertFalse(engine.calls.any { it.startsWith("create") || it.startsWith("resume") })
        assertEquals(0, runner.jobs.value.size)
    }

    @Test
    fun `a saved area found on open is paused with a Resume that names it`() {
        val engine = FakeOfflineRegionEngine()
        runBlocking { engine.create(storedRecord("Old town")) }
        engine.calls.clear()
        mount(engine, view = null)

        compose.onNodeWithText(OfflineMapsCopy.paused(PauseReason.RESTARTED)).assertIsDisplayed()
        compose.onNodeWithContentDescription("${OfflineMapsCopy.RESUME} Old town").assertIsEnabled()
        compose.onNodeWithContentDescription("${OfflineMapsCopy.DELETE} Old town").assertIsEnabled()
        assertFalse(engine.calls.any { it.startsWith("resume") })
    }

    @Test
    fun `delete asks first and Keep leaves the area`() {
        val engine = FakeOfflineRegionEngine()
        runBlocking { engine.create(storedRecord("Old town")) }
        mount(engine, view = null)

        compose.onNodeWithContentDescription("${OfflineMapsCopy.DELETE} Old town").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(OfflineMapsCopy.deleteTitle("Old town")).assertIsDisplayed()
        assertFalse(engine.calls.any { it.startsWith("delete") })

        compose.onNodeWithText(OfflineMapsCopy.KEEP).performClick()
        compose.waitForIdle()
        assertFalse(engine.calls.any { it.startsWith("delete") })
    }

    @Test
    fun `with nothing to offer and nothing saved the pane says what to do`() {
        mount(FakeOfflineRegionEngine(), view = null)
        compose.onNodeWithText(OfflineMapsCopy.EMPTY).assertIsDisplayed()
    }

    @Test
    fun `the places in the photos are offered with their photo count`() {
        mount(FakeOfflineRegionEngine(), view = null, points = listOf(GeoPoint(48.86, 2.35), GeoPoint(48.861, 2.351)))
        compose.onNodeWithText(OfflineMapsCopy.HEADING_PLACES).assertIsDisplayed()
        compose.onNodeWithText("2 photos").assertIsDisplayed()
    }

    @Test
    fun `no engine says unavailable`() {
        val runner = JobRunner(scope)
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                OfflineMapsPaneBody(null, ConnectionCheck { Connection.UNMETERED }, { 0L }, emptyList(), paris, runner, {})
            }
        }
        compose.onNodeWithText(OfflineMapsCopy.UNAVAILABLE).assertIsDisplayed()
    }
}
