package com.fotoxplorr.app.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PackReaderTest {
    private fun record(id: Long, body: ByteArray, trailer: ByteArray = MessageDigest.getInstance("SHA-256").digest(body)): ByteArray =
        PackRecord.encodeHeader(id, body.size.toLong()) + body + trailer

    private fun reader(vararg records: ByteArray) = PackReader(ByteArrayInputStream(records.fold(ByteArray(0)) { a, b -> a + b }))

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun `records come out in order with their bytes and checksum`() {
        val a = BackupTestSupport.plaintext(1_000, seed = 1)
        val b = BackupTestSupport.plaintext(5, seed = 2)
        val r = reader(record(7, a), record(9, b))

        val first = r.next()!!
        assertEquals(7L, first.entryId)
        assertEquals(1_000L, first.size)
        val out = ByteArrayOutputStream()
        val digest = runBlocking { r.consume(out) }
        assertArrayEquals(a, out.toByteArray())
        assertTrue(digest.trailerMatches)
        assertEquals(hex(MessageDigest.getInstance("SHA-256").digest(a)), digest.sha256)

        assertEquals(9L, r.next()!!.entryId)
        runBlocking { r.consume(null) }
        assertNull(r.next())
    }

    @Test
    fun `an empty record and an empty pack are fine`() {
        val r = reader(record(1, ByteArray(0)))
        assertEquals(0L, r.next()!!.size)
        assertTrue(runBlocking { r.consume(null) }.trailerMatches)
        assertNull(r.next())
        assertNull(reader().next())
    }

    @Test
    fun `a trailer that does not match the bytes is reported, not thrown`() {
        val body = BackupTestSupport.plaintext(50, seed = 3)
        val r = reader(record(1, body, trailer = ByteArray(32)))
        r.next()
        val digest = runBlocking { r.consume(null) }
        assertFalse(digest.trailerMatches)
        assertEquals(hex(MessageDigest.getInstance("SHA-256").digest(body)), digest.sha256)
    }

    @Test
    fun `a record cut short is damage`() {
        val whole = record(1, BackupTestSupport.plaintext(100, seed = 4))
        for (cut in listOf(5, PackRecord.HEADER_BYTES + 40, whole.size - 1)) {
            val r = PackReader(ByteArrayInputStream(whole.copyOf(cut)))
            try {
                r.next()
                runBlocking { r.consume(null) }
                fail("cut at $cut should be damage")
            } catch (e: BackupException.Damaged) {
                // expected
            }
        }
    }

    @Test
    fun `a header with a negative id or size is damage`() {
        val bad = ByteArray(PackRecord.HEADER_BYTES) { 0xFF.toByte() }
        try {
            PackReader(ByteArrayInputStream(bad + ByteArray(40))).next()
            fail("an impossible header must not be read")
        } catch (e: BackupException.Damaged) {
            // expected
        }
    }

    @Test
    fun `the next record cannot be read before this one is consumed`() {
        val r = reader(record(1, ByteArray(3)), record(2, ByteArray(3)))
        r.next()
        try {
            r.next()
            fail("skipping a record would read its bytes as a header")
        } catch (e: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun `a failure of the output is the output's own and the reader says nothing about it`() {
        val r = reader(record(1, ByteArray(10)))
        r.next()
        val failing = object : java.io.OutputStream() {
            override fun write(b: Int) = throw IOException("disk")
        }
        try {
            runBlocking { r.consume(failing) }
            fail("the write failure must surface")
        } catch (e: IOException) {
            assertFalse(e is BackupException)
        }
    }
}
