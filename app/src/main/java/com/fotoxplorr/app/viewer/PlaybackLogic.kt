package com.fotoxplorr.app.viewer

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player

/*
 * The decisions [VideoPlayer] makes about WHEN and WHETHER, pulled out as pure functions so they
 * are pinned by plain JUnit (`PlaybackLogicTest`) rather than argued about on a device this
 * project has never had. Everything here takes plain values -- the Int constants from
 * [Player] and [PlaybackException] are compile-time inlined, so nothing in this file needs a real
 * player, a Context or Robolectric.
 */

/**
 * How long a freshly composed [VideoPlayer] waits before it builds a player, resolves sidecar
 * subtitles and prepares. A filmstrip scrub or a fast page swipe composes (and discards) a
 * [VideoPlayer] for every video the finger passes over; paying for an ExoPlayer, a MediaStore
 * query and a codec per pass is what made scrubbing past a video stutter. 250 ms is long enough
 * that a finger in motion never gets there and short enough that a deliberate stop feels instant.
 */
internal const val ARMED_START_DELAY_MS = 250L

/** Upper bound on the sidecar-subtitle lookup before playback starts without it. A subtitle is
 *  never worth holding the video back for. */
internal const val SIDECAR_QUERY_TIMEOUT_MS = 1_500L

/** Key-moment indexing starts this long AFTER the player first reports ready, so the metadata
 *  retriever's frame decodes never compete with the player's own first-frame decode. */
internal const val INDEXER_DELAY_AFTER_READY_MS = 1_500L

/**
 * True while a [VideoPlayer] that is not armed yet should arm: not already armed, the user's finger
 * is not on the filmstrip, and the host is [started] (see [playbackMayStart]). Holding the arming
 * back while the strip is touched is what stops a slow scrub that happens to rest on a video for
 * 300 ms from starting its audio under the finger. Holding it back while stopped is what stops a
 * video that comes up after Home or the screen locking (a slideshow advancing, a finger released
 * late) from building a player nothing would ever pause, with audio focus taken behind the home
 * screen: the ON_STOP pause only acts on a player that already exists.
 */
internal fun mayArm(armed: Boolean, scrubSuspended: Boolean, started: Boolean): Boolean =
    !armed && !scrubSuspended && started

/**
 * Whether a video may begin playing now. The lifecycle being at least STARTED is the rule; a
 * picture-in-picture window counts as started too, because it is the one state where the activity
 * is paused (and on some devices stopped) while playback is wanted -- the same exception
 * [VideoPlayer]'s ON_STOP pause makes.
 */
internal fun playbackMayStart(lifecycleStarted: Boolean, inPictureInPicture: Boolean): Boolean =
    lifecycleStarted || inPictureInPicture

/** The `playWhenReady` a freshly built player starts with: autoplay, but never while [started] is
 *  false, so a late-built player waits (poster plus play glyph) instead of playing unseen. */
internal fun initialPlayWhenReady(autoplay: Boolean, started: Boolean): Boolean = autoplay && started

/**
 * The position a seek request is clamped to. The upper bound is the duration only when it is known:
 * an unknown one (0 from a MediaStore row the scanner never probed, before the player has a
 * timeline) used to make the range 0..0, so every seek -- double-tap +10 s, "Resume from 1:23" --
 * went to 0:00. With no known end the player clamps a too-large seek itself.
 */
internal fun clampSeekMs(ms: Long, durationMs: Long): Long =
    ms.coerceIn(0L, if (durationMs > 0L) durationMs else Long.MAX_VALUE)

/**
 * Whether the transport should offer "play" rather than "pause". The same rule as
 * `androidx.media3.common.util.Util.shouldShowPlayButton`: it keys on the player's INTENT
 * (`playWhenReady`), not on `isPlaying`, so a video that is buffering still offers Pause, and an
 * idle or finished one offers Play. `isPlaying` is false while buffering, which is what made the old
 * transport impossible to pause until the first frame.
 */
internal fun transportShowsPlay(playWhenReady: Boolean, playbackState: Int): Boolean =
    !playWhenReady || playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED

/**
 * Whether the big centre play glyph is drawn. Stricter than [transportShowsPlay] in one way: an
 * idle player that WANTS to play (autoplay, between creation and the first prepare, or a silent
 * retry in flight) shows nothing instead of flashing a play button for a moment -- and nothing at
 * all once a failure panel owns the centre.
 */
internal fun centreGlyphVisible(playWhenReady: Boolean, playbackState: Int, failed: Boolean): Boolean =
    !failed && (!playWhenReady || playbackState == Player.STATE_ENDED)

/**
 * Whether playback is actually in progress or about to be: the player wants to play and has not
 * stopped, ended or failed. Drives keep-screen-on and [VideoPlayer]'s `onPlaybackActive`, which
 * lets the slideshow wait for a video instead of cutting it off.
 */
internal fun isPlaybackActive(playWhenReady: Boolean, playbackState: Int): Boolean =
    playWhenReady && (playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_READY)

/**
 * The duration to show and seek against. The player's own value wins the moment it is real; until
 * then the last known value is kept, and failing that the MediaStore value, which can be 0 for rows
 * the scanner never probed. [C.TIME_UNSET] (what ExoPlayer reports before it has a timeline) and
 * anything non-positive count as "not known".
 */
internal fun resolveDurationMs(playerDurationMs: Long, currentMs: Long, mediaStoreMs: Long): Long = when {
    playerDurationMs != C.TIME_UNSET && playerDurationMs > 0L -> playerDurationMs
    currentMs > 0L -> currentMs
    mediaStoreMs > 0L -> mediaStoreMs
    else -> 0L
}

/** An asset is "in the catalogue" for the video player's purposes when the host allows catalogue
 *  writes AND the asset is not one this app was merely handed (open-with, share-to). */
internal fun isCatalogued(catalogueActions: Boolean, isExternal: Boolean): Boolean =
    catalogueActions && !isExternal

/**
 * Whether a resume position may be read or written for this video at all. Synthetic ids (negative,
 * see `syntheticNegativeMediaId`, and `MediaId.EXTERNAL`) belong to files this app does not own,
 * so nothing is remembered for them even if a caller forgets to check [isCatalogued].
 */
internal fun resumeEligible(catalogued: Boolean, mediaIdValue: Long): Boolean = catalogued && mediaIdValue >= 0L

/** What a "save the resume position now" trigger should do to the stored value. */
internal enum class ResumeWrite { SKIP, SAVE, CLEAR }

/**
 * The single policy behind every resume-save trigger (pause, ON_STOP, the poll loop's `finally`,
 * dispose). [engaged] is whether the viewer has played or seeked this video in this session: an
 * open-and-leave must NOT overwrite a remembered position with 0:00, which the store would treat
 * as "not worth keeping" and clear.
 *
 *  - not eligible, never engaged, or duration still unknown: leave the store alone;
 *  - ended: clear, a finished video never offers to resume itself;
 *  - otherwise save (the store itself drops positions too close to either end).
 */
internal fun resumeWriteFor(
    eligible: Boolean,
    playbackState: Int,
    engaged: Boolean,
    durationMs: Long,
): ResumeWrite = when {
    !eligible -> ResumeWrite.SKIP
    playbackState == Player.STATE_ENDED && engaged -> ResumeWrite.CLEAR
    !engaged -> ResumeWrite.SKIP
    durationMs <= 0L -> ResumeWrite.SKIP
    else -> ResumeWrite.SAVE
}

/** Where a video starts and whether the "Resume from" chip is offered. */
internal data class ResumeStart(val startPositionMs: Long, val chipPositionMs: Long?)

/**
 * With autoplay on a video starts by itself, so a "Resume from 1:23" chip would flash and vanish
 * while the clip restarted from 0:00 -- resume silently instead. With autoplay off the video waits
 * at 0:00 and the chip is the way back.
 */
internal fun resumeStartFor(rememberedMs: Long?, autoplay: Boolean): ResumeStart = when {
    rememberedMs == null || rememberedMs <= 0L -> ResumeStart(0L, null)
    autoplay -> ResumeStart(rememberedMs, null)
    else -> ResumeStart(0L, rememberedMs)
}

/** The family of a playback failure, which decides the wording and the recovery offers. */
internal enum class FailureKind { NOT_FOUND, NO_PERMISSION, IO, UNSUPPORTED_FORMAT, DECODER, CORRUPT, UNKNOWN }

/** What the error panel shows for a failure. [message] is plain language, never an error code. */
internal data class PlaybackFailure(
    val kind: FailureKind,
    val message: String,
    val canRetry: Boolean,
    val offerOpenWith: Boolean,
)

/**
 * Classifies a [PlaybackException.errorCode]. Retry is offered where trying again can plausibly
 * help (a transient read failure, a decoder that was busy, a permission the user just granted) and
 * withheld where it cannot (a missing file, an unsupported or damaged stream). Open-with is offered
 * wherever ANOTHER app might still play the file, which excludes only the two cases that are about
 * this app's access to it.
 */
internal fun playbackFailureFor(errorCode: Int): PlaybackFailure {
    val kind = when (errorCode) {
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> FailureKind.NOT_FOUND
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> FailureKind.NO_PERMISSION
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> FailureKind.CORRUPT
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> FailureKind.UNSUPPORTED_FORMAT
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED -> FailureKind.DECODER
        in IO_ERROR_RANGE -> FailureKind.IO
        else -> FailureKind.UNKNOWN
    }
    return when (kind) {
        FailureKind.NOT_FOUND -> PlaybackFailure(
            kind, "This video can't be found. It may have been moved or deleted.",
            canRetry = false, offerOpenWith = false,
        )
        FailureKind.NO_PERMISSION -> PlaybackFailure(
            kind, "Fotoz doesn't have permission to read this video.",
            canRetry = true, offerOpenWith = false,
        )
        FailureKind.IO -> PlaybackFailure(
            kind, "The video couldn't be read.",
            canRetry = true, offerOpenWith = true,
        )
        FailureKind.UNSUPPORTED_FORMAT -> PlaybackFailure(
            kind, "This device can't play this video's format.",
            canRetry = false, offerOpenWith = true,
        )
        FailureKind.DECODER -> PlaybackFailure(
            kind, "This device couldn't decode the video.",
            canRetry = true, offerOpenWith = true,
        )
        FailureKind.CORRUPT -> PlaybackFailure(
            kind, "The video file looks damaged.",
            canRetry = false, offerOpenWith = true,
        )
        FailureKind.UNKNOWN -> PlaybackFailure(
            kind, "The video couldn't be played.",
            canRetry = true, offerOpenWith = true,
        )
    }
}

private val IO_ERROR_RANGE = PlaybackException.ERROR_CODE_IO_UNSPECIFIED..PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE

/**
 * Whether a failure should be answered by silently reloading the video WITHOUT its sidecar
 * subtitles, once. A subtitle file that cannot be opened is a load error on the merged source and
 * surfaces as a fatal [PlaybackException]; a bad `.srt` must never cost the owner a good video.
 * Only read/parse-family errors qualify (a decoder error is about the video, not the subtitles),
 * only when sidecars were actually attached, and never twice.
 */
internal fun shouldRetryWithoutSubtitles(errorCode: Int, hadSidecars: Boolean, alreadyRetried: Boolean): Boolean {
    if (!hadSidecars || alreadyRetried) return false
    return errorCode == PlaybackException.ERROR_CODE_UNSPECIFIED ||
        errorCode in IO_ERROR_RANGE ||
        errorCode in PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED..PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED
}

/**
 * Whether the second transport row (speed, subtitles, audio, A-B loop, PiP) folds behind a "more"
 * toggle. The transport stacks above the filmstrip band ([bottomInsetDp]); in a 360dp-tall
 * landscape window both rows plus the strip would cover about half the picture. Collapses when the
 * fixed chrome (inset, padding, two button rows) would take more than 40% of the height.
 */
internal fun shouldCollapseSecondRow(screenHeightDp: Int, bottomInsetDp: Float): Boolean {
    if (bottomInsetDp <= 0f || screenHeightDp <= 0) return false
    val chromeDp = bottomInsetDp + TRANSPORT_PADDING_DP + 2 * TRANSPORT_ROW_DP + TRANSPORT_GAP_DP
    return chromeDp > COLLAPSE_CHROME_FRACTION * screenHeightDp
}

internal const val TRANSPORT_PADDING_DP = 14f
internal const val TRANSPORT_ROW_DP = 36f
internal const val TRANSPORT_GAP_DP = 10f
private const val COLLAPSE_CHROME_FRACTION = 0.40f
