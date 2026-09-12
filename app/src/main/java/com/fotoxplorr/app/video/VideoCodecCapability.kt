package com.fotoxplorr.app.video

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
