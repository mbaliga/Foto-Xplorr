package com.fotoxplorr.core.organize.fileops

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Property-style tests over random batches (deterministic seeds). The invariants:
 *  1. no file under a final name is ever partial, at ANY crash point;
 *  2. after the crash, one resume completes the batch;
 *  3. every original content still exists somewhere (in place, copied, or in a trash): nothing is lost;
 *  4. nothing named *.fotoz-part survives a completed run;
 *  5. the engine only deletes its own temp files.
 */
class PropertyTest {
    private class Scenario(val h: Harness, val ops: List<FileOp>, val conflicts: ConflictSettings, val original: List<String>)

    private fun scenario(seed: Int): Scenario {
        val rnd = Lcg(seed.toLong())
        val h = Harness(chunk = 1 + rnd.next(40))
        val roots = listOf("/phone", "/usb", "/sd")
        val names = listOf("a.jpg", "b.jpg", "c.png", "d.jpg", "e.mp4")
        val ops = ArrayList<FileOp>()
        val count = 1 + rnd.next(5)
        val used = HashSet<String>()
        repeat(count) { i ->
            val root = roots[rnd.next(3)]
            val dir = "$root/src${rnd.next(2)}"
            val name = names[rnd.next(names.size)]
            val src = "$dir/$name"
            if (!used.add(src)) return@repeat
            h.fs.put(src, bytesOf(seed * 10 + i, 1 + rnd.next(200)))
            val destRoot = roots[rnd.next(3)]
            val dest = "$destRoot/dest${rnd.next(2)}"
            // Pre-existing files make conflicts likely.
            if (rnd.next(2) == 0) h.fs.put("$dest/$name", bytesOf(900 + i, 1 + rnd.next(50)))
            ops += when (rnd.next(5)) {
                0, 1 -> FileOp.Copy(src, dest)
                2 -> FileOp.Move(src, dest)
                3 -> FileOp.Rename(src, names[rnd.next(names.size)])
                else -> FileOp.Trash(src)
            }
        }
        val policy = listOf(ConflictPolicy.SKIP, ConflictPolicy.KEEP_BOTH, ConflictPolicy.REPLACE)[rnd.next(3)]
        val settings = ConflictSettings(policy, replaceConsent = true)
        return Scenario(h, ops, settings, h.fs.contentHashes())
    }

    private fun assertNoDataLost(s: Scenario) {
        val now = s.h.fs.contentHashes().toMutableList()
        for (o in s.original) {
            assertTrue(now.remove(o), "an original content vanished")
        }
    }

    private fun assertOnlyTempsDeleted(s: Scenario) {
        assertEquals(emptyList(), s.h.fs.deletedOriginals())
    }

    @Test
    fun randomBatchesCompleteWithNothingLost() {
        for (seed in 1..60) {
            val s = scenario(seed)
            val plan = s.h.plan(s.ops, s.conflicts)
            val r = s.h.engine.run(plan)
            // Only SOURCE_GONE-style failures from earlier random steps are acceptable (a rename may consume a later source).
            for (res in r.results) {
                assertTrue(
                    res.status != StepStatus.FAILED || res.error == FileOpsError.SOURCE_GONE,
                    "seed $seed step ${res.stepId} ${res.error} ${res.message}",
                )
            }
            assertEquals(emptyList(), s.h.partFiles(), "seed $seed")
            assertNoDataLost(s)
            assertOnlyTempsDeleted(s)
        }
    }

    @Test
    fun crashingAtEveryOperationThenResumingNeverLosesOrHalfWritesAnything() {
        for (seed in 1..25) {
            var crashAt = 1
            while (true) {
                val s = scenario(seed)
                val plan = s.h.plan(s.ops, s.conflicts)
                s.h.fs.crashAtOp = crashAt
                var crashed = false
                try {
                    s.h.engine.run(plan)
                } catch (e: SimulatedCrash) {
                    crashed = true
                }
                s.h.fs.crashAtOp = null
                // After the crash: no partial file under a final name (every non-temp file is a full original or copy).
                assertNoDataLost(s)
                assertNoPartialFinals(s)
                // One resume (a fresh engine over the same journal) finishes the batch.
                val engine2 = FileOpsEngine(s.h.fs, FileJournal(s.h.fs, "/app/journal"), s.h.clock, chunkSize = 7)
                val r = engine2.run(plan)
                for (res in r.results) {
                    assertTrue(
                        res.status != StepStatus.FAILED || res.error == FileOpsError.SOURCE_GONE,
                        "seed $seed crash $crashAt step ${res.stepId} ${res.error} ${res.message}",
                    )
                    assertTrue(res.status != StepStatus.CANCELLED && res.status != StepStatus.NOT_RUN)
                }
                assertEquals(emptyList(), s.h.partFiles(), "seed $seed crash $crashAt")
                assertNoDataLost(s)
                assertOnlyTempsDeleted(s)
                if (!crashed) break
                crashAt++
                assertTrue(crashAt < 2000, "runaway sweep")
            }
        }
    }

    /** A final name never holds partial content: every non-temp file's bytes equal some original content. */
    private fun assertNoPartialFinals(s: Scenario) {
        val known = s.original.toSet()
        for ((path, node) in s.h.fs.files) {
            if (path.endsWith(".fotoz-part") || path.startsWith("/app/") || path.endsWith(".nomedia") || path.contains("manifest.json")) continue
            assertTrue(hashOf(node.bytes) in known, "partial or foreign content under final name $path")
        }
    }

    @Test
    fun unpluggingTheDestinationAtEveryByteBoundaryLeavesNoPartialAndResumes() {
        val data = bytesOf(7, 97)
        for (budget in 1..97 step 3) {
            val h = Harness(chunk = 10)
            h.fs.put("/phone/a.jpg", data)
            h.fs.put("/phone/b.jpg", data.reversedArray())
            val plan = h.plan(listOf(FileOp.Copy("/phone/a.jpg", "/usb/out"), FileOp.Copy("/phone/b.jpg", "/usb/out")))
            h.fs.writeBudget = budget.toLong()
            h.fs.unplugRootOnWriteBudget = "/usb"
            val r1 = h.engine.run(plan)
            assertEquals(FileOpsError.DESTINATION_GONE, r1.abortedBy, "budget $budget")
            h.fs.replug("/usb")
            val finals = h.fs.files.filter { it.key.startsWith("/usb/out/") && !it.key.endsWith(".fotoz-part") }
            for ((_, n) in finals) assertTrue(n.bytes.contentEquals(data) || n.bytes.contentEquals(data.reversedArray()))
            assertOk(h.engine.run(plan))
            assertEqualsBytes(data, h.fs.files["/usb/out/a.jpg"]?.bytes)
            assertEqualsBytes(data.reversedArray(), h.fs.files["/usb/out/b.jpg"]?.bytes)
            assertEquals(emptyList(), h.partFiles(), "budget $budget")
        }
    }
}
