package com.fotoxplorr.app.chroma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The transition table of the capture, as a pure function. */
class CaptureStateTest {
    private val facts = FakeCameraSource.facts()

    private fun run(start: CaptureState, vararg events: CaptureEvent): CaptureState =
        events.fold(start) { s, e -> reduce(s, e) }

    @Test
    fun theHappyPathGoesIdleOpeningReadyLockingLockedAmbientFlashDone() {
        var s: CaptureState = CaptureState.Idle
        s = reduce(s, CaptureEvent.PermissionGranted)
        assertSame(CaptureState.Opening, s)
        s = reduce(s, CaptureEvent.Opened(facts))
        assertTrue(s is CaptureState.Ready)
        s = reduce(s, CaptureEvent.LockRequested)
        assertTrue(s is CaptureState.Locking)
        s = reduce(s, CaptureEvent.LightLocked)
        assertTrue(s is CaptureState.Locked)
        s = reduce(s, CaptureEvent.ShootRequested)
        assertTrue(s is CaptureState.Ambient)
        s = reduce(s, CaptureEvent.AmbientTaken(ambientOnly = false))
        assertTrue(s is CaptureState.Flash)
        s = reduce(s, CaptureEvent.FlashTaken)
        assertTrue(s is CaptureState.Done)
        assertSame(facts, s.cameraFacts)
    }

    @Test
    fun withoutAFlashTheFirstShotFinishesTheCapture() {
        val s = run(
            CaptureState.Locked(facts),
            CaptureEvent.ShootRequested,
            CaptureEvent.AmbientTaken(ambientOnly = true),
        )
        assertTrue(s is CaptureState.Done)
    }

    @Test
    fun theCameraIsOnlyOpenedAfterTheAnswerToTheQuestion() {
        assertSame(CaptureState.NeedsPermission, reduce(CaptureState.Idle, CaptureEvent.PermissionNeeded))
        assertSame(CaptureState.PermissionDenied, reduce(CaptureState.NeedsPermission, CaptureEvent.PermissionRefused))
        assertSame(CaptureState.Opening, reduce(CaptureState.PermissionDenied, CaptureEvent.PermissionGranted))
        // A refusal does not open anything, and nothing else opens it.
        assertSame(CaptureState.PermissionDenied, reduce(CaptureState.PermissionDenied, CaptureEvent.Opened(facts)))
        assertSame(CaptureState.NeedsPermission, reduce(CaptureState.PermissionDenied, CaptureEvent.PermissionNeeded))
    }

    @Test
    fun stoppedFromAnywhereReleasesToIdle() {
        val states = listOf(
            CaptureState.NeedsPermission,
            CaptureState.PermissionDenied,
            CaptureState.Opening,
            CaptureState.Ready(facts),
            CaptureState.Locking(facts),
            CaptureState.Locked(facts),
            CaptureState.Ambient(facts),
            CaptureState.Flash(facts),
            CaptureState.Done(facts),
            CaptureState.Failed(CaptureFailure.MOVED, facts),
            CaptureState.Cancelled(facts),
        )
        for (s in states) assertSame("$s", CaptureState.Idle, reduce(s, CaptureEvent.Stopped))
    }

    @Test
    fun cancelWorksOnlyWhileShooting() {
        assertTrue(reduce(CaptureState.Locking(facts), CaptureEvent.Cancel) is CaptureState.Cancelled)
        assertTrue(reduce(CaptureState.Ambient(facts), CaptureEvent.Cancel) is CaptureState.Cancelled)
        assertTrue(reduce(CaptureState.Flash(facts), CaptureEvent.Cancel) is CaptureState.Cancelled)
        val ready = CaptureState.Ready(facts)
        assertSame(ready, reduce(ready, CaptureEvent.Cancel))
        val done = CaptureState.Done(facts)
        assertSame(done, reduce(done, CaptureEvent.Cancel))
    }

    @Test
    fun aCancelledOrFailedCaptureGoesBackToThePreviewWhenTheCameraIsStillOpen() {
        assertTrue(reduce(CaptureState.Cancelled(facts), CaptureEvent.Reset(keepLock = false)) is CaptureState.Ready)
        assertTrue(reduce(CaptureState.Failed(CaptureFailure.MOVED, facts), CaptureEvent.Reset(false)) is CaptureState.Ready)
        assertTrue(reduce(CaptureState.Failed(CaptureFailure.LOCK_SLIPPED, facts), CaptureEvent.Reset(false)) is CaptureState.Ready)
    }

    @Test
    fun aFailureThatClosedTheCameraGoesBackToIdleSoTheScreenOpensItAgain() {
        for (f in CaptureFailure.entries.filter { it.closesCamera }) {
            assertSame("$f", CaptureState.Idle, reduce(CaptureState.Failed(f, facts), CaptureEvent.Reset(false)))
        }
        // A failure while opening has no facts to go back to either.
        assertSame(CaptureState.Idle, reduce(CaptureState.Failed(CaptureFailure.NO_LOCK, null), CaptureEvent.Reset(false)))
    }

    @Test
    fun doneGoesBackToTheHeldLightOrToThePreview() {
        assertTrue(reduce(CaptureState.Done(facts), CaptureEvent.Reset(keepLock = true)) is CaptureState.Locked)
        assertTrue(reduce(CaptureState.Done(facts), CaptureEvent.Reset(keepLock = false)) is CaptureState.Ready)
        assertTrue(reduce(CaptureState.Locked(facts), CaptureEvent.Reset(keepLock = false)) is CaptureState.Ready)
    }

    @Test
    fun aFailureAnywhereInTheSequenceIsRecordedWithItsReason() {
        for (s in listOf(CaptureState.Opening, CaptureState.Ready(facts), CaptureState.Locking(facts), CaptureState.Locked(facts), CaptureState.Ambient(facts), CaptureState.Flash(facts))) {
            val after = reduce(s, CaptureEvent.Fail(CaptureFailure.TIMED_OUT))
            assertTrue("$s", after is CaptureState.Failed)
            assertEquals(CaptureFailure.TIMED_OUT, (after as CaptureState.Failed).failure)
        }
    }

    @Test
    fun eventsThatDoNotFitAreIgnored() {
        val ready = CaptureState.Ready(facts)
        assertSame(ready, reduce(ready, CaptureEvent.FlashTaken))
        assertSame(ready, reduce(ready, CaptureEvent.ShootRequested))
        assertSame(CaptureState.Idle, reduce(CaptureState.Idle, CaptureEvent.FlashTaken))
        val ambient = CaptureState.Ambient(facts)
        assertSame(ambient, reduce(ambient, CaptureEvent.LockRequested))
    }

    @Test
    fun everyFailureHasASingleShortSentence() {
        for (f in CaptureFailure.entries) {
            assertTrue("$f", f.message.endsWith(".") && f.message.count { it == '.' } == 1 && f.message.length < 90)
        }
    }
}
