package com.fotoxplorr.app.jobs

import com.fotoxplorr.app.hyle.ActivityKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The state-transition contract every call site in `FotoXplorrActivity` relies on: a job is
 * visible in [JobRunner.jobs] for exactly as long as it is running, its progress updates land on
 * the SAME entry, and every outcome -- success, a thrown exception, or a cancellation -- reaches
 * [JobRunner.outcomes] exactly once.
 */
class JobRunnerTest {

    @Test
    fun `a launched job appears in the running list until it finishes`() = runTest {
        val runner = JobRunner(this)
        val gate = CompletableDeferred<Unit>()

        runner.launch("Test job", ActivityKind.EXPORTING) { onProgress ->
            onProgress(null)
            gate.await()
            Result.success("done")
        }
        advanceUntilIdle()

        assertEquals(1, runner.jobs.value.size)
        assertEquals("Test job", runner.jobs.value.first().title)
        assertEquals(ActivityKind.EXPORTING, runner.jobs.value.first().kind)

        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue(runner.jobs.value.isEmpty())
    }

    @Test
    fun `progress updates land on the running job`() = runTest {
        val runner = JobRunner(this)
        val gate = CompletableDeferred<Unit>()

        runner.launch("Progress job", ActivityKind.COPYING) { onProgress ->
            onProgress(0.25f)
            gate.await()
            Result.success(Unit)
        }
        advanceUntilIdle()

        assertEquals(0.25f, runner.jobs.value.first().progress)

        gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `a successful job's outcome carries its result`() = runTest {
        val runner = JobRunner(this)
        val outcomes = mutableListOf<JobOutcome<*>>()
        val collector = launch { runner.outcomes.collect { outcomes.add(it) } }

        runner.launch("Zip export", ActivityKind.EXPORTING) { Result.success("Exported.") }
        advanceUntilIdle()

        assertEquals(1, outcomes.size)
        assertTrue(outcomes.single().result.isSuccess)
        assertEquals("Exported.", outcomes.single().result.getOrNull())
        collector.cancel()
    }

    @Test
    fun `a thrown exception inside the block is reported as a failure, not left to escape`() = runTest {
        val runner = JobRunner(this)
        val outcomes = mutableListOf<JobOutcome<*>>()
        val collector = launch { runner.outcomes.collect { outcomes.add(it) } }

        runner.launch<Unit>("Convert", ActivityKind.EXPORTING) { throw IllegalStateException("boom") }
        advanceUntilIdle()

        assertTrue(outcomes.single().result.isFailure)
        assertEquals("boom", outcomes.single().result.exceptionOrNull()?.message)
        assertTrue(runner.jobs.value.isEmpty())
        collector.cancel()
    }

    @Test
    fun `cancelling a job removes it from the running list`() = runTest {
        val runner = JobRunner(this)
        val handle = runner.launch<Unit>("Cancel me", ActivityKind.MOVING) { onProgress ->
            onProgress(null)
            awaitCancellation()
        }
        advanceUntilIdle()
        assertEquals(1, runner.jobs.value.size)

        handle.cancel()
        advanceUntilIdle()

        assertTrue(runner.jobs.value.isEmpty())
    }

    @Test
    fun `cancelAll stops every running job`() = runTest {
        val runner = JobRunner(this)
        repeat(3) { index ->
            runner.launch<Unit>("Job $index", ActivityKind.EXPORTING) { onProgress ->
                onProgress(null)
                awaitCancellation()
            }
        }
        advanceUntilIdle()
        assertEquals(3, runner.jobs.value.size)

        runner.cancelAll()
        advanceUntilIdle()

        assertTrue(runner.jobs.value.isEmpty())
    }

    @Test
    fun `hasRunningJobs reflects the current list`() = runTest {
        val runner = JobRunner(this)
        assertFalse(runner.hasRunningJobs())

        val gate = CompletableDeferred<Unit>()
        runner.launch("Job", ActivityKind.EXPORTING) { gate.await(); Result.success(Unit) }
        advanceUntilIdle()
        assertTrue(runner.hasRunningJobs())

        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(runner.hasRunningJobs())
    }

    @Test
    fun `each job gets its own id and two jobs never collide`() = runTest {
        val runner = JobRunner(this)
        val gate = CompletableDeferred<Unit>()
        runner.launch("A", ActivityKind.COPYING) { gate.await(); Result.success(Unit) }
        runner.launch("B", ActivityKind.MOVING) { gate.await(); Result.success(Unit) }
        advanceUntilIdle()

        val ids = runner.jobs.value.map { it.id }
        assertEquals(2, ids.toSet().size)

        gate.complete(Unit)
        advanceUntilIdle()
    }
}
