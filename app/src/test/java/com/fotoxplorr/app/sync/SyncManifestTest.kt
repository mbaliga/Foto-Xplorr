package com.fotoxplorr.app.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncManifestTest {
    private val sha = "a".repeat(64)
    private fun done(key: String = "DCIM/a.jpg", size: Long = 10, mtime: Long = 5, path: String = "Fotoz/2023/2023-11/a.jpg") =
        SyncDone(key, size, mtime, path, size, sha, 99)

    @Test
    fun `records survive a round trip through text`() {
        val m = SyncManifest()
        m.recordDone(done())
        m.recordBegun(SyncBegun("DCIM/b.jpg", "Fotoz/2023/2023-11/b.jpg", 7, 8))
        val back = SyncManifest.decode(m.encode())
        assertEquals(done(), back.doneFor("DCIM/a.jpg"))
        assertEquals(SyncBegun("DCIM/b.jpg", "Fotoz/2023/2023-11/b.jpg", 7, 8), back.begunFor("DCIM/b.jpg"))
        assertEquals(0, back.ignoredLines)
    }

    @Test
    fun `a done line closes the begun line of its key`() {
        val text = SyncManifestCodec.HEADER + "\n" +
            SyncManifestCodec.begunLine(SyncBegun("k", "Fotoz/x.jpg", 1, 2)) +
            SyncManifestCodec.doneLine(done(key = "k", path = "Fotoz/x.jpg"))
        val m = SyncManifest.decode(text)
        assertNull(m.begunFor("k"))
        assertTrue(m.doneFor("k") != null)
    }

    @Test
    fun `later lines win`() {
        val text = SyncManifestCodec.doneLine(done(size = 1)) + SyncManifestCodec.doneLine(done(size = 2))
        assertEquals(2L, SyncManifest.decode(text).doneFor("DCIM/a.jpg")!!.sizeBytes)
    }

    @Test
    fun `a torn last line is ignored and counted`() {
        val good = SyncManifestCodec.doneLine(done())
        val torn = SyncManifestCodec.doneLine(done(key = "other")).dropLast(9) // cut short, no newline
        val m = SyncManifest.decode(SyncManifestCodec.HEADER + "\n" + good + torn)
        assertEquals(1, m.done.size)
        assertEquals(1, m.ignoredLines)
    }

    @Test
    fun `corrupt and unknown lines are skipped, never fatal`() {
        val text = listOf(
            SyncManifestCodec.HEADER,
            "garbage with no tabs",
            "D\tonly\tthree",
            "D\tk\tNaN\t1\tFotoz/a\t1\t-\t1",
            "D\tk\t1\t1\t../escape\t1\t-\t1",
            "Z\tfuture\trecord",
            SyncManifestCodec.doneLine(done()).trimEnd('\n'),
            "",
        ).joinToString("\n") + "\n"
        val m = SyncManifest.decode(text)
        assertEquals(1, m.done.size)
        assertEquals(5, m.ignoredLines)
        // A record kind this version does not know is kept when the file is rewritten.
        assertTrue(m.encode().contains("Z\tfuture\trecord"))
    }

    @Test
    fun `empty and header-only text decode to an empty manifest`() {
        assertEquals(0, SyncManifest.decode("").done.size)
        assertEquals(0, SyncManifest.decode(SyncManifestCodec.HEADER + "\n").done.size)
        assertEquals(1, SyncManifest.decode("no newline at all").ignoredLines)
    }

    @Test
    fun `tabs newlines percent signs and unicode in a key survive`() {
        val key = "DCIM/we\tird\nna%me 日本.jpg"
        val m = SyncManifest()
        m.recordDone(done(key = key))
        assertEquals(key, SyncManifest.decode(m.encode()).doneFor(key)!!.key)
    }

    @Test
    fun `a malformed hash is dropped but the record is kept`() {
        val line = "D\tk\t1\t1\tFotoz/a.jpg\t1\tnot-a-hash\t1\n"
        val m = SyncManifest.decode(line)
        assertNull(m.doneFor("k")!!.sha256)
    }

    @Test
    fun `up to date means same size and same modified time`() {
        val m = SyncManifest()
        m.recordDone(done(size = 10, mtime = 5))
        assertTrue(m.isUpToDate("DCIM/a.jpg", 10, 5))
        assertFalse(m.isUpToDate("DCIM/a.jpg", 11, 5))
        assertFalse(m.isUpToDate("DCIM/a.jpg", 10, 6))
        assertFalse(m.isUpToDate("DCIM/other.jpg", 10, 5))
        assertEquals(1, m.lookalikesOf("DCIM/other.jpg", 10, 5).size)
        assertEquals(0, m.lookalikesOf("DCIM/a.jpg", 10, 5).size)
    }

    @Test
    fun `no secret-looking field exists in a record`() {
        val m = SyncManifest()
        m.recordDone(done())
        // The format has no slot for credentials, tokens or passphrases; this pins the column list.
        assertEquals(8, m.encode().lines().first { it.startsWith("D\t") }.split('\t').size)
    }
}
