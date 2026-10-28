package com.fotoxplorr.app.chroma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure guidance: where to hold the phone, whether it is steady, whether the lock slipped. */
class ChromaGuidanceTest {

    @Test
    fun aBrightRoomWantsThePhoneNearerThanADimOne() {
        assertEquals(HoldDistance.NEAR_CM, HoldDistance.targetCm(minFocusDiopters = null, brightRoom = true))
        assertEquals(HoldDistance.USUAL_CM, HoldDistance.targetCm(minFocusDiopters = null, brightRoom = false))
    }

    @Test
    fun theTargetIsNeverNearerThanTheLensCanFocus() {
        // 5 dioptres is 20 cm: the target is 22, even in a bright room.
        assertEquals(22, HoldDistance.targetCm(5f, brightRoom = true))
        assertEquals(22, HoldDistance.targetCm(5f, brightRoom = false))
        // A lens that focuses to 10 cm leaves the usual distances alone.
        assertEquals(12, HoldDistance.targetCm(10f, brightRoom = true))
    }

    @Test
    fun theTargetStopsAtTheFarthestAndIgnoresAFixedFocusLens() {
        assertEquals(HoldDistance.FARTHEST_CM, HoldDistance.targetCm(1f, brightRoom = false))
        assertEquals(HoldDistance.USUAL_CM, HoldDistance.targetCm(0f, brightRoom = false))
    }

    @Test
    fun theDistanceHintSaysCloserFartherOrGood() {
        assertEquals(DistanceHint.UNKNOWN, HoldDistance.hint(null, 15))
        assertEquals(DistanceHint.GOOD, HoldDistance.hint(0.15, 15))
        assertEquals(DistanceHint.GOOD, HoldDistance.hint(0.17, 15))
        assertEquals(DistanceHint.CLOSER, HoldDistance.hint(0.25, 15))
        assertEquals(DistanceHint.FARTHER, HoldDistance.hint(0.08, 15))
    }

    @Test
    fun theRoomIsBrightOnlyWhenTheExposureSaysSo() {
        assertTrue(RoomLight.isBright(exposureNanos = 2_000_000L, iso = 100)) // 1/500 s at ISO 100
        assertFalse(RoomLight.isBright(exposureNanos = 16_000_000L, iso = 400)) // 1/60 s at ISO 400
        assertFalse(RoomLight.isBright(null, 100))
        assertFalse(RoomLight.isBright(2_000_000L, null))
        assertFalse(RoomLight.isBright(0L, 100))
    }

    @Test
    fun motionIsUnknownUntilTheGyroscopeReports() {
        val m = MotionWindow()
        assertNull(m.steady)
        m.push(0.02f)
        assertEquals(true, m.steady)
        repeat(20) { m.push(1.0f) }
        assertEquals(false, m.steady)
        repeat(60) { m.push(0.02f) }
        assertEquals(true, m.steady)
    }

    @Test
    fun aSingleJoltDoesNotFlipTheHint() {
        val m = MotionWindow()
        repeat(10) { m.push(0.02f) }
        m.push(0.3f)
        assertEquals(true, m.steady)
    }

    @Test
    fun theWindowSeesMovementOnlyInsideItself() {
        val m = MotionWindow()
        repeat(10) { m.push(1.0f) } // shaking before the shots
        repeat(60) { m.push(0.01f) }
        m.beginWindow()
        assertFalse(m.moved)
        repeat(60) { m.push(0.02f) }
        m.endWindow()
        assertFalse(m.moved)
        m.beginWindow()
        repeat(5) { m.push(1.2f) }
        assertTrue(m.moved)
        m.endWindow()
        // A window that opens while the phone is still shaking starts out moved.
        m.beginWindow()
        assertTrue(m.moved)
        m.endWindow()
        repeat(80) { m.push(0.01f) }
        m.beginWindow()
        assertFalse(m.moved)
    }

    @Test
    fun aNanReadingIsIgnored() {
        val m = MotionWindow()
        m.push(Float.NaN)
        assertNull(m.steady)
    }

    @Test
    fun aPairTakenUnderTheSameLightHasNotSlipped() {
        val a = ShotFacts(10_000_000L, 100, floatArrayOf(1.9f, 1f, 1f, 1.5f), 10f, true)
        val b = ShotFacts(10_000_000L, 100, floatArrayOf(1.9f, 1f, 1f, 1.5f), 10f, true)
        assertFalse(LockCheck.slipped(a, b))
        // Within the tolerance is the same light.
        assertFalse(LockCheck.slipped(a, ShotFacts(10_100_000L, 100, floatArrayOf(1.91f, 1f, 1f, 1.5f), 10f, true)))
    }

    @Test
    fun aChangedExposureIsoOrWhiteBalanceIsASlip() {
        val a = ShotFacts(10_000_000L, 100, floatArrayOf(1.9f, 1f, 1f, 1.5f), 10f, true)
        assertTrue(LockCheck.slipped(a, ShotFacts(12_000_000L, 100, a.gains, 10f, true)))
        assertTrue(LockCheck.slipped(a, ShotFacts(10_000_000L, 200, a.gains, 10f, true)))
        assertTrue(LockCheck.slipped(a, ShotFacts(10_000_000L, 100, floatArrayOf(1.9f, 1f, 1f, 1.8f), 10f, true)))
    }

    @Test
    fun whatTheCameraDoesNotReportCannotProveASlip() {
        val a = ShotFacts(10_000_000L, 100, null, null, false)
        val b = ShotFacts(null, null, null, null, false)
        assertFalse(LockCheck.slipped(a, b))
    }

    @Test
    fun withoutAWhiteBalanceLockAMissingGainCannotProveTheLightHeld() {
        val withGains = ShotFacts(10_000_000L, 100, floatArrayOf(1.9f, 1f, 1f, 1.5f), 10f, true)
        val noGains = ShotFacts(10_000_000L, 100, null, 10f, true)
        // The balance was locked: a camera that says nothing about gains is trusted as before.
        assertFalse(LockCheck.slipped(noGains, noGains, awbLocked = true))
        // It was not: the pair could have been taken under two balances, and nothing says otherwise.
        assertTrue(LockCheck.slipped(noGains, noGains, awbLocked = false))
        assertTrue(LockCheck.slipped(withGains, noGains, awbLocked = false))
        // The gains are reported and agree: fine without a lock.
        assertFalse(LockCheck.slipped(withGains, withGains, awbLocked = false))
        assertTrue(LockCheck.slipped(withGains, ShotFacts(10_000_000L, 100, floatArrayOf(1.9f, 1f, 1f, 1.8f), 10f, true), awbLocked = false))
    }

    @Test
    fun aWhiteBalanceThatCanNeitherBeHeldNorWatchedIsRefusedAtOpen() {
        assertEquals(LockKind.NONE, FakeCameraSource.facts(awbLock = false, reportsGains = false).lockKind)
        // Either one is enough: held, or watched.
        assertEquals(LockKind.MANUAL, FakeCameraSource.facts(awbLock = true, reportsGains = false).lockKind)
        assertEquals(LockKind.MANUAL, FakeCameraSource.facts(awbLock = false, reportsGains = true).lockKind)
        assertEquals(LockKind.AE_LOCK, FakeCameraSource.facts(manualSensor = false, awbLock = false, reportsGains = true).lockKind)
    }

    @Test
    fun anUncalibratedLensDistanceIsNeverUsedAndAnApproximateOneIsHeldTight() {
        assertFalse(FocusCalibration.UNCALIBRATED.usable)
        assertTrue(FocusCalibration.APPROXIMATE.usable && FocusCalibration.CALIBRATED.usable)
        assertEquals(FlashSolver.DISTANCE_LIMIT, FakeCameraSource.facts().distanceLimit, 0.0)
        assertEquals(
            FlashSolver.APPROXIMATE_FOCUS_DISTANCE_LIMIT,
            FakeCameraSource.facts(focusCalibration = FocusCalibration.APPROXIMATE).distanceLimit,
            0.0,
        )
    }

    @Test
    fun theLockKindPrefersManualThenTheAeLockThenRefuses() {
        assertEquals(LockKind.MANUAL, FakeCameraSource.facts(manualSensor = true, aeLock = true).lockKind)
        assertEquals(LockKind.AE_LOCK, FakeCameraSource.facts(manualSensor = false, aeLock = true).lockKind)
        assertEquals(LockKind.NONE, FakeCameraSource.facts(manualSensor = false, aeLock = false).lockKind)
    }

    @Test
    fun aShotsDistanceNeedsSettledFocus() {
        val frame = ChromaTestKit.flat(100, 100, 100)
        assertEquals(0.2, Shot(frame, ShotFacts(1L, 1, null, 5f, true)).distanceMetres!!, 1e-9)
        assertNull(Shot(frame, ShotFacts(1L, 1, null, 5f, false)).distanceMetres)
        assertNull(Shot(frame, ShotFacts(1L, 1, null, 0f, true)).distanceMetres)
        assertNull(Shot(frame, ShotFacts(1L, 1, null, null, true)).distanceMetres)
    }

    @Test
    fun clearingAShotZeroesItsPixels() {
        val s = Shot(ChromaTestKit.flat(200, 100, 50), ShotFacts(1L, 1, null, null, false))
        s.clear()
        assertTrue(s.frame.argb.all { it == 0 })
    }

    @Test
    fun theLiveViewWarnsOnlyAboveTheMeasurementsOwnLimit() {
        assertFalse(LiveView().washedOut)
        assertFalse(LiveView(clippedShare = PatchSampler.MAX_CLIPPED_SHARE).washedOut)
        assertTrue(LiveView(clippedShare = PatchSampler.MAX_CLIPPED_SHARE + 0.01).washedOut)
    }
}
