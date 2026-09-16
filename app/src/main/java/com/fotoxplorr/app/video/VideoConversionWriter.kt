package com.fotoxplorr.app.video

import android.content.Context
import android.net.Uri
import com.fotoxplorr.app.media.MediaAsset

/**
 * Video's own "Save As": re-encodes a video into H.264/AAC/MP4 as a new file beside the source,
 * on Media3 Transformer (see `docs/adr/ADR-008-video-transcode-pipeline.md`, revision 2, for why
 * this replaced a hand-written MediaCodec/EGL pipeline). All the actual work -- decode/encode,
 * MediaStore publish with `IS_PENDING`, delete-on-failure, `RELATIVE_PATH` validation -- lives in
 * [VideoExporter], which the video editor's export path shares; this class is a thin,
 * signature-stable front door for [com.fotoxplorr.app.FotoXplorrActivity]'s existing call site.
 */
class VideoConversionWriter(context: Context) {
    private val exporter = VideoExporter(context.applicationContext)

    /** Plain format conversion: no trim, no speed change, no crop -- just re-encode into the one
     *  target every API 26+ device is guaranteed to be able to decode. */
    suspend fun convertToH264Mp4(source: MediaAsset): Result<Uri> =
        convertToH264Mp4(source, VideoExportOptions())

    /** Overload for a caller that wants to steer the conversion (a non-default codec/quality) and
     *  observe progress -- the plain overload above is [VideoExportOptions()] with no edits. */
    suspend fun convertToH264Mp4(
        source: MediaAsset,
        options: VideoExportOptions,
        onProgress: (Float) -> Unit = {},
    ): Result<Uri> = exporter.export(source, options, onProgress, nameMarker = "converted")
}

/**
 * The name a converted or edited copy is saved under: the source's stem, then a marker, then
 * `.mp4` -- mirrors [com.fotoxplorr.app.editor.editedName]'s exact shape (and the same
 * re-conversion and awkward-filename cases it handles) for the same reason: a converted or edited
 * video is a version of the source, not a new thing, and shares the naming convention every other
 * "version of this file" feature in this app already uses.
 */
internal fun convertedName(displayName: String, marker: String = "converted"): String {
    val trimmed = displayName.trim().ifBlank { "video" }
    val dot = trimmed.lastIndexOf('.')
    val stem = if (dot > 0) trimmed.substring(0, dot) else trimmed
    val base = stem.removeSuffix("-$marker")
    return "$base-$marker.mp4"
}
