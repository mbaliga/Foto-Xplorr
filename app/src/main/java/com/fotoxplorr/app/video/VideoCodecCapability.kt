package com.fotoxplorr.app.video

import android.media.MediaCodecList
import android.media.MediaFormat

/**
 * Real, on-device encoder capability checks. Android's own compatibility definition guarantees an
 * AVC (H.264) and an AAC encoder on every API 26+ device this app supports, but "should never be
 * false" is not "cannot be false" — this app's established convention (see
 * [com.fotoxplorr.app.formats.MediaFormat.isLikelyDecodable]) is to check the real capability
 * rather than assume the platform spec was followed, so a genuinely non-compliant device gets a
 * clear, specific failure message rather than an opaque exception from deep inside Transformer.
 * HEVC (H.265) is NOT guaranteed at all, which is why [VideoExporter] checks
 * [deviceSupportsVideoEncoder] before honouring [VideoCodec.HEVC] and the editor only offers HEVC
 * once this has said yes.
 */
internal fun deviceEncoderMimeTypes(): Set<String> =
    MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        .filter { it.isEncoder }
        .flatMap { it.supportedTypes.asList() }
        .toSet()

internal fun deviceSupportsH264AacEncoding(): Boolean {
    val encoderMimeTypes = deviceEncoderMimeTypes()
    return MediaFormat.MIMETYPE_VIDEO_AVC in encoderMimeTypes && MediaFormat.MIMETYPE_AUDIO_AAC in encoderMimeTypes
}

internal fun deviceSupportsVideoEncoder(codec: VideoCodec): Boolean =
    codec.mimeType in deviceEncoderMimeTypes()
