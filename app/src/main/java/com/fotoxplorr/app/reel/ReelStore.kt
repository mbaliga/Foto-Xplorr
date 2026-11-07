package com.fotoxplorr.app.reel

import com.fotoxplorr.app.editor.JsonValue
import com.fotoxplorr.app.editor.asLong
import com.fotoxplorr.app.editor.get
import com.fotoxplorr.app.editor.parseJson
import com.fotoxplorr.app.editor.stringify
import com.fotoxplorr.app.editor.toJson
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One reel in the list, read from its file. A file that cannot be read still has a row, so it can be deleted. */
class SavedReel(
    val id: String,
    val name: String,
    val modifiedMillis: Long,
    val itemCount: Int,
    val durationMs: Long,
    val readable: Boolean,
)

/** The reels on disk, newest first. */
class SavedReels(val reels: List<SavedReel>)

/** What opening a reel gave. */
sealed interface ReelLoad {
    /** [decoded] says what, if anything, could not be read (an item, a newer file). */
    class Loaded(val id: String, val decoded: ReelDecoded) : ReelLoad

    /** No file under that id. */
    data object Missing : ReelLoad

    /** There is a file and it is not a reel this build can read. It is left where it is. */
    data object Unreadable : ReelLoad
}

/** What saving did. */
sealed interface ReelSave {
    data class Saved(val id: String) : ReelSave

    /** [ReelProjectStore.MAX_REELS] reels are kept already and this one would be new; nothing was written. */
    data object Full : ReelSave

    /** The file could not be written; the old one, if there was one, is as it was. */
    data object Failed : ReelSave
}

/** Where the screen keeps reels, so a test can give it one in memory. */
interface ReelLibrary {
    fun list(): SavedReels

    fun load(id: String): ReelLoad

    /** Writes [project] under [id], or under a new id when [id] is null. */
    fun save(id: String?, project: ReelProject): ReelSave

    fun delete(id: String): Boolean
}

/**
 * The saved reels: one file per reel, `<id>.json`, in one folder of the app's private storage
 * (`filesDir/reels`). A file is the reel's own text ([ReelCodec]) with the time it was saved added at the top,
 * so it still reads as a reel and an older or newer build ignores what it does not know.
 *
 *  - **Written whole, atomically.** The text goes to `<id>.json.tmp`, is synced, and is renamed over the old
 *    file, so a kill or a full disk leaves the old reel or the new one, never half of each. A `.tmp` left by a
 *    kill is removed by [sweepTemporary] (the list does it).
 *  - **A damaged file is not erased.** It is listed as unreadable (so the person can delete it) and opening it
 *    says so; saving never touches a file it did not make an id for.
 *  - **Ids are made here** (a random UUID) and every id that comes in is checked with [isValidId] before it
 *    reaches a path, so nothing a file or a caller says can name another folder.
 *  - **At most [MAX_REELS] reels.** A save of a reel that already has a file always works; only a new one is
 *    refused at the limit.
 *  - **One lock for the process**, so a save and a delete never meet.
 *
 * A file holds the recipe: which library photos and clips, in what order, with what moves, and the address of
 * the sound file, never a pixel. Not part of the encrypted backup. [writeFile] and [readFile] are the only
 * places the disk is touched, so a test can make either fail. Nothing here has run on a device.
 *
 * The file is `ReelStore.kt` but the class is not `ReelStore`: the writer's tests have a stand-in MediaStore
 * provider of that name in the same package, and two classes of one name in main and test shadow each other.
 */
class ReelProjectStore(
    private val directory: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val writeFile: (File, ByteArray) -> Unit = ::writeAtomically,
    private val readFile: (File) -> String = ::readLimited,
) : ReelLibrary {

    override fun list(): SavedReels = synchronized(LOCK) {
        sweepTemporary()
        val files = directory.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(EXT) && isValidId(it.name.removeSuffix(EXT)) }
        SavedReels(files.map(::summaryOf).sortedWith(compareByDescending<SavedReel> { it.modifiedMillis }.thenBy { it.id }))
    }

    override fun load(id: String): ReelLoad = synchronized(LOCK) {
        if (!isValidId(id)) return ReelLoad.Missing
        val file = fileOf(id)
        if (!file.isFile) return ReelLoad.Missing
        val text = readText(file) ?: return ReelLoad.Unreadable
        val decoded = ReelCodec.decode(text) ?: return ReelLoad.Unreadable
        ReelLoad.Loaded(id, decoded)
    }

    override fun save(id: String?, project: ReelProject): ReelSave = synchronized(LOCK) {
        val target = when {
            id == null -> {
                if (directory.listFiles().orEmpty().count { it.isFile && it.name.endsWith(EXT) } >= MAX_REELS) return ReelSave.Full
                newId()
            }
            isValidId(id) -> id
            else -> return ReelSave.Failed
        }
        val root = parseJson(ReelCodec.encode(project.copy(name = ReelEditing.cleanName(project.name)))) as? JsonValue.JsonObject
            ?: return ReelSave.Failed
        val stamped = JsonValue.JsonObject(LinkedHashMap<String, JsonValue>(root.entries).apply { put(MODIFIED_KEY, clock().toJson()) })
        try {
            if (!directory.isDirectory && !directory.mkdirs()) return ReelSave.Failed
            writeFile(fileOf(target), stamped.stringify().toByteArray(Charsets.UTF_8))
            ReelSave.Saved(target)
        } catch (e: Exception) {
            File(directory, "$target$EXT$TMP_EXT").delete()
            ReelSave.Failed
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

    private fun summaryOf(file: File): SavedReel {
        val id = file.name.removeSuffix(EXT)
        val text = readText(file)
        val decoded = text?.let(ReelCodec::decode)
        if (text == null || decoded == null) return SavedReel(id, ReelCopy.DAMAGED_REEL, file.lastModified(), 0, 0L, readable = false)
        val modified = (parseJson(text)?.get(MODIFIED_KEY)?.asLong(0L) ?: 0L).takeIf { it > 0L } ?: file.lastModified()
        val project = decoded.project
        return SavedReel(id, project.name.ifBlank { ReelCopy.DEFAULT_NAME }, modified, project.items.size, project.totalDurationMs, readable = true)
    }

    private fun readText(file: File): String? =
        try {
            readFile(file)
        } catch (e: Exception) {
            null
        }

    private fun fileOf(id: String) = File(directory, "$id$EXT")

    companion object {
        const val DIRECTORY_NAME = "reels"
        const val MAX_REELS = 60
        private const val EXT = ".json"
        private const val TMP_EXT = ".tmp"
        private const val MODIFIED_KEY = "modified"
        private val LOCK = Any()
        private val ID_SHAPE = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        /**
         * A reel whose text is longer than this is not put in the saved-state bundle (which has a limit of about
         * a megabyte for the whole screen); it comes back from its autosaved file instead.
         */
        const val SAVED_STATE_SAFE_CHARS = 60_000

        /** The longest file read: 300 items of a few hundred characters each, with room to spare. */
        private const val MAX_BYTES = 2L * 1024 * 1024

        fun newId(): String = UUID.randomUUID().toString()

        fun isValidId(id: String): Boolean = ID_SHAPE.matches(id)

        private fun readLimited(file: File): String {
            if (file.length() > MAX_BYTES) throw IOException("too big to be a reel")
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

/**
 * Saves the open reel into the library, one save at a time, and only when it differs from what was saved
 * last. The screen calls [save] from its debounced watcher, when a reel is left, and when the screen is torn
 * down; the lock makes those meet in order, so a reel that has no file yet gets exactly one (the second
 * caller finds the id the first made and the same project, and does nothing).
 *
 * A new reel with nothing in it is never written, so opening "Start with nothing" and going back leaves no
 * file, and a reel opened and left unchanged is not written again (its file may carry what this build cannot read). Plain Kotlin and the library interface, so it is tested on the JVM with an in-memory library.
 */
internal class ReelAutosaver(private val library: ReelLibrary) {
    private val lock = Mutex()
    private var token = -1L
    private var id: String? = null
    private var project: ReelProject? = null

    /** The result of a save that wrote (or found it already written), or null when there was nothing to write. */
    suspend fun save(open: OpenReel): ReelSave? = lock.withLock {
        if (token != open.token) {
            token = open.token
            id = open.storeId
            // A reel opened from its file starts as that file; one brought back after a kill, or new, has no known copy.
            project = open.loaded
        }
        val current = id
        if (current == null && open.project.items.isEmpty()) return null
        if (current != null && project == open.project) return ReelSave.Saved(current)
        val result = withContext(Dispatchers.IO) { library.save(current, open.project) }
        if (result is ReelSave.Saved) {
            id = result.id
            project = open.project
        }
        result
    }
}
