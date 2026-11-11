package com.fotoxplorr.app.palettebuilder

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The saved palettes as read, and whether anything in the file could not be. */
internal class SavedPalettes(
    val palettes: List<Palette>,
    /** At least one palette in the file was damaged and left out. */
    val damaged: Boolean = false,
    /** The file exists but could not be read at all; [palettes] is empty and a save is refused until it can be. */
    val unreadable: Boolean = false,
)

/** What a save did. */
internal sealed interface StoreSave {
    /** Stored; [replaced] when a palette of the same name was overwritten. */
    data class Saved(val replaced: Boolean) : StoreSave

    /** [PaletteCodec.MAX_SAVED] palettes are already saved; nothing was written. */
    data object Full : StoreSave

    /** The file could not be written or the old one not read; the old file is as it was. */
    data object Failed : StoreSave
}

/** Where the builder keeps palettes. The screen holds this, so a test gives it an in-memory one. */
internal interface PaletteLibrary {
    /** Never throws. */
    suspend fun load(): SavedPalettes

    suspend fun save(palette: Palette): StoreSave

    /** Removes the saved palette named [name] (names are unique, ignoring case); false when there was none. */
    suspend fun delete(name: String): Boolean
}

/**
 * The saved palettes: ONE text file, `palettes.txt` in [directory] (the app's private storage), holding every
 * palette in [PaletteCodec.encodeAll]'s format, newest first.
 *
 * - **A name is a palette.** Two palettes whose names are equal ignoring case are the same one: saving over a
 *   name replaces it and moves it to the front. The result says so ([StoreSave.Saved.replaced]).
 * - **Written whole, atomically.** The new text goes to `palettes.txt.tmp`, is synced, and is renamed over the
 *   old file, so a kill or a full disk leaves either the old file or the new one, never half of each. A `.tmp`
 *   left by a kill is removed by the next save.
 * - **A damaged file is not erased.** A palette in the file that does not decode is left out of the list
 *   ([SavedPalettes.damaged]); before the next save writes the file without it, the old file is copied to
 *   `palettes.txt.bad` (once per such save, replacing an earlier copy). A file that cannot be read at all
 *   ([SavedPalettes.unreadable]) is never overwritten: the save fails instead.
 * - **At most [PaletteCodec.MAX_SAVED]**; a new name beyond that is refused ([StoreSave.Full]).
 * - **One lock for the process.** Any number of instances over the same directory share [LOCK], so a save and a
 *   delete never read the same old text (TRAPS #26's lesson, for a file instead of preferences).
 *
 * Holds only colours and names the person typed: no photo, file name or place. Not part of the encrypted backup.
 * [writeFile] and [readFile] are the only places the file is written and read, so a test can make either fail. Nothing here has run on a device.
 */
internal class PaletteStore(
    private val directory: File,
    private val writeFile: (File, ByteArray) -> Unit = ::writeAtomically,
    private val readFile: (File) -> String = { it.readText(Charsets.UTF_8) },
) : PaletteLibrary {
    private val file: File get() = File(directory, FILE_NAME)

    override suspend fun load(): SavedPalettes = withContext(Dispatchers.IO) { loadNow() }

    override suspend fun save(palette: Palette): StoreSave = withContext(Dispatchers.IO) { saveNow(palette) }

    override suspend fun delete(name: String): Boolean = withContext(Dispatchers.IO) { deleteNow(name) }

    fun loadNow(): SavedPalettes = synchronized(LOCK) { read().first }

    fun saveNow(palette: Palette): StoreSave = synchronized(LOCK) {
        val (current, text) = read()
        if (current.unreadable) return StoreSave.Failed
        val kept = current.palettes.filterNot { sameName(it, palette) }
        val replaced = kept.size != current.palettes.size
        if (!replaced && current.palettes.size >= PaletteCodec.MAX_SAVED) return StoreSave.Full
        val all = (listOf(palette) + kept).take(PaletteCodec.MAX_SAVED)
        return if (write(all, aside = if (current.damaged) text else null)) StoreSave.Saved(replaced) else StoreSave.Failed
    }

    fun deleteNow(name: String): Boolean = synchronized(LOCK) {
        val (current, text) = read()
        if (current.unreadable) return false
        val kept = current.palettes.filterNot { it.name.equals(name, ignoreCase = true) }
        if (kept.size == current.palettes.size) return false
        write(kept, aside = if (current.damaged) text else null)
    }

    /** The file read, with its raw text for the copy-aside. A missing file is an empty store, not damage. */
    private fun read(): Pair<SavedPalettes, String?> {
        val f = file
        if (!f.isFile) return SavedPalettes(emptyList()) to null
        val text = try {
            readFile(f)
        } catch (e: IOException) {
            return SavedPalettes(emptyList(), unreadable = true) to null
        } catch (e: SecurityException) {
            return SavedPalettes(emptyList(), unreadable = true) to null
        }
        if (text.isBlank()) return SavedPalettes(emptyList()) to text
        val palettes = PaletteCodec.decodeAll(text)
        val headers = text.lines().count { it.startsWith(PALETTE_HEADER) }
        // Text with no palette header at all is damage too, unless it is only whitespace (handled above).
        val damaged = palettes.size < minOf(headers, PaletteCodec.MAX_SAVED) || (headers == 0)
        return SavedPalettes(palettes, damaged = damaged) to text
    }

    private fun write(palettes: List<Palette>, aside: String?): Boolean {
        try {
            if (!directory.isDirectory && !directory.mkdirs()) return false
            File(directory, TMP_NAME).delete()
            if (aside != null) {
                // Keep what is about to be dropped; a failure to keep it must not stop the save.
                runCatching { writeFile(File(directory, BAD_NAME), aside.toByteArray(Charsets.UTF_8)) }
            }
            writeFile(file, PaletteCodec.encodeAll(palettes).toByteArray(Charsets.UTF_8))
            return true
        } catch (e: IOException) {
            return false
        } catch (e: SecurityException) {
            return false
        }
    }

    private fun sameName(a: Palette, b: Palette): Boolean = a.name.equals(b.name, ignoreCase = true)

    companion object {
        const val FILE_NAME = "palettes.txt"
        const val TMP_NAME = "palettes.txt.tmp"
        const val BAD_NAME = "palettes.txt.bad"

        /** The first line of every palette in [PaletteCodec]'s text; a test pins it to the codec's own. */
        internal const val PALETTE_HEADER = "fotoz-palette"

        private val LOCK = Any()

        /** The app's palette folder under [filesDir]. */
        fun directoryIn(filesDir: File): File = File(filesDir, "palettes")

        private fun writeAtomically(target: File, bytes: ByteArray) {
            val tmp = File(target.parentFile, target.name + ".tmp")
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
