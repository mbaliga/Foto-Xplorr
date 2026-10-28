package com.fotoxplorr.app.reel

import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.ExportException
import java.io.IOException

/** What the job does about an export that failed. */
enum class ReelFailureKind {
    /** The phone ran out of memory: take the next, smaller rung. */
    MEMORY,

    /** A codec, the compositor or the muxer gave up: take the next rung, which asks less of the phone. */
    STEP_DOWN,

    /** The sound could not be decoded, processed or encoded: ask the person whether to make the reel without it. */
    AUDIO,

    /** A photo, clip or the sound file could not be opened or read: a smaller rung will not help. */
    SOURCE,

    /** The file could not be written: not a thing to retry. */
    STORAGE,
}

/** Sorting an export's failure into what to do next. Pure: the Media3 error code is an Int, so tests need no Transformer. */
@UnstableApi
object ReelFailures {

    /**
     * [errorCode] is `ExportException.errorCode`; [codecIsVideo] is `codecInfo?.isVideo` (null when the
     * failure was not a codec's). [soundInPlan] says whether the render carried any sound: an audio fault in
     * a render with no sound cannot be the sound's fault and is treated as any other.
     */
    fun classify(errorCode: Int, codecIsVideo: Boolean?, soundInPlan: Boolean): ReelFailureKind {
        val audioCodec = codecIsVideo == false
        return when (errorCode) {
            ExportException.ERROR_CODE_AUDIO_PROCESSING_FAILED -> if (soundInPlan) ReelFailureKind.AUDIO else ReelFailureKind.STEP_DOWN
            ExportException.ERROR_CODE_DECODER_INIT_FAILED,
            ExportException.ERROR_CODE_DECODING_FAILED,
            ExportException.ERROR_CODE_ENCODER_INIT_FAILED,
            ExportException.ERROR_CODE_ENCODING_FAILED,
            ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED,
            -> if (audioCodec && soundInPlan) ReelFailureKind.AUDIO else ReelFailureKind.STEP_DOWN
            // A format this phone cannot decode stays unreadable at any size.
            ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                if (audioCodec && soundInPlan) ReelFailureKind.AUDIO else ReelFailureKind.SOURCE
            ExportException.ERROR_CODE_IO_FILE_NOT_FOUND,
            ExportException.ERROR_CODE_IO_NO_PERMISSION,
            ExportException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            ExportException.ERROR_CODE_IO_UNSPECIFIED,
            -> ReelFailureKind.SOURCE
            ExportException.ERROR_CODE_MUXING_FAILED -> ReelFailureKind.STORAGE
            else -> ReelFailureKind.STEP_DOWN
        }
    }

    /** [classify] for a real export failure: an out-of-memory error anywhere in its causes is [ReelFailureKind.MEMORY]. */
    fun classify(failure: ExportException, soundInPlan: Boolean): ReelFailureKind {
        var t: Throwable? = failure
        var depth = 0
        while (t != null && depth++ < 8) {
            if (t is OutOfMemoryError) return ReelFailureKind.MEMORY
            t = t.cause
        }
        return classify(failure.errorCode, failure.codecInfo?.isVideo, soundInPlan)
    }

    /** True when [failure] or something it was caused by is the phone running out of space. */
    fun isOutOfSpace(failure: Throwable?): Boolean {
        var t = failure
        var depth = 0
        while (t != null && depth++ < 8) {
            if (t is ReelNoSpaceException) return true
            val text = t.message.orEmpty()
            if (t is IOException && (text.contains("ENOSPC") || text.contains("No space left", ignoreCase = true))) return true
            t = t.cause
        }
        return false
    }
}

/** The phone is nearly out of space and the reel could not be written. */
class ReelNoSpaceException(cause: Throwable? = null) : IOException("the phone has no space left for the reel", cause)

/** A photo or clip became protected before the reel was saved. */
class ReelProtectedException : IOException("a photo or clip became protected before the reel was saved")
