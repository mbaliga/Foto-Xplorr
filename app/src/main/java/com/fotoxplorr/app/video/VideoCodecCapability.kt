package com.fotoxplorr.app.video

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat

/**
 * Whether this device can actually reach [VideoTranscoder]'s fixed H.264/AAC/MP4 target.
 *
 * Android's own compatibility definition requires both an AVC encoder and an AAC encoder on every
 * API 26+ device this app supports, so this should never come back `false` in practice — but
 * "should never" is not "cannot", and this app's established convention (see
 * [com.fotoxplorr.app.formats.MediaFormat.isLikelyDecodable]) is to check a real capability rather
 * than assume the platform spec was followed, so a genuinely non-compliant device gets a clear,
 * specific failure message instead of an opaque [android.media.MediaCodec.CodecException] from
 * deep inside the transcode pipeline.
 */
internal fun deviceSupportsH264AacEncoding(): Boolean {
    val encoderMimeTypes = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        .filter { it.isEncoder }
        .flatMap { it.supportedTypes.asList() }
    return MediaFormat.MIMETYPE_VIDEO_AVC in encoderMimeTypes && MediaFormat.MIMETYPE_AUDIO_AAC in encoderMimeTypes
}

/**
 * Whether SOME H.264 encoder on this device can actually produce output at [width]x[height] —
 * [deviceSupportsH264AacEncoding] only confirms an AVC encoder exists at all, not that it accepts a
 * given source's own resolution: [MediaCodecInfo.VideoCapabilities.isSizeSupported] is itself
 * resolution-dependent (a hardware encoder commonly caps out below 4K even when it otherwise
 * supports the codec). Checked against every AVC encoder this device reports, not just the first,
 * the same way [deviceSupportsH264AacEncoding] checks the full codec list rather than assuming the
 * first encoder found is representative (P0-11).
 */
internal fun avcEncoderSupportsSize(width: Int, height: Int): Boolean =
    MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        .filter { it.isEncoder }
        .mapNotNull { info -> runCatching { info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC) }.getOrNull() }
        .any { capabilities -> capabilities.videoCapabilities?.isSizeSupported(width, height) == true }

/**
 * [value] rounded DOWN to the nearest even number (P0-11) — an AVC encoder's macroblock/chroma
 * subsampling requires even width and height; a source with an odd dimension (some screen
 * recorders and a handful of camera apps produce these) would otherwise fail
 * [MediaCodecInfo.VideoCapabilities.isSizeSupported] outright even though the device can encode
 * every size one pixel away from it. Rounding down crops at most one row or column, imperceptible
 * on any real video, rather than refusing a source that is one pixel off from convertible.
 */
internal fun evenSize(value: Int): Int = value - (value % 2)

/**
 * HEVC/VP9/AV1 `MediaCodecInfo.CodecProfileLevel` profile values that mean "this stream is HDR",
 * restricted to the ones introduced at or before this app's own minSdk (26) so referencing them
 * needs no per-constant API-level guard. The HDR10+ and AV1 HDR profile constants (API 29) are a
 * known, accepted gap — [hdrRefusalReason]'s own `colorTransfer` check is the primary, more
 * reliably populated signal regardless (P0-11).
 */
private val HDR_PROFILES = setOf(
    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10,
    MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR,
    MediaCodecInfo.CodecProfileLevel.VP9Profile3HDR,
)

/**
 * Why [colorTransfer]/[profile] describe an HDR source, for [VideoTranscoder]'s pre-flight refusal
 * message, or null when they don't (or say nothing either way) — this app's fixed H.264/AAC/MP4
 * target is SDR only, and re-encoding an HDR source through an SDR pipeline would silently wash out
 * or clip its highlights rather than fail cleanly, which is worse than refusing up front (P0-11).
 *
 * A pure decision over already-extracted [MediaFormat] values (never a [MediaFormat] itself, an
 * Android class this function has no need to touch), so it is unit-testable without Robolectric —
 * see [VideoTranscoder]'s own pre-flight check for where `colorTransfer`/`profile` actually come
 * from ([android.media.MediaFormat.KEY_COLOR_TRANSFER]/[android.media.MediaFormat.KEY_PROFILE]).
 */
internal fun hdrRefusalReason(colorTransfer: Int?, profile: Int?): String? {
    val transferIsHdr = colorTransfer == MediaFormat.COLOR_TRANSFER_ST2084 ||
        colorTransfer == MediaFormat.COLOR_TRANSFER_HLG
    val profileIsHdr = profile != null && profile in HDR_PROFILES
    return if (transferIsHdr || profileIsHdr) "HDR video conversion isn't supported yet" else null
}
