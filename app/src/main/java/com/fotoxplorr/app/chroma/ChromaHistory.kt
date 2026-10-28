package com.fotoxplorr.app.chroma

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** How a reading was made, which is how far to trust it. */
internal enum class ReadingKind(val code: String) {
    /** Flash and room, no card: the shade of colour only; how light it is was not measured. */
    APPROXIMATE("approx"),

    /** Flash and room, corrected with a white card (about 3 to 6 CIEDE2000 off at best, and only if the card is plain white). */
    CALIBRATED("card"),

    /** No flash: the colour under the room's light, not the surface's own; how light it is was not measured. */
    ROOM("room"),
    ;

    /**
     * Whether the colour's lightness comes from the surface. Only a card fixes the level of the flash, so only
     * a card-corrected reading has it; the others are exposed by the camera's own auto exposure, which lights a
     * dark surface and a light one alike.
     */
    val measuresLightness: Boolean get() = this == CALIBRATED

    companion object {
        fun of(code: String): ReadingKind? = entries.firstOrNull { it.code == code }
    }
}

/**
 * One finished reading as it is kept: the colour (opaque sRGB), how it was made, and when. Nothing else:
 * no picture, no place, no camera setting. Everything the screen shows about the colour (Lab, names,
 * words) is worked out again from [argb], so a stored line stays small and a rule change reaches old readings.
 */
internal data class SavedReading(val argb: Int, val kind: ReadingKind, val atMillis: Long) {
    init {
        require((argb ushr 24) == 0xFF) { "readings are opaque" }
        require(atMillis >= 0L) { "time before 1970" }
    }

    val hex: String get() = ColourSpace.hex(argb)

    /** `reading #RRGGBB approx 1700000000000`, the text form of one line of the history file. */
    fun toLine(): String = "reading $hex ${kind.code} $atMillis"

    companion object {
        /** The reading on [line], or null when it is not a well-formed `reading` line. */
        fun fromLine(line: String): SavedReading? {
            val parts = line.trim().split(' ').filter { it.isNotEmpty() }
            if (parts.size != 4 || parts[0] != "reading") return null
            if (!parts[1].startsWith("#")) return null
            val argb = ColourSpace.parseHex(parts[1]) ?: return null
            val kind = ReadingKind.of(parts[2]) ?: return null
            val at = parts[3].toLongOrNull()?.takeIf { it >= 0L } ?: return null
            return SavedReading(argb, kind, at)
        }
    }
}

/**
 * The plain-text form of the history:
 *
 * ```
 * fotoz-chroma 1
 * reading #B03020 card 1700000000000
 * reading #20A0C0 approx 1699999999000
 * ```
 *
 * Newest first. Strict where it matters and tolerant where a newer build might have added something: the
 * header must be `fotoz-chroma <whole number >= 1>`; a line that is not a `reading` line is ignored, a
 * `reading` line that is malformed is counted in [Decoded.skipped] and left out; at most [MAX_READINGS] are
 * read, and a text over [MAX_TEXT_LENGTH] characters is refused whole. Never throws. Line ends may be `\n` or `\r\n`.
 */
internal object ChromaHistoryCodec {
    const val VERSION = 1
    const val MAX_READINGS = 50
    const val MAX_TEXT_LENGTH = 16 * 1024
    private const val HEADER = "fotoz-chroma"

    /** [readings] as read; [skipped] malformed `reading` lines; [headerOk] false when the text is not a history at all. */
    class Decoded(val readings: List<SavedReading>, val skipped: Int, val headerOk: Boolean) {
        val damaged: Boolean get() = !headerOk || skipped > 0
    }

    fun encode(readings: List<SavedReading>): String = buildString {
        append(HEADER).append(' ').append(VERSION).append('\n')
        for (r in readings.take(MAX_READINGS)) append(r.toLine()).append('\n')
    }

    fun decode(text: String): Decoded {
        if (text.length > MAX_TEXT_LENGTH) return Decoded(emptyList(), 0, headerOk = false)
        val lines = text.lines().map { it.trimEnd('\r') }.dropWhile { it.isBlank() }
        val header = lines.firstOrNull()?.trim()?.split(' ')?.filter { it.isNotEmpty() }
        val version = if (header != null && header.size == 2 && header[0] == HEADER) header[1].toIntOrNull() else null
        if (version == null || version < 1) return Decoded(emptyList(), 0, headerOk = false)
        val readings = ArrayList<SavedReading>()
        var skipped = 0
        for (line in lines.drop(1)) {
            if (!line.trim().startsWith("reading ") && line.trim() != "reading") continue
            val r = SavedReading.fromLine(line)
            if (r == null) skipped++ else if (readings.size < MAX_READINGS) readings += r
        }
        return Decoded(readings, skipped, headerOk = true)
    }
}

/** The history as read, and whether anything in the file could not be. */
internal class HistoryLoad(
    val readings: List<SavedReading>,
    /** A line in the file was damaged and left out. */
    val damaged: Boolean = false,
    /** The file exists but could not be read at all; [readings] is empty and an add is refused until it is deleted. */
    val unreadable: Boolean = false,
)

/** Where the readings are kept. The screen holds this, so a test gives it an in-memory one. */
internal interface ChromaHistory {
    /** Never throws. */
    suspend fun load(): HistoryLoad

    /** Puts [reading] first and drops the oldest beyond [ChromaHistoryCodec.MAX_READINGS]; false when it could not be written. */
    suspend fun add(reading: SavedReading): Boolean

    /** Deletes every reading; true when none is left. */
    suspend fun clear(): Boolean
}

/**
 * The reading history: ONE text file, `history.txt` in [directory] (the app's private storage), in
 * [ChromaHistoryCodec]'s format, newest first, at most [ChromaHistoryCodec.MAX_READINGS].
 *
 * - **Written whole, atomically.** The new text goes to `history.txt.tmp`, is synced and renamed over the old
 *   file, so a kill or a full disk leaves either the old file or the new one.
 * - **A damaged line is dropped, not fatal.** The history is a convenience, not a record: a line that does not
 *   decode is left out and the next add writes the file without it (no copy of it is kept). A file that cannot be
 *   read at all is never overwritten by an add; [clear] still deletes it, which is the way out.
 * - **One lock for the process**, so an add and a clear never read the same old text.
 *
 * Holds colours, a kind and a time. No picture, no place, no file name. The app has backup switched off, so it
 * is not copied off the phone; it goes with the app's data. [writeFile] and [readFile] are the only places the
 * file is written and read, so a test can make either fail. Nothing here has run on a device.
 */
internal class ChromaHistoryStore(
    private val directory: File,
    private val writeFile: (File, ByteArray) -> Unit = ::writeAtomically,
    private val readFile: (File) -> String = { it.readText(Charsets.UTF_8) },
) : ChromaHistory {
    private val file: File get() = File(directory, FILE_NAME)

    override suspend fun load(): HistoryLoad = withContext(Dispatchers.IO) { loadNow() }

    override suspend fun add(reading: SavedReading): Boolean = withContext(Dispatchers.IO) { addNow(reading) }

    override suspend fun clear(): Boolean = withContext(Dispatchers.IO) { clearNow() }

    fun loadNow(): HistoryLoad = synchronized(LOCK) { read() }

    fun addNow(reading: SavedReading): Boolean = synchronized(LOCK) {
        val current = read()
        if (current.unreadable) return false
        val all = (listOf(reading) + current.readings).take(ChromaHistoryCodec.MAX_READINGS)
        try {
            if (!directory.isDirectory && !directory.mkdirs()) return false
            File(directory, TMP_NAME).delete()
            writeFile(file, ChromaHistoryCodec.encode(all).toByteArray(Charsets.UTF_8))
            true
        } catch (e: IOException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    fun clearNow(): Boolean = synchronized(LOCK) {
        try {
            File(directory, TMP_NAME).delete()
            file.delete() || !file.exists()
        } catch (e: SecurityException) {
            false
        }
    }

    private fun read(): HistoryLoad {
        val f = file
        if (!f.isFile) return HistoryLoad(emptyList())
        val text = try {
            readFile(f)
        } catch (e: IOException) {
            return HistoryLoad(emptyList(), unreadable = true)
        } catch (e: SecurityException) {
            return HistoryLoad(emptyList(), unreadable = true)
        }
        if (text.isBlank()) return HistoryLoad(emptyList())
        val decoded = ChromaHistoryCodec.decode(text)
        return HistoryLoad(decoded.readings, damaged = decoded.damaged)
    }

    companion object {
        const val FILE_NAME = "history.txt"
        const val TMP_NAME = "history.txt.tmp"

        /** The tool's folder under [filesDir]. */
        fun directoryIn(filesDir: File): File = File(filesDir, "chroma")

        private val LOCK = Any()

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
