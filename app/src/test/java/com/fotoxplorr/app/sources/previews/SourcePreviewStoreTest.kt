package com.fotoxplorr.app.sources.previews

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SourcePreviewStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_000L
    private var cap = 1_000L

    private fun store(root: File = File(tmp.root, "source-previews")) =
        SourcePreviewStore(root, capOf = { cap }, clock = { now++ }, io = Dispatchers.Unconfined)

    private fun bytes(n: Int, fill: Int = 7) = ByteArray(n) { fill.toByte() }
    private fun dirFiles(row: Long) = File(tmp.root, "source-previews/$row").listFiles().orEmpty().map { it.name }.sorted()

    @Test
    fun `a written preview reads back byte for byte and the size is the sum of the files`() = runBlocking {
        val s = store()
        assertTrue(s.write(1, 10, 100, bytes(300, 1)))
        assertTrue(s.write(1, 11, 100, bytes(200, 2)))
        assertArrayEquals(bytes(300, 1), s.read(1, 10, 100))
        assertNull("another revision is another picture", s.read(1, 10, 101))
        assertEquals(500L, s.sizeBytes(1))
        val onDisk = File(tmp.root, "source-previews/1").listFiles()!!.filter { PreviewPolicy.parseName(it.name) != null }.sumOf { it.length() }
        assertEquals(onDisk, s.sizeBytes(1))
        assertEquals(500L, s.sizes.value[1])
        assertEquals(0L, s.sizeBytes(2))
    }

    @Test
    fun `a new revision replaces the old file`() = runBlocking {
        val s = store()
        s.write(1, 10, 100, bytes(300))
        s.write(1, 10, 200, bytes(120))
        assertEquals(listOf("10-200.webp", "index.tsv"), dirFiles(1))
        assertEquals(120L, s.sizeBytes(1))
    }

    @Test
    fun `the cap evicts least recently used and a read counts as a use`() = runBlocking {
        val s = store()
        s.write(1, 1, 1, bytes(400)); s.write(1, 2, 1, bytes(400))
        s.read(1, 1, 1) // asset 1 is now more recent than asset 2
        s.write(1, 3, 1, bytes(400)) // 1200 > 1000: asset 2 goes
        assertNull(s.read(1, 2, 1))
        assertNotNull(s.read(1, 1, 1)); assertNotNull(s.read(1, 3, 1))
        assertTrue(s.sizeBytes(1) <= 1_000L)
        assertFalse(File(tmp.root, "source-previews/1/2-1.webp").exists())
    }

    @Test
    fun `a single preview bigger than the cap is still kept so the asset is never empty-handed`() = runBlocking {
        cap = 100
        val s = store()
        assertTrue(s.write(1, 1, 1, bytes(400)))
        assertEquals(400L, s.sizeBytes(1))
    }

    @Test
    fun `a cap of zero writes nothing and lowering the cap evicts at once`() = runBlocking {
        val s = store()
        s.write(1, 1, 1, bytes(400)); s.write(1, 2, 1, bytes(400))
        cap = 500
        s.enforceCap(1)
        assertEquals(400L, s.sizeBytes(1))
        cap = 0
        assertFalse(s.write(1, 3, 1, bytes(10)))
        s.enforceCap(1)
        assertEquals(0L, s.sizeBytes(1))
        assertTrue(dirFiles(1).none { it.endsWith(".webp") })
    }

    @Test
    fun `clear deletes only the cache files and leaves anything else alone`() = runBlocking {
        val s = store()
        s.write(1, 1, 1, bytes(100)); s.write(2, 1, 1, bytes(100))
        val foreign = File(tmp.root, "source-previews/1/photo-i-love.jpg").apply { writeText("precious") }
        val outside = File(tmp.root, "IMG_0001.jpg").apply { writeText("original") }
        val freed = s.clear(1)
        assertEquals(100L, freed)
        assertEquals(listOf("photo-i-love.jpg"), dirFiles(1))
        assertTrue(foreign.exists() && outside.exists())
        assertEquals(0L, s.sizeBytes(1))
        assertEquals("another source is untouched", 100L, s.sizeBytes(2))
        assertNotNull(s.read(2, 1, 1))
    }

    @Test
    fun `clear removes the empty folder`() = runBlocking {
        val s = store()
        s.write(1, 1, 1, bytes(100))
        s.clear(1)
        assertFalse(File(tmp.root, "source-previews/1").exists())
    }

    @Test
    fun `a new store over the same folder finds everything after the process died`() = runBlocking {
        store().apply { write(1, 1, 5, bytes(250)); write(1, 2, 5, bytes(150)) }
        val again = store()
        assertEquals(400L, again.sizeBytes(1))
        assertArrayEquals(bytes(250), again.read(1, 1, 5))
    }

    @Test
    fun `a lost or damaged index costs only the eviction order never a preview`() = runBlocking {
        store().apply { write(1, 1, 5, bytes(250)); write(1, 2, 5, bytes(150)) }
        File(tmp.root, "source-previews/1/index.tsv").writeText("\u0000\u0000 not an index \n1\t2")
        assertEquals(400L, store().sizeBytes(1))
        File(tmp.root, "source-previews/1/index.tsv").delete()
        assertEquals(400L, store().sizeBytes(1))
    }

    @Test
    fun `a half written temp file and an empty file are cleaned up not counted`() = runBlocking {
        store().apply { write(1, 1, 5, bytes(100)) }
        val d = File(tmp.root, "source-previews/1")
        File(d, "2-5.webp.tmp").writeBytes(bytes(50))
        File(d, "3-5.webp").writeBytes(ByteArray(0))
        val s = store()
        assertEquals(100L, s.sizeBytes(1))
        assertEquals(listOf("1-5.webp", "index.tsv"), dirFiles(1))
    }

    @Test
    fun `a vanished file is forgotten on read and a discarded one is gone`() = runBlocking {
        val s = store()
        s.write(1, 1, 5, bytes(100)); s.write(1, 2, 5, bytes(100))
        File(tmp.root, "source-previews/1/1-5.webp").delete()
        assertNull(s.read(1, 1, 5))
        assertEquals(100L, s.sizeBytes(1))
        s.discard(1, 2)
        assertFalse(s.has(1, 2, 5))
        assertEquals(0L, s.sizeBytes(1))
    }

    @Test
    fun `no write leaves a temp file behind`() = runBlocking {
        val s = store()
        repeat(5) { s.write(1, it + 1L, 1, bytes(10)) }
        assertTrue(dirFiles(1).none { it.endsWith(".tmp") })
    }

    @Test
    fun `forgetting a source removes its previews and its size entry`() = runBlocking {
        val s = store()
        s.write(1, 1, 1, bytes(10))
        s.forget(1)
        assertFalse(File(tmp.root, "source-previews/1").exists())
        assertNull(s.sizes.value[1])
    }

    private fun assertNotNull(a: Any?) = org.junit.Assert.assertNotNull(a)
}
