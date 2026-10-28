package com.fotoxplorr.app.migration

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The one-time removal of the old audio library's app-private leftovers (see
 * [LegacyAudioCleanup]). Real files under Robolectric: the thing under test IS whether the files
 * are gone afterwards, and that nothing next to them was touched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LegacyAudioCleanupTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun createAudioLeftovers() {
        context.openOrCreateDatabase(LegacyAudioCleanup.AUDIO_CATALOGUE_DB, Context.MODE_PRIVATE, null).use { db: SQLiteDatabase ->
            db.execSQL("CREATE TABLE audio (id INTEGER PRIMARY KEY)")
        }
        context.getSharedPreferences(LegacyAudioCleanup.AUDIO_SCAN_PREFS, Context.MODE_PRIVATE)
            .edit().putLong("last_modified_seconds", 42L).commit()
    }

    @Test
    fun `removes the audio catalogue database and the scan watermark`() {
        createAudioLeftovers()
        assertTrue(context.getDatabasePath(LegacyAudioCleanup.AUDIO_CATALOGUE_DB).exists())

        LegacyAudioCleanup.run(context)

        assertFalse(context.getDatabasePath(LegacyAudioCleanup.AUDIO_CATALOGUE_DB).exists())
        // A fresh handle reads defaults: the old value is not coming back from disk or a cache.
        val reread = context.getSharedPreferences(LegacyAudioCleanup.AUDIO_SCAN_PREFS, Context.MODE_PRIVATE)
        assertEquals(0L, reread.getLong("last_modified_seconds", 0L))
    }

    @Test
    fun `leaves every other store alone`() {
        createAudioLeftovers()
        context.getSharedPreferences(LegacyStoreFiles.FAVORITES_PREFS, Context.MODE_PRIVATE)
            .edit().putString("marker", "keep").commit()
        context.openOrCreateDatabase(LegacyStoreFiles.CATALOGUE_DB, Context.MODE_PRIVATE, null).close()

        LegacyAudioCleanup.run(context)

        assertEquals(
            "keep",
            context.getSharedPreferences(LegacyStoreFiles.FAVORITES_PREFS, Context.MODE_PRIVATE)
                .getString("marker", null),
        )
        assertTrue(context.getDatabasePath(LegacyStoreFiles.CATALOGUE_DB).exists())
    }

    @Test
    fun `is a no-op when there is nothing to remove, and safe to repeat`() {
        LegacyAudioCleanup.run(context)
        LegacyAudioCleanup.run(context)
        assertFalse(context.getDatabasePath(LegacyAudioCleanup.AUDIO_CATALOGUE_DB).exists())
    }
}
