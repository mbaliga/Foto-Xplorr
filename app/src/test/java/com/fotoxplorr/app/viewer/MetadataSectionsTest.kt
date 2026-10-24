package com.fotoxplorr.app.viewer

import com.fotoxplorr.app.gallery.browsableAssets
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.metadata.CurrentMetadata
import com.fotoxplorr.core.model.MediaId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/** The info room's detail sections: what they hold, what they leave out, and what a failed read says. */
class MetadataSectionsTest {

    private val utc = ZoneId.of("UTC")
    private val taken = ZonedDateTime.of(2026, 8, 1, 17, 3, 0, 0, utc).toInstant().toEpochMilli()
    private val now = ZonedDateTime.of(2026, 10, 21, 12, 0, 0, 0, utc).toInstant().toEpochMilli()

    private val full = ImageExifDetails(
        make = "Apple",
        model = "iPhone 17 Pro",
        lensModel = "Ultra Wide Camera",
        focalLengthMm = 13.0,
        aperture = 2.2,
        iso = "50",
        latitude = 51.5007,
        longitude = -0.1246,
        colorSpace = "sRGB",
        colorProfile = "Display P3",
        professionalMetadata = CurrentMetadata(null, "Ada", "(c) Ada", 4, listOf("harbour", "dusk")),
        orientation = 6,
        meteringMode = 5,
        exposureProgram = 2,
        whiteBalance = 0,
        software = "17.2",
        dateTimeOriginal = "2026:08:01 22:33:00",
        offsetTimeOriginal = "+05:30",
        gpsAltitudeMeters = 12.0,
        gpsBearingDegrees = 45.0,
        gpsBearingMagnetic = true,
        gpsSpeedKmh = 4.0,
        isMotionPhoto = true,
        hasDepthMap = true,
        isPanorama = false,
    )

    private fun build(
        asset: MediaAsset = photo(),
        exif: ImageExifDetails = full,
        video: VideoDetails? = null,
        manual: Pair<Double, Double>? = null,
        nearby: Int? = null,
    ) = MetadataSections.build(
        asset = asset,
        exif = exif,
        video = video,
        manualLatitude = manual?.first,
        manualLongitude = manual?.second,
        nearbyCount = nearby,
        nowMillis = now,
        zone = utc,
        locale = Locale.UK,
    )

    private fun MetadataModel.section(id: SectionId) = sections.firstOrNull { it.id == id }
    private fun MetadataModel.section(id: SectionId, label: String) =
        section(id)?.rows?.firstOrNull { it.label == label }?.value

    @Test
    fun `a full-EXIF photo has every section, in order, with File and Image open`() {
        val model = build(nearby = 3)
        assertEquals(
            listOf(SectionId.FILE, SectionId.IMAGE, SectionId.CAPTURE, SectionId.LOCATION, SectionId.LIBRARY),
            model.sections.map { it.id },
        )
        assertFalse(model.loading)
        assertEquals(
            listOf(true, true, false, false, false),
            model.sections.map { it.initiallyExpanded },
        )
        assertTrue(model.sections.all { it.rows.isNotEmpty() })
    }

    @Test
    fun `the file section names the file with its extension and says which clock the time is on`() {
        val model = build()
        assertEquals("IMG_0767.HEIC", model.section(SectionId.FILE, "Name"))
        assertEquals("image/heic", model.section(SectionId.FILE, "Type"))
        assertEquals("2,202,010 bytes (2.1 MB)", model.section(SectionId.FILE, "Size"))
        assertEquals("Camera", model.section(SectionId.FILE, "Album"))
        assertEquals("DCIM/Camera/", model.section(SectionId.FILE, "Where"))
        assertEquals("Saturday • 1 Aug 2026 • 10:33 PM (UTC+05:30)", model.section(SectionId.FILE, "Taken"))
        assertEquals("2 months ago", model.section(SectionId.FILE, "Age"))
    }

    @Test
    fun `with no offset in the file the time is the device's and says so`() {
        val model = build(exif = full.copy(offsetTimeOriginal = null))
        assertEquals("Saturday • 1 Aug 2026 • 5:03 PM (device time)", model.section(SectionId.FILE, "Taken"))
        val badOffset = build(exif = full.copy(offsetTimeOriginal = "banana"))
        assertTrue(badOffset.section(SectionId.FILE, "Taken")!!.endsWith("(device time)"))
    }

    @Test
    fun `a photo with no MediaStore date falls back to the EXIF wall clock`() {
        val model = build(asset = photo(dateTaken = 0L), exif = full.copy(offsetTimeOriginal = "+05:30"))
        assertEquals("Saturday • 1 Aug 2026 • 10:33 PM (UTC+05:30)", model.section(SectionId.FILE, "Taken"))
        assertNull("no date, no age", model.section(SectionId.FILE, "Age"))
        val none = build(asset = photo(dateTaken = 0L), exif = full.copy(dateTimeOriginal = null))
        assertNull(none.section(SectionId.FILE, "Taken"))
    }

    @Test
    fun `image and capture rows are the ones the card does not say`() {
        val model = build()
        assertEquals("3 : 4", model.section(SectionId.IMAGE, "Aspect"))
        assertEquals("Rotated 90° clockwise", model.section(SectionId.IMAGE, "Orientation"))
        assertEquals("Motion Photo, Depth map", model.section(SectionId.IMAGE, "Includes"))
        assertEquals("Multi-segment", model.section(SectionId.CAPTURE, "Metering"))
        assertEquals("Normal program", model.section(SectionId.CAPTURE, "Exposure program"))
        assertEquals("Auto", model.section(SectionId.CAPTURE, "White balance"))
        assertEquals("17.2", model.section(SectionId.CAPTURE, "Software"))
    }

    @Test
    fun `no section repeats a fact the camera card or the three Information rows already carry`() {
        val labels = build(nearby = 2).sections.flatMap { s -> s.rows.map { it.label } }.toSet()
        // The card: camera, lens, dimensions, flash, dynamic range, the exposure strip.
        val card = setOf(
            "Camera", "Lens", "Dimensions", "Size in pixels", "Resolution", "Flash", "ISO", "Focal length",
            "Aperture", "Shutter", "Shutter speed", "Exposure", "Exposure bias", "Dynamic range", "HDR",
        )
        // The Information block's own rows.
        val information = setOf("Modified", "Colour space", "Colour profile")
        assertTrue("overlap with the card: ${labels intersect card}", (labels intersect card).isEmpty())
        assertTrue("overlap with Information: ${labels intersect information}", (labels intersect information).isEmpty())
        // And no value in any section is the card's own lens or camera line.
        val values = build().sections.flatMap { s -> s.rows.map { it.value } }
        assertFalse(values.any { it.contains("iPhone") || it.contains("Ultra Wide") })
    }

    @Test
    fun `a screenshot with no EXIF has only the file rows it really has, and no dashes`() {
        val shot = photo(name = "Screenshot_1.png", mime = "image/png", bucket = null, path = null, dateTaken = 0L, width = 1080, height = 2400)
        val model = build(asset = shot, exif = ImageExifDetails())
        assertEquals(listOf(SectionId.FILE, SectionId.IMAGE), model.sections.map { it.id })
        val rows = model.sections.flatMap { it.rows }
        assertTrue(rows.none { it.value.isBlank() || it.value == "—" || it.value == "-" })
        assertTrue(rows.none { it.value.contains("not recorded", ignoreCase = true) })
        assertEquals("9 : 20", model.section(SectionId.IMAGE, "Aspect"))
    }

    @Test
    fun `while the read is pending only the File section is returned, and the model is loading`() {
        val model = build(exif = ImageExifDetails.PENDING)
        assertTrue(model.loading)
        assertEquals(listOf(SectionId.FILE), model.sections.map { it.id })
        assertTrue(model.sections.flatMap { it.rows }.none { it.failure })
        val pendingVideo = build(asset = clip(), video = VideoDetails.PENDING)
        assertTrue(pendingVideo.loading)
        assertEquals(listOf(SectionId.FILE), pendingVideo.sections.map { it.id })
        val noVideoYet = build(asset = clip(), video = null)
        assertTrue("a video whose read has not been handed in is pending", noVideoYet.loading)
    }

    @Test
    fun `a failed read is Couldn't read, never not recorded, and hides what it could not see`() {
        val model = build(exif = ImageExifDetails(readOk = false))
        assertFalse(model.loading)
        assertEquals(listOf(SectionId.FILE), model.sections.map { it.id })
        val failure = model.section(SectionId.FILE)!!.rows.single { it.failure }
        assertEquals("Photo details", failure.label)
        assertEquals("Couldn't read", failure.value)
        val everything = model.sections.flatMap { s -> s.rows.flatMap { listOf(it.label, it.value) } }
        assertTrue(everything.none { it.contains("not recorded", ignoreCase = true) })
        // The MediaStore facts are still there.
        assertNotNull(model.section(SectionId.FILE, "Name"))
    }

    @Test
    fun `a failed video read says so on the video`() {
        val model = build(asset = clip(), video = VideoDetails(readOk = false))
        val failure = model.section(SectionId.FILE)!!.rows.single { it.failure }
        assertEquals("Video details", failure.label)
        assertEquals("Couldn't read", failure.value)
        assertNull(model.section(SectionId.VIDEO))
    }

    @Test
    fun `an embedded location shows its coordinates, altitude, facing and speed`() {
        val model = build()
        assertEquals("51.5007° N · 0.1246° W", model.section(SectionId.LOCATION, "Coordinates"))
        assertEquals("From the photo", model.section(SectionId.LOCATION, "Source"))
        assertEquals("12 m above sea level", model.section(SectionId.LOCATION, "Altitude"))
        assertEquals("NE 45° (magnetic)", model.section(SectionId.LOCATION, "Facing"))
        assertEquals("4 km/h", model.section(SectionId.LOCATION, "Speed"))
        assertEquals("51.50070, -0.12460", model.section(SectionId.LOCATION)!!.rows.first { it.label == "Coordinates" }.copyText)
    }

    @Test
    fun `a hand-placed location is labelled and carries none of the file's altitude, facing or speed`() {
        val model = build(exif = full.copy(latitude = null, longitude = null), manual = 17.4435 to 78.3772)
        assertEquals("17.4435° N · 78.3772° E", model.section(SectionId.LOCATION, "Coordinates"))
        assertEquals("Placed by you", model.section(SectionId.LOCATION, "Source"))
        assertNull(model.section(SectionId.LOCATION, "Altitude"))
        assertNull(model.section(SectionId.LOCATION, "Facing"))
        assertNull(model.section(SectionId.LOCATION, "Speed"))
    }

    @Test
    fun `an embedded fix wins over a hand-placed one, as in the place block`() {
        val model = build(manual = 10.0 to 10.0)
        assertEquals("From the photo", model.section(SectionId.LOCATION, "Source"))
        assertEquals("51.5007° N · 0.1246° W", model.section(SectionId.LOCATION, "Coordinates"))
    }

    @Test
    fun `no drawable location means no Location section`() {
        assertNull(build(exif = full.copy(latitude = null, longitude = null)).section(SectionId.LOCATION))
        assertNull(build(exif = full.copy(latitude = Double.NaN, longitude = 5.0)).section(SectionId.LOCATION))
        assertNull(build(exif = full.copy(latitude = null, longitude = null), manual = 95.0 to 5.0).section(SectionId.LOCATION))
    }

    @Test
    fun `a video has Video and no Capture, and an audio track is information only`() {
        val details = VideoDetails(
            containerMime = "video/mp4",
            videoMime = "video/hevc",
            profile = "Main 10",
            width = 3840,
            height = 2160,
            frameRate = 29.97,
            captureFps = 240.0,
            bitrate = 48_000_000L,
            hdr = VideoHdr.HLG,
            hasAudio = true,
            audioMime = "audio/mp4a-latm",
            audioChannels = 2,
            createdAtMillis = taken,
        )
        val model = build(asset = clip(width = 3840, height = 2160), exif = ImageExifDetails(), video = details)
        assertNull(model.section(SectionId.CAPTURE))
        assertEquals("HEVC (H.265) · Main 10", model.section(SectionId.VIDEO, "Codec"))
        assertEquals("29.97 fps", model.section(SectionId.VIDEO, "Frame rate"))
        assertEquals("240 fps", model.section(SectionId.VIDEO, "Captured at"))
        assertEquals("48.0 Mbps", model.section(SectionId.VIDEO, "Bitrate"))
        assertEquals("HLG", model.section(SectionId.VIDEO, "Dynamic range"))
        assertEquals("AAC · 2 channels", model.section(SectionId.VIDEO, "Audio"))
        assertEquals("Saturday • 1 Aug 2026 • 5:03 PM (UTC)", model.section(SectionId.VIDEO, "Recorded"))
        assertNull("the card already says the size", model.section(SectionId.VIDEO, "Resolution"))
        assertNull(model.section(SectionId.VIDEO, "Rotation"))
        assertFalse(model.section(SectionId.VIDEO)!!.initiallyExpanded)
    }

    @Test
    fun `a capture rate equal to the frame rate is not a second fact, and a video with no audio says so`() {
        val details = VideoDetails(frameRate = 30.0, captureFps = 30.0, hasAudio = false, rotationDegrees = 90, width = 1920, height = 1080)
        val model = build(asset = clip(width = 1080, height = 1920), exif = ImageExifDetails(), video = details)
        assertNull(model.section(SectionId.VIDEO, "Captured at"))
        assertEquals("No audio track", model.section(SectionId.VIDEO, "Audio"))
        assertEquals("90° clockwise", model.section(SectionId.VIDEO, "Rotation"))
        // 1920x1080 turned a quarter is 1080x1920: the same as the card, so no Resolution row.
        assertNull(model.section(SectionId.VIDEO, "Resolution"))
    }

    @Test
    fun `a video size that disagrees with MediaStore is shown`() {
        val details = VideoDetails(width = 1280, height = 720)
        val model = build(asset = clip(width = 0, height = 0), exif = ImageExifDetails(), video = details)
        assertEquals("1280 × 720", model.section(SectionId.VIDEO, "Resolution"))
    }

    @Test
    fun `an unknown audio state is no row at all`() {
        val model = build(asset = clip(), exif = ImageExifDetails(), video = VideoDetails(videoMime = "video/avc", hasAudio = null))
        assertNull(model.section(SectionId.VIDEO, "Audio"))
        assertEquals("H.264", model.section(SectionId.VIDEO, "Codec"))
    }

    @Test
    fun `library shows the file's keywords and the neighbours, and leaves rating and creator to the Metadata block`() {
        val model = build(nearby = 3)
        assertEquals("harbour, dusk", model.section(SectionId.LIBRARY, "Keywords in the file"))
        assertEquals("3 others within a minute, in this folder", model.section(SectionId.LIBRARY, "Nearby in time"))
        val labels = model.section(SectionId.LIBRARY)!!.rows.map { it.label }
        assertTrue(labels.none { it == "Rating" || it == "Creator" || it == "Copyright" })
        assertEquals("1 other within a minute, in this folder", build(nearby = 1).section(SectionId.LIBRARY, "Nearby in time"))
        assertNull(build(nearby = 0).section(SectionId.LIBRARY, "Nearby in time"))
        assertNull(build(nearby = null).section(SectionId.LIBRARY, "Nearby in time"))
        assertNull("no keywords, no neighbours, no section", build(exif = full.copy(professionalMetadata = CurrentMetadata.EMPTY)).section(SectionId.LIBRARY))
    }

    @Test
    fun `serial numbers and owner names never appear`() {
        val labels = build(nearby = 1).sections.flatMap { s -> s.rows.map { it.label.lowercase() } }
        assertTrue(labels.none { "serial" in it || "owner" in it })
    }

    @Test
    fun `rows with no value are omitted and sections with no rows are omitted`() {
        val sparse = ImageExifDetails(software = "   ", meteringMode = 0, exposureProgram = 0, whiteBalance = 7)
        val model = build(exif = sparse)
        assertNull("unknown codes are nothing, not 'Other'", model.section(SectionId.CAPTURE))
        assertTrue(model.sections.all { it.rows.isNotEmpty() })
        assertTrue(model.sections.flatMap { it.rows }.none { it.value.isBlank() })
    }

    // ── the neighbours ───────────────────────────────────────────────────────────────────────

    @Test
    fun `nearby counts others in the same folder inside the window, edges included`() {
        val me = photo(id = 1L, dateTaken = taken)
        val others = listOf(
            photo(id = 2L, dateTaken = taken + 60_000L),
            photo(id = 3L, dateTaken = taken - 60_000L),
            photo(id = 4L, dateTaken = taken + 60_001L),
            photo(id = 5L, dateTaken = taken + 1_000L, bucketId = 99L, bucket = "Other", path = "DCIM/Other/"),
            photo(id = 6L, dateTaken = 0L),
            me,
        )
        assertEquals("the edges count, the far one, the other folder, the undated and myself do not", 2, MetadataSections.nearbyCount(me, others))
        assertEquals(0, MetadataSections.nearbyCount(photo(id = 9L, dateTaken = 0L), others))
    }

    @Test
    fun `nearby, fed browsableAssets, never counts a trashed, archived or locked-folder photo`() {
        val me = photo(id = 1L, dateTaken = taken)
        val visible = photo(id = 2L, dateTaken = taken + 5_000L)
        val archived = photo(id = 3L, dateTaken = taken + 5_000L)
        val trashed = photo(id = 4L, dateTaken = taken + 5_000L, trashed = true)
        val sensitive = photo(id = 5L, dateTaken = taken + 5_000L)
        val all = listOf(me, visible, archived, trashed, sensitive)

        val browsable = browsableAssets(
            all,
            archivedIds = setOf(archived.id),
            sensitiveIds = setOf(sensitive.id),
            lockedFolders = emptySet(),
            unlockedFolders = emptySet(),
            hideSensitive = true,
        )
        assertEquals(1, MetadataSections.nearbyCount(me, browsable))

        val lockedKey = com.fotoxplorr.app.gallery.folderIdentity(me).key.value
        val locked = browsableAssets(
            all,
            archivedIds = emptySet(),
            sensitiveIds = emptySet(),
            lockedFolders = setOf(lockedKey),
            unlockedFolders = emptySet(),
            hideSensitive = false,
        )
        assertEquals("everything in a locked folder is gone from the list", 0, MetadataSections.nearbyCount(me, locked))
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────

    private fun photo(
        id: Long = 1L,
        name: String = "IMG_0767.HEIC",
        mime: String = "image/heic",
        width: Int = 3024,
        height: Int = 4032,
        size: Long = 2_202_010L,
        dateTaken: Long = taken,
        bucket: String? = "Camera",
        bucketId: Long? = 7L,
        path: String? = "DCIM/Camera/",
        trashed: Boolean = false,
    ) = MediaAsset(
        id = MediaId(id),
        contentUriString = "content://media/external/images/media/$id",
        displayName = name,
        mimeType = mime,
        bucketName = bucket,
        bucketId = bucketId.takeIf { bucket != null },
        dateTakenMillis = dateTaken,
        dateModifiedSeconds = 0L,
        width = width,
        height = height,
        sizeBytes = size,
        relativePath = path,
        isFavorite = false,
        isTrashed = trashed,
    )

    private fun clip(width: Int = 1920, height: Int = 1080) = photo(
        id = 50L,
        name = "VID_0001.mp4",
        mime = "video/mp4",
        width = width,
        height = height,
        size = 12_000_000L,
    ).copy(durationMillis = 10_000L)
}
