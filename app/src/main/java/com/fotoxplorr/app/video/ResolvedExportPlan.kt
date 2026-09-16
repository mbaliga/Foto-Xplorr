package com.fotoxplorr.app.video

import android.media.MediaFormat

/**
 * [VideoExportOptions] translated into the shape [VideoExporter] actually hands Media3 —
 * separated out so the TRANSLATION (rotation sign convention, crop geometry, codec/quality
 * mapping) is a pure function testable on the JVM with no Transformer/Composition instance
 * involved, rather than something only checkable by reading [VideoExporter]'s body.
 */
data class ResolvedExportPlan(
    /** Degrees Media3's `ScaleAndRotateTransformation` should rotate by. Media3 rotates
     *  COUNTER-clockwise for a positive value; [VideoExportOptions.rotationDegrees] is clockwise
     *  (matching [com.fotoxplorr.app.editor.EditRecipe]'s convention), so this is the conversion
     *  between the two, not a copy of the input. */
    val media3RotationDegrees: Float,
    val scaleX: Float,
    val crop: NdcCrop?,
    val videoMimeType: String,
    val audioMimeType: String,
    val targetShortSide: Int?,
    val targetBitrate: Int?,
    val exportedDurationMs: Long,
)

fun resolveExportPlan(
    options: VideoExportOptions,
    sourceWidth: Int,
    sourceHeight: Int,
    sourceDurationMs: Long,
): ResolvedExportPlan = ResolvedExportPlan(
    media3RotationDegrees = ((360 - options.rotationDegrees) % 360).toFloat(),
    scaleX = if (options.mirror) -1f else 1f,
    crop = options.cropAspect?.ndcCrop(sourceWidth, sourceHeight, options.rotationDegrees),
    videoMimeType = options.codec.mimeType,
    audioMimeType = MediaFormat.MIMETYPE_AUDIO_AAC,
    targetShortSide = targetShortSideForQuality(options.quality),
    targetBitrate = targetBitrateForQuality(options.quality),
    exportedDurationMs = options.exportedDurationMs(sourceDurationMs),
)
