package com.fotoxplorr.app.aquarium

import java.io.File

/** What [CreatureStore.save] did. */
sealed interface SaveResult {
    data class Saved(val creature: StoredCreature) : SaveResult

    /** The store already holds [CreatureStore.MAX_CREATURES]; nothing was written. */
    data object Full : SaveResult

    /** A file could not be written; nothing visible was left behind. */
    data object Failed : SaveResult
}

/** One creature on disk, read back: its record and the PNG bytes (not yet decoded). */
class StoredSprite(val record: StoredCreature, val png: ByteArray)

/**
 * The creatures that survive between runs: for each, `<id>.png` (the picture) and `<id>.json` (the
 * [CreatureCodec] record), in one folder of the app's private storage.
 *
 * The PNG is written FIRST and the JSON LAST, each through a temporary file renamed into place, and
 * only a creature whose JSON exists is listed. A write that stops half way (the process dies, the
 * disk fills) therefore leaves either nothing or an unlisted PNG, never a creature without a
 * picture; [sweepIncomplete] removes such leftovers. A creature whose JSON or PNG is damaged, or
 * whose PNG is missing, is skipped, not an error (a PNG that is listed but will not decode is
 * removed on load by [SpriteIo.loadAndSweep], since this class never decodes). Deleting removes the JSON first, for the same reason.
 *
 * File names are `CreatureId.generate()` UUIDs and nothing from user input; every id that comes
 * in from outside is checked with [CreatureId.isValid] before it is used in a path. The whole
 * store is one process-wide lock, so a save and a sweep never meet.
 *
 * The folder is plain, unencrypted app storage and is not part of the encrypted backup (the app
 * disallows system backup). It holds only what the person drew, never a library photo.
 *
 * [writeFile] is the one place a file is written, so a test can watch the order or make it fail.
 */
class CreatureStore(
    private val directory: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val writeFile: (File, ByteArray) -> Unit = ::writeAtomically,
) {
    /** Every creature that has both files, oldest first. Never throws. */
    fun list(): List<StoredSprite> = synchronized(LOCK) {
        val files = directory.listFiles().orEmpty()
        files.asSequence()
            .filter { it.isFile && it.name.endsWith(JSON_EXT) }
            .mapNotNull { readPair(it) }
            .sortedWith(compareBy({ it.record.createdAtMillis }, { it.record.id }))
            .toList()
    }

    /**
     * Stores a creature whose picture is [png] ([widthPx] x [heightPx]). The id is generated here.
     */
    fun save(png: ByteArray, widthPx: Int, heightPx: Int, headLeft: Boolean): SaveResult = synchronized(LOCK) {
        if (png.isEmpty() || widthPx <= 0 || heightPx <= 0) return SaveResult.Failed
        if (list().size >= MAX_CREATURES) return SaveResult.Full
        val record = StoredCreature(
            id = CreatureId.generate(),
            createdAtMillis = clock(),
            widthPx = widthPx,
            heightPx = heightPx,
            headLeft = headLeft,
        )
        try {
            if (!directory.isDirectory && !directory.mkdirs()) return SaveResult.Failed
            writeFile(pngFile(record.id), png)
            // Last: until this lands the creature does not exist.
            writeFile(jsonFile(record.id), CreatureCodec.encode(record).toByteArray(Charsets.UTF_8))
            SaveResult.Saved(record)
        } catch (e: Exception) {
            discard(record.id)
            SaveResult.Failed
        }
    }

    /** Removes the creature with [id]; false when the id is not a creature id or nothing was there. */
    fun delete(id: String): Boolean = synchronized(LOCK) {
        if (!CreatureId.isValid(id)) return false
        val json = jsonFile(id).delete()
        val png = pngFile(id).delete()
        json || png
    }

    /** Deletes temporary files and pictures that have no record: what a stopped save leaves. Returns how many files went. */
    fun sweepIncomplete(): Int = synchronized(LOCK) {
        var removed = 0
        for (f in directory.listFiles().orEmpty()) {
            if (!f.isFile) continue
            val name = f.name
            val orphan = when {
                name.endsWith(TMP_EXT) -> true
                name.endsWith(PNG_EXT) -> {
                    val id = name.removeSuffix(PNG_EXT)
                    !CreatureId.isValid(id) || !jsonFile(id).isFile
                }
                else -> false
            }
            if (orphan && f.delete()) removed++
        }
        removed
    }

    private fun readPair(json: File): StoredSprite? {
        val idFromName = json.name.removeSuffix(JSON_EXT)
        if (!CreatureId.isValid(idFromName)) return null
        val record = try {
            CreatureCodec.decode(json.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            null
        } ?: return null
        // The name decides, so a record that names another id (or a path) cannot reach a file of that name.
        if (!record.id.equals(idFromName, ignoreCase = true)) return null
        if (record.widthPx <= 0 || record.heightPx <= 0) return null
        val png = pngFile(idFromName)
        if (!png.isFile || png.length() > MAX_PNG_BYTES) return null
        val bytes = try {
            png.readBytes()
        } catch (e: Exception) {
            return null
        }
        return StoredSprite(record.copy(id = idFromName), bytes)
    }

    private fun discard(id: String) {
        jsonFile(id).delete()
        pngFile(id).delete()
        File(directory, "$id$PNG_EXT$TMP_EXT").delete()
        File(directory, "$id$JSON_EXT$TMP_EXT").delete()
    }

    private fun pngFile(id: String) = File(directory, "$id$PNG_EXT")
    private fun jsonFile(id: String) = File(directory, "$id$JSON_EXT")

    companion object {
        /** The tank holds this many, so the store does too: the list is what is in the tank. */
        const val MAX_CREATURES = TankSimulation.MAX_CREATURES

        /** A picture bigger than this is not ours (ours are a few KB) and is not read. */
        const val MAX_PNG_BYTES = 2L * 1024 * 1024

        const val DIRECTORY_NAME = "aquarium"
        private const val PNG_EXT = ".png"
        private const val JSON_EXT = ".json"
        private const val TMP_EXT = ".tmp"
        private val LOCK = Any()

        /** Writes to `<name>.tmp` then renames it, so a reader never sees half a file. */
        private fun writeAtomically(target: File, bytes: ByteArray) {
            val tmp = File(target.parentFile, target.name + TMP_EXT)
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw java.io.IOException("could not move ${tmp.name} into place")
            }
        }
    }
}
