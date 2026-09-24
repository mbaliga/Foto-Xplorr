package com.fotoxplorr.app.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins [ExifCopier.COPIED_TAGS] and [EXCLUDED_TAGS] as a partition of every real
 * `ExifInterface.TAG_*` field ([allExifTags]) -- see [ExifCopier]'s own class doc for why that
 * matters: an androidx upgrade that adds a new tag must land a new tag in exactly one of the two
 * sets, not silently in neither (which this test would catch as a missing tag) or, impossible by
 * construction today but worth pinning against a future hand-typed [ExifCopier.COPIED_TAGS],
 * accidentally in both.
 *
 * Robolectric, not a plain JUnit test: `ExifInterface`'s own static initialization reaches into
 * Android framework classes that throw `Stub!` outside an instrumented (or Robolectric-shadowed)
 * runtime -- reflecting over its fields still needs the class to load first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExifCopierCompletenessTest {

    @Test
    fun `every real TAG_ field is in exactly one of COPIED_TAGS or EXCLUDED_TAGS`() {
        val all = allExifTags()
        assertTrue("allExifTags() found no TAG_ fields at all -- reflection is broken", all.isNotEmpty())

        val union = ExifCopier.COPIED_TAGS + EXCLUDED_TAGS
        assertEquals(
            "a real tag is missing from both COPIED_TAGS and EXCLUDED_TAGS",
            all,
            union,
        )

        val overlap = ExifCopier.COPIED_TAGS.intersect(EXCLUDED_TAGS)
        assertTrue("a tag is in both COPIED_TAGS and EXCLUDED_TAGS: $overlap", overlap.isEmpty())
    }

    @Test
    fun `EXCLUDED_TAGS contains only real tag values, not a typo`() {
        val all = allExifTags()
        val unknown = EXCLUDED_TAGS - all
        assertTrue("EXCLUDED_TAGS names a value that is not a real ExifInterface.TAG_*: $unknown", unknown.isEmpty())
    }
}
