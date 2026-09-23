package com.fotoxplorr.app.audio

import android.content.ContentValues
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.fotoxplorr.app.media.MediaKind
import com.fotoxplorr.app.media.mediaStoreRelativePath
import com.fotoxplorr.app.video.EncodedTrack
import com.fotoxplorr.app.video.writeSamples
import com.fotoxplorr.app.videoeditor.VideoEditRecipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Re-encodes a standalone audio file to AAC in an M4A container — Save As for audio, using the
 * exact same [AudioTranscoder] the video pipeline already uses for a video's own audio track (see
 * `docs/adr/ADR-008-video-transcode-pipeline.md`), just muxed alone rather than alongside a video
 * track. The video counterpart is
 * [com.fotoxplorr.app.video.VideoConversionWriter]; this class is its audio-only sibling, not a
 * duplicate of its reasoning — see that class's doc for why H.264/AAC (here, just AAC) is the
 * fixed target rather than a chosen one, and for the same "not verified on a real device" caveat
 * this class inherits by construction (it calls the identical, equally-unexercised transcode
 * loop).
 */
class AudioConversionWriter(context: Context) {
    private val appContext = context.applicationContext

    suspend fun convertToAac(source: AudioAsset): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val track = transcodeToAac(source)

            val name = convertedAudioName(source.displayName)
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.MIME_TYPE, TARGET_MIME_TYPE)
                put(MediaStore.Audio.Media.IS_MUSIC, 1)
                put(MediaStore.Audio.Media.DATE_ADDED, System.currentTimeMillis() / 1_000)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Alongside the source when its own folder is one MediaProvider still accepts
                    // an insert into, otherwise this app's own fallback folder — see
                    // mediaStoreRelativePath's own doc for why a WhatsApp or Download source
                    // cannot just reuse its source path verbatim here.
                    put(
                        MediaStore.Audio.Media.RELATIVE_PATH,
                        mediaStoreRelativePath(source.relativePath, MediaKind.AUDIO),
                    )
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
            }

            val resolver = appContext.contentResolver
            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Android would not create a new audio file")

            try {
                resolver.openFileDescriptor(uri, "rw")?.use { descriptor ->
                    val muxer = MediaMuxer(descriptor.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    try {
                        val muxerTrackIndex = muxer.addTrack(track.format)
                        muxer.start()
                        muxer.writeSamples(muxerTrackIndex, track)
                        muxer.stop()
                    } finally {
                        runCatching { muxer.release() }
                    }
                } ?: error("Could not open the new file for writing")
            } catch (t: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw t
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) },
                    null,
                    null,
                )
            }
            uri
        }
    }

    private suspend fun transcodeToAac(source: AudioAsset): EncodedTrack {
        val extractor = MediaExtractor().apply { setDataSource(appContext, source.contentUri, null) }
        try {
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("${source.displayName} has no audio track")

            // Unlike VideoTranscoder.runTranscode, nothing here previously bounded how long a
            // source could be before AudioTranscoder.decodeToPcm buffered its whole decoded PCM
            // track in memory (P0-11) -- the same class of unbounded-memory risk, just for audio.
            // Read off the track's own declared duration first, falling back to the MediaStore
            // scan's value only when the track itself declares nothing, mirroring
            // VideoTranscoder's own precedent exactly.
            val trackDurationMs = extractor.getTrackFormat(trackIndex)
                .let { format -> if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1_000L else null }
                ?: source.durationMillis
            check(trackDurationMs in 1..MAX_CONVERT_DURATION_MS) {
                "${source.displayName} is too long to convert in this build " +
                    "(${trackDurationMs / 1_000}s, limit ${MAX_CONVERT_DURATION_MS / 1_000}s)"
            }

            return AudioTranscoder.transcodeTrack(extractor, trackIndex, VideoEditRecipe(), appContext.cacheDir)
        } finally {
            runCatching { extractor.release() }
        }
    }

    private companion object {
        const val TARGET_MIME_TYPE = "audio/mp4"

        /** [AudioTranscoder.decodeToPcm]'s decoded PCM is held in memory before encoding begins
         *  (unlike the compressed [com.fotoxplorr.app.video.EncodedTrack] output, which spills to
         *  disk as of P0-11) -- an hour of even high-quality stereo PCM is still well under a
         *  phone's per-process memory budget, so this generously covers a real standalone audio
         *  file (podcasts, long recordings) without the OOM risk an unbounded one would carry. */
        const val MAX_CONVERT_DURATION_MS = 60 * 60 * 1_000L
    }
}

/** Mirrors `com.fotoxplorr.app.video.convertedName`'s exact shape, for an audio conversion. */
internal fun convertedAudioName(displayName: String, marker: String = "converted"): String {
    val trimmed = displayName.trim().ifBlank { "audio" }
    val dot = trimmed.lastIndexOf('.')
    val stem = if (dot > 0) trimmed.substring(0, dot) else trimmed
    val base = stem.removeSuffix("-$marker")
    return "$base-$marker.m4a"
}
