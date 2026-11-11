package com.fotoxplorr.app.sync

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Where one destination's manifest lives. [append] must be durable when it returns (the engine records a file as done only after). */
interface SyncManifestStore {
    fun load(): SyncManifest
    fun append(line: String)

    /** Rewrites the file from [manifest] (drops superseded lines). Atomic: the old file stays if this fails. */
    fun compact(manifest: SyncManifest)
}

/**
 * The manifest as a plain file, one per destination. Appends are `write + fsync` of a whole line, so a
 * crash leaves at worst one torn final line, which [SyncManifest.decode] ignores. Not safe for two
 * writers; the service allows one run at a time.
 */
class FileSyncManifestStore(private val file: File) : SyncManifestStore {
    override fun load(): SyncManifest =
        if (file.exists()) SyncManifest.decode(file.readText(Charsets.UTF_8)) else SyncManifest()

    override fun append(line: String) {
        require(line.endsWith("\n")) { "A manifest line ends with a newline" }
        file.parentFile?.mkdirs()
        val fresh = !file.exists() || file.length() == 0L
        // A torn tail has no newline; start a new line so the next record is not glued to it.
        val needsBreak = !fresh && !endsWithNewline()
        FileOutputStream(file, true).use { out ->
            if (fresh) out.write((SyncManifestCodec.HEADER + "\n").toByteArray(Charsets.UTF_8))
            if (needsBreak) out.write('\n'.code)
            out.write(line.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
    }

    override fun compact(manifest: SyncManifest) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temp).use { out ->
            out.write(manifest.encode().toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        if (!temp.renameTo(file)) {
            temp.delete()
            throw IOException("The sync record could not be saved")
        }
    }

    private fun endsWithNewline(): Boolean = java.io.RandomAccessFile(file, "r").use {
        it.seek(it.length() - 1)
        it.read() == '\n'.code
    }
}
