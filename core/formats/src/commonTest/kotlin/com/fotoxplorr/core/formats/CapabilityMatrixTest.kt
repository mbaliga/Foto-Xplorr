package com.fotoxplorr.core.formats

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CapabilityMatrixTest {

    @Test
    fun `every format has a row and a distinct format id`() {
        val ids = MediaFormat.all.map { it.formatId.value }
        assertEquals(ids.size, ids.toSet().size, "ids must be unique: $ids")
        // of() is an exhaustive when; calling it for every member proves no row throws.
        MediaFormat.all.forEach { FormatCapabilities.of(it) }
        assertEquals(30 + RawVariant.entries.size, MediaFormat.all.size)
    }

    @Test
    fun `every format has a badge of at most five upper case characters`() {
        MediaFormat.all.forEach { format ->
            val badge = FormatLabels.badge(format)
            assertTrue(badge.isNotEmpty() && badge.length <= 5, "$format badge '$badge'")
            assertEquals(badge.uppercase(), badge, "$format badge")
        }
    }

    @Test
    fun `animating or reading a region or writing implies decoding`() {
        MediaFormat.all.forEach { format ->
            val caps = FormatCapabilities.of(format)
            if (caps.isNotEmpty()) assertTrue(Capability.Decode in caps, "$format has capabilities but cannot decode")
            if (Capability.MetadataWrite in caps) assertTrue(Capability.MetadataRead in caps, "$format")
            if (Capability.Thumbnail in caps) assertTrue(Capability.Decode in caps, "$format")
        }
    }

    @Test
    fun `nothing is ever written into a raw file`() {
        RawVariant.entries.forEach { variant ->
            val caps = FormatCapabilities.of(MediaFormat.Raw(variant))
            assertFalse(Capability.Write in caps)
            assertFalse(Capability.MetadataWrite in caps)
            assertTrue(Capability.MetadataRead in caps)
        }
    }

    @Test
    fun `plan section 4 rows`() {
        assertTrue(FormatCapabilities.supports(MediaFormat.Jpeg, Capability.Hdr))
        assertTrue(FormatCapabilities.supports(MediaFormat.Gif, Capability.Animate))
        assertTrue(FormatCapabilities.supports(MediaFormat.WebP, Capability.Animate))
        assertTrue(FormatCapabilities.supports(MediaFormat.Apng, Capability.Animate))
        assertFalse(FormatCapabilities.supports(MediaFormat.Png, Capability.Animate))
        assertFalse(FormatCapabilities.supports(MediaFormat.Svg, Capability.Write))
        assertTrue(FormatCapabilities.of(MediaFormat.Xcf).isEmpty())
        assertTrue(FormatCapabilities.of(MediaFormat.Psb).isEmpty())
        assertTrue(FormatCapabilities.of(MediaFormat.Other).isEmpty())
    }
}
