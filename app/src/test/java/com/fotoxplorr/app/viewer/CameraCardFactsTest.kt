package com.fotoxplorr.app.viewer

import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.model.MediaId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The camera card's facts: the reference card line for line when the file answers everything,
 * a dash in every slot when it answers nothing, and the division of labour the room's KDoc
 * promises -- the strip shows the 35 mm-equivalent focal length when the file records one.
 */
class CameraCardFactsTest {

    private val reference = ImageExifDetails(
        make = "Apple",
        model = "iPhone 17 Pro",
        lensModel = "Ultra Wide Camera",
        focalLengthMm = 13.0,
        focalLength35mm = 24,
        aperture = 2.2,
        iso = "50",
        shutterSpeed = "1/33 s",
        exposureBiasEv = 0.0,
        flash = 0,
    )

    @Test
    fun `the reference card, line for line`() {
        val facts = CameraCardFacts.from(photo(mime = "image/heic"), reference)
        assertEquals("Apple iPhone 17 Pro", facts.title)
        assertEquals("HEIF", facts.formatBadge)
        assertFalse(facts.flashFired)
        assertEquals("Ultra Wide Camera — 13 mm ƒ2.2", facts.lensLine)
        assertEquals("12 MP • 3024 × 4032 • 2.1 MB", facts.dimensionsLine)
        assertEquals(listOf("ISO 50", "24 mm", "0 ev", "ƒ2.2", "1/33 s"), facts.exposure)
        assertTrue(facts.showExposure)
        // A HEIF's dynamic range cannot be verified negative, so no chip (P0-16).
        assertNull(facts.dynamicRangeBadge)
    }

    @Test
    fun `a JPEG with no gain map is a genuine STANDARD`() {
        assertEquals("STANDARD", CameraCardFacts.from(photo(mime = "image/jpeg"), reference).dynamicRangeBadge)
        assertEquals("ULTRA HDR", CameraCardFacts.from(photo(mime = "image/jpeg"), reference.copy(hasHdrGainMap = true)).dynamicRangeBadge)
    }

    @Test
    fun `a file with no EXIF at all still has every line, as dashes`() {
        val facts = CameraCardFacts.from(photo(mime = "image/png", name = "Screenshot.png"), ImageExifDetails())
        assertEquals("Unknown camera", facts.title)
        assertEquals("PNG", facts.formatBadge)
        assertEquals("—", facts.lensLine)
        assertEquals(listOf("ISO —", "—", "—", "—", "—"), facts.exposure)
        assertTrue(facts.showExposure)
    }

    @Test
    fun `the strip falls back to the real focal length when there is no 35 mm equivalent`() {
        val facts = CameraCardFacts.from(photo(), reference.copy(focalLength35mm = null))
        assertEquals("13 mm", facts.exposure[1])
    }

    @Test
    fun `zero dimensions and zero size do not crash and do not claim numbers`() {
        val facts = CameraCardFacts.from(photo(width = 0, height = 0, size = 0L), ImageExifDetails())
        assertEquals("Unknown dimensions", facts.dimensionsLine)
    }

    @Test
    fun `a video has no exposure strip and names its duration on the lens line`() {
        val facts = CameraCardFacts.from(photo(mime = "video/mp4", name = "clip.mp4", duration = 64_000L), ImageExifDetails())
        assertFalse(facts.showExposure)
        assertEquals("Video — 1:04", facts.lensLine)
        assertEquals("MP4", facts.formatBadge)
    }

    @Test
    fun `flash lights the glyph on EXIF bit zero`() {
        assertTrue(CameraCardFacts.from(photo(), reference.copy(flash = 1)).flashFired)
        assertTrue(CameraCardFacts.from(photo(), reference.copy(flash = 0x19)).flashFired)
        assertFalse(CameraCardFacts.from(photo(), reference.copy(flash = 0x10)).flashFired)
    }

    private fun photo(
        mime: String = "image/jpeg",
        name: String = "IMG_0767.HEIC",
        width: Int = 3024,
        height: Int = 4032,
        size: Long = 2_202_010L,
        duration: Long = 0L,
    ) = MediaAsset(
        id = MediaId(1L),
        contentUriString = "content://media/external/images/media/1",
        displayName = name,
        mimeType = mime,
        bucketName = "Camera",
        dateTakenMillis = 0L,
        dateModifiedSeconds = 0L,
        width = width,
        height = height,
        sizeBytes = size,
        durationMillis = duration,
        relativePath = null,
        isFavorite = false,
        isTrashed = false,
    )
}
