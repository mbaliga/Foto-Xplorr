package com.fotoxplorr.app.palettebuilder

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.fotoxplorr.app.editor.EditedCopyWriter
import com.fotoxplorr.app.editor.OutputFormat
import com.fotoxplorr.app.media.MediaAsset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Writes the recoloured copy through the editor's own [EditedCopyWriter.save]: a new JPEG beside the original,
 * staged and encoded before any MediaStore row is made, with the source's metadata carried over (see
 * [RecolourJob] for what that includes: the capture date, and the place too, as with an edited copy kept in this
 * library). The name is the original's stem plus `-edited`, because that writer has one marker.
 *
 * The write runs to its end even when the job is cancelled, because that writer swallows a cancellation into a
 * failed result and would otherwise leave a finished file that nobody knows the address of; the job discards it
 * afterwards. Nothing here has run on a device.
 */
internal class EditorRecolourSink(context: Context) : RecolourSink {
    private val appContext = context.applicationContext
    private val writer = EditedCopyWriter(appContext)

    override suspend fun save(source: MediaAsset, bitmap: Bitmap, onSteppedDown: () -> Unit): Result<String> {
        val result = withContext(NonCancellable) {
            writer.save(source, bitmap, OutputFormat.JPEG, onSteppedDown = { _, _ -> onSteppedDown() })
        }
        return result.fold(
            onSuccess = { Result.success(it.toString()) },
            onFailure = { Result.failure(if (isOutOfSpace(it)) RecolourNoSpaceException(it) else it) },
        )
    }

    override suspend fun discard(ref: String) {
        withContext(Dispatchers.IO) { runCatching { appContext.contentResolver.delete(Uri.parse(ref), null, null) } }
    }
}

/**
 * True when [failure] or something it was caused by is the phone running out of space: a [RecolourNoSpaceException],
 * or an I/O error whose text is the system's `ENOSPC (No space left on device)`. Any other failure, "disk full" in
 * a message included, is not.
 */
internal fun isOutOfSpace(failure: Throwable?): Boolean {
    var t = failure
    var depth = 0
    while (t != null && depth++ < 8) {
        if (t is RecolourNoSpaceException) return true
        val text = t.message.orEmpty()
        if (t is IOException && (text.contains("ENOSPC") || text.contains("No space left", ignoreCase = true))) return true
        t = t.cause
    }
    return false
}
