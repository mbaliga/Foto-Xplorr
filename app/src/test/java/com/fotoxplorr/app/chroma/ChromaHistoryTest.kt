package com.fotoxplorr.app.chroma

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The reading history on disk: the text form, the bound of 50, damage, a failed write, delete all. */
class ChromaHistoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun reading(i: Int, kind: ReadingKind = ReadingKind.APPROXIMATE) =
        SavedReading(ChromaTestKit.argb(i % 256, (i * 7) % 256, (i * 13) % 256), kind, 1_700_000_000_000L + i)

    private val dir get() = File(temp.root, "chroma")
    private fun store(write: ((File, ByteArray) -> Unit)? = null, read: ((File) -> String)? = null) = when {
        write != null && read != null -> ChromaHistoryStore(dir, write, read)
        write != null -> ChromaHistoryStore(dir, writeFile = write)
        read != null -> ChromaHistoryStore(dir, readFile = read)
        else -> ChromaHistoryStore(dir)
    }

    private fun file() = File(dir, ChromaHistoryStore.FILE_NAME)

    // ---- the text form ----------------------------------------------------------------------------------

    @Test
    fun `a line round trips and is plain text`() {
        val r = SavedReading(ChromaTestKit.argb(176, 48, 32), ReadingKind.CALIBRATED, 1_700_000_000_000L)
        assertEquals("reading #B03020 card 1700000000000", r.toLine())
        assertEquals(r, SavedReading.fromLine(r.toLine()))
    }

    @Test
    fun `a malformed reading line is not a reading`() {
        for (bad in listOf(
            "", "reading", "reading #B03020 card", "reading B03020 card 5", "reading #B0302 card 5", "reading #GG3020 card 5",
            "reading #B03020 shiny 5", "reading #B03020 card five", "reading #B03020 card -5", "reading #B03020 card 5 extra",
            "colour #B03020 card 5",
        )) {
            assertNull("'$bad' should not decode", SavedReading.fromLine(bad))
        }
    }

    @Test
    fun `the codec round trips a list in order`() {
        val list = (1..5).map { reading(it, ReadingKind.entries[it % 3]) }
        val back = ChromaHistoryCodec.decode(ChromaHistoryCodec.encode(list))
        assertEquals(list, back.readings)
        assertFalse(back.damaged)
    }

    @Test
    fun `an unknown line and a newer version are tolerated, a missing or bad header is not`() {
        val text = "fotoz-chroma 2\nfuture field\n${reading(1).toLine()}\n"
        val ok = ChromaHistoryCodec.decode(text)
        assertEquals(listOf(reading(1)), ok.readings)
        assertFalse(ok.damaged)
        for (bad in listOf("", "reading #B03020 card 5", "fotoz-chroma", "fotoz-chroma x", "fotoz-chroma 0", "other 1\n${reading(1).toLine()}")) {
            val d = ChromaHistoryCodec.decode(bad)
            assertTrue("'$bad' has no readings", d.readings.isEmpty())
            assertFalse("'$bad' has no header", d.headerOk)
        }
    }

    @Test
    fun `a damaged reading line is left out and counted, and the rest is kept`() {
        val text = "fotoz-chroma 1\n${reading(1).toLine()}\nreading #ZZZZZZ card 5\n${reading(2).toLine()}\n"
        val d = ChromaHistoryCodec.decode(text)
        assertEquals(listOf(reading(1), reading(2)), d.readings)
        assertEquals(1, d.skipped)
        assertTrue(d.damaged)
    }

    @Test
    fun `windows line ends are read and a huge text is refused whole`() {
        val crlf = "fotoz-chroma 1\r\n${reading(1).toLine()}\r\n"
        assertEquals(listOf(reading(1)), ChromaHistoryCodec.decode(crlf).readings)
        val huge = "fotoz-chroma 1\n" + "x".repeat(ChromaHistoryCodec.MAX_TEXT_LENGTH)
        assertTrue(ChromaHistoryCodec.decode(huge).readings.isEmpty())
    }

    @Test
    fun `no more than fifty are read or written`() {
        val many = (1..80).map { reading(it) }
        assertEquals(ChromaHistoryCodec.MAX_READINGS, ChromaHistoryCodec.decode(ChromaHistoryCodec.encode(many)).readings.size)
        val text = "fotoz-chroma 1\n" + many.joinToString("\n") { it.toLine() }
        assertEquals(50, ChromaHistoryCodec.decode(text).readings.size)
    }

    // ---- the store --------------------------------------------------------------------------------------

    @Test
    fun `nothing saved is an empty history, not damage`() {
        val loaded = store().loadNow()
        assertTrue(loaded.readings.isEmpty())
        assertFalse(loaded.damaged)
        assertFalse(loaded.unreadable)
    }

    @Test
    fun `readings come back newest first`() {
        val s = store()
        assertTrue(s.addNow(reading(1)))
        assertTrue(s.addNow(reading(2)))
        assertTrue(s.addNow(reading(3)))
        assertEquals(listOf(reading(3), reading(2), reading(1)), store().loadNow().readings)
    }

    @Test
    fun `the oldest is dropped beyond fifty`() {
        val s = store()
        for (i in 1..55) assertTrue(s.addNow(reading(i)))
        val back = s.loadNow().readings
        assertEquals(50, back.size)
        assertEquals(reading(55), back.first())
        assertEquals(reading(6), back.last())
    }

    @Test
    fun `the file lives in the tools own folder as plain text`() {
        store().addNow(reading(1))
        assertEquals("history.txt", file().name)
        assertEquals("chroma", file().parentFile?.name)
        assertTrue(file().readText().startsWith("fotoz-chroma 1\n"))
        assertFalse(File(dir, ChromaHistoryStore.TMP_NAME).exists())
        assertEquals(ChromaHistoryStore.directoryIn(temp.root), dir)
    }

    @Test
    fun `delete all removes the file and a later add starts again`() {
        val s = store()
        s.addNow(reading(1))
        s.addNow(reading(2))
        assertTrue(s.clearNow())
        assertFalse(file().exists())
        assertTrue(s.loadNow().readings.isEmpty())
        assertTrue(s.addNow(reading(3)))
        assertEquals(listOf(reading(3)), s.loadNow().readings)
    }

    @Test
    fun `delete all with nothing saved is fine`() {
        assertTrue(store().clearNow())
    }

    @Test
    fun `a failed write says so and leaves the old file as it was`() {
        val good = store()
        good.addNow(reading(1))
        val before = file().readText()
        val failing = store(write = { _, _ -> throw IOException("disk full") })
        assertFalse(failing.addNow(reading(2)))
        assertEquals(before, file().readText())
        assertEquals(listOf(reading(1)), good.loadNow().readings)
    }

    @Test
    fun `a half written temp file from a kill is removed by the next add`() {
        dir.mkdirs()
        File(dir, ChromaHistoryStore.TMP_NAME).writeText("fotoz-chroma 1\nreading #B03")
        assertTrue(store().addNow(reading(1)))
        assertFalse(File(dir, ChromaHistoryStore.TMP_NAME).exists())
    }

    @Test
    fun `a damaged line is left out of the list and the next add writes the file without it`() {
        dir.mkdirs()
        file().writeText("fotoz-chroma 1\n${reading(1).toLine()}\nreading #QQQQQQ approx 1\n")
        val s = store()
        val loaded = s.loadNow()
        assertEquals(listOf(reading(1)), loaded.readings)
        assertTrue(loaded.damaged)
        assertTrue(s.addNow(reading(2)))
        assertEquals(listOf(reading(2), reading(1)), s.loadNow().readings)
        assertFalse(s.loadNow().damaged)
    }

    @Test
    fun `a file that cannot be read is never overwritten by an add, but delete all clears it`() {
        dir.mkdirs()
        file().writeText("fotoz-chroma 1\n${reading(1).toLine()}\n")
        val s = store(read = { throw IOException("locked") })
        assertTrue(s.loadNow().unreadable)
        assertFalse(s.addNow(reading(2)))
        assertEquals("fotoz-chroma 1\n${reading(1).toLine()}\n", file().readText())
        assertTrue(s.clearNow())
        assertFalse(file().exists())
    }

    @Test
    fun `the saved text holds a colour, a kind and a time and nothing else`() {
        val s = store()
        s.addNow(reading(9, ReadingKind.ROOM))
        val lines = file().readText().lines().filter { it.isNotBlank() }
        assertEquals(2, lines.size)
        assertNotNull(SavedReading.fromLine(lines[1]))
        assertEquals(4, lines[1].split(' ').size)
    }
}
