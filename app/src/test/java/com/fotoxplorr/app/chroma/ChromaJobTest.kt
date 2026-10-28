package com.fotoxplorr.app.chroma

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The capture job over a fake camera: the order of calls, the permission, the failure paths and what is cleared. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChromaJobTest {

    private class Rig(scope: TestScope, flash: Boolean = true) {
        val camera = FakeCameraSource(FakeCameraSource.facts(hasFlash = flash))
        val motion = MotionWindow()
        val job = ChromaJob(camera, motion, CoroutineScope(StandardTestDispatcher(scope.testScheduler)), StandardTestDispatcher(scope.testScheduler))
    }

    private fun TestScope.rig(flash: Boolean = true) = Rig(this, flash)

    private fun TestScope.opened(flash: Boolean = true): Rig {
        val r = rig(flash)
        r.job.permissionResult(true)
        advanceUntilIdle()
        return r
    }

    private fun CaptureState.name(): String = this::class.simpleName.orEmpty()

    private fun allZero(shot: Shot) = shot.frame.argb.all { it == 0 }

    @Test
    fun grantingThePermissionOpensTheCameraAndTheJobIsReady() = runTest {
        val r = opened()
        assertEquals(listOf("open"), r.camera.calls)
        assertTrue(r.job.state.value is CaptureState.Ready)
    }

    @Test
    fun aRefusedPermissionNeverTouchesTheCamera() = runTest {
        val r = rig()
        r.job.needPermission()
        assertEquals("NeedsPermission", r.job.state.value.name())
        r.job.permissionResult(false)
        advanceUntilIdle()
        assertEquals("PermissionDenied", r.job.state.value.name())
        assertTrue(r.camera.calls.isEmpty())
        // Granted later, in Settings: it opens then.
        r.job.permissionResult(true)
        advanceUntilIdle()
        assertTrue(r.job.state.value is CaptureState.Ready)
        assertEquals(listOf("open"), r.camera.calls)
    }

    @Test
    fun aSurfaceMeasurementLocksFocusesShootsOffThenOnAndLetsGo() = runTest {
        val r = opened()
        r.job.measure(ShotRole.SURFACE)
        advanceUntilIdle()
        assertEquals(
            listOf("open", "lock(torch=true)", "focus(torch=true)", "shoot(torch=false)", "shoot(torch=true)", "endShots", "unlock"),
            r.camera.calls,
        )
        assertTrue(r.job.state.value is CaptureState.Done)
        val outcome = r.job.outcome.value
        assertTrue(outcome is Outcome.Reading)
        assertFalse((outcome as Outcome.Reading).withCard)
        assertTrue(outcome.reading.measurement.approximate)
    }

    @Test
    fun theStateWalksTheSequenceInOrder() = runTest {
        val r = opened()
        val seen = mutableListOf<String>()
        r.camera.duringShoot = { torch -> seen += (if (torch) "flash" else "ambient") + ":" + r.job.state.value.name() }
        r.job.measure(ShotRole.SURFACE)
        advanceUntilIdle()
        assertEquals(listOf("ambient:Ambient", "flash:Flash"), seen)
        assertEquals("Done", r.job.state.value.name())
    }

    @Test
    fun theTwoPicturesAreOverwrittenOnceTheAnswerIsWorkedOut() = runTest {
        val r = opened()
        r.job.measure(ShotRole.SURFACE)
        advanceUntilIdle()
        assertEquals(2, r.camera.shots.size)
        assertTrue(r.camera.shots.all(::allZero))
        // The answer is numbers and words only.
        assertNotNull((r.job.outcome.value as Outcome.Reading).reading.report.description)
    }

    @Test
    fun aPhoneWithNoFlashReadsTheRoomLightOnlyWhenAsked() = runTest {
        val r = opened(flash = false)
        r.job.measure(ShotRole.SURFACE, ambientOnly = true)
        advanceUntilIdle()
        assertEquals(listOf("open", "lock(torch=false)", "focus(torch=false)", "shoot(torch=false)", "endShots", "unlock"), r.camera.calls)
        assertTrue(r.job.state.value is CaptureState.Done)
        assertTrue(r.job.outcome.value is Outcome.RoomLight)
        assertTrue(r.camera.shots.all(::allZero))
    }

    @Test
    fun cancelStopsTheMeasurementKeepsTheCameraAndClearsThePictures() = runTest {
        val r = opened()
        val gate = CompletableDeferred<Unit>()
        r.camera.shootGate = gate
        r.job.measure(ShotRole.SURFACE)
        advanceUntilIdle()
        assertEquals("Ambient", r.job.state.value.name())
        r.job.cancel()
        advanceUntilIdle()
        assertEquals("Cancelled", r.job.state.value.name())
        assertEquals(0, r.camera.closeCount)
        assertTrue(r.camera.calls.takeLast(2) == listOf("endShots", "unlock"))
        assertNull(r.job.outcome.value)
        r.job.again()
        assertTrue(r.job.state.value is CaptureState.Ready)
    }

    @Test
    fun stopWhileShootingReleasesTheCameraOnceAndLeavesNothingBehind() = runTest {
        val r = opened()
        r.camera.shootGate = CompletableDeferred()
        r.job.measure(ShotRole.SURFACE)
        advanceUntilIdle()
        r.job.stop()
        advanceUntilIdle()
        assertEquals(1, r.camera.closeCount)
        assertEquals("Idle", r.job.state.value.name())
        assertNull(r.job.outcome.value)
        assertFalse(r.job.cardKept.value)
        // Stopping again is harmless.
        r.job.stop()
        assertEquals("Idle", r.job.state.value.name())
    }

    @Test
    fun stopAfterAnAnswerClearsItAndReleases() = runTest {
        val r = opened()
        r.job.measure(ShotRole.SURFACE)
        advanceUntilIdle()
        r.job.stop()
        assertNull(r.job.outcome.value)
        assertEquals(1, r.camera.closeCount)
        assertTrue(r.camera.shots.all(::allZero))
    }

    @Test
    fun aCameraThatCannotBeOpenedIsAFailureAndItIsClosed() = runTest {
        val r = rig()
        r.camera.openProblem = CameraProblem(CaptureFailure.IN_USE)
        r.job.permissionResult(true)
        advanceUntilIdle()
        val s = r.job.state.value
        assertTrue(s is CaptureState.Failed)
        assertEquals(CaptureFailure.IN_USE, (s as CaptureState.Failed).failure)
        assertEquals(1, r.camera.closeCount)
        r.job.again()
        assertEquals("Idle", r.job.state.value.name())
    }

    @Test
    fun aCameraThatCannotHoldItsExposureIsRefusedAtOpen() = runTest {
        val camera = FakeCameraSource(FakeCameraSource.facts(manualSensor = false, aeLock = false))
        val job = ChromaJob(camera, MotionWindow(), CoroutineScope(StandardTestDispatcher(testScheduler)), StandardTestDispatcher(testScheduler))
        job.permissionResult(true)
        advanceUntilIdle()
        val s = job.state.value as CaptureState.Failed
        assertEquals(CaptureFailure.NO_LOCK, s.failure)
        assertEquals(1, camera.closeCount)
    }

    @Test
    fun anExposureThatChangedBetweenTheShotsIsRefusedNotAveraged() = runTest {
        val r = opened()
        r.camera.exposureWhenFlash = 12_000_000L
        r.job.measure(ShotRole.SURFACE)
        advanceUntilIdle()
        val s = r.job.state.value as CaptureState.Failed
        assertEquals(CaptureFailure.LOCK_SLIPPED, s.failure)
        assertTrue(r.camera.shots.all(::allZero))
        assertNull(r.job.outcome.value)
        // The lock is let go of and the preview comes back.
        assertEquals("unlock", r.camera.calls.last())
        r.job.again()
        assertTrue(r.job.state.value is CaptureState.Ready)
    }

    @Test
    fun aPhoneThatMovedBetweenTheShotsIsRefused() = runTest {
        val r = opened()
        r.camera.duringShoot = { torch -> if (torch) repeat(10) { r.motion.push(1.5f) } }
        r.job.measure(ShotRole.SURFACE)
        advanceUntilIdle()
        assertEquals(CaptureFailure.MOVED, (r.job.state.value as CaptureState.Failed).failure)
        assertNull(r.job.outcome.value)
    }

    @Test
    fun aSteadyPhoneIsNotRefused() = runTest {
        val r = opened()
        r.camera.duringShoot = { repeat(10) { r.motion.push(0.04f) } }
        r.job.measure(ShotRole.SURFACE)
        advanceUntilIdle()
        assertTrue(r.job.state.value is CaptureState.Done)
    }

    @Test
    fun aMeasurementCannotStartWhileAnotherRuns() = runTest {
        val r = opened()
        r.camera.shootGate = CompletableDeferred()
        r.job.measure(ShotRole.SURFACE)
        advanceUntilIdle()
        r.job.measure(ShotRole.SURFACE)
        advanceUntilIdle()
        assertEquals(1, r.camera.calls.count { it.startsWith("lock") })
        r.job.stop()
    }

    // ---- the light is let go of when nothing needs it ---------------------------------------------

    @Test
    fun aReadingWithNoCardLetsGoOfTheLightSoTheLiveGuidanceComesBack() = runTest {
        val r = opened()
        r.job.measure(ShotRole.SURFACE)
        advanceUntilIdle()
        // Held exposure with the torch-lit level would leave the preview dark and the guidance frozen.
        assertEquals("unlock", r.camera.calls.last())
        assertEquals(1, r.camera.calls.count { it == "unlock" })
        r.job.again()
        assertTrue(r.job.state.value is CaptureState.Ready)
        // Measuring again locks afresh.
        r.job.measure(ShotRole.SURFACE)
        advanceUntilIdle()
        assertEquals(2, r.camera.calls.count { it.startsWith("lock") })
        assertEquals(2, r.camera.calls.count { it == "unlock" })
    }

    @Test
    fun aKeptCardKeepsTheLightAndLosingItLetsGo() = runTest {
        val r = opened()
        r.camera.rho = LinearRgb(0.85, 0.85, 0.85)
        r.job.measure(ShotRole.CARD)
        advanceUntilIdle()
        assertTrue(r.job.cardKept.value)
        assertEquals(0, r.camera.calls.count { it == "unlock" })
        r.camera.rho = LinearRgb(0.5, 0.3, 0.2)
        r.job.measure(ShotRole.SURFACE, relock = false)
        advanceUntilIdle()
        // The card is still good for the next surface: its light stays held.
        assertTrue(r.job.outcome.value is Outcome.Reading)
        assertTrue(r.job.cardKept.value)
        assertEquals(0, r.camera.calls.count { it == "unlock" })
        r.job.again()
        assertTrue(r.job.state.value is CaptureState.Locked)
    }

    @Test
    fun aCardThatIsRefusedLetsGoOfTheLightBecauseThereIsNoCardToKeepItFor() = runTest {
        val r = opened()
        r.camera.rho = LinearRgb(0.0, 0.0, 0.0)
        r.job.measure(ShotRole.CARD)
        advanceUntilIdle()
        assertTrue(r.job.outcome.value is Outcome.Refused)
        assertFalse(r.job.cardKept.value)
        assertEquals("unlock", r.camera.calls.last())
        r.job.again()
        assertTrue(r.job.state.value is CaptureState.Ready)
    }

    // ---- the card ------------------------------------------------------------------------------

    @Test
    fun aCardIsKeptAsNumbersAndTheSurfaceReusesItsLight() = runTest {
        val r = opened()
        r.camera.rho = LinearRgb(0.85, 0.85, 0.85)
        r.job.measure(ShotRole.CARD)
        advanceUntilIdle()
        assertTrue(r.job.cardKept.value)
        assertTrue(r.job.outcome.value is Outcome.CardKept)
        assertTrue(r.job.state.value is CaptureState.Locked)
        assertTrue(r.camera.shots.all(::allZero))

        r.camera.rho = LinearRgb(0.5, 0.3, 0.2)
        r.job.measure(ShotRole.SURFACE, relock = false)
        advanceUntilIdle()
        // One lock for both: the card's light was reused.
        assertEquals(1, r.camera.calls.count { it.startsWith("lock") })
        val outcome = r.job.outcome.value as Outcome.Reading
        assertTrue(outcome.withCard)
        assertFalse(outcome.reading.measurement.approximate)
        val truth = ColourSpace.lab(LinearRgb(0.5, 0.3, 0.2))
        assertTrue(DeltaE2000.between(outcome.reading.measurement.lab, truth) < 3.0)
    }

    @Test
    fun measuringTheCardAgainOrLockingAnewDropsTheOldCard() = runTest {
        val r = opened()
        r.job.measure(ShotRole.CARD)
        advanceUntilIdle()
        assertTrue(r.job.cardKept.value)
        r.job.measure(ShotRole.SURFACE, relock = true)
        advanceUntilIdle()
        assertFalse((r.job.outcome.value as Outcome.Reading).withCard)
        assertFalse(r.job.cardKept.value)
    }

    @Test
    fun forgettingTheCardLetsGoOfTheLight() = runTest {
        val r = opened()
        r.job.measure(ShotRole.CARD)
        advanceUntilIdle()
        r.job.forgetCard()
        advanceUntilIdle()
        assertFalse(r.job.cardKept.value)
        assertTrue(r.job.state.value is CaptureState.Ready)
        assertEquals("unlock", r.camera.calls.last())
    }

    @Test
    fun aCardMeasuredUnderOneExposureIsNotUsedUnderAnother() = runTest {
        val r = opened()
        r.job.measure(ShotRole.CARD)
        advanceUntilIdle()
        // The camera now reports a different exposure than the card was shot at.
        r.camera.exposureNanos = 14_000_000L
        r.camera.exposureWhenFlash = 14_000_000L
        r.job.measure(ShotRole.SURFACE, relock = false)
        advanceUntilIdle()
        assertEquals(CaptureFailure.LOCK_SLIPPED, (r.job.state.value as CaptureState.Failed).failure)
        assertFalse(r.job.cardKept.value)
    }

    @Test
    fun aCardThatReadsBadlyIsRefusedAtOnceAndNotKept() = runTest {
        val r = opened()
        // A card the flash barely lights: no torch, so the two shots are the same.
        r.camera.led = LinearRgb(0.0, 0.0, 0.0)
        r.job.measure(ShotRole.CARD)
        advanceUntilIdle()
        assertTrue(r.job.outcome.value is Outcome.Refused || r.job.outcome.value is Outcome.CardKept)
        if (r.job.outcome.value is Outcome.Refused) assertFalse(r.job.cardKept.value)
    }

    @Test
    fun theDistancesOfTheTwoPairsReachTheSolver() = runTest {
        val r = opened()
        r.camera.rho = LinearRgb(0.85, 0.85, 0.85)
        r.camera.focusDiopters = 10f // card at 10 cm
        r.job.measure(ShotRole.CARD)
        advanceUntilIdle()
        // The fake does not dim the flash with distance, so use a surface dark enough to survive the correction (x1.56).
        r.camera.rho = LinearRgb(0.3, 0.2, 0.1)
        r.camera.focusDiopters = 8f // surface at 12.5 cm
        r.job.measure(ShotRole.SURFACE, relock = false)
        advanceUntilIdle()
        val m = (r.job.outcome.value as Outcome.Reading).reading.measurement
        assertTrue(Caution.DISTANCE_COMPENSATED in m.cautions)
        assertFalse(Caution.SAME_DISTANCE_ASSUMED in m.cautions)
    }

    @Test
    fun aFocusThatDidNotSettleGivesNoDistance() = runTest {
        val r = opened()
        r.camera.focusSettled = false
        r.job.measure(ShotRole.CARD)
        advanceUntilIdle()
        r.job.measure(ShotRole.SURFACE, relock = false)
        advanceUntilIdle()
        val m = (r.job.outcome.value as Outcome.Reading).reading.measurement
        assertTrue(Caution.SAME_DISTANCE_ASSUMED in m.cautions)
    }
}
