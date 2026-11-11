package com.fotoxplorr.app.gallery

import com.fotoxplorr.core.metadata.SidecarAnnotations
import com.fotoxplorr.core.metadata.SidecarFlag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CullingTest {
    private val ids = listOf(10L, 11L, 12L)

    @Test
    fun `keys map to flags ratings labels and navigation`() {
        assertEquals(CullAction.Flag(SidecarFlag.PICK), cullActionFor('p'))
        assertEquals(CullAction.Flag(SidecarFlag.REJECT), cullActionFor('X'))
        assertEquals(CullAction.Flag(SidecarFlag.NONE), cullActionFor('u'))
        assertEquals(CullAction.Rate(0), cullActionFor('0'))
        assertEquals(CullAction.Rate(5), cullActionFor('5'))
        assertEquals(CullAction.Label("Red"), cullActionFor('6'))
        assertEquals(CullAction.Label("Blue"), cullActionFor('9'))
        assertEquals(CullAction.Next, cullActionFor(']'))
        assertNull(cullActionFor('q'))
    }

    @Test
    fun `a pick is stored and advances`() {
        val store = InMemoryAnnotationStore()
        val s = CullState(ids).apply(CullAction.Flag(SidecarFlag.PICK), store)
        assertEquals(SidecarFlag.PICK, store.get(10)?.flag)
        assertEquals(1, s.index)
    }

    @Test
    fun `rating keeps the flag already set`() {
        val store = InMemoryAnnotationStore()
        var s = CullState(ids, autoAdvance = false).apply(CullAction.Flag(SidecarFlag.PICK), store)
        s = s.apply(CullAction.Rate(4), store).apply(CullAction.Label("Green"), store)
        assertEquals(SidecarAnnotations(rating = 4, label = "Green", flag = SidecarFlag.PICK), store.get(10))
        assertEquals(0, s.index)
    }

    @Test
    fun `advance stops on the last photo`() {
        val store = InMemoryAnnotationStore()
        var s = CullState(ids, index = 2)
        s = s.apply(CullAction.Rate(3), store)
        assertEquals(2, s.index)
        assertEquals(3, store.get(12)?.rating)
    }

    @Test
    fun `rating is clamped to five`() {
        val store = InMemoryAnnotationStore()
        CullState(ids).apply(CullAction.Rate(9), store)
        assertEquals(5, store.get(10)?.rating)
    }

    @Test
    fun `undo restores the previous value and the position`() {
        val store = InMemoryAnnotationStore()
        val a = CullState(ids).apply(CullAction.Rate(2), store)
        val b = a.apply(CullAction.Flag(SidecarFlag.REJECT), store)
        val c = b.apply(CullAction.Undo, store)
        assertEquals(1, c.index)
        assertEquals(SidecarFlag.NONE, store.get(11)?.flag)
        assertEquals(2, store.get(10)?.rating)
        assertNull(c.undo)
        assertEquals(c, c.apply(CullAction.Undo, store))
    }

    @Test
    fun `moving writes nothing and stays in range`() {
        val store = InMemoryAnnotationStore()
        var s = CullState(ids).apply(CullAction.Previous, store)
        assertEquals(0, s.index)
        s = s.apply(CullAction.Next, store).apply(CullAction.Next, store).apply(CullAction.Next, store)
        assertEquals(2, s.index)
        assertNull(store.get(10))
    }

    @Test
    fun `an empty pass ignores actions`() {
        val s = CullState(emptyList())
        assertEquals(s, s.apply(CullAction.Rate(1), InMemoryAnnotationStore()))
    }

    @Test
    fun `filters and summary count by flag`() {
        val store = InMemoryAnnotationStore()
        store.put(10, SidecarAnnotations(flag = SidecarFlag.PICK))
        store.put(11, SidecarAnnotations(flag = SidecarFlag.REJECT))
        assertTrue(cullVisible(store.get(10), CullFilter.PICKS))
        assertFalse(cullVisible(store.get(11), CullFilter.HIDE_REJECTS))
        assertTrue(cullVisible(store.get(12), CullFilter.UNDECIDED))
        assertFalse(cullVisible(store.get(10), CullFilter.UNDECIDED))
        assertEquals(CullSummary(1, 1, 1), cullSummary(ids, store))
    }

    @Test
    fun `write-through keeps memory when the durable store refuses`() {
        val refusing = object : AnnotationStore {
            override fun get(id: Long): SidecarAnnotations? = null
            override fun put(id: Long, annotations: SidecarAnnotations) = false
        }
        val store = WriteThroughAnnotationStore(refusing)
        CullState(ids).apply(CullAction.Rate(5), store)
        assertEquals(5, store.get(10)?.rating)
        assertEquals(0, store.durableCount)
    }

    @Test
    fun `write-through counts what stuck`() {
        val store = WriteThroughAnnotationStore(InMemoryAnnotationStore())
        CullState(ids).apply(CullAction.Rate(1), store)
        assertEquals(1, store.durableCount)
    }
}
