package com.fotoxplorr.app.editor

import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build

// HEIC export

/**
 * Whether this device can actually encode HEIC right now.
 *
 * Two conditions, both required: API 28 (the floor for `android.media.MediaMuxer`'s HEIC
 * container support, which is what `androidx.heifwriter` wraps) AND a real HEVC encoder present
 * in [MediaCodecList] — HEIC's image coding is HEVC intra frames, so no HEVC encoder means no
 * HEIC encoder regardless of API level. Plenty of real API 28+ devices (budget chipsets, several
 * emulator images) ship no hardware or software HEVC encoder at all; offering a format whose
 * export would throw partway through is worse than not offering it, which is why every call site
 * that lists [OutputFormat.HEIC] as a choice checks this first rather than trusting the API level
 * alone.
 *
 * Queried fresh rather than cached: [MediaCodecList] is cheap to enumerate (it is not probing
 * hardware, only reading the platform's own static codec registry) and this is called once per
 * editor session opening its export controls, not per frame.
 */
fun isHeicExportSupported(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
    return runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder &&
                info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) }
        }
    }.getOrDefault(false)
}
