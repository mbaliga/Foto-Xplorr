package com.fotoxplorr.app.widget

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import java.io.File

/**
 * When Fotoz last finished reading the library from the phone (a completed MediaStore scan), so the
 * widgets can say how fresh the catalogue they draw really is. Only the app's own screens start a
 * scan, and a process started just to draw a widget or to run the midnight job never does, so the
 * moment of drawing would overstate it: "As of 00:01" over a catalogue last read days ago.
 *
 * One number in one file under `noBackupFilesDir`, so it is never backed up or carried to another
 * phone, where it would describe a library that is not there. Reads and writes never throw.
 */
internal object LibraryReadStamp {
    private const val FILE_NAME = "widget_library_read_at"

    fun record(context: Context, wallClockMillis: Long) {
        val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, FILE_NAME))
        runCatching {
            val out = file.startWrite()
            try {
                out.write(wallClockMillis.toString().toByteArray(Charsets.US_ASCII))
                file.finishWrite(out)
            } catch (t: Throwable) {
                file.failWrite(out)
                throw t
            }
        }.onFailure { Log.w("LibraryReadStamp", "could not record the library read time", it) }
    }

    /** The recorded time, or null when none has been recorded (or the file cannot be read). */
    fun read(context: Context): Long? = runCatching {
        AtomicFile(File(context.applicationContext.noBackupFilesDir, FILE_NAME))
            .readFully()
            .toString(Charsets.US_ASCII)
            .trim()
            .toLongOrNull()
            ?.takeIf { it > 0L }
    }.getOrNull()
}
