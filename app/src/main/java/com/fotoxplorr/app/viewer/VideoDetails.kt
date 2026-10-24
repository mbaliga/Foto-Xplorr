package com.fotoxplorr.app.viewer

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.fotoxplorr.app.media.MediaAsset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * What a video file says about itself, for the room's Video section: codec, profile, size, frame
 * rate, bitrate, rotation, HDR format, whether it has an audio track, and when it was recorded.
 * Everything is optional because containers disagree about what they carry; a null field means
 * the file did not say, never "no". The one exception is [hasAudio], which is only set when the
 * tracks were actually enumerated (or the container named an audio track).
 *
 * Built by [from], a pure function over the raw strings and numbers the platform returns, so the
 * mapping is unit-testable without a real video (TRAPS 67: the JVM tests cannot open a descriptor on
 * a fabricated URI). [readVideoDetails] is the only code that touches the platform.
 */
data class VideoDetails(
    /** The container's MIME type ("video/mp4"). */
    val containerMime: String? = null,
    /** The video track's MIME type ("video/hevc"); see [codecName]. */
    val videoMime: String? = null,
    /** The codec profile in words ("Main 10"), when the codec and the code are both known. */
    val profile: String? = null,
    /** The coded size, before [rotationDegrees] is applied; 0 when unknown. */
    val width: Int = 0,
    val height: Int = 0,
    /** Clockwise rotation the player applies, 0/90/180/270. */
    val rotationDegrees: Int = 0,
    /** The frame rate of the recorded file. */
    val frameRate: Double? = null,
    /** The rate the camera captured at, when the container records one (slow motion shows here). */
    val captureFps: Double? = null,
    /** Whole-file average bits per second (audio included). */
    val bitrate: Long? = null,
    val hdr: VideoHdr? = null,
    /** True/false only when the tracks were read; null when it could not be told. */
    val hasAudio: Boolean? = null,
    val audioMime: String? = null,
    val audioChannels: Int? = null,
    /** When the file was recorded, UTC epoch millis, from the container header. */
    val createdAtMillis: Long? = null,
    /** Whether the read produced anything at all; false is "couldn't read", not "empty". */
    val readOk: Boolean = true,
    /** True only for [PENDING]: nothing in this value is evidence of anything yet. */
    val pending: Boolean = false,
) {
    /** The size as displayed: width and height swapped when the video is turned a quarter. */
    val displayWidth: Int get() = if (rotationDegrees == 90 || rotationDegrees == 270) height else width
    val displayHeight: Int get() = if (rotationDegrees == 90 || rotationDegrees == 270) width else height

    companion object {
        /** The state before the read finishes. */
        val PENDING = VideoDetails(pending = true)

        /**
         * The pure mapper. [container] is what the metadata retriever returned (null when it could
         * not open the file), [video] and [audio] are the first track of each kind from the
         * extractor, and [tracksRead] says whether the extractor got as far as listing tracks, which
         * is what makes a missing [audio] a real "no audio track".
         */
        internal fun from(
            container: VideoContainerRaw?,
            video: VideoTrackRaw?,
            audio: AudioTrackRaw?,
            tracksRead: Boolean,
        ): VideoDetails {
            if (container == null && !tracksRead) return VideoDetails(readOk = false)
            val durationMs = container?.durationMs?.trim()?.toLongOrNull()
            val rotation = normalisedRotation(container?.rotation?.trim()?.toIntOrNull())
            val frameRate = video?.frameRate?.takeIf { it.isFinite() && it > 0.0 }
                ?: frameRateFromCount(container?.frameCount?.trim()?.toLongOrNull(), durationMs)
            val hasAudio = when {
                tracksRead -> audio != null
                container?.hasAudio.equals("yes", ignoreCase = true) -> true
                else -> null
            }
            return VideoDetails(
                containerMime = container?.mimeType?.trim()?.takeIf(String::isNotEmpty),
                videoMime = video?.mime?.trim()?.takeIf(String::isNotEmpty),
                profile = profileName(video?.mime, video?.profile),
                width = video?.width?.takeIf { it > 0 } ?: container?.width?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: 0,
                height = video?.height?.takeIf { it > 0 } ?: container?.height?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: 0,
                rotationDegrees = rotation,
                frameRate = frameRate,
                captureFps = container?.captureFps?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 },
                bitrate = container?.bitrate?.trim()?.toLongOrNull()?.takeIf { it > 0L }
                    ?: video?.bitrate?.takeIf { it > 0L },
                hdr = hdrKind(video),
                hasAudio = hasAudio,
                audioMime = audio?.mime?.trim()?.takeIf(String::isNotEmpty),
                audioChannels = audio?.channels?.takeIf { it > 0 },
                createdAtMillis = parseContainerDate(container?.date),
            )
        }

        /** A rotation the container wrote, folded to 0/90/180/270; anything that is not a quarter turn is 0. */
        internal fun normalisedRotation(raw: Int?): Int {
            val folded = ((raw ?: 0) % 360 + 360) % 360
            return if (folded % 90 == 0) folded else 0
        }

        private fun frameRateFromCount(frameCount: Long?, durationMs: Long?): Double? {
            if (frameCount == null || durationMs == null || frameCount <= 0L || durationMs <= 0L) return null
            return frameCount * 1_000.0 / durationMs
        }

        /** The HDR format, claimed only on evidence; null when the file does not say either way. */
        internal fun hdrKind(video: VideoTrackRaw?): VideoHdr? {
            if (video == null) return null
            val mime = video.mime?.lowercase()
            if (mime == "video/dolby-vision") return VideoHdr.DOLBY_VISION
            val plusProfile = (mime == "video/hevc" || mime == "video/av01") && video.profile == PROFILE_HDR10_PLUS
            val hdr10Profile = (mime == "video/hevc" || mime == "video/av01") && video.profile == PROFILE_HDR10
            return when (video.colorTransfer) {
                TRANSFER_ST2084 -> if (plusProfile) VideoHdr.HDR10_PLUS else VideoHdr.HDR10
                TRANSFER_HLG -> VideoHdr.HLG
                TRANSFER_SDR -> VideoHdr.SDR
                else -> when {
                    plusProfile -> VideoHdr.HDR10_PLUS
                    hdr10Profile -> VideoHdr.HDR10
                    else -> null
                }
            }
        }

        // MediaFormat colour-transfer codes and the HDR10 / HDR10+ codec profile codes. Written as
        // numbers so this file's mapper needs nothing from the platform.
        private const val TRANSFER_SDR = 3
        private const val TRANSFER_ST2084 = 6
        private const val TRANSFER_HLG = 7
        private const val PROFILE_HDR10 = 0x1000
        private const val PROFILE_HDR10_PLUS = 0x2000

        /** The codec profile in words for the codecs people meet; null for anything else. */
        internal fun profileName(mime: String?, code: Int?): String? {
            if (mime == null || code == null) return null
            return when (mime.lowercase()) {
                "video/avc" -> when (code) {
                    0x01 -> "Baseline"
                    0x02 -> "Main"
                    0x04 -> "Extended"
                    0x08 -> "High"
                    0x10 -> "High 10"
                    0x20 -> "High 4:2:2"
                    0x40 -> "High 4:4:4"
                    0x10000 -> "Constrained Baseline"
                    0x80000 -> "Constrained High"
                    else -> null
                }
                "video/hevc" -> when (code) {
                    0x01 -> "Main"
                    0x02 -> "Main 10"
                    0x04 -> "Main Still Picture"
                    PROFILE_HDR10 -> "Main 10 HDR10"
                    PROFILE_HDR10_PLUS -> "Main 10 HDR10+"
                    else -> null
                }
                "video/x-vnd.on2.vp9" -> when (code) {
                    0x01 -> "Profile 0"
                    0x02 -> "Profile 1"
                    0x04 -> "Profile 2"
                    0x08 -> "Profile 3"
                    else -> null
                }
                "video/av01" -> when (code) {
                    0x01 -> "Main 8-bit"
                    0x02 -> "Main 10-bit"
                    PROFILE_HDR10 -> "Main 10 HDR10"
                    PROFILE_HDR10_PLUS -> "Main 10 HDR10+"
                    else -> null
                }
                else -> null
            }
        }

        /**
         * A video MIME type as the name a person knows it by. An unfamiliar one falls back to its
         * subtype upper-cased rather than to nothing: "VIDEO/FOO" tells an enthusiast more than a
         * missing row does.
         */
        fun codecName(mime: String?): String? {
            val text = mime?.trim()?.lowercase()?.takeIf(String::isNotEmpty) ?: return null
            return when (text) {
                "video/avc" -> "H.264"
                "video/hevc" -> "HEVC (H.265)"
                "video/x-vnd.on2.vp8" -> "VP8"
                "video/x-vnd.on2.vp9" -> "VP9"
                "video/av01" -> "AV1"
                "video/dolby-vision" -> "Dolby Vision"
                "video/mp4v-es" -> "MPEG-4"
                "video/3gpp" -> "H.263"
                "video/mpeg2" -> "MPEG-2"
                else -> text.substringAfter('/').uppercase().takeIf(String::isNotEmpty)
            }
        }

        /** An audio MIME type as a name; same fallback as [codecName]. */
        fun audioCodecName(mime: String?): String? {
            val text = mime?.trim()?.lowercase()?.takeIf(String::isNotEmpty) ?: return null
            return when (text) {
                "audio/mp4a-latm" -> "AAC"
                "audio/mpeg" -> "MP3"
                "audio/opus" -> "Opus"
                "audio/vorbis" -> "Vorbis"
                "audio/ac3" -> "AC-3"
                "audio/eac3" -> "E-AC-3"
                "audio/raw" -> "PCM"
                "audio/3gpp" -> "AMR"
                "audio/amr-wb" -> "AMR-WB"
                "audio/flac" -> "FLAC"
                else -> text.substringAfter('/').uppercase().takeIf(String::isNotEmpty)
            }
        }

        /**
         * The retriever's DATE value, "20260801T223300.000Z" (UTC, fraction optional), as epoch
         * millis. A container that never had its clock set writes the MP4 epoch (1904) or the Unix
         * one (1970); anything before 1971 is read as "not recorded" rather than a date.
         */
        internal fun parseContainerDate(raw: String?): Long? {
            val match = DATE_PATTERN.matchEntire(raw?.trim() ?: return null) ?: return null
            val (year, month, day, hour, minute, second) = match.destructured
            if (year.toInt() < 1971) return null
            val millis = match.groupValues[7].padEnd(3, '0').take(3).toIntOrNull() ?: 0
            return runCatching {
                LocalDateTime.of(year.toInt(), month.toInt(), day.toInt(), hour.toInt(), minute.toInt(), second.toInt())
                    .plusNanos(millis * 1_000_000L)
                    .toInstant(ZoneOffset.UTC)
                    .toEpochMilli()
            }.getOrNull()
        }

        private val DATE_PATTERN = Regex("(\\d{4})(\\d{2})(\\d{2})T(\\d{2})(\\d{2})(\\d{2})(?:\\.(\\d{1,3}))?Z?")
    }
}

/** An HDR format a video can be recorded in. */
enum class VideoHdr(val label: String) {
    SDR("SDR"),
    HDR10("HDR10"),
    HDR10_PLUS("HDR10+"),
    HLG("HLG"),
    DOLBY_VISION("Dolby Vision"),
}

/** The metadata retriever's answers, each still the string it returned (null when absent). */
internal data class VideoContainerRaw(
    val mimeType: String? = null,
    val width: String? = null,
    val height: String? = null,
    val rotation: String? = null,
    val bitrate: String? = null,
    val durationMs: String? = null,
    val hasAudio: String? = null,
    val captureFps: String? = null,
    val frameCount: String? = null,
    val date: String? = null,
)

/** The first video track's format, as the extractor reports it. */
internal data class VideoTrackRaw(
    val mime: String? = null,
    val profile: Int? = null,
    val frameRate: Double? = null,
    val bitrate: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val colorTransfer: Int? = null,
)

/** The first audio track's format. */
internal data class AudioTrackRaw(
    val mime: String? = null,
    val channels: Int? = null,
)

/**
 * Reads [asset]'s video details off the main thread. The retriever and the extractor are each
 * opened separately and each released in `finally`, so one failing leaves the other's answers
 * standing; [VideoDetails.readOk] is false only when neither opened. A non-video returns an empty,
 * ok value: there is nothing here to be wrong about.
 */
suspend fun readVideoDetails(context: Context, asset: MediaAsset): VideoDetails {
    if (!asset.isVideo) return VideoDetails()
    return withContext(Dispatchers.IO) {
        val uri = asset.contentUri
        val container = readContainer(context, uri)
        val tracks = readTracks(context, uri)
        VideoDetails.from(container, tracks?.video, tracks?.audio, tracksRead = tracks != null)
    }
}

private class TracksRaw(val video: VideoTrackRaw?, val audio: AudioTrackRaw?)

private fun readContainer(context: Context, uri: Uri): VideoContainerRaw? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        fun key(code: Int): String? = retriever.extractMetadata(code)
        VideoContainerRaw(
            mimeType = key(MediaMetadataRetriever.METADATA_KEY_MIMETYPE),
            width = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),
            height = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),
            rotation = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION),
            bitrate = key(MediaMetadataRetriever.METADATA_KEY_BITRATE),
            durationMs = key(MediaMetadataRetriever.METADATA_KEY_DURATION),
            hasAudio = key(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO),
            captureFps = key(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE),
            frameCount = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                key(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
            } else {
                null
            },
            date = key(MediaMetadataRetriever.METADATA_KEY_DATE),
        )
    } catch (failure: Exception) {
        null
    } finally {
        // Not .close(): AutoCloseable on MediaMetadataRetriever is API 29+, below minSdk 26.
        runCatching { retriever.release() }
    }
}

private fun readTracks(context: Context, uri: Uri): TracksRaw? {
    val extractor = MediaExtractor()
    return try {
        extractor.setDataSource(context, uri, null)
        var video: VideoTrackRaw? = null
        var audio: AudioTrackRaw? = null
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            val mime = format.stringOrNull(MediaFormat.KEY_MIME) ?: continue
            when {
                mime.startsWith("video/") && video == null -> video = VideoTrackRaw(
                    mime = mime,
                    profile = format.intOrNull(MediaFormat.KEY_PROFILE),
                    frameRate = format.numberOrNull(MediaFormat.KEY_FRAME_RATE),
                    bitrate = format.intOrNull(MediaFormat.KEY_BIT_RATE)?.toLong(),
                    width = format.intOrNull(MediaFormat.KEY_WIDTH),
                    height = format.intOrNull(MediaFormat.KEY_HEIGHT),
                    colorTransfer = format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER),
                )
                mime.startsWith("audio/") && audio == null -> audio = AudioTrackRaw(
                    mime = mime,
                    channels = format.intOrNull(MediaFormat.KEY_CHANNEL_COUNT),
                )
            }
        }
        TracksRaw(video, audio)
    } catch (failure: Exception) {
        null
    } finally {
        runCatching { extractor.release() }
    }
}

// MediaFormat's getters throw for an absent key, and getInteger throws for a key stored as a
// float (a frame rate is either); these return null instead so one odd key cannot lose the rest.
private fun MediaFormat.stringOrNull(key: String): String? =
    if (containsKey(key)) runCatching { getString(key) }.getOrNull() else null

private fun MediaFormat.intOrNull(key: String): Int? =
    if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

private fun MediaFormat.numberOrNull(key: String): Double? {
    if (!containsKey(key)) return null
    return runCatching { getInteger(key).toDouble() }
        .recoverCatching { getFloat(key).toDouble() }
        .getOrNull()
}
