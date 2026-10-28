package com.fotoxplorr.app.reel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContract

/**
 * Asks the system's document picker for ONE sound file, and nothing else: `ACTION_OPEN_DOCUMENT` for
 * any audio type. The picker is the platform's own screen, so the app declares no audio or storage permission for
 * this and never lists the person's music; it is handed the one file, for reading, and may keep the right to
 * read it ([SoundtrackAccess.keep]). The file is only ever laid into a saved video; nothing is played back
 * in the app.
 *
 * Use it with `rememberLauncherForActivityResult(PickSoundtrack())` and `launch(Unit)`. The result is the
 * file's address, or null when the person backed out or the picker returned nothing.
 */
class PickSoundtrack : ActivityResultContract<Unit, Uri?>() {
    override fun createIntent(context: Context, input: Unit): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = MIME
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == android.app.Activity.RESULT_OK) intent?.data else null

    companion object {
        const val MIME = "audio/*"
    }
}

/** What to do with a picked sound file's address. */
object SoundtrackAccess {
    /**
     * Keep the right to read [uri] after the app restarts, so a saved project can still be rendered. False when
     * the provider does not allow keeping it (the file then works until the process ends, and the job's look
     * at the file will ask the person again if it stops working).
     */
    fun keep(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        true
    } catch (e: SecurityException) {
        false
    } catch (e: IllegalArgumentException) {
        false
    }

    /** Give back the right [keep] took; nothing happens when it was never held or the provider will not say. */
    fun release(context: Context, uri: Uri) {
        try {
            context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // Not held: nothing to give back.
        } catch (e: IllegalArgumentException) {
            // An address the system does not know.
        }
    }

    /** The file's name as the provider gives it, for the line "Sound: name"; null when it will not say. */
    fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
        }
    } catch (e: Exception) {
        null
    }
}
