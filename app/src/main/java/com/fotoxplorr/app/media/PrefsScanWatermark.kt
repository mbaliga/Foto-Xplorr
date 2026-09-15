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
        val advanced = watermarkAdvance(lastCompletedSeconds(), seconds) ?: return
        prefs.edit().putLong(KEY_LAST_MODIFIED, advanced).apply()
    }

    private companion object {
        const val KEY_LAST_MODIFIED = "last_modified_seconds"
    }
}

/**
 * The watermark's monotonic advance rule, pulled out of the `SharedPreferences` wrapper so
 * it is testable without a Context (FX-003): a delta pass that happens to see only older
 * rows must never move the mark backwards — that would make the next pass redo work it
 * already did — and a non-positive candidate is MediaStore telling us nothing, not zero.
 *
 * @return the value to persist, or null when the mark must not move.
 */
internal fun watermarkAdvance(current: Long, candidate: Long): Long? =
    candidate.takeIf { it > 0L && it > current }
