package com.fotoxplorr.app.video

/**
 * Everything a Transformer-based export can be asked to do to a video, as pure data — the video
 * counterpart of [com.fotoxplorr.app.editor.EditRecipe], and the shape both the plain "Save As"
 * conversion ([VideoConversionWriter]) and the video editor
 * ([com.fotoxplorr.app.videoeditor.VideoEditPlan]) reduce themselves to before [VideoExporter]
 * ever touches a decoder. See `docs/adr/ADR-008-video-transcode-pipeline.md` (revision 2) for why
 * this replaced a hand-written MediaCodec/EGL pipeline rather than sitting beside it.
 */
data class VideoExportOptions(
    val trimStartMs: Long = 0L,
    /** Null means "to the end of the source". */
    val trimEndMs: Long? = null,
    val speedFactor: Float = 1f,
    /** Clockwise degrees, one of 0/90/180/270 — same convention as
     *  [com.fotoxplorr.app.editor.EditRecipe.quarterTurns] but already expanded to degrees since
     *  nothing else here counts in quarter turns. */
    val rotationDegrees: Int = 0,
    val mirror: Boolean = false,
    val cropAspect: CropAspect? = null,
    val muted: Boolean = false,
    val codec: VideoCodec = VideoCodec.H264,
    val quality: VideoQuality = VideoQuality.ORIGINAL,
    /** -1f..1f, 0f = unchanged. Mapped to Media3's [androidx.media3.effect.Brightness] /
     *  [androidx.media3.effect.Contrast] / [androidx.media3.effect.HslAdjustment] effects. */
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val textOverlay: TextOverlay? = null,
) {
    init {
        require(rotationDegrees in ALLOWED_ROTATIONS) {
            "rotationDegrees must be one of $ALLOWED_ROTATIONS, was $rotationDegrees"
        }
        require(speedFactor in MIN_SPEED..MAX_SPEED) {
            "speedFactor $speedFactor is outside $MIN_SPEED..$MAX_SPEED"
        }
        require(brightness in -1f..1f) { "brightness must be -1..1, was $brightness" }
        require(contrast in -1f..1f) { "contrast must be -1..1, was $contrast" }
        require(saturation in -1f..1f) { "saturation must be -1..1, was $saturation" }
    }

    val hasFilter: Boolean get() = brightness != 0f || contrast != 0f || saturation != 0f

    companion object {
        const val MIN_SPEED = 0.25f
        const val MAX_SPEED = 4f
        val ALLOWED_ROTATIONS = setOf(0, 90, 180, 270)
    }
}

/** What the exported clip will run for, after trim and speed — pure so it can be shown in a
 *  readout ("0:12 -> 0:06 at 2x") without an export having actually run yet. */
fun VideoExportOptions.exportedDurationMs(sourceDurationMs: Long): Long {
    val end = trimEndMs ?: sourceDurationMs
    val trimmed = (end - trimStartMs).coerceAtLeast(0L)
    return (trimmed / speedFactor).toLong()
}

/** Target video codec. Both are guaranteed encoders on every API 26+ device per the platform
 *  compatibility definition for H.264; HEVC is not guaranteed, which is why the editor only
 *  offers it after checking [deviceSupportsVideoEncoder]. */
enum class VideoCodec(val mimeType: String) {
    H264(android.media.MediaFormat.MIMETYPE_VIDEO_AVC),
    HEVC(android.media.MediaFormat.MIMETYPE_VIDEO_HEVC),
}

/** Quality presets. [ORIGINAL] leaves resolution and bitrate to the encoder's own defaults for
 *  the source's own frame size -- the only preset that can legitimately claim to reproduce the
 *  source's own quality. */
enum class VideoQuality { ORIGINAL, P1080, P720 }

/** The short-side (portrait or landscape) pixel target for [quality], or null for "leave the
 *  source's own frame size alone" -- [ORIGINAL] never up- or down-scales. */
fun targetShortSideForQuality(quality: VideoQuality): Int? = when (quality) {
    VideoQuality.ORIGINAL -> null
    VideoQuality.P1080 -> 1080
    VideoQuality.P720 -> 720
}

/** A reasonable fixed bitrate for [quality], or null to leave the encoder's own bitrate selection
 *  alone. These numbers sit inside the range devices' H.264/HEVC encoders are commonly validated
 *  against for 30fps content at the matching resolution; an owner device pass should confirm they
 *  read well on the actual hardware this ships to (see the ADR's device-check list). */
fun targetBitrateForQuality(quality: VideoQuality): Int? = when (quality) {
    VideoQuality.ORIGINAL -> null
    VideoQuality.P1080 -> 8_000_000
    VideoQuality.P720 -> 5_000_000
}

/** Centred crop targets, in the order the strip offers them. Ported from the Media3-based video
 *  editor prototype (`origin/claude/fotoz-continue`); kept in `video/` rather than `videoeditor/`
 *  so the plain conversion path and the editor share one geometry implementation. */
enum class CropAspect(val widthOverHeight: Float, val label: String) {
    SQUARE(1f, "1:1"),
    WIDE(16f / 9f, "16:9"),
    TALL(9f / 16f, "9:16"),
    CLASSIC(4f / 3f, "4:3"),
    ;

    /**
     * The normalized device-coordinate crop box for a source frame of [sourceWidth] x
     * [sourceHeight] AFTER [rotatedDegrees] has been applied — Media3's `Crop` effect takes NDC
     * edges in -1..1. Returns null when the (rotated) source already has this aspect (within one
     * part in a thousand): a no-op crop must not force a re-encode of the video track's geometry
     * for nothing.
     */
    fun ndcCrop(sourceWidth: Int, sourceHeight: Int, rotatedDegrees: Int): NdcCrop? {
        if (sourceWidth <= 0 || sourceHeight <= 0) return null
        val swapped = (rotatedDegrees / 90) % 2 == 1
        val frameWidth = if (swapped) sourceHeight else sourceWidth
        val frameHeight = if (swapped) sourceWidth else sourceHeight
        val sourceAspect = frameWidth.toFloat() / frameHeight
        val target = widthOverHeight
        if (kotlin.math.abs(sourceAspect - target) / target < 0.001f) return null
        return if (sourceAspect > target) {
            // Source is wider than the target: crop the sides.
            val keep = target / sourceAspect // fraction of width kept, 0..1
            NdcCrop(left = -keep, right = keep, bottom = -1f, top = 1f)
        } else {
            // Source is taller: crop top and bottom.
            val keep = sourceAspect / target
            NdcCrop(left = -1f, right = 1f, bottom = -keep, top = keep)
        }
    }
}

/** A crop in normalized device coordinates, the shape Media3's `Crop` effect consumes. */
data class NdcCrop(val left: Float, val right: Float, val bottom: Float, val top: Float) {
    init {
        require(left < right && bottom < top) { "Inverted crop box." }
        require(left >= -1f && right <= 1f && bottom >= -1f && top <= 1f) { "Crop box outside NDC." }
    }
}

/** A single burned-in text caption. Position is a named anchor rather than free-form coordinates:
 *  a small, fixed set of sane placements is easier to get right on every aspect ratio than a
 *  drag-anywhere overlay, and is what the task calls for as the v1 shape. */
data class TextOverlay(
    val text: String,
    val position: TextOverlayPosition = TextOverlayPosition.BOTTOM,
    /** Scales the rendered text bitmap; 1f is the baseline size [TextOverlayRenderer] renders at. */
    val sizeScale: Float = 1f,
    val colorArgb: Int = DEFAULT_COLOR_ARGB,
) {
    init {
        require(sizeScale > 0f) { "sizeScale must be positive, was $sizeScale" }
    }

    private companion object {
        const val DEFAULT_COLOR_ARGB = 0xFFFFFFFF.toInt()
    }
}

enum class TextOverlayPosition { TOP, CENTER, BOTTOM }
