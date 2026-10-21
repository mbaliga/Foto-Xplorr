package com.fotoxplorr.app.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class QuickHashCollectorTest {
    private fun direct(bytes: ByteArray): String {
        val size = bytes.size.toLong()
        val head = bytes.copyOfRange(0, QuickHash.headLength(size))
        val tail = bytes.copyOfRange(QuickHash.tailOffset(size).toInt(), bytes.size)
        return QuickHash.of(size, head, tail)
    }

    private fun streamed(bytes: ByteArray, chunk: Int): String {
        val collector = QuickHashCollector()
        var at = 0
        while (at < bytes.size) {
            val n = minOf(chunk, bytes.size - at)
            collector.update(bytes, at, n)
            at += n
        }
        assertEquals(bytes.size.toLong(), collector.bytesSeen)
        return collector.result()
    }

    @Test
    fun `it equals the hash of the whole file at every awkward size and chunking`() {
        val sample = QuickHash.SAMPLE
        val sizes = listOf(
            0, 1, 2, sample - 1, sample, sample + 1, 2 * sample - 1, 2 * sample, 2 * sample + 1,
            3 * sample, 3 * sample + 17, 5 * sample + 3, 200_000,
        )
        for (size in sizes) {
            val bytes = BackupTestSupport.plaintext(size, seed = size + 1)
            val expected = direct(bytes)
            for (chunk in listOf(1, 7, 4_096, sample - 1, sample, sample + 1, 256 * 1024)) {
                if (size > 50_000 && chunk < 100) continue // a byte at a time over 200 kB adds nothing
                assertEquals("size $size chunk $chunk", expected, streamed(bytes, chunk))
            }
        }
    }

    @Test
    fun `two files that differ only in the middle share a quick hash and one that differs at the end does not`() {
        val a = BackupTestSupport.plaintext(300_000, seed = 1)
        val b = a.copyOf().also { it[150_000] = (it[150_000].toInt() xor 1).toByte() }
        val c = a.copyOf().also { it[a.size - 1] = (it[a.size - 1].toInt() xor 1).toByte() }
        assertEquals(streamed(a, 8_192), streamed(b, 8_192))
        assertNotEquals(streamed(a, 8_192), streamed(c, 8_192))
    }
}
