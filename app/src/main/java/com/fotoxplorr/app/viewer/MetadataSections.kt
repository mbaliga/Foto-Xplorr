package com.fotoxplorr.app.viewer

import com.fotoxplorr.app.gallery.folderIdentity
import com.fotoxplorr.app.media.MediaAsset
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.abs

/** The detail sections the info room can draw, in the order [MetadataSections.build] returns them. */
internal enum class SectionId { FILE, IMAGE, VIDEO, CAPTURE, LOCATION, LIBRARY }

/**
 * One row of a section. [copyText] is what a long-press puts on the clipboard (the value, unless a
 * tidier machine form exists). [failure] marks the one row that says a read failed, so the UI can
 * draw it as a warning rather than a fact.
 */
internal data class MetaRow(
    val label: String,
    val value: String,
    val copyText: String = value,
    val failure: Boolean = false,
)

internal data class MetaSection(
    val id: SectionId,
    val title: String,
    val rows: List<MetaRow>,
    val initiallyExpanded: Boolean,
)

/** [sections] to draw and whether more are still on their way ([loading]: a read has not finished). */
internal data class MetadataModel(val sections: List<MetaSection>, val loading: Boolean)

/**
 * The info room's detail sections, built from facts already in hand -- no I/O, no clock of its own
 * (the caller passes "now"), so every string is unit-testable.
 *
 * Rules the tests pin:
 * - A row with no value is OMITTED, and a section with no rows is omitted. (The camera card and the
 *   three always-visible Information rows keep their dashes; that is the owner's 2026-08-18 note
 *   and it governs those, not a forty-row list.)
 * - A read that FAILED is not an absence (TRAPS 32). `readOk = false` gives the File section from
 *   MediaStore plus one row saying "Couldn't read", and every section that would have come from the
 *   unread file is left out; it never says "not recorded", which would claim the file was empty.
 * - A read that has not finished is not an absence either: while pending, only the File section
 *   (all MediaStore) is returned and [MetadataModel.loading] is set.
 * - Facts the camera card or the three Information rows already say (make and model, lens, size,
 *   exposure strip, flash, dynamic range, Modified, colour space, colour profile) are not repeated.
 * - Nothing here is a serial number or an owner's name, and nothing reads a location from anywhere
 *   but the embedded fix and the hand-placed one the caller passes.
 */
internal object MetadataSections {

    /** How close in time, in seconds, another photo must be to count as taken "at the same time". */
    const val NEARBY_WINDOW_SECONDS = 60L

    fun build(
        asset: MediaAsset,
        exif: ImageExifDetails,
        video: VideoDetails?,
        manualLatitude: Double?,
        manualLongitude: Double?,
        nearbyCount: Int?,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): MetadataModel {
        val isVideo = asset.isVideo
        // A video's file read is the VideoDetails one; a still's is the EXIF one. A video whose
        // read has not been handed in yet (null) is as pending as one marked so.
        val pending = if (isVideo) video == null || video.pending else exif.pending
        val readFailed = if (isVideo) video?.readOk == false else !exif.readOk

        val file = fileSection(asset, exif, video, readFailed, nowMillis, zone, locale)
        if (pending) return MetadataModel(listOf(file).filter { it.rows.isNotEmpty() }, loading = true)

        val sections = buildList<MetaSection> {
            add(file)
            if (!readFailed) {
                add(imageSection(asset, exif, video))
                if (isVideo) add(videoSection(asset, video!!, zone, locale)) else add(captureSection(exif))
            }
            add(locationSection(asset, exif, manualLatitude, manualLongitude))
            if (!readFailed) add(librarySection(exif, nearbyCount))
        }
        return MetadataModel(sections.filter { it.rows.isNotEmpty() }, loading = false)
    }

    /**
     * How many OTHER items in [browsable] were taken within [windowSeconds] of [asset], in the same
     * folder: the offline stand-in for "similar photos" (true similarity needs a model this flavour
     * does not ship). [browsable] MUST already be filtered through `browsableAssets` -- this function
     * trusts it, so a locked, hidden, archived or trashed photo can never be counted, and through the
     * count be inferred (TRAPS 33). One pass over the list, so the caller can run it once per viewer
     * open without an index.
     */
    fun nearbyCount(asset: MediaAsset, browsable: List<MediaAsset>, windowSeconds: Long = NEARBY_WINDOW_SECONDS): Int {
        if (asset.dateTakenMillis <= 0L) return 0
        val windowMillis = windowSeconds * 1_000L
        return browsable.count { other ->
            other.id != asset.id &&
                other.dateTakenMillis > 0L &&
                sameFolder(asset, other) &&
                abs(other.dateTakenMillis - asset.dateTakenMillis) <= windowMillis
        }
    }

    /** The library's own idea of "the same folder", so a locked folder's key means the same here as there. */
    private fun sameFolder(a: MediaAsset, b: MediaAsset): Boolean = folderIdentity(a).key == folderIdentity(b).key

    // ── File ─────────────────────────────────────────────────────────────────────────────────

    private fun fileSection(
        asset: MediaAsset,
        exif: ImageExifDetails,
        video: VideoDetails?,
        readFailed: Boolean,
        nowMillis: Long,
        zone: ZoneId,
        locale: Locale,
    ): MetaSection {
        val rows = buildList<MetaRow> {
            addRow("Name", asset.displayName.takeIf(String::isNotBlank))
            addRow("Type", asset.mimeType.takeIf(String::isNotBlank))
            addRow("Size", asset.sizeBytes.takeIf { it > 0L }?.let { DetailFormatting.byteLine(it, locale) })
            addRow("Album", asset.bucketName?.takeIf(String::isNotBlank))
            addRow("Where", asset.relativePath?.takeIf(String::isNotBlank))
            takenLine(asset, exif, zone, locale)?.let { add(MetaRow("Taken", it)) }
            addRow("Age", DetailFormatting.fileAgeText(asset.dateTakenMillis, nowMillis, zone))
            if (readFailed) {
                add(MetaRow(if (asset.isVideo) "Video details" else "Photo details", COULDNT_READ, failure = true))
            }
        }
        return MetaSection(SectionId.FILE, "File", rows, initiallyExpanded = true)
    }

    /**
     * When the photo was taken, on the clock it was taken by. MediaStore's DATE_TAKEN is the moment
     * (it is what the library sorts by and the room's date line shows); the file's OffsetTimeOriginal
     * says which zone's wall clock to read it on. With no offset the line is the device's clock and
     * says so. A file with no MediaStore date but an EXIF wall-clock time is read as the device's
     * zone, for the same reason.
     */
    private fun takenLine(asset: MediaAsset, exif: ImageExifDetails, zone: ZoneId, locale: Locale): String? {
        val offset = if (asset.isVideo) null else DetailFormatting.exifOffset(exif.offsetTimeOriginal)
        if (asset.dateTakenMillis > 0L) return DetailFormatting.takenLine(asset.dateTakenMillis, offset, zone, locale)
        if (asset.isVideo) return null
        val wallClock = DetailFormatting.exifDateTime(exif.dateTimeOriginal) ?: return null
        val instant: Instant = wallClock.atZone(offset ?: zone).toInstant()
        return DetailFormatting.takenLine(instant.toEpochMilli(), offset, zone, locale)
    }

    // ── Image ────────────────────────────────────────────────────────────────────────────────

    private fun imageSection(asset: MediaAsset, exif: ImageExifDetails, video: VideoDetails?): MetaSection {
        val (width, height) = if (asset.isVideo && video != null && video.displayWidth > 0) {
            video.displayWidth to video.displayHeight
        } else {
            asset.width to asset.height
        }
        val rows = buildList<MetaRow> {
            addRow("Aspect", aspectRatioLabel(width, height))
            if (!asset.isVideo) {
                addRow("Orientation", DetailFormatting.orientationText(exif.orientation))
                val extras = listOfNotNull(
                    "Motion Photo".takeIf { exif.isMotionPhoto },
                    "Depth map".takeIf { exif.hasDepthMap },
                    "Panorama".takeIf { exif.isPanorama },
                )
                addRow("Includes", extras.joinToString(", ").takeIf(String::isNotEmpty))
            }
        }
        return MetaSection(SectionId.IMAGE, "Image", rows, initiallyExpanded = true)
    }

    // ── Capture (stills) ─────────────────────────────────────────────────────────────────────

    private fun captureSection(exif: ImageExifDetails): MetaSection {
        val rows = buildList<MetaRow> {
            addRow("Metering", DetailFormatting.meteringText(exif.meteringMode))
            addRow("Exposure program", DetailFormatting.exposureProgramText(exif.exposureProgram))
            addRow("White balance", DetailFormatting.whiteBalanceText(exif.whiteBalance))
            addRow("Software", exif.software?.takeIf(String::isNotBlank))
        }
        return MetaSection(SectionId.CAPTURE, "Capture", rows, initiallyExpanded = false)
    }

    // ── Video ────────────────────────────────────────────────────────────────────────────────

    private fun videoSection(asset: MediaAsset, video: VideoDetails, zone: ZoneId, locale: Locale): MetaSection {
        val rows = buildList<MetaRow> {
            val codec = VideoDetails.codecName(video.videoMime)
            addRow("Codec", listOfNotNull(codec, video.profile).joinToString(" · ").takeIf(String::isNotEmpty))
            // The card's dimensions line already says the size; repeat it only when MediaStore
            // gave a different (or no) size, so the two never silently disagree.
            val w = video.displayWidth
            val h = video.displayHeight
            val sameAsCard = (w == asset.width && h == asset.height) || (w == asset.height && h == asset.width)
            if (w > 0 && h > 0 && !sameAsCard) add(MetaRow("Resolution", "$w × $h"))
            addRow("Frame rate", DetailFormatting.fpsText(video.frameRate))
            // Capture rate is only a fact when it differs from the file's: slow motion is the case.
            val capture = video.captureFps
            if (capture != null && (video.frameRate == null || abs(capture - video.frameRate) > 0.5)) {
                addRow("Captured at", DetailFormatting.fpsText(capture))
            }
            addRow("Bitrate", DetailFormatting.bitrateText(video.bitrate))
            addRow("Dynamic range", video.hdr?.label)
            if (video.rotationDegrees != 0) add(MetaRow("Rotation", "${video.rotationDegrees}° clockwise"))
            audioValue(video)?.let { add(MetaRow("Audio", it)) }
            video.createdAtMillis?.let {
                add(MetaRow("Recorded", DetailFormatting.takenLine(it, ZoneOffset.UTC, zone, locale)))
            }
        }
        return MetaSection(SectionId.VIDEO, "Video", rows, initiallyExpanded = false)
    }

    /** "AAC · 2 channels", "Yes" when the track exists but names nothing, "None" when there is none; null if unknown. */
    private fun audioValue(video: VideoDetails): String? = when (video.hasAudio) {
        null -> null
        false -> "No audio track"
        true -> {
            val channels = video.audioChannels?.let { if (it == 1) "1 channel" else "$it channels" }
            listOfNotNull(VideoDetails.audioCodecName(video.audioMime), channels)
                .joinToString(" · ")
                .ifEmpty { "Has an audio track" }
        }
    }

    // ── Location ─────────────────────────────────────────────────────────────────────────────

    private fun locationSection(
        asset: MediaAsset,
        exif: ImageExifDetails,
        manualLatitude: Double?,
        manualLongitude: Double?,
    ): MetaSection {
        val embedded = !asset.isVideo && PlaceMorph.isDrawable(exif.latitude, exif.longitude)
        val latitude = if (embedded) exif.latitude else manualLatitude
        val longitude = if (embedded) exif.longitude else manualLongitude
        val rows = if (!PlaceMorph.isDrawable(latitude, longitude)) {
            emptyList()
        } else {
            buildList<MetaRow> {
                add(
                    MetaRow(
                        label = "Coordinates",
                        value = PlaceMorph.coordinateLine(latitude!!, longitude!!),
                        copyText = String.format(Locale.US, "%.5f, %.5f", latitude, longitude),
                    ),
                )
                add(MetaRow("Source", if (embedded) "From the photo" else "Placed by you"))
                // Altitude, direction and speed belong to the file's own fix; a hand-placed point
                // has none, and the file's would describe somewhere else.
                if (embedded) {
                    addRow("Altitude", DetailFormatting.altitudeText(exif.gpsAltitudeMeters))
                    DetailFormatting.compassText(exif.gpsBearingDegrees)?.let {
                        add(MetaRow("Facing", if (exif.gpsBearingMagnetic) "$it (magnetic)" else it))
                    }
                    addRow("Speed", DetailFormatting.speedText(exif.gpsSpeedKmh))
                }
            }
        }
        return MetaSection(SectionId.LOCATION, "Location", rows, initiallyExpanded = false)
    }

    // ── Library ──────────────────────────────────────────────────────────────────────────────

    private fun librarySection(exif: ImageExifDetails, nearbyCount: Int?): MetaSection {
        val rows = buildList<MetaRow> {
            // Keywords the FILE carries (XMP dc:subject). Rating, creator and copyright stay in the
            // room's Metadata block, which edits them; the library's own tags have their own block.
            val keywords = exif.professionalMetadata.keywords.map(String::trim).filter(String::isNotEmpty)
            addRow("Keywords in the file", keywords.joinToString(", ").takeIf(String::isNotEmpty))
            if (nearbyCount != null && nearbyCount > 0) {
                val others = if (nearbyCount == 1) "1 other" else "$nearbyCount others"
                add(MetaRow("Nearby in time", "$others within a minute, in this folder"))
            }
        }
        return MetaSection(SectionId.LIBRARY, "Library", rows, initiallyExpanded = false)
    }

    /** Adds a row unless [value] is null or blank -- the one place "omit what is empty" is decided. */
    private fun MutableList<MetaRow>.addRow(label: String, value: String?) {
        if (!value.isNullOrBlank()) add(MetaRow(label, value))
    }

    /** What a failed read says. Deliberately not "not recorded": that would claim the file was empty. */
    const val COULDNT_READ = "Couldn't read"
}
