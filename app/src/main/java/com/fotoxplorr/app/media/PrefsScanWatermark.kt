package com.fotoxplorr.app.media

import android.content.Context

/**
 * The scan watermark, persisted in the app's own preferences.
 *
 * Deliberately plain `SharedPreferences` and not a row in the media database: the watermark
 * must survive the database being rebuilt (a schema upgrade drops and re-derives the
 * catalogue), and losing it is safe in only one direction — a missing watermark forces a
 * full pass, which is slow but correct, whereas a watermark that outlives a wiped catalogue
 * would produce a delta over an empty library and leave the gallery permanently empty.
 * [ScanPlan.decide] guards that case by also requiring a non-empty repository.
 */
class PrefsScanWatermark(context: Context) : ScanWatermark {

    private val prefs = context.applicationContext
        .getSharedPreferences("media_scan", Context.MODE_PRIVATE)

    override fun lastCompletedSeconds(): Long = prefs.getLong(KEY_LAST_MODIFIED, 0L)

    override fun record(seconds: Long) {
        if (seconds <= 0L) return
        // Monotonic: a delta pass that happens to see only older rows must never move the
        // mark backwards, which would make the next pass redo work it already did.
        if (seconds <= lastCompletedSeconds()) return
        prefs.edit().putLong(KEY_LAST_MODIFIED, seconds).apply()
    }

    private companion object {
        const val KEY_LAST_MODIFIED = "last_modified_seconds"
    }
}
