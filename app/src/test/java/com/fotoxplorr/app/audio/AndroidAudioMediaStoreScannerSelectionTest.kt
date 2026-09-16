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

    private val notARingtoneClause = "${MediaStore.Audio.AudioColumns.IS_RINGTONE}=0" +
        " AND ${MediaStore.Audio.AudioColumns.IS_ALARM}=0" +
        " AND ${MediaStore.Audio.AudioColumns.IS_NOTIFICATION}=0"

    @Test
    fun `a full scan excludes ringtones, alarms and notifications, not IS_MUSIC`() {
        val query = buildAudioSelection(ScanPlan.Full)

        // Deliberately NOT IS_MUSIC!=0 -- that flag also drops recordings, podcasts and
        // audiobooks, which this destination must show. See buildAudioSelection's own doc.
        assertEquals(notARingtoneClause, query.clause)
        assertEquals(emptyList<String>(), query.args)
    }

    @Test
    fun `a delta scan appends the rewound watermark bound, not a fresh clause`() {
        val query = buildAudioSelection(ScanPlan.Delta(sinceSeconds = 1_700_000_000L))

        assertTrue(query.clause.startsWith("$notARingtoneClause AND"))
        assertTrue(query.clause.endsWith("${MediaStore.Audio.AudioColumns.DATE_MODIFIED}>=?"))
        assertEquals(listOf("1700000000"), query.args)
    }

    @Test
    fun `trackNumberFrom unpacks MediaStore's disc-number-times-1000-plus-track encoding`() {
        assertEquals(3, trackNumberFrom(2003L))
        assertEquals(7, trackNumberFrom(7L))
        assertEquals(null, trackNumberFrom(null))
    }
}
