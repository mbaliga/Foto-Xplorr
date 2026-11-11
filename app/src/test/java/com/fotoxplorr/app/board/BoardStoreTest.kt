package com.fotoxplorr.app.board

import com.fotoxplorr.app.board.BoardTestKit.RED
import com.fotoxplorr.app.board.BoardTestKit.board
import com.fotoxplorr.app.board.BoardTestKit.hint
import com.fotoxplorr.app.board.BoardTestKit.layer
import com.fotoxplorr.app.board.BoardTestKit.photo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class BoardStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var dir: File
    private var now = 1_000L

    @Before
    fun folder() {
        dir = File(temp.root, "boards")
    }

    private fun store(
        write: (File, ByteArray) -> Unit = { f, b -> f.writeBytes(b) },
        read: (File) -> String = { it.readText() },
    ) = BoardStore(dir, { now }, write, read)

    private val sample = board(
        layer(1, 300f, 300f, content = photo(5), opacity = 0.5f, blend = LayerBlend.MULTIPLY),
        layer(2, 600f, 500f, w = 200f, h = 80f, rot = 30f, content = LayerContent.Text("Hello", RED, 48f, bold = true)),
        layer(3, 540f, 540f, mask = LayerMask(MaskShape.Subject(listOf(hint(true, 0.4f, 0.4f))))),
        canvas = CanvasSize(1080, 1350),
    ).copy(background = BoardBackground.Transparent)

    // ---- the round trip ----

    @Test
    fun `a saved board comes back equal, with its name`() {
        val s = store()
        val saved = s.save(null, "Spring", sample) as BoardSave.Saved
        val loaded = s.load(saved.id) as BoardLoad.Loaded
        assertEquals("Spring", loaded.name)
        assertEquals(sample, loaded.decoded.board)
        assertTrue(loaded.decoded.isComplete)
        assertEquals(saved.id, loaded.id)
    }

    @Test
    fun `saving under the same id replaces the board and keeps one file`() {
        val s = store()
        val id = (s.save(null, "A", sample) as BoardSave.Saved).id
        val changed = sample.copy(layers = sample.layers.drop(1))
        assertEquals(BoardSave.Saved(id), s.save(id, "B", changed))
        assertEquals(1, s.list().boards.size)
        val loaded = s.load(id) as BoardLoad.Loaded
        assertEquals("B", loaded.name)
        assertEquals(2, loaded.decoded.board.layers.size)
    }

    @Test
    fun `the file is still a board for the board codec, with the name beside it`() {
        val s = store()
        val id = (s.save(null, "Spring", sample) as BoardSave.Saved).id
        val text = File(dir, "$id.json").readText()
        assertEquals(sample, BoardJson.decode(text)!!.board)
        assertTrue(text.contains("\"name\""))
    }

    // ---- the list ----

    @Test
    fun `the list is newest first and says what each board is`() {
        val s = store()
        now = 1_000L
        val a = (s.save(null, "Old", sample) as BoardSave.Saved).id
        now = 3_000L
        val b = (s.save(null, "New", board(layer(1, 1f, 1f))) as BoardSave.Saved).id
        now = 2_000L
        val c = (s.save(null, "Middle", sample) as BoardSave.Saved).id
        val list = s.list()
        assertEquals(listOf(b, c, a), list.boards.map { it.id })
        assertEquals("New", list.boards[0].name)
        assertEquals(1, list.boards[0].layerCount)
        assertEquals(3, list.boards[1].layerCount)
        assertEquals(CanvasSize(1080, 1350), list.boards[1].canvas)
        assertEquals(0, list.damaged)
    }

    @Test
    fun `saving an old board again moves it to the front`() {
        val s = store()
        now = 1_000L
        val a = (s.save(null, "A", sample) as BoardSave.Saved).id
        now = 2_000L
        val b = (s.save(null, "B", sample) as BoardSave.Saved).id
        now = 3_000L
        s.save(a, "A", sample)
        assertEquals(listOf(a, b), s.list().boards.map { it.id })
    }

    @Test
    fun `an empty or missing folder lists nothing`() {
        assertTrue(store().list().boards.isEmpty())
        dir.mkdirs()
        assertTrue(store().list().boards.isEmpty())
    }

    @Test
    fun `files that are not board ids are ignored`() {
        dir.mkdirs()
        File(dir, "notes.json").writeText("{}")
        File(dir, "photo.png").writeBytes(byteArrayOf(1))
        assertTrue(store().list().boards.isEmpty())
    }

    // ---- damage ----

    @Test
    fun `a damaged file is listed as unreadable, never loaded and never deleted by the list`() {
        val s = store()
        val good = (s.save(null, "Good", sample) as BoardSave.Saved).id
        val bad = BoardStore.newId()
        File(dir, "$bad.json").writeText("{ this is not json")
        val list = s.list()
        assertEquals(2, list.boards.size)
        assertEquals(1, list.damaged)
        val row = list.boards.first { it.id == bad }
        assertFalse(row.readable)
        assertEquals(BoardCopy.DAMAGED_BOARD, row.name)
        assertEquals(BoardLoad.Unreadable, s.load(bad))
        assertTrue("still there", File(dir, "$bad.json").isFile)
        assertTrue(s.delete(bad))
        assertFalse(File(dir, "$bad.json").exists())
        assertNotNull(s.load(good))
    }

    @Test
    fun `text that is JSON but not a board is unreadable`() {
        dir.mkdirs()
        val id = BoardStore.newId()
        File(dir, "$id.json").writeText("""{"kind":"something-else"}""")
        assertEquals(BoardLoad.Unreadable, store().load(id))
    }

    @Test
    fun `a file cut short is unreadable and a layer the codec skips is counted`() {
        val s = store()
        val id = (s.save(null, "Cut", sample) as BoardSave.Saved).id
        val text = File(dir, "$id.json").readText()
        File(dir, "$id.json").writeText(text.take(text.length / 2))
        assertEquals(BoardLoad.Unreadable, s.load(id))

        val other = (s.save(null, "Odd", sample) as BoardSave.Saved).id
        val broken = File(dir, "$other.json").readText().replaceFirst("\"layers\":[", "\"layers\":[{\"nonsense\":true},")
        File(dir, "$other.json").writeText(broken)
        val loaded = s.load(other) as BoardLoad.Loaded
        assertEquals(1, loaded.decoded.skippedLayers)
        assertEquals(3, loaded.decoded.board.layers.size)
    }

    @Test
    fun `a read that throws is an unreadable board, not a crash`() {
        val s = store()
        val id = (s.save(null, "A", sample) as BoardSave.Saved).id
        val failing = store(read = { throw IOException("disk") })
        assertEquals(BoardLoad.Unreadable, failing.load(id))
        assertEquals(1, failing.list().damaged)
    }

    @Test
    fun `an enormous file is not read`() {
        dir.mkdirs()
        val id = BoardStore.newId()
        File(dir, "$id.json").outputStream().use { out ->
            val chunk = ByteArray(1 shl 20)
            repeat(13) { out.write(chunk) }
        }
        assertEquals(BoardLoad.Unreadable, BoardStore(dir).load(id))
    }

    // ---- the write ----

    @Test
    fun `a write that fails leaves the old board as it was and reports it`() {
        val s = store()
        val id = (s.save(null, "Keep", sample) as BoardSave.Saved).id
        val failing = store(write = { _, _ -> throw IOException("full") })
        assertEquals(BoardSave.Failed, failing.save(id, "Lost", sample.copy(layers = emptyList())))
        val loaded = s.load(id) as BoardLoad.Loaded
        assertEquals("Keep", loaded.name)
        assertEquals(3, loaded.decoded.board.layers.size)
    }

    @Test
    fun `a failed first save makes no file`() {
        val failing = store(write = { _, _ -> throw IOException("full") })
        assertEquals(BoardSave.Failed, failing.save(null, "x", sample))
        assertTrue(store().list().boards.isEmpty())
    }

    @Test
    fun `the real writer leaves no temporary file behind and replaces whole`() {
        val s = BoardStore(dir, { now })
        val id = (s.save(null, "Real", sample) as BoardSave.Saved).id
        s.save(id, "Real", sample.copy(layers = emptyList()))
        assertEquals(listOf("$id.json"), dir.list()!!.toList())
        assertEquals(0, (s.load(id) as BoardLoad.Loaded).decoded.board.layers.size)
    }

    @Test
    fun `a temporary file a kill left is swept when the list is read`() {
        val s = store()
        s.save(null, "A", sample)
        File(dir, "${BoardStore.newId()}.json.tmp").writeText("half")
        assertEquals(1, s.list().boards.size)
        assertTrue(dir.list()!!.none { it.endsWith(".tmp") })
    }

    @Test
    fun `the limit stops a new board but never a board that has a file`() {
        val s = store()
        val ids = (1..BoardStore.MAX_BOARDS).map { (s.save(null, "B$it", sample) as BoardSave.Saved).id }
        assertEquals(BoardSave.Full, s.save(null, "One more", sample))
        assertEquals(BoardSave.Saved(ids[0]), s.save(ids[0], "Again", sample))
        assertTrue(s.delete(ids[1]))
        assertTrue(s.save(null, "Now it fits", sample) is BoardSave.Saved)
    }

    // ---- ids and names ----

    @Test
    fun `an id that is not one of ours never reaches a path`() {
        val s = store()
        assertEquals(BoardLoad.Missing, s.load("../../etc/passwd"))
        assertEquals(BoardSave.Failed, s.save("../x", "n", sample))
        assertFalse(s.delete("../x"))
        assertFalse(BoardStore.isValidId("ABCDEF00-0000-0000-0000-000000000000"))
        assertTrue(BoardStore.isValidId(BoardStore.newId()))
        assertNotEquals(BoardStore.newId(), BoardStore.newId())
    }

    @Test
    fun `a missing board is missing, and deleting it says so`() {
        val s = store()
        assertEquals(BoardLoad.Missing, s.load(BoardStore.newId()))
        assertFalse(s.delete(BoardStore.newId()))
    }

    @Test
    fun `names are trimmed, cut, stripped of line breaks, and never blank`() {
        assertEquals("a b", BoardStore.cleanName("  a b \n"))
        assertEquals(BoardCopy.DEFAULT_NAME, BoardStore.cleanName("   "))
        assertEquals(BoardCopy.DEFAULT_NAME, BoardStore.cleanName("\n\t"))
        assertEquals(BoardStore.MAX_NAME, BoardStore.cleanName("x".repeat(200)).length)
        assertEquals("ab", BoardStore.cleanName("a\u0000b"))
    }

    @Test
    fun `a name with quotes and unicode survives the file`() {
        val s = store()
        val id = (s.save(null, "Mum's \"best\" - é世界", sample) as BoardSave.Saved).id
        assertEquals("Mum's \"best\" - é世界", (s.load(id) as BoardLoad.Loaded).name)
    }
}
