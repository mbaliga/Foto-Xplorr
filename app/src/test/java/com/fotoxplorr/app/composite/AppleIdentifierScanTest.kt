package com.fotoxplorr.app.composite

import com.fotoxplorr.app.composite.CompositeTestFiles.asset
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.formats.composite.AppleIdentifiers
import com.fotoxplorr.core.model.MediaId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** The Apple-identifier scan opens each file once per process, however often the grid regroups. */
class AppleIdentifierScanTest {
    /** 20k iPhone HEICs, a burst of three among them (shared BurstUUID, unrelated names and times). */
    private val library: List<MediaAsset> = (1L..20_000L).map { asset(it, "IMG_$it.HEIC", taken = it * 60_000L, mime = "image/heic") }
    private val burstIds = setOf(MediaId(100), MediaId(5_000), MediaId(19_000))

    private val opened = AtomicInteger()
    private val opener: (MediaAsset) -> AppleIdentifiers? = { a ->
        opened.incrementAndGet()
        if (a.id in burstIds) AppleIdentifiers(contentIdentifier = null, burstUuid = "B1") else null
    }

    private fun pass(memory: AppleIdentifierMemory, list: List<MediaAsset> = library): CompositeGroups {
        opened.set(0)
        var last = CompositeGroups()
        runBlocking { AppleIdentifierPass(memory, opener).run(list, pairLive = true, groupBursts = true) { last = it } }
        return last
    }

    @Test
    fun `a 20k HEIC library is opened once and later passes open nothing`() {
        val memory = AppleIdentifierMemory()
        val first = pass(memory)
        assertEquals(20_000, opened.get())
        assertEquals(listOf(burstIds), first.bursts.map { it.members.toSet() })

        val second = pass(memory)
        assertEquals(0, opened.get())
        assertEquals(first, second)
        pass(memory)
        assertEquals(0, opened.get())
    }

    @Test
    fun `a search or time range over read files opens nothing and a new file opens alone`() {
        val memory = AppleIdentifierMemory()
        pass(memory)
        pass(memory, library.filter { it.id.value % 7 == 0L })
        assertEquals(0, opened.get())
        pass(memory, library.take(500))
        assertEquals(0, opened.get())

        val added = asset(30_000, "IMG_new.HEIC", taken = 99_000_000_000L, mime = "image/heic")
        pass(memory, library + added)
        assertEquals(1, opened.get())
    }

    @Test
    fun `a re-saved file is read again and the first groups come from memory before any open`() {
        val memory = AppleIdentifierMemory()
        pass(memory)
        val resaved = library.map { if (it.id == MediaId(100)) it.copy(dateModifiedSeconds = 2L, sizeBytes = 2_000L) else it }
        val published = mutableListOf<CompositeGroups>()
        opened.set(0)
        runBlocking { AppleIdentifierPass(memory, opener).run(resaved, true, true) { published += it } }
        assertEquals(1, opened.get())
        // The first publish already stacks the two unchanged frames from memory: no flicker to ungrouped.
        assertTrue(published.first().bursts.single().members.containsAll(listOf(MediaId(5_000), MediaId(19_000))))
    }

    @Test
    fun `memory checks size and date and keeps only its capacity`() {
        val memory = AppleIdentifierMemory(capacity = 3)
        val a = asset(1, "a.HEIC", mime = "image/heic")
        memory.put(a, null)
        assertEquals(null, memory.get(a)!!.value)
        assertNull(memory.get(a.copy(sizeBytes = 5L)))
        assertNull(memory.get(a.copy(dateModifiedSeconds = 9L)))
        (2L..5L).forEach { memory.put(asset(it, "$it.HEIC", mime = "image/heic"), null) }
        assertEquals(3, memory.size)
        assertNull(memory.get(a))
        assertTrue(AppleIdentifierMemory.CAPACITY >= 2 * CompositeGrouping.SCAN_CAP)
    }
}
