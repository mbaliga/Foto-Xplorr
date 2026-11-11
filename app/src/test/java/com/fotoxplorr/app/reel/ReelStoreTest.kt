package com.fotoxplorr.app.reel

import androidx.compose.runtime.saveable.SaverScope
import com.fotoxplorr.app.reel.ReelScreenKit.stills
import java.io.File
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The reel files on disk: a round trip, a damaged file, a failed write, the limit, and the autosaver over a library. */
class ReelStoreTest {
    private lateinit var dir: File
    private var now = 1_000L

    @Before
    fun directory() {
        dir = File.createTempFile("reels-", "").apply { delete() }
    }

    @After
    fun clean() {
        dir.deleteRecursively()
    }

    private fun store(write: ((File, ByteArray) -> Unit)? = null) =
        if (write == null) ReelProjectStore(dir, clock = { now }) else ReelProjectStore(dir, clock = { now }, writeFile = write)

    private fun sample() = stills(1, 2, 3).copy(
        name = "Beach",
        aspect = ReelAspect.TALL,
        soundtrack = ReelSoundtrack("content://doc/7", 5_000L, 3_000L),
    )

    @Test
    fun `a reel saves under a new id and reads back exactly`() {
        val s = store()
        val saved = s.save(null, sample()) as ReelSave.Saved
        assertTrue(ReelProjectStore.isValidId(saved.id))
        val loaded = s.load(saved.id) as ReelLoad.Loaded
        assertEquals(sample(), loaded.decoded.project)
        assertEquals(0, loaded.decoded.skippedItems)
        assertFalse(loaded.decoded.fromNewerVersion)
    }

    @Test
    fun `saving again under the same id replaces the file and keeps one`() {
        val s = store()
        val id = (s.save(null, sample()) as ReelSave.Saved).id
        now = 5_000L
        assertEquals(ReelSave.Saved(id), s.save(id, stills(9)))
        assertEquals(1, dir.listFiles()!!.count { it.name.endsWith(".json") })
        assertEquals(stills(9), (s.load(id) as ReelLoad.Loaded).decoded.project)
        assertEquals(5_000L, s.list().reels.single().modifiedMillis)
    }

    @Test
    fun `the list is newest first with name, count and length`() {
        val s = store()
        now = 1_000L
        val a = (s.save(null, sample()) as ReelSave.Saved).id
        now = 2_000L
        val b = (s.save(null, stills(1).copy(name = "")) as ReelSave.Saved).id
        val list = s.list().reels
        assertEquals(listOf(b, a), list.map { it.id })
        assertEquals(ReelCopy.DEFAULT_NAME, list[0].name)
        assertEquals("Beach", list[1].name)
        assertEquals(3, list[1].itemCount)
        assertEquals(sample().totalDurationMs, list[1].durationMs)
        assertTrue(list.all { it.readable })
    }

    @Test
    fun `the name is cleaned when it is saved`() {
        val s = store()
        val id = (s.save(null, stills(1).copy(name = "  A\u0007 b  ")) as ReelSave.Saved).id
        assertEquals("A b", (s.load(id) as ReelLoad.Loaded).decoded.project.name)
    }

    @Test
    fun `a damaged file is listed as unreadable, is not erased, and says so when opened`() {
        val s = store()
        val id = ReelProjectStore.newId()
        dir.mkdirs()
        File(dir, "$id.json").writeText("not a reel {{{")
        val row = s.list().reels.single()
        assertFalse(row.readable)
        assertEquals(ReelCopy.DAMAGED_REEL, row.name)
        assertEquals(ReelLoad.Unreadable, s.load(id))
        assertTrue(File(dir, "$id.json").isFile)
        assertTrue(s.delete(id))
        assertTrue(s.list().reels.isEmpty())
    }

    @Test
    fun `a file that is far too big is unreadable and a missing one is missing`() {
        val s = store()
        val id = ReelProjectStore.newId()
        dir.mkdirs()
        File(dir, "$id.json").writeBytes(ByteArray(3 * 1024 * 1024) { 'a'.code.toByte() })
        assertEquals(ReelLoad.Unreadable, s.load(id))
        assertEquals(ReelLoad.Missing, s.load(ReelProjectStore.newId()))
    }

    @Test
    fun `an id that is not one of ours never reaches a path`() {
        val s = store()
        for (bad in listOf("../x", "a/b", "", "1234", "..", "x".repeat(36))) {
            assertFalse(ReelProjectStore.isValidId(bad))
            assertEquals(ReelLoad.Missing, s.load(bad))
            assertEquals(ReelSave.Failed, s.save(bad, sample()))
            assertFalse(s.delete(bad))
        }
        assertFalse(dir.exists() && dir.listFiles()!!.isNotEmpty())
    }

    @Test
    fun `a failed write leaves the old reel and no temporary file`() {
        val id = (store().save(null, sample()) as ReelSave.Saved).id
        val failing = store { target, _ ->
            File(target.parentFile, target.name + ".tmp").writeText("half")
            throw IOException("disk full")
        }
        assertEquals(ReelSave.Failed, failing.save(id, stills(9)))
        assertEquals(sample(), (store().load(id) as ReelLoad.Loaded).decoded.project)
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun `a temporary file left by a kill is swept when the list is read`() {
        val s = store()
        s.save(null, sample())
        File(dir, "${ReelProjectStore.newId()}.json.tmp").writeText("half")
        assertEquals(1, s.list().reels.size)
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun `the limit stops a new reel but never a reel that has a file`() {
        val s = store()
        val ids = (1..ReelProjectStore.MAX_REELS).map { (s.save(null, stills(it.toLong())) as ReelSave.Saved).id }
        assertEquals(ReelSave.Full, s.save(null, sample()))
        assertEquals(ReelSave.Saved(ids[0]), s.save(ids[0], sample()))
        assertEquals(ReelProjectStore.MAX_REELS, dir.listFiles()!!.size)
    }

    @Test
    fun `a file from a newer build opens, says so, and keeps what this build can read`() {
        val s = store()
        val id = ReelProjectStore.newId()
        dir.mkdirs()
        File(dir, "$id.json").writeText(ReelCodec.encode(sample()).replaceFirst("\"version\":1", "\"version\":9"))
        val loaded = s.load(id) as ReelLoad.Loaded
        assertTrue(loaded.decoded.fromNewerVersion)
        assertEquals(sample(), loaded.decoded.project)
        assertEquals(ReelCopy.NEWER_VERSION, noticeFor(loaded.decoded))
        assertEquals(ReelCopy.partsLost(2), noticeFor(ReelDecoded(ReelProject(), 2, false)))
        assertNull(noticeFor(ReelDecoded(ReelProject(), 0, false)))
    }

    @Test
    fun `one item more than one is told apart in the words`() {
        assertEquals("1 item could not be read and was left out.", ReelCopy.partsLost(1))
        assertEquals("4 items could not be read and were left out.", ReelCopy.partsLost(4))
    }

    // ---- the autosaver ----

    @Test
    fun `a new reel with nothing in it is never written, one with items is written once`() = runBlocking {
        val lib = MemoryReelLibrary()
        val saver = ReelAutosaver(lib)
        val empty = OpenReel(1, null, ReelProject())
        assertNull(saver.save(empty))
        assertTrue(lib.saves.isEmpty())

        val withItems = OpenReel(2, null, stills(1))
        val first = saver.save(withItems) as ReelSave.Saved
        // The same reel again, now with the id the first save made, writes nothing more.
        assertEquals(first, saver.save(withItems.saved(first.id)))
        assertEquals(1, lib.saves.size)
        val changed = withItems.saved(first.id).withProject(stills(1, 2))
        assertEquals(first, saver.save(changed))
        assertEquals(2, lib.saves.size)
        assertEquals(first.id, lib.saves[1].first)
    }

    @Test
    fun `two saves of the same new reel at once make one file`() = runBlocking {
        val lib = MemoryReelLibrary()
        val saver = ReelAutosaver(lib)
        val open = OpenReel(3, null, stills(1))
        val a = async { saver.save(open) }
        val b = async { saver.save(open) }
        a.await()
        b.await()
        assertEquals(1, lib.entries.size)
    }

    @Test
    fun `a reel opened from its file and left alone is not written again, an edited one is`() = runBlocking {
        val lib = MemoryReelLibrary()
        val project = stills(1, 2)
        val id = lib.put(project)
        val saver = ReelAutosaver(lib)
        val opened = OpenReel(4, id, project, loaded = project)
        assertEquals(ReelSave.Saved(id), saver.save(opened))
        assertTrue(lib.saves.isEmpty())
        saver.save(opened.withProject(stills(1, 2, 3)))
        assertEquals(1, lib.saves.size)
    }

    @Test
    fun `a reel brought back after a kill is written, since nothing says what the file has`() = runBlocking {
        val lib = MemoryReelLibrary()
        val id = lib.put(stills(1))
        val saver = ReelAutosaver(lib)
        saver.save(OpenReel(5, id, stills(1, 2)))
        assertEquals(1, lib.saves.size)
    }

    @Test
    fun `a failed save is reported and tried again next time`() = runBlocking {
        val lib = MemoryReelLibrary()
        val saver = ReelAutosaver(lib)
        lib.result = { ReelSave.Failed }
        val open = OpenReel(6, null, stills(1))
        assertEquals(ReelSave.Failed, saver.save(open))
        lib.result = null
        assertTrue(saver.save(open) is ReelSave.Saved)
        assertEquals(2, lib.saves.size)
    }

    @Test
    fun `a full library is reported`() = runBlocking {
        val lib = MemoryReelLibrary().apply { result = { ReelSave.Full } }
        assertEquals(ReelSave.Full, ReelAutosaver(lib).save(OpenReel(7, null, stills(1))))
    }

    // ---- what survives the process being killed ----

    private val scope = SaverScope { true }

    @Test
    fun `the open reel comes back from the saved state with its place in the library`() {
        val open = OpenReel(8, "abc", sample())
        val saved = with(OpenReelSaver) { scope.save(open) }
        assertNotNull(saved)
        val back = OpenReelSaver.restore(saved!!)
        assertEquals(sample(), back?.project)
        assertEquals("abc", back?.storeId)
        assertNull(back?.loaded)
    }

    @Test
    fun `a reel too long for the bundle is not put in it, and nothing open saves as nothing`() {
        val huge = ReelProject(items = (1..ReelLimits.MAX_ITEMS).map { TitleItem("r$it", "x".repeat(ReelLimits.TEXT_MAX_CHARS)) })
        val saved = with(OpenReelSaver) { scope.save(OpenReel(9, "abc", huge)) }
        assertNotNull(saved)
        assertNull("it comes back from its file, not from the bundle", OpenReelSaver.restore(saved!!))
        assertNull(with(OpenReelSaver) { scope.save(null) })
        assertNull(OpenReelSaver.restore("junk"))
    }
}
