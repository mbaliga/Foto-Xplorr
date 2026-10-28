package com.fotoxplorr.app.aquarium

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class CreatureStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_000L
    private fun store(dir: File = File(tmp.root, "aquarium"), write: ((File, ByteArray) -> Unit)? = null) =
        if (write == null) CreatureStore(dir, clock = { now++ }) else CreatureStore(dir, clock = { now++ }, writeFile = write)

    private val png = byteArrayOf(1, 2, 3, 4, 5)

    private fun saved(r: SaveResult) = (r as SaveResult.Saved).creature

    @Test
    fun `a saved creature comes back with its picture and record`() {
        val s = store()
        val c = saved(s.save(png, 120, 80, headLeft = true))
        val back = s.list().single()
        assertEquals(c, back.record)
        assertEquals(120, back.record.widthPx)
        assertEquals(80, back.record.heightPx)
        assertTrue(back.record.headLeft)
        assertArrayEquals(png, back.png)
    }

    @Test
    fun `the file names are the generated id and nothing else`() {
        val s = store()
        val c = saved(s.save(png, 10, 10, false))
        assertTrue(CreatureId.isValid(c.id))
        val names = File(tmp.root, "aquarium").list()!!.toSet()
        assertEquals(setOf("${c.id}.png", "${c.id}.json"), names)
    }

    @Test
    fun `the json is written last, each file through a temporary name`() {
        val order = ArrayList<String>()
        val s = store(write = { f, b -> order.add(f.name); f.writeBytes(b) })
        val c = saved(s.save(png, 10, 10, false))
        assertEquals(listOf("${c.id}.png", "${c.id}.json"), order)
    }

    @Test
    fun `a save that stops after the picture leaves no creature and no files`() {
        val s = store(write = { f, b ->
            if (f.name.endsWith(".json")) throw IOException("disk full")
            f.writeBytes(b)
        })
        assertEquals(SaveResult.Failed, s.save(png, 10, 10, false))
        assertTrue(s.list().isEmpty())
        assertEquals(emptyList<String>(), File(tmp.root, "aquarium").list()!!.toList())
    }

    @Test
    fun `a picture with no record is invisible and the sweep removes it`() {
        val s = store()
        saved(s.save(png, 10, 10, false))
        val dir = File(tmp.root, "aquarium")
        val orphan = File(dir, "${CreatureId.generate()}.png").also { it.writeBytes(png) }
        val tmpFile = File(dir, "x.json.tmp").also { it.writeBytes(png) }
        assertEquals(1, s.list().size)
        assertEquals(2, s.sweepIncomplete())
        assertFalse(orphan.exists())
        assertFalse(tmpFile.exists())
        assertEquals(1, s.list().size)
    }

    @Test
    fun `the sweep keeps a complete pair`() {
        val s = store()
        saved(s.save(png, 10, 10, false))
        assertEquals(0, s.sweepIncomplete())
        assertEquals(1, s.list().size)
    }

    @Test
    fun `a record without a picture is ignored`() {
        val s = store()
        val c = saved(s.save(png, 10, 10, false))
        File(tmp.root, "aquarium/${c.id}.png").delete()
        assertTrue(s.list().isEmpty())
    }

    @Test
    fun `a damaged record is ignored and does not hide the others`() {
        val s = store()
        val good = saved(s.save(png, 10, 10, false))
        val bad = saved(s.save(png, 10, 10, false))
        File(tmp.root, "aquarium/${bad.id}.json").writeText("{not json")
        assertEquals(listOf(good.id), s.list().map { it.record.id })
    }

    @Test
    fun `a record naming another id is ignored, so a name cannot be redirected`() {
        val s = store()
        val c = saved(s.save(png, 10, 10, false))
        val other = CreatureId.generate()
        File(tmp.root, "aquarium/${c.id}.json").writeText(
            CreatureCodec.encode(StoredCreature(other, 1, 10, 10, false)),
        )
        assertTrue(s.list().isEmpty())
    }

    @Test
    fun `files that are not creatures are left alone`() {
        val s = store()
        val dir = File(tmp.root, "aquarium").also { it.mkdirs() }
        File(dir, "..json").writeText("{}")
        File(dir, "notes.json").writeText("{}")
        File(dir, "readme.txt").writeText("hi")
        assertTrue(s.list().isEmpty())
        s.sweepIncomplete()
        assertTrue(File(dir, "readme.txt").exists())
        assertTrue(File(dir, "notes.json").exists())
    }

    @Test
    fun `a record with a zero size is ignored`() {
        val s = store()
        val c = saved(s.save(png, 10, 10, false))
        File(tmp.root, "aquarium/${c.id}.json").writeText(CreatureCodec.encode(c.copy(widthPx = 0)))
        assertTrue(s.list().isEmpty())
    }

    @Test
    fun `an oversize picture file is not read`() {
        val s = store()
        val c = saved(s.save(png, 10, 10, false))
        File(tmp.root, "aquarium/${c.id}.png").writeBytes(ByteArray((CreatureStore.MAX_PNG_BYTES + 1).toInt()))
        assertTrue(s.list().isEmpty())
    }

    @Test
    fun `creatures are listed oldest first`() {
        val s = store()
        val a = saved(s.save(png, 10, 10, false))
        val b = saved(s.save(png, 10, 10, false))
        val c = saved(s.save(png, 10, 10, false))
        assertEquals(listOf(a.id, b.id, c.id), s.list().map { it.record.id })
    }

    @Test
    fun `delete removes both files, and refuses anything that is not an id`() {
        val s = store()
        val c = saved(s.save(png, 10, 10, false))
        val keep = File(tmp.root, "keep.txt").also { it.writeText("x") }
        assertFalse(s.delete("../keep"))
        assertFalse(s.delete(""))
        assertFalse(s.delete("../../keep.txt"))
        assertTrue(keep.exists())
        assertTrue(s.delete(c.id))
        assertTrue(File(tmp.root, "aquarium").list()!!.isEmpty())
        assertFalse("already gone", s.delete(c.id))
    }

    @Test
    fun `the store holds no more than the tank does`() {
        val s = store()
        repeat(CreatureStore.MAX_CREATURES) { saved(s.save(png, 10, 10, false)) }
        assertEquals(SaveResult.Full, s.save(png, 10, 10, false))
        assertEquals(CreatureStore.MAX_CREATURES, s.list().size)
        // Removing one makes room.
        s.delete(s.list().first().record.id)
        saved(s.save(png, 10, 10, false))
    }

    @Test
    fun `empty or sizeless input is refused without writing`() {
        val s = store()
        assertEquals(SaveResult.Failed, s.save(ByteArray(0), 10, 10, false))
        assertEquals(SaveResult.Failed, s.save(png, 0, 10, false))
        assertEquals(SaveResult.Failed, s.save(png, 10, -1, false))
        assertTrue(s.list().isEmpty())
    }

    @Test
    fun `listing a folder that does not exist is empty`() {
        assertTrue(store(File(tmp.root, "nothing-here")).list().isEmpty())
    }

    @Test
    fun `the real writer leaves no temporary file behind`() {
        val s = store()
        saved(s.save(png, 10, 10, false))
        assertTrue(File(tmp.root, "aquarium").list()!!.none { it.endsWith(".tmp") })
    }

    @Test
    fun `a second store over the same folder sees the same creatures`() {
        val a = store()
        val c = saved(a.save(png, 10, 10, true))
        assertEquals(c, store().list().single().record)
    }
}
