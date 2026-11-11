package com.fotoxplorr.app.gallery

import com.fotoxplorr.core.metadata.SidecarAnnotations
import com.fotoxplorr.core.metadata.SidecarFlag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cull's counts come from memory: the durable side (sidecar file IO) is read at most once per
 * photo, however many keys are pressed, and a toggle made while a write is in flight survives it.
 */
class CullStoreTest {

    /** A durable store that counts its reads, holding a pick on every tenth photo. */
    private class CountingDurable : AnnotationStore {
        var reads = 0
        val map = HashMap<Long, SidecarAnnotations>()
        override fun get(id: Long): SidecarAnnotations? {
            reads++
            return map[id] ?: if (id % 10 == 0L) SidecarAnnotations(flag = SidecarFlag.PICK) else null
        }
        override fun put(id: Long, annotations: SidecarAnnotations): Boolean {
            map[id] = annotations
            return true
        }
    }

    private val ids = (0L until 1000L).toList()

    @Test
    fun `a run of keys reads each photo's sidecar once and counts from memory`() {
        val durable = CountingDurable()
        val store = WriteThroughAnnotationStore(durable)
        // The first pass on open.
        ids.forEach { store.get(it) }
        assertEquals(1000, durable.reads)
        assertEquals(CullSummary(100, 0, 900), store.summary(ids.size))
        var cull = CullState(ids, index = 1)
        repeat(50) {
            cull = cull.apply(CullAction.Flag(SidecarFlag.REJECT), store)
            store.summary(ids.size)
            store.get(cull.currentId!!)
        }
        assertEquals("no file read per key press", 1000, durable.reads)
        // Photos 1..50 were rejected; five of them (10, 20 .. 50) had been picks.
        assertEquals(CullSummary(95, 50, 855), store.summary(ids.size))
    }

    @Test
    fun `the counts follow a flag changed back and forth`() {
        val store = WriteThroughAnnotationStore(InMemoryAnnotationStore())
        store.put(1, SidecarAnnotations(flag = SidecarFlag.PICK))
        store.put(1, SidecarAnnotations(flag = SidecarFlag.REJECT))
        store.put(2, SidecarAnnotations(flag = SidecarFlag.PICK))
        store.put(2, SidecarAnnotations(flag = SidecarFlag.NONE))
        assertEquals(CullSummary(0, 1, 2), store.summary(3))
    }

    @Test
    fun `a miss is remembered too`() {
        val durable = CountingDurable()
        val store = WriteThroughAnnotationStore(durable)
        assertNull(store.get(3))
        assertNull(store.get(3))
        assertEquals(1, durable.reads)
    }

    @Test
    fun `a value put by a key is not overwritten by an older durable read`() {
        val store = WriteThroughAnnotationStore(CountingDurable())
        store.put(10, SidecarAnnotations(flag = SidecarFlag.REJECT))
        // id 10 holds a pick on the durable side; the key's reject wins.
        assertEquals(SidecarFlag.REJECT, store.get(10)?.flag)
        assertEquals(CullSummary(0, 1, 0), store.summary(1))
    }

    @Test
    fun `after a folder grant the misses are read again and the keys' values kept`() {
        val durable = CountingDurable()
        val store = WriteThroughAnnotationStore(durable)
        store.get(3)
        store.put(4, SidecarAnnotations(rating = 2))
        store.rereadMisses()
        store.get(3)
        store.get(4)
        assertEquals(2, durable.reads)
        assertEquals(2, store.get(4)?.rating)
    }

    /** A durable store that refuses every write until its folder is granted. */
    private class GrantedLater : AnnotationStore {
        var granted = false
        val map = HashMap<Long, SidecarAnnotations>()
        override fun get(id: Long): SidecarAnnotations? = if (granted) map[id] else null
        override fun put(id: Long, annotations: SidecarAnnotations): Boolean {
            if (!granted) return false
            map[id] = annotations
            return true
        }
    }

    @Test
    fun `ratings made before a folder grant are written once it is granted`() {
        val durable = GrantedLater()
        val store = WriteThroughAnnotationStore(durable)
        (1L..30L).forEach { store.put(it, SidecarAnnotations(rating = 3)) }
        assertEquals(30, store.unsavedCount)
        durable.granted = true
        store.rereadMisses()
        assertEquals(0, store.flushUnsaved())
        store.put(31, SidecarAnnotations(rating = 4))
        assertEquals(31, store.durableCount)
        assertEquals(31, durable.map.size)
        assertEquals(3, durable.map[1]?.rating)
        assertTrue(store.lastPutDurable)
    }

    @Test
    fun `a good write after refused ones still leaves them owed`() {
        val durable = GrantedLater()
        val store = WriteThroughAnnotationStore(durable)
        store.put(1, SidecarAnnotations(rating = 2))
        durable.granted = true
        store.put(2, SidecarAnnotations(rating = 5))
        assertTrue(store.lastPutDurable)
        assertEquals("the first rating is still in memory only", 1, store.unsavedCount)
    }

    @Test
    fun `a flush with the folder still out of reach keeps the values owed`() {
        val store = WriteThroughAnnotationStore(GrantedLater())
        store.put(1, SidecarAnnotations(rating = 2))
        assertEquals(1, store.flushUnsaved())
        assertFalse(store.lastPutDurable)
        assertEquals(2, store.get(1)?.rating)
    }

    @Test
    fun `an Auto or Stay toggle made during a write survives it`() {
        var latest = CullState(ids)
        val slowWrite = object : AnnotationStore {
            override fun get(id: Long): SidecarAnnotations? = null
            override fun put(id: Long, annotations: SidecarAnnotations): Boolean {
                // The chip is tapped while the sidecar write is still running.
                latest = latest.copy(autoAdvance = false)
                return true
            }
        }
        val before = latest
        val next = before.apply(CullAction.Flag(SidecarFlag.PICK), WriteThroughAnnotationStore(slowWrite))
        latest = latest.afterWrite(next)
        assertFalse(latest.autoAdvance)
        // The write's own result is kept: it moved on and can be undone.
        assertEquals(1, latest.index)
        assertTrue(latest.undo != null)
    }
}
