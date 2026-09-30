package com.fotoxplorr.core.metadata

import kotlin.test.Test
import kotlin.test.assertEquals

class SidecarNamingTest {

    @Test
    fun `darktable appends xmp to the full original name`() {
        assertEquals("IMG_1234.CR3.xmp", SidecarNaming.sidecarFileName("IMG_1234.CR3", SidecarStyle.DARKTABLE))
    }

    @Test
    fun `lightroom replaces the original extension with xmp`() {
        assertEquals("IMG_1234.xmp", SidecarNaming.sidecarFileName("IMG_1234.CR3", SidecarStyle.LIGHTROOM))
    }

    @Test
    fun `lightroom replaces only the last extension when the name has several dots`() {
        assertEquals(
            "My.Vacation.Photo.xmp",
            SidecarNaming.sidecarFileName("My.Vacation.Photo.CR3", SidecarStyle.LIGHTROOM),
        )
    }

    @Test
    fun `darktable still just appends when the name has several dots`() {
        assertEquals(
            "My.Vacation.Photo.CR3.xmp",
            SidecarNaming.sidecarFileName("My.Vacation.Photo.CR3", SidecarStyle.DARKTABLE),
        )
    }

    @Test
    fun `a name with no extension gives the same result under both styles`() {
        assertEquals("IMG1234.xmp", SidecarNaming.sidecarFileName("IMG1234", SidecarStyle.DARKTABLE))
        assertEquals("IMG1234.xmp", SidecarNaming.sidecarFileName("IMG1234", SidecarStyle.LIGHTROOM))
    }

    @Test
    fun `original extension casing is preserved -- not normalised`() {
        assertEquals("IMG_1234.cr3.xmp", SidecarNaming.sidecarFileName("IMG_1234.cr3", SidecarStyle.DARKTABLE))
    }

    @Test
    fun `candidates cover both styles for a normal raw filename`() {
        assertEquals(
            listOf("IMG_1234.xmp", "IMG_1234.CR3.xmp"),
            SidecarNaming.candidateSidecarFileNames("IMG_1234.CR3"),
        )
    }

    @Test
    fun `candidates deduplicate when both styles coincide`() {
        assertEquals(listOf("IMG1234.xmp"), SidecarNaming.candidateSidecarFileNames("IMG1234"))
    }
}
