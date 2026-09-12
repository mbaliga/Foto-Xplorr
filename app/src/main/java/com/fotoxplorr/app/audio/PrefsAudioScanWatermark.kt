package com.fotoxplorr.app.audio

import android.content.Context
import com.fotoxplorr.app.media.ScanWatermark
import com.fotoxplorr.app.media.watermarkAdvance

/**
 * [com.fotoxplorr.app.media.PrefsScanWatermark]'s exact shape, in its OWN preferences file.
 *
 * A shared file with the photo/video watermark would mean the two scans always resetting or
 * advancing together, when they read entirely separate MediaStore collections on entirely
 * separate schedules — reusing [watermarkAdvance] (already `internal`, so visible across this
 * module regardless of package) is the right amount of sharing; the persisted value itself is not.
 */
class PrefsAudioScanWatermark(context: Context) : ScanWatermark {

    private val prefs = context.applicationContext
        .getSharedPreferences("audio_scan", Context.MODE_PRIVATE)

    override fun lastCompletedSeconds(): Long = prefs.getLong(KEY_LAST_MODIFIED, 0L)

    override fun record(seconds: Long) {
        val advanced = watermarkAdvance(lastCompletedSeconds(), seconds) ?: return
        prefs.edit().putLong(KEY_LAST_MODIFIED, advanced).apply()
    }

    private companion object {
        const val KEY_LAST_MODIFIED = "last_modified_seconds"
    }
}
