package com.fotoxplorr.core.organize.fileops

import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Harness(chunk: Int = 4, val fs: FakeFileSystem = FakeFileSystem(), val clock: TestClock = TestClock()) {
    val journal = FileJournal(fs, "/app/journal")
    val engine = FileOpsEngine(fs, journal, clock, chunkSize = chunk)
    val planner = FileOpsPlanner(fs)

    fun plan(ops: List<FileOp>, conflicts: ConflictSettings = ConflictSettings(ConflictPolicy.KEEP_BOTH)) =
        planner.plan(ops, conflicts)

    fun partFiles(): List<String> = fs.files.keys.filter { it.endsWith(".fotoz-part") }
}

fun bytesOf(seed: Int, size: Int): ByteArray = ByteArray(size) { ((it * 31 + seed * 17 + it / 7) and 0xff).toByte() }

fun hashOf(b: ByteArray): String = Sha256().also { it.update(b) }.digestHex()

fun assertOk(r: RunReport) {
    assertTrue(r.completed, "run should complete but was ${r.results.map { it.status to it.error }} aborted=${r.abortedBy}")
}

fun assertEqualsBytes(expected: ByteArray, actual: ByteArray?) {
    assertTrue(actual != null && expected.contentEquals(actual), "bytes differ")
}

fun assertEq(a: Any?, b: Any?) = assertEquals(a, b)
