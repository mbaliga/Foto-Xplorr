package com.fotoxplorr.app.viewer.deepzoom

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The descriptor half of [RegionTileDecoder.open]: a half-open (the fd decode failed, then the stream
 * fallback threw) closes the descriptor instead of leaving it to the finalizer, and closing the
 * decoder never runs on the caller's (main) thread.
 */
class RegionTileDecoderCloseTest {
    private class Counting : Closeable {
        var closes = 0
        override fun close() { closes++ }
    }

    @Test
    fun `a throw while opening closes the descriptor and still propagates`() {
        val pfd = Counting()
        try {
            closingOnFailure<String>(pfd) { throw IOException("stream fallback rejected the file") }
            fail("the throw must propagate to open()'s own guard")
        } catch (e: IOException) {
            assertEquals("stream fallback rejected the file", e.message)
        }
        assertEquals(1, pfd.closes)
    }

    @Test
    fun `no decoder closes the descriptor`() {
        val pfd = Counting()
        assertNull(closingOnFailure<String>(pfd) { null })
        assertEquals(1, pfd.closes)
    }

    @Test
    fun `a decoder that took the descriptor keeps it open`() {
        val pfd = Counting()
        assertEquals("decoder", closingOnFailure(pfd) { "decoder" })
        assertEquals(0, pfd.closes)
    }

    @Test
    fun `no descriptor at all is fine`() {
        assertNull(closingOnFailure<String>(null) { null })
    }

    @Test
    fun `closing runs on the executor, not on the calling thread`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val done = CountDownLatch(1)
            var closedOn: Thread? = null
            closeOffMain(Closeable { closedOn = Thread.currentThread(); done.countDown() }, executor)
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertNotEquals(Thread.currentThread(), closedOn)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `the default executor is off the calling thread too`() {
        val done = CountDownLatch(1)
        var closedOn: Thread? = null
        closeOffMain(Closeable { closedOn = Thread.currentThread(); done.countDown() })
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertNotEquals(Thread.currentThread(), closedOn)
    }
}
