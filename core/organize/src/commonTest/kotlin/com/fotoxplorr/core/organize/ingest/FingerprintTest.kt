package com.fotoxplorr.core.organize.ingest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class FingerprintTest {
    @Test fun stableAcrossReadChunking() {
        val b = content(1, 300_000)
        val a = Fingerprints.cheap(MemReader(b), 5)
        val c = Fingerprints.cheap(MemReader(b, maxPerRead = 7), 5)
        assertEquals(a, c)
        assertEquals(Fingerprints.full(MemReader(b)), Fingerprints.full(MemReader(b, maxPerRead = 1000)))
    }

    @Test fun knownVectorsDoNotDrift() {
        // Pinned so a refactor of the mixer is noticed: a changed hash would orphan every stored fingerprint.
        assertEquals("10-1234-7aad489e5db90ae8-7aad489e5db90ae8", Fingerprints.cheap(MemReader(ByteArray(10) { it.toByte() }), 1234).key)
        assertEquals("7aad489e5db90ae86e44f1292530209e", Fingerprints.full(MemReader(ByteArray(10) { it.toByte() })))
    }

    @Test fun sensitiveToSizeTimeHeadAndTail() {
        val b = content(2, 200_000)
        val base = Fingerprints.cheap(MemReader(b), 10)
        assertNotEquals(base, Fingerprints.cheap(MemReader(b), 11))
        assertNotEquals(base, Fingerprints.cheap(MemReader(b.copyOf(199_999)), 10))
        val head = b.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertNotEquals(base.headHash, Fingerprints.cheap(MemReader(head), 10).headHash)
        val tail = b.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertNotEquals(base.tailHash, Fingerprints.cheap(MemReader(tail), 10).tailHash)
    }

    @Test fun middleDifferenceTiesCheaplyButFullHashSeparates() {
        val b = content(3, 300_000)
        val m = b.copyOf().also { it[150_000] = (it[150_000] + 1).toByte() }
        assertEquals(Fingerprints.cheap(MemReader(b), 1), Fingerprints.cheap(MemReader(m), 1))
        assertNotEquals(Fingerprints.full(MemReader(b)), Fingerprints.full(MemReader(m)))
    }

    @Test fun journalCodecRoundTripsAndRejectsTornLines() {
        val e = JournalEntry("id\twith\ttabs\nand\\slash", 1, JournalState.VERIFIED, "a/b c.jpg", "d")
        assertEquals(e, JournalCodec.decode(JournalCodec.encode(e)))
        val line = JournalCodec.encode(e)
        assertEquals(null, JournalCodec.decode(line.dropLast(3)))
        assertEquals(null, JournalCodec.decode(""))
    }
}
