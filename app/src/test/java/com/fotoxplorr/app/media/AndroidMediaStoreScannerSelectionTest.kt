package com.fotoxplorr.app.media

import android.provider.MediaStore
import com.fotoxplorr.app.formats.SVG_MIME_TYPE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [buildSelection] is the fix for the SVG-indexing bug: MediaStore's own `MEDIA_TYPE` bucketing
 * never puts an SVG row in IMAGE or VIDEO (see that function's KDoc), so a query that filtered
 * on `MEDIA_TYPE` alone silently never saw an SVG file at all. These tests pin the exact
 * selection string and argument list a real `ContentResolver.query` would receive, without
 * needing one -- `MediaStore`'s constants used here are compile-time-constant `int`/`String`
 * fields, so they resolve correctly even under the unit-test `android.jar` stub.
 */
class AndroidMediaStoreScannerSelectionTest {

    @Test
    fun `a full scan matches images, videos, and SVGs by mime or extension`() {
        val query = buildSelection(ScanPlan.Full)

        assertEquals(
            "(" +
                "(${MediaStore.Files.FileColumns.MEDIA_TYPE}=? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=?) OR " +
                "(${MediaStore.MediaColumns.MIME_TYPE}=? OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?)" +
                ")",
            query.clause,
        )
        assertEquals(
            listOf(
                MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
                SVG_MIME_TYPE,
                "%.svg",
            ),
            query.args,
        )
    }

    @Test
    fun `a delta scan appends the rewound watermark bound, not a fresh clause`() {
        val query = buildSelection(ScanPlan.Delta(sinceSeconds = 1_700_000_000L))

        assertTrue(query.clause.endsWith("AND ${MediaStore.MediaColumns.DATE_MODIFIED}>=?"))
        assertEquals(5, query.args.size)
        assertEquals("1700000000", query.args.last())
    }

    @Test
    fun `the svg arm is anchored to exactly the svg mime and a literal dot-svg suffix`() {
        // Precision check for the bug this exists to fix: nothing about this clause is a broad
        // "anything MediaStore left uncategorised" sweep -- a stray PDF or text file that also
        // lands in MEDIA_TYPE_NONE matches neither arm, only a file whose mime is exactly
        // image/svg+xml or whose name ends in literally ".svg" does.
        val query = buildSelection(ScanPlan.Full)
        assertEquals(SVG_MIME_TYPE, query.args[2])
        assertEquals("%.svg", query.args[3])
    }
}
