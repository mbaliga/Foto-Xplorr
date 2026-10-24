package com.fotoxplorr.app.migration

import android.content.Context

/**
 * Fotoz is visuals only, so the audio library that earlier builds carried is gone. This removes
 * the two app-private leftovers it could have written: the audio catalogue database and the audio
 * scan watermark preferences. Both are rebuildable caches of MediaStore, so nothing is lost.
 *
 * The user's own audio files (including any converted `.m4a` copies and edited tags) live in
 * shared storage, not here, and are never touched.
 *
 * Idempotent: deleting something that is not there is a no-op, so it simply runs on every process
 * start rather than keeping a flag of its own that would itself be one more leftover.
 */
internal object LegacyAudioCleanup {
    /** `audio/SqliteAudioRepository.kt`'s `DATABASE_NAME` before that class was deleted. */
    const val AUDIO_CATALOGUE_DB = "foto_xplorr_audio_catalogue.db"

    /** `audio/PrefsAudioScanWatermark.kt`'s preferences file before that class was deleted. */
    const val AUDIO_SCAN_PREFS = "audio_scan"

    fun run(context: Context) {
        val app = context.applicationContext
        app.deleteDatabase(AUDIO_CATALOGUE_DB)
        app.deleteSharedPreferences(AUDIO_SCAN_PREFS)
    }
}
