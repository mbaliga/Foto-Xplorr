package com.fotoxplorr.app.sync

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncRetryAndRunTest {
    @Test
    fun `backoff is bounded jittered and deterministic for a seed`() {
        val policy = SyncRetryPolicy(maxAttempts = 6, baseMillis = 1_000, maxMillis = 8_000)
        val a = (2..7).map { policy.delayBefore(it, Random(42 + it)) }
        val b = (2..7).map { policy.delayBefore(it, Random(42 + it)) }
        assertEquals(a, b)
        // Equal jitter: at least half the step, at most the step; the step doubles then stops at the maximum.
        val steps = listOf(1_000L, 2_000L, 4_000L, 8_000L, 8_000L, 8_000L)
        a.forEachIndexed { i, d -> assertTrue("attempt ${i + 2}: $d", d in steps[i] / 2..steps[i]) }
    }

    @Test
    fun `jitter actually varies`() {
        val policy = SyncRetryPolicy()
        val delays = (1..30).map { policy.delayBefore(3, Random(it)) }.toSet()
        assertTrue(delays.size > 5)
    }

    @Test
    fun `only transient failures are retried and only within the attempt bound`() {
        val policy = SyncRetryPolicy(maxAttempts = 3)
        assertTrue(policy.shouldRetry(SyncFailure.TRANSIENT, 1))
        assertTrue(policy.shouldRetry(SyncFailure.TRANSIENT, 2))
        assertFalse(policy.shouldRetry(SyncFailure.TRANSIENT, 3))
        for (f in SyncFailure.entries.filter { it != SyncFailure.TRANSIENT }) assertFalse(f.name, policy.shouldRetry(f, 1))
        assertFalse(SyncRetryPolicy.NONE.shouldRetry(SyncFailure.TRANSIENT, 1))
    }

    @Test
    fun `huge attempt numbers do not overflow`() {
        val policy = SyncRetryPolicy(maxAttempts = 100, baseMillis = 1_000, maxMillis = 60_000)
        assertTrue(policy.delayBefore(99, Random(1)) in 30_000..60_000)
    }

    private fun reduceAll(vararg events: SyncRunEvent): SyncRunState = events.fold(SyncRunState.Idle as SyncRunState) { s, e -> SyncRunMachine.reduce(s, e) }

    @Test
    fun `a run goes idle preparing running finished`() {
        val t = SyncTally(uploaded = 1)
        val state = reduceAll(
            SyncRunEvent.Start,
            SyncRunEvent.Planned(2, 200, SyncTally()),
            SyncRunEvent.Bytes(50),
            SyncRunEvent.FileFinished(100, t),
            SyncRunEvent.Bytes(100),
            SyncRunEvent.FileFinished(100, t.copy(uploaded = 2)),
            SyncRunEvent.Completed,
        )
        assertEquals(SyncRunState.Finished(t.copy(uploaded = 2)), state)
    }

    @Test
    fun `progress is bytes done over bytes total and never beyond one`() {
        val running = reduceAll(SyncRunEvent.Start, SyncRunEvent.Planned(2, 200, SyncTally()), SyncRunEvent.FileFinished(100, SyncTally()), SyncRunEvent.Bytes(50)) as SyncRunState.Running
        assertEquals(0.75f, running.progress!!, 0.0001f)
        val over = running.copy(bytesInFile = 10_000)
        assertEquals(1f, over.progress!!, 0.0001f)
        assertEquals(null, SyncRunState.Running(0, 0, 0, 0, 0, SyncTally()).progress)
    }

    @Test
    fun `cancelling a running run pauses it with what is left`() {
        val state = reduceAll(SyncRunEvent.Start, SyncRunEvent.Planned(5, 500, SyncTally()), SyncRunEvent.FileFinished(100, SyncTally(uploaded = 1)), SyncRunEvent.Cancelled)
        assertEquals(SyncRunState.Paused(SyncTally(uploaded = 1), 4), state)
    }

    @Test
    fun `cancelling while preparing returns to idle`() {
        assertEquals(SyncRunState.Idle, reduceAll(SyncRunEvent.Start, SyncRunEvent.Cancelled))
    }

    @Test
    fun `an abort carries the reason and the tally`() {
        val state = reduceAll(SyncRunEvent.Start, SyncRunEvent.Planned(1, 1, SyncTally(failed = 1)), SyncRunEvent.Aborted(SyncFailure.AUTH, "no"))
        assertEquals(SyncRunState.Failed(SyncFailure.AUTH, "no", SyncTally(failed = 1)), state)
    }

    @Test
    fun `events out of order leave the state alone`() {
        assertEquals(SyncRunState.Idle, reduceAll(SyncRunEvent.Bytes(5), SyncRunEvent.Completed, SyncRunEvent.Cancelled))
        val finished = reduceAll(SyncRunEvent.Start, SyncRunEvent.Completed)
        assertEquals(finished, SyncRunMachine.reduce(finished, SyncRunEvent.Bytes(3)))
        // A second Start while running does not restart it.
        val running = reduceAll(SyncRunEvent.Start, SyncRunEvent.Planned(1, 1, SyncTally()))
        assertEquals(running, SyncRunMachine.reduce(running, SyncRunEvent.Start))
        // A finished run can be started again.
        assertEquals(SyncRunState.Preparing, SyncRunMachine.reduce(finished, SyncRunEvent.Start))
    }

    @Test
    fun `guard refuses mobile data by default and no network at all`() {
        assertEquals(null, SyncGuard.check(needsNetwork = false, wifiOnly = true, allowMobileData = false, network = SyncNetwork.NONE))
        assertEquals(SyncStartRefusal.NO_NETWORK, SyncGuard.check(true, true, false, SyncNetwork.NONE))
        assertEquals(SyncStartRefusal.WAITING_FOR_WIFI, SyncGuard.check(true, true, false, SyncNetwork.METERED))
        assertEquals(null, SyncGuard.check(true, true, true, SyncNetwork.METERED))
        assertEquals(null, SyncGuard.check(true, false, false, SyncNetwork.METERED))
        assertEquals(null, SyncGuard.check(true, true, false, SyncNetwork.UNMETERED))
        assertEquals(SyncStartRefusal.NO_NETWORK, SyncGuard.check(true, false, true, SyncNetwork.NONE))
    }
}
