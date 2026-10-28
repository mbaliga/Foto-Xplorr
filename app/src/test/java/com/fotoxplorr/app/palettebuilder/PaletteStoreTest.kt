package com.fotoxplorr.app.palettebuilder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/** The saved palettes on disk: round trip, replace by name, delete, damage, a failed write, and many hands at once. */
class PaletteStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun palette(name: String, vararg colours: Int) = Palette.of(name, colours.toList())!!

    private val sunset = palette("Sunset", 0xFFFF8800.toInt(), 0xFF1A1A1A.toInt())
    private val sea = palette("Sea", 0xFF0066AA.toInt(), 0xFFEEEEEE.toInt(), 0xFF003355.toInt()).withLock(1, true)

    private fun store(dir: File = File(temp.root, "palettes"), write: ((File, ByteArray) -> Unit)? = null) =
        if (write == null) PaletteStore(dir) else PaletteStore(dir, write)

    private fun file(dir: File = File(temp.root, "palettes")) = File(dir, PaletteStore.FILE_NAME)

    @Test
    fun `nothing saved is an empty list, not damage`() {
        val loaded = store().loadNow()
        assertTrue(loaded.palettes.isEmpty())
        assertFalse(loaded.damaged)
        assertFalse(loaded.unreadable)
    }

    @Test
    fun `a saved palette comes back with its name, order and locks, newest first`() {
        val s = store()
        assertEquals(StoreSave.Saved(replaced = false), s.saveNow(sunset))
        assertEquals(StoreSave.Saved(replaced = false), s.saveNow(sea))
        assertEquals(listOf(sea, sunset), store().loadNow().palettes)
    }

    @Test
    fun `saving a name again replaces that palette, whatever the case, and moves it first`() {
        val s = store()
        s.saveNow(sunset)
        s.saveNow(sea)
        val newer = palette("SUNSET", 0xFF000000.toInt(), 0xFFFFFFFF.toInt())
        assertEquals(StoreSave.Saved(replaced = true), s.saveNow(newer))
        assertEquals(listOf(newer, sea), s.loadNow().palettes)
    }

    @Test
    fun `delete removes by name ignoring case and says whether anything went`() {
        val s = store()
        s.saveNow(sunset)
        s.saveNow(sea)
        assertTrue(s.deleteNow("sunset"))
        assertFalse(s.deleteNow("sunset"))
        assertEquals(listOf(sea), s.loadNow().palettes)
        assertTrue(s.deleteNow("Sea"))
        assertTrue(s.loadNow().palettes.isEmpty())
        assertEquals("an empty store is an empty file", "", file().readText())
    }

    @Test
    fun `the store is capped and says so, but a palette already there can still be replaced`() {
        val s = store()
        val many = (1..PaletteCodec.MAX_SAVED).map { palette("P$it", 0xFF000000.toInt(), ColorMath.rgb(it, 0, 0)) }
        file().parentFile!!.mkdirs()
        file().writeText(PaletteCodec.encodeAll(many))
        assertEquals(StoreSave.Full, s.saveNow(palette("One more", 0xFF111111.toInt(), 0xFF222222.toInt())))
        assertEquals(PaletteCodec.MAX_SAVED, s.loadNow().palettes.size)
        assertEquals(StoreSave.Saved(replaced = true), s.saveNow(palette("p7", 0xFF111111.toInt(), 0xFF222222.toInt())))
        assertEquals("p7", s.loadNow().palettes.first().name)
        assertEquals(PaletteCodec.MAX_SAVED, s.loadNow().palettes.size)
    }

    // ---- damage ---------------------------------------------------------------------------------

    @Test
    fun `a damaged palette is left out and reported, the rest still load`() {
        file().parentFile!!.mkdirs()
        file().writeText(PaletteCodec.encode(sunset) + "---\nfotoz-palette 1\ncolor #zzzzzz\ncolor #000000\n---\n" + PaletteCodec.encode(sea))
        val loaded = store().loadNow()
        assertEquals(listOf(sunset, sea), loaded.palettes)
        assertTrue(loaded.damaged)
        assertFalse(loaded.unreadable)
    }

    @Test
    fun `garbage in the file is damage, not a crash`() {
        file().parentFile!!.mkdirs()
        file().writeBytes(byteArrayOf(0, 1, 2, -1, -2, 65, 66))
        val loaded = store().loadNow()
        assertTrue(loaded.palettes.isEmpty())
        assertTrue(loaded.damaged)
    }

    @Test
    fun `the next save keeps what it is about to drop, in a copy`() {
        file().parentFile!!.mkdirs()
        val damagedText = PaletteCodec.encode(sunset) + "---\nfotoz-palette 1\ncolor #zzzzzz\ncolor #000000\n"
        file().writeText(damagedText)
        val s = store()
        assertEquals(StoreSave.Saved(replaced = false), s.saveNow(sea))
        assertEquals(listOf(sea, sunset), s.loadNow().palettes)
        assertFalse(s.loadNow().damaged)
        assertEquals(damagedText, File(file().parentFile, PaletteStore.BAD_NAME).readText())
    }

    @Test
    fun `an undamaged file is not copied aside`() {
        val s = store()
        s.saveNow(sunset)
        s.saveNow(sea)
        assertFalse(File(file().parentFile, PaletteStore.BAD_NAME).exists())
    }

    @Test
    fun `a file that cannot be read is reported, never overwritten, and a save or delete is refused`() {
        val dir = File(temp.root, "palettes")
        store(dir).saveNow(sunset)
        val before = file(dir).readText()
        val deaf = PaletteStore(dir, readFile = { throw IOException("no access") })
        val loaded = deaf.loadNow()
        assertTrue(loaded.unreadable)
        assertTrue(loaded.palettes.isEmpty())
        assertEquals(StoreSave.Failed, deaf.saveNow(sea))
        assertFalse(deaf.deleteNow("Sunset"))
        assertEquals(before, file(dir).readText())
    }

    @Test
    fun `a save that cannot replace the file says so`() {
        // A directory where the file should be: it reads as nothing, and the move into place fails.
        val dir = File(temp.root, "palettes").apply { mkdirs() }
        val blocker = File(dir, PaletteStore.FILE_NAME)
        blocker.mkdirs()
        assertEquals(StoreSave.Failed, store(dir).saveNow(sunset))
        assertTrue(blocker.isDirectory)
    }

    // ---- writing --------------------------------------------------------------------------------

    @Test
    fun `a failed write leaves the old file as it was and says so`() {
        val dir = File(temp.root, "palettes")
        val good = store(dir)
        good.saveNow(sunset)
        val before = file(dir).readText()
        val failing = store(dir) { _, _ -> throw IOException("disk full") }
        assertEquals(StoreSave.Failed, failing.saveNow(sea))
        assertFalse(failing.deleteNow("Sunset"))
        assertEquals(before, file(dir).readText())
        assertEquals(listOf(sunset), good.loadNow().palettes)
    }

    @Test
    fun `the new text is written to a temporary file and moved into place`() {
        val dir = File(temp.root, "palettes")
        val written = ArrayList<String>()
        val s = store(dir) { target, bytes ->
            written += target.name
            target.writeBytes(bytes)
        }
        s.saveNow(sunset)
        assertEquals(listOf(PaletteStore.FILE_NAME), written)
        // The real writer leaves no temporary file behind.
        val real = store(File(temp.root, "other"))
        real.saveNow(sunset)
        assertEquals(listOf(PaletteStore.FILE_NAME), File(temp.root, "other").list()!!.toList())
    }

    @Test
    fun `a temporary file left by a killed save is removed by the next save`() {
        val dir = File(temp.root, "palettes").apply { mkdirs() }
        File(dir, PaletteStore.TMP_NAME).writeText("half a palet")
        store(dir).saveNow(sunset)
        assertFalse(File(dir, PaletteStore.TMP_NAME).exists())
    }

    @Test
    fun `the folder is made when it is not there`() {
        val dir = File(temp.root, "deep/er/palettes")
        assertEquals(StoreSave.Saved(replaced = false), store(dir).saveNow(sunset))
        assertTrue(file(dir).isFile)
    }

    @Test
    fun `two instances over one folder do not lose each other's saves`() {
        val dir = File(temp.root, "palettes")
        val a = store(dir)
        val b = store(dir)
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val tasks = (1..40).map { i ->
            pool.submit {
                start.await()
                (if (i % 2 == 0) a else b).saveNow(palette("P$i", 0xFF000000.toInt(), ColorMath.rgb(i, 0, 0)))
            }
        }
        start.countDown()
        tasks.forEach { it.get() }
        pool.shutdown()
        assertEquals(40, a.loadNow().palettes.size)
    }

    @Test
    fun `the header the store counts is the codec's own`() {
        assertEquals(PaletteStore.PALETTE_HEADER, PaletteCodec.encode(sunset).lines().first().substringBefore(' '))
    }

    @Test
    fun `the folder is named in files dir`() {
        assertEquals(File(temp.root, "palettes"), PaletteStore.directoryIn(temp.root))
    }
}
