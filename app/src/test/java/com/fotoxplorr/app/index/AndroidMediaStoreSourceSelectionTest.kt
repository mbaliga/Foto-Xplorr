package com.fotoxplorr.app.index

import android.provider.MediaStore
import com.fotoxplorr.core.formats.SVG_MIME_TYPE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [buildSelection]'s own pure-function test, the same shape as [com.fotoxplorr.app.media.
 * AndroidMediaStoreScannerSelectionTest] pins for the old scanner's `buildSelection` -- no
 * Robolectric/`ContentResolver` needed, since `MediaStore`'s constants used here are
 * compile-time-constant `int`/`String` fields that resolve correctly under the unit-test
 * `android.jar` stub.
 */
class AndroidMediaStoreSourceSelectionTest {

    @Test
    fun `a full pass (no generation bound) matches images, videos, and SVGs by mime or extension`() {
        val query = buildSelection(minGeneration = null)

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
    fun `a delta pass appends a strict-greater-than GENERATION_MODIFIED bound, not DATE_MODIFIED`() {
        val query = buildSelection(minGeneration = 42L)

        assertTrue(query.clause.endsWith("AND ${MediaStore.MediaColumns.GENERATION_MODIFIED}>?"))
        assertEquals(5, query.args.size)
        assertEquals("42", query.args.last())
    }

    @Test
    fun `the svg arm is anchored to exactly the svg mime and a literal dot-svg suffix`() {
        val query = buildSelection(minGeneration = null)
        assertEquals(SVG_MIME_TYPE, query.args[2])
        assertEquals("%.svg", query.args[3])
    }
}
