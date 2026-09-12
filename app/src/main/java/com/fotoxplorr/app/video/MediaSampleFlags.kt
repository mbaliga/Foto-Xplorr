package com.fotoxplorr.app.video

import android.media.MediaCodec
import android.media.MediaExtractor

/**
 * Translates [MediaExtractor]'s sample flags into [MediaCodec.BufferInfo] flags.
 *
 * They are DIFFERENT flag sets that happen to share bit values, which is exactly what makes
 * passing one straight through look correct and be wrong. `SAMPLE_FLAG_SYNC` is bit 1 and so is
 * `BUFFER_FLAG_KEY_FRAME`, so keyframes survive by luck — but `SAMPLE_FLAG_ENCRYPTED` is bit 2,
 * which [MediaCodec.BufferInfo] reads as `BUFFER_FLAG_CODEC_CONFIG`, and `SAMPLE_FLAG_PARTIAL_FRAME`
 * is bit 4, which it reads as `BUFFER_FLAG_END_OF_STREAM`. A single partial-frame sample would
 * tell a muxer the track is over. Lint's `WrongConstant` check caught exactly this pass-through in
 * [com.fotoxplorr.app.moments.ClipExporter] (see `docs/TRAPS.md` #24), and it was right to —
 * pulled out here, once this pipeline needed the identical translation for its own audio-copy
 * path, so the fix lives in one tested place rather than two independently-written copies of the
 * same easy mistake.
 *
 * Only the keyframe bit is carried across. It is the one flag a [android.media.MediaMuxer] or a
 * [android.media.MediaCodec] encoder input actually uses (to build the sync-sample table a player
 * seeks by, or to know a frame may be dropped under load); an encrypted sample cannot be
 * stream-copied into a plain MP4 in any case, and a partial-frame mark has no meaning once samples
 * are being written to a new container rather than assembled from the one they came from.
 */
internal fun muxerBufferFlagsFor(sampleFlags: Int): Int =
    if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
