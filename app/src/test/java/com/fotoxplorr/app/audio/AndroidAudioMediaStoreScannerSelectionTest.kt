package com.fotoxplorr.app.audio

import android.provider.MediaStore
import com.fotoxplorr.app.media.ScanPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [buildAudioSelection] pinned the same way
 * `com.fotoxplorr.app.media.AndroidMediaStoreScannerSelectionTest` pins its own query: `MediaStore`
 * constants are compile-time-constant `int`/`String` fields, so they resolve correctly under the
 * unit-test `android.jar` stub with no Robolectric needed.
 */
class AndroidAudioMediaStoreScannerSelectionTest {

    @Test
    fun `a full scan excludes ringtones, notifications and alarms, not non-music`() {
        val query = buildAudioSelection(ScanPlan.Full)

        assertEquals(
            "${MediaStore.Audio.AudioColumns.IS_RINGTONE}=0 AND " +
                "${MediaStore.Audio.AudioColumns.IS_NOTIFICATION}=0 AND " +
                "${MediaStore.Audio.AudioColumns.IS_ALARM}=0",
            query.clause,
        )
        assertEquals(emptyList<String>(), query.args)
    }

    @Test
    fun `the clause does not require IS_MUSIC, so recordings and podcasts are not excluded`() {
        val query = buildAudioSelection(ScanPlan.Full)

        assertTrue(
            "must not filter on IS_MUSIC (P0-10): that excluded recordings and podcasts too",
            !query.clause.contains(MediaStore.Audio.AudioColumns.IS_MUSIC),
        )
    }

    @Test
    fun `a delta scan appends the rewound watermark bound, not a fresh clause`() {
        val query = buildAudioSelection(ScanPlan.Delta(sinceSeconds = 1_700_000_000L))

        assertTrue(query.clause.startsWith("${MediaStore.Audio.AudioColumns.IS_RINGTONE}=0 AND"))
        assertTrue(query.clause.endsWith("${MediaStore.Audio.AudioColumns.DATE_MODIFIED}>=?"))
        assertEquals(listOf("1700000000"), query.args)
    }
}
