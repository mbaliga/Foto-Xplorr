package com.fotoxplorr.app.receive

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ReceiveSessionTest {
    private fun items(n: Int) = (1..n).map { ReceiveItem("content://s/$it", "image/jpeg", ReceiveKind.IMAGE, "p$it.jpg") }

    @Test
    fun `every item is saved once in order`() = runBlocking {
        val session = ReceiveSession(items(3))
        val seen = ArrayList<String>()
        session.run { seen += it.uri; it.displayName!! }
        assertEquals(listOf("content://s/1", "content://s/2", "content://s/3"), seen)
        val p = session.progress.value
        assertEquals(3, p.saved)
        assertEquals(0, p.failed)
        assertFalse(p.running)
        assertEquals(ReceiveItemState.Saved("p2.jpg"), session.stateOf(1))
    }

    @Test
    fun `one failure is recorded and the run goes on`() = runBlocking {
        val session = ReceiveSession(items(3))
        session.run { if (it.uri.endsWith("2")) throw IOException("disk full") else "ok" }
        val p = session.progress.value
        assertEquals(2, p.saved)
        assertEquals(1, p.failed)
        assertEquals("disk full", session.failures().single().second)
        assertEquals("Saved 2 photos and videos. 1 could not be saved.", ReceiveCopy.summary(p, cancelled = false))
    }

    @Test
    fun `a cancel stops at the item in flight and a second run skips the saved ones`() = runBlocking {
        val session = ReceiveSession(items(4))
        val entered = CompletableDeferred<Unit>()
        val calls = ArrayList<String>()
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            session.run { item ->
                calls += item.uri
                if (item.uri.endsWith("3")) {
                    entered.complete(Unit)
                    CompletableDeferred<Unit>().await()
                }
                "n"
            }
        }
        entered.await()
        job.cancelAndJoin()
        val after = session.progress.value
        assertEquals(2, after.saved)
        assertFalse("running is cleared by a cancel", after.running)
        assertEquals(ReceiveItemState.Pending, session.stateOf(2))
        assertEquals(2, after.resumable)
        assertEquals("Stopped after 2 of 4.", ReceiveCopy.summary(after, cancelled = true))

        calls.clear()
        session.run { calls += it.uri; "n" }
        assertEquals("only the rest is saved", listOf("content://s/3", "content://s/4"), calls)
        assertEquals(4, session.progress.value.saved)
    }

    @Test
    fun `a failed item is tried again by the next run`() = runBlocking {
        val session = ReceiveSession(items(2))
        var first = true
        session.run { if (first) { first = false; throw IOException("x") } else "n" }
        assertEquals(1, session.progress.value.failed)
        session.run { "n" }
        assertEquals(2, session.progress.value.saved)
        assertEquals(0, session.progress.value.failed)
        assertTrue(session.failures().isEmpty())
    }
}
