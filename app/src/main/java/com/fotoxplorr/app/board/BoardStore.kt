package com.fotoxplorr.app.board

import com.fotoxplorr.app.editor.JsonValue
import com.fotoxplorr.app.editor.asLong
import com.fotoxplorr.app.editor.asString
import com.fotoxplorr.app.editor.get
import com.fotoxplorr.app.editor.parseJson
import com.fotoxplorr.app.editor.stringify
import com.fotoxplorr.app.editor.toJson
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/** One board in the list, read from its file. A file that cannot be read still has a row, so it can be deleted. */
class SavedBoard(
    val id: String,
    val name: String,
    val modifiedMillis: Long,
    val canvas: CanvasSize,
    val layerCount: Int,
    val readable: Boolean,
)

/** The boards on disk, newest first, and how many files were too damaged to list properly. */
class SavedBoards(val boards: List<SavedBoard>) {
    val damaged: Int get() = boards.count { !it.readable }
}

/** What opening a board gave. */
sealed interface BoardLoad {
    /** [decoded] says what, if anything, could not be read (a layer, a mask, a newer file). */
    class Loaded(val id: String, val name: String, val decoded: BoardJson.Decoded) : BoardLoad

    /** No file under that id. */
    data object Missing : BoardLoad

    /** There is a file and it is not a board this build can read. It is left where it is. */
    data object Unreadable : BoardLoad
}

/** What saving did. */
sealed interface BoardSave {
    data class Saved(val id: String) : BoardSave

    /** [BoardStore.MAX_BOARDS] boards are kept already and this one would be new; nothing was written. */
    data object Full : BoardSave

    /** The file could not be written; the old one, if there was one, is as it was. */
    data object Failed : BoardSave
}

/** Where the screen keeps boards, so a test can give it one in memory. */
interface BoardLibrary {
    fun list(): SavedBoards

    fun load(id: String): BoardLoad

    /** Writes [board] under [id], or under a new id when [id] is null. */
    fun save(id: String?, name: String, board: Board): BoardSave

    fun delete(id: String): Boolean
}

/**
 * The saved boards: one file per board, `<id>.json`, in one folder of the app's private storage
 * (`filesDir/boards`). A file is the board's own text ([BoardJson]) with the board's name and the time
 * it was saved added at the top, so it still reads as a board and a newer or older build ignores
 * what it does not know.
 *
 *  - **Written whole, atomically.** The text goes to `<id>.json.tmp`, is synced, and is renamed over
 *    the old file, so a kill or a full disk leaves the old board or the new one, never half of each. A
 *    `.tmp` left by a kill is removed by [sweepTemporary] (the screen calls it when it lists).
 *  - **A damaged file is not erased.** It is listed as unreadable (so the person can delete it) and
 *    opening it says so; saving never touches a file it did not make an id for.
 *  - **Ids are made here** (a random UUID) and every id that comes in is checked with [isValidId]
 *    before it reaches a path, so nothing a file or a caller says can name another folder.
 *  - **At most [MAX_BOARDS] boards.** A save of a board that already has a file always works; only a
 *    new one is refused at the limit.
 *  - **One lock for the process**, so a save and a delete never meet.
 *
 * It holds the layout and the photo references of a board (ids, names, sizes, dates taken), never a
 * pixel; a photo is found again from the library when the board is opened. Not part of the encrypted
 * backup. [writeFile] and [readFile] are the only places the disk is touched, so a test can make
 * either fail. Nothing here has run on a device.
 */
class BoardStore(
    private val directory: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val writeFile: (File, ByteArray) -> Unit = ::writeAtomically,
    private val readFile: (File) -> String = ::readLimited,
) : BoardLibrary {

    override fun list(): SavedBoards = synchronized(LOCK) {
        sweepTemporary()
        val files = directory.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(EXT) && isValidId(it.name.removeSuffix(EXT)) }
        SavedBoards(files.map(::summaryOf).sortedWith(compareByDescending<SavedBoard> { it.modifiedMillis }.thenBy { it.id }))
    }

    override fun load(id: String): BoardLoad = synchronized(LOCK) {
        if (!isValidId(id)) return BoardLoad.Missing
        val file = fileOf(id)
        if (!file.isFile) return BoardLoad.Missing
        val root = readJson(file) ?: return BoardLoad.Unreadable
        val decoded = BoardJson.decodeValue(root) ?: return BoardLoad.Unreadable
        BoardLoad.Loaded(id, nameOf(root), decoded)
    }

    override fun save(id: String?, name: String, board: Board): BoardSave = synchronized(LOCK) {
        val target = when {
            id == null -> {
                if (directory.listFiles().orEmpty().count { it.isFile && it.name.endsWith(EXT) } >= MAX_BOARDS) return BoardSave.Full
                newId()
            }
            isValidId(id) -> id
            else -> return BoardSave.Failed
        }
        val root = BoardJson.encodeValue(board) as JsonValue.JsonObject
        val withName = JsonValue.JsonObject(
            LinkedHashMap<String, JsonValue>().apply {
                putAll(root.entries)
                put(NAME_KEY, cleanName(name).toJson())
                put(MODIFIED_KEY, clock().toJson())
            },
        )
        try {
            if (!directory.isDirectory && !directory.mkdirs()) return BoardSave.Failed
            writeFile(fileOf(target), withName.stringify().toByteArray(Charsets.UTF_8))
            BoardSave.Saved(target)
        } catch (e: Exception) {
            File(directory, "$target$EXT$TMP_EXT").delete()
            BoardSave.Failed
        }
    }

    override fun delete(id: String): Boolean = synchronized(LOCK) {
        if (!isValidId(id)) return false
        val gone = fileOf(id).delete()
        File(directory, "$id$EXT$TMP_EXT").delete()
        gone
    }

    /** Removes what a save that was stopped leaves: `.tmp` files. Returns how many. */
    fun sweepTemporary(): Int = synchronized(LOCK) {
        var removed = 0
        for (f in directory.listFiles().orEmpty()) if (f.isFile && f.name.endsWith(TMP_EXT) && f.delete()) removed++
        removed
    }

    private fun summaryOf(file: File): SavedBoard {
        val id = file.name.removeSuffix(EXT)
        val root = readJson(file)
        val decoded = root?.let(BoardJson::decodeValue)
        if (root == null || decoded == null) {
            return SavedBoard(id, BoardCopy.DAMAGED_BOARD, file.lastModified(), CanvasPreset.SQUARE.size, 0, readable = false)
        }
        val modified = root[MODIFIED_KEY].asLong(0L).takeIf { it > 0L } ?: file.lastModified()
        return SavedBoard(id, nameOf(root), modified, decoded.board.canvas, decoded.board.layers.size, readable = true)
    }

    private fun readJson(file: File): JsonValue? =
        try {
            parseJson(readFile(file))
        } catch (e: Exception) {
            null
        }

    private fun nameOf(root: JsonValue): String = cleanName(root[NAME_KEY].asString())

    private fun fileOf(id: String) = File(directory, "$id$EXT")

    companion object {
        const val DIRECTORY_NAME = "boards"
        const val MAX_BOARDS = 60
        const val MAX_NAME = 60
        private const val EXT = ".json"
        private const val TMP_EXT = ".tmp"
        private const val NAME_KEY = "name"
        private const val MODIFIED_KEY = "modified"
        private val LOCK = Any()
        private val ID_SHAPE = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        fun newId(): String = UUID.randomUUID().toString()

        fun isValidId(id: String): Boolean = ID_SHAPE.matches(id)

        /** A name the list can show: no line breaks or control characters, trimmed, cut to [MAX_NAME]; blank is the default. */
        fun cleanName(name: String): String =
            name.filter { !it.isISOControl() }.trim().take(MAX_NAME).trim().ifBlank { BoardCopy.DEFAULT_NAME }

        /** The longest file read: a board's text is capped at [BoardJson.MAX_CHARS] characters, and a character is at most 3 bytes. */
        private const val MAX_BYTES = BoardJson.MAX_CHARS * 3L

        private fun readLimited(file: File): String {
            if (file.length() > MAX_BYTES) throw IOException("too big to be a board")
            return file.readText(Charsets.UTF_8)
        }

        /** Writes to `<name>.tmp`, syncs, then renames over the target. */
        private fun writeAtomically(target: File, bytes: ByteArray) {
            val tmp = File(target.parentFile, target.name + TMP_EXT)
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw IOException("could not move ${tmp.name} into place")
            }
        }
    }
}
