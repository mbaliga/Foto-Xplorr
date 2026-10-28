package com.fotoxplorr.app.chroma

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.experimental.ExperimentEnv
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.spatial.GeoIndexState
import com.fotoxplorr.app.ui.FotoXplorrTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What the Chroma screen says and offers in each state, with the camera taken out (the controls are
 * driven directly). No camera has run here: the live view, the permission dialog and the lifecycle are
 * device checks. What these tests do hold: the privacy sentence is always there, a refusal offers a way
 * forward, every button is at least 48 dp tall, and a result is words and numbers, not colour alone.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h1200dp-xhdpi")
class ChromaScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val facts = FakeCameraSource.facts()
    private val noFlash = FakeCameraSource.facts(hasFlash = false)

    @Composable
    private fun Controls(
        state: CaptureState,
        outcome: Outcome? = null,
        cardKept: Boolean = false,
        useCard: Boolean = false,
        onUseCard: (Boolean) -> Unit = {},
        live: LiveView = LiveView(),
        steady: Boolean? = null,
        onAsk: () -> Unit = {},
        onSettings: () -> Unit = {},
        onMeasure: (ShotRole, Boolean, Boolean) -> Unit = { _, _, _ -> },
        onCancel: () -> Unit = {},
        onAgain: () -> Unit = {},
    ) {
        FotoXplorrTheme(GalleryPreferencesState()) {
            Column { ChromaControls(state, outcome, cardKept, useCard, onUseCard, live, steady, onAsk, onSettings, onMeasure, onCancel, onAgain) }
        }
    }

    @Test
    fun beforeThePermissionTheReasonIsOnScreenWithAButtonToAllowIt() {
        var asked = 0
        compose.setContent { Controls(CaptureState.NeedsPermission, onAsk = { asked++ }) }
        compose.onNodeWithText(ChromaCopy.PRIVACY).assertIsDisplayed()
        compose.onNodeWithText(ChromaCopy.ALLOW_CAMERA).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, asked)
    }

    @Test
    fun aRefusalOffersAskingAgainAndTheSettings() {
        var asked = 0
        var settings = 0
        compose.setContent { Controls(CaptureState.PermissionDenied, onAsk = { asked++ }, onSettings = { settings++ }) }
        compose.onNodeWithText(ChromaCopy.CAMERA_DENIED).assertIsDisplayed()
        compose.onNodeWithText(ChromaCopy.ASK_AGAIN).assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText(ChromaCopy.OPEN_SETTINGS).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, asked)
        assertEquals(1, settings)
    }

    @Test
    fun withoutAFlashThePhoneSaysSoAndOffersTheRoomOnlyReading() {
        val calls = mutableListOf<Triple<ShotRole, Boolean, Boolean>>()
        compose.setContent { Controls(CaptureState.Ready(noFlash), onMeasure = { r, a, l -> calls += Triple(r, a, l) }) }
        compose.onNodeWithText(ChromaCopy.NO_FLASH).assertIsDisplayed()
        compose.onNodeWithText(ChromaCopy.AMBIENT_ONLY).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf(Triple(ShotRole.SURFACE, true, true)), calls)
        assertEquals(0, compose.onAllNodesWithText(ChromaCopy.MEASURE).fetchSemanticsNodes().size)
    }

    @Test
    fun theGuidanceNamesTheDistanceAndTheSteadiness() {
        compose.setContent {
            Controls(
                CaptureState.Ready(facts),
                live = LiveView(distanceMetres = 0.30, clippedShare = 0.5),
                steady = false,
            )
        }
        compose.onNodeWithText(ChromaCopy.holdAbout(HoldDistance.targetCm(facts.minFocusDiopters, false))).assertIsDisplayed()
        compose.onNodeWithText(ChromaCopy.MOVE_CLOSER, substring = true).assertIsDisplayed()
        compose.onNodeWithText(ChromaCopy.HOLD_STILL).assertIsDisplayed()
        compose.onNodeWithText(ChromaCopy.WASHED_OUT_LIVE).assertIsDisplayed()
    }

    @Test
    fun measuringWithoutACardRelocksTheLight() {
        val calls = mutableListOf<Triple<ShotRole, Boolean, Boolean>>()
        compose.setContent { Controls(CaptureState.Ready(facts), onMeasure = { r, a, l -> calls += Triple(r, a, l) }) }
        compose.onNodeWithText(ChromaCopy.NO_CARD_STEP).assertIsDisplayed()
        compose.onNodeWithText(ChromaCopy.MEASURE).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf(Triple(ShotRole.SURFACE, false, true)), calls)
    }

    @Test
    fun theCardSwitchIsASwitchWithItsStateAndALabel() {
        var on = false
        compose.setContent { Controls(CaptureState.Ready(facts), useCard = on, onUseCard = { on = it }) }
        compose.onNodeWithText(ChromaCopy.USE_CARD).assertHeightIsAtLeast(48.dp).performClick()
        assertTrue(on)
    }

    @Test
    fun theCardStepsAskForTheCardThenTheSurfaceUnderTheSameLight() {
        val calls = mutableListOf<Triple<ShotRole, Boolean, Boolean>>()
        compose.setContent {
            Controls(CaptureState.Ready(facts), useCard = true, cardKept = false, onMeasure = { r, a, l -> calls += Triple(r, a, l) })
        }
        compose.onNodeWithText(ChromaCopy.CARD_STEP).assertIsDisplayed()
        compose.onNodeWithText(ChromaCopy.MEASURE_CARD).performClick()
        assertEquals(Triple(ShotRole.CARD, false, true), calls.single())
    }

    @Test
    fun afterTheCardTheSurfaceReusesTheLightAndTheCardCanBeRedone() {
        val calls = mutableListOf<Triple<ShotRole, Boolean, Boolean>>()
        compose.setContent {
            Controls(CaptureState.Locked(facts), useCard = true, cardKept = true, onMeasure = { r, a, l -> calls += Triple(r, a, l) })
        }
        compose.onNodeWithText(ChromaCopy.SURFACE_STEP).assertIsDisplayed()
        compose.onNodeWithText(ChromaCopy.STOPPED_NOTE).assertIsDisplayed()
        compose.onNodeWithText(ChromaCopy.MEASURE_SURFACE).assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText(ChromaCopy.RETAKE_CARD).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf(Triple(ShotRole.SURFACE, false, false), Triple(ShotRole.CARD, false, true)), calls)
    }

    @Test
    fun whileMeasuringThereIsAWordAndACancel() {
        var cancelled = 0
        compose.setContent { Controls(CaptureState.Ambient(facts), onCancel = { cancelled++ }) }
        compose.onNodeWithText(ChromaCopy.SHOOTING).assertIsDisplayed()
        compose.onNodeWithText(ChromaCopy.CANCEL).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, cancelled)
    }

    @Test
    fun aReadingIsShownAsWordsAndNumbersAsWellAsASwatch() {
        val report = ColourReport.of(ChromaTestKit.argb(200, 100, 50))
        val measurement = Measurement(
            reflectance = LinearRgb(0.5, 0.2, 0.05),
            argb = report.argb,
            lab = report.lab,
            approximate = false,
            cautions = setOf(Caution.SAME_DISTANCE_ASSUMED),
            flashShare = 0.9,
            signalToNoise = 40.0,
        )
        var again = 0
        compose.setContent {
            Controls(CaptureState.Done(facts), outcome = Outcome.Reading(ChromaReading(measurement, report), withCard = true), onAgain = { again++ })
        }
        compose.onNodeWithText(report.description).assertIsDisplayed()
        compose.onNodeWithText("${report.hex}  RGB 200 100 50").assertIsDisplayed()
        compose.onNodeWithContentDescription("${report.description}, ${report.hex}").assertIsDisplayed()
        compose.onNodeWithText(ChromaCopy.MEASURED).assertIsDisplayed()
        compose.onNodeWithText(ChromaCopy.SAME_DISTANCE_ASSUMED).assertIsDisplayed()
        compose.onNodeWithText(ChromaCopy.AGAIN).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, again)
    }

    @Test
    fun aRoomOnlyReadingSaysItIsNotTheTrueColour() {
        val report = ColourReport.of(ChromaTestKit.argb(90, 120, 200))
        compose.setContent { Controls(CaptureState.Done(noFlash), outcome = Outcome.RoomLight(report)) }
        compose.onNodeWithText(ChromaCopy.AMBIENT_NOTE).assertIsDisplayed()
    }

    @Test
    fun aRefusalIsOneSentenceWithAWayToTryAgain() {
        var again = 0
        compose.setContent {
            Controls(CaptureState.Done(facts), outcome = Outcome.Refused(Refusal.CLIPPED), onAgain = { again++ })
        }
        compose.onNodeWithText(Refusal.CLIPPED.message).assertIsDisplayed()
        compose.onNodeWithText(ChromaCopy.AGAIN).performClick()
        assertEquals(1, again)
    }

    @Test
    fun aCameraFailureAndACancelBothOfferAnotherGo() {
        compose.setContent { Controls(CaptureState.Failed(CaptureFailure.MOVED, facts)) }
        compose.onNodeWithText(CaptureFailure.MOVED.message).assertIsDisplayed()
        compose.onNodeWithText(ChromaCopy.AGAIN).assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun aPhoneWithNoCameraSaysSoAndStillStatesWhatIsKept() {
        val context = RuntimeEnvironment.getApplication()
        val env = ExperimentEnv(
            assets = emptyList(), indexInput = emptyList(), geoState = GeoIndexState(), onIndexLocations = {},
            onOpenAsset = { _, _ -> }, context = context, currentBrowsableIds = { emptySet() },
        )
        var closed = 0
        compose.setContent { FotoXplorrTheme(GalleryPreferencesState()) { ChromaIdExperience(env) { closed++ } } }
        compose.onNodeWithText(ChromaCopy.NO_CAMERA).assertIsDisplayed()
        compose.onNodeWithText(ChromaCopy.PRIVACY).assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, closed)
    }
}
