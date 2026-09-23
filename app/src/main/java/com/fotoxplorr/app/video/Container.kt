package com.fotoxplorr.app.video

import android.media.MediaFormat
import android.media.MediaMuxer

/** An output container [MediaMuxer] can actually write to, plus the file extension and share
 * MIME type that go with it. */
enum class Container(internal val muxerOutputFormat: Int, val extension: String, val mimeType: String) {
    MP4(MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, "mp4", "video/mp4"),
    WEBM(MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM, "webm", "video/webm"),
}

/**
 * Which container [MediaMuxer] can actually mux [videoMime] (and, if present, [audioMime]) into
 * -- not which container a *decoder* can play the pair back from, a broader and separate
 * question this deliberately does not answer. Checked against Android's own "Supported media
 * formats" table (developer.android.com/media/platform/supported-formats) crossed with
 * [MediaMuxer]'s own narrower *writer*-side support, which several confirmed reports (muxer
 * `addTrack` throwing `IllegalArgumentException`) show is stricter than the platform's general
 * decode table for the same pairing:
 *
 * - H.264/AVC ([MediaFormat.MIMETYPE_VIDEO_AVC]) and H.265/HEVC ([MediaFormat.MIMETYPE_VIDEO_HEVC]
 *   -- muxable into MP4 since API 24, four levels below this app's `minSdk` 26, so no version gate
 *   is needed here) go into [Container.MP4], with AAC ([MediaFormat.MIMETYPE_AUDIO_AAC]) or AMR
 *   ([MediaFormat.MIMETYPE_AUDIO_AMR_NB]/[MediaFormat.MIMETYPE_AUDIO_AMR_WB]) audio, or none.
 * - VP8/VP9 ([MediaFormat.MIMETYPE_VIDEO_VP8]/[MediaFormat.MIMETYPE_VIDEO_VP9] -- MP4 can *decode*
 *   VP9 on many devices, but [MediaMuxer] itself only ever accepts a VP8/VP9 track into
 *   `MUXER_OUTPUT_WEBM`) go into [Container.WEBM], with Vorbis ([MediaFormat.MIMETYPE_AUDIO_VORBIS])
 *   or Opus ([MediaFormat.MIMETYPE_AUDIO_OPUS]) audio, or none.
 * - Everything else -- AV1 chief among them, whose *muxer*-specific support (as opposed to the
 *   platform's general decode support, only mandatory from API 34) this task could not pin down
 *   to a specific API level from the current documentation -- returns null rather than guessing.
 *   The one caller ([com.fotoxplorr.app.share.SharePreparer]) treats null exactly like a muxer
 *   exception: "Failed, share the original instead" -- so an unrecognized codec never silently
 *   defeats the whole point of remuxing (removing the location) by going out unremuxed.
 */
fun containerFor(videoMime: String, audioMime: String?): Container? {
    val videoFitsMp4 = videoMime == MediaFormat.MIMETYPE_VIDEO_AVC || videoMime == MediaFormat.MIMETYPE_VIDEO_HEVC
    val videoFitsWebm = videoMime == MediaFormat.MIMETYPE_VIDEO_VP8 || videoMime == MediaFormat.MIMETYPE_VIDEO_VP9
    val audioFitsMp4 = audioMime == null ||
        audioMime == MediaFormat.MIMETYPE_AUDIO_AAC ||
        audioMime == MediaFormat.MIMETYPE_AUDIO_AMR_NB ||
        audioMime == MediaFormat.MIMETYPE_AUDIO_AMR_WB
    val audioFitsWebm = audioMime == null ||
        audioMime == MediaFormat.MIMETYPE_AUDIO_VORBIS ||
        audioMime == MediaFormat.MIMETYPE_AUDIO_OPUS
    return when {
        videoFitsMp4 && audioFitsMp4 -> Container.MP4
        videoFitsWebm && audioFitsWebm -> Container.WEBM
        else -> null
    }
}

/** The first video track and the first audio track, by index into [trackMimeTypes] (each track's
 * own MIME type, in extractor order) -- everything else (a subtitle track, a timed-metadata track
 * such as Apple's `mebx`, a second video or audio track) is dropped. Pure and index-based, rather
 * than taking a real [android.media.MediaExtractor], so it is testable on its own. Null means no
 * video track was found at all -- nothing for [com.fotoxplorr.app.video.remux] to copy. */
internal data class PrimaryTrackSelection(val videoTrackIndex: Int, val audioTrackIndex: Int?)

internal fun selectPrimaryTracks(trackMimeTypes: List<String>): PrimaryTrackSelection? {
    val videoIndex = trackMimeTypes.indexOfFirst { it.startsWith("video/") }
    if (videoIndex < 0) return null
    val audioIndex = trackMimeTypes.indexOfFirst { it.startsWith("audio/") }.takeIf { it >= 0 }
    return PrimaryTrackSelection(videoIndex, audioIndex)
}
