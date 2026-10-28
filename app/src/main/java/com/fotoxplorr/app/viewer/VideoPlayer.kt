package com.fotoxplorr.app.viewer

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.util.Log
import android.util.Rational
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaImage
import com.fotoxplorr.app.moments.MomentSource
import com.fotoxplorr.app.moments.VideoMoment
import com.fotoxplorr.app.moments.VideoMomentIndexer
import com.fotoxplorr.app.moments.VideoMomentStore
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The video player, its own transport chrome, and the key-moment feature layered over it — now on
 * Media3 ExoPlayer rather than `android.widget.VideoView`. See
 * `docs/adr/ADR-008-video-transcode-pipeline.md` (revision 2) for why this dependency, forbidden
 * by this file's own previous KDoc, is now the adopted direction: VideoView's budget (no position
 * stream, no volume control, no track selection at all) was the ceiling this file worked around
 * for as long as no real media library was allowed in; none of that ceiling is a real constraint
 * any more.
 *
 * ## What is new here
 * Play/pause/seek/mute/key-moments/clip-share all carry over unchanged in spirit (ExoPlayer
 * replaces the plumbing, not the feature). New on top of that: speed presets with a pitch-preserve
 * toggle, embedded AND sidecar (`.srt`/`.vtt`/`.ass`/`.ssa`) subtitle selection, audio track
 * selection, double-tap seek, edge-swipe brightness/volume, a long-press-to-2x hold, an A-B loop,
 * a per-video resume position, picture-in-picture, and a [VideoTrackInfo] readout for the details
 * room. [onSnapshotChanged] and [onTrackInfoChanged] are additive, default-no-op callbacks a host
 * can wire up without anything else in this file changing.
 *
 * ## Starting: armed, not mounted
 * A filmstrip scrub or a quick page swipe composes (and discards) a player for every video the
 * finger passes. So [VideoPlayer] itself is only a poster until it has been on screen for
 * [ARMED_START_DELAY_MS] without the finger on the filmstrip and with the host started ([mayArm],
 * [playbackMayStart]: a video that comes up after Home or the screen locking must not play unseen,
 * and the ON_STOP pause only reaches a player that already exists); only then does
 * [ArmedVideoPlayer] build an ExoPlayer, look up sidecar subtitles, prepare, and (later still)
 * index key moments. The poster stays up until ExoPlayer reports its first rendered frame, so the
 * hand-over from the still thumbnail to live video does not flash black.
 *
 * ## Transport state is intent, not `isPlaying`
 * Play/pause is derived from `playWhenReady` + the playback state ([transportShowsPlay]) and acted
 * on with Media3's own `Util.handlePlayPauseButtonAction`: Pause works while buffering, Play from
 * an idle or finished player re-prepares or replays, and neither depends on a duration being known.
 * `isPlaying` is false while buffering, which made the old transport impossible to pause until the
 * first frame.
 *
 * ## Failing, and leaving
 * A playback error shows a panel (Retry / Open with) instead of a black rectangle with a Play glyph
 * that did nothing. A fatal error while sidecar subtitles are attached is retried ONCE without them
 * ([shouldRetryWithoutSubtitles]): a bad `.srt` must never cost a good video. The resume position
 * is written on pause, on ON_STOP, when the poll loop ends, and just before release -- not only on
 * dispose, which never runs when the process is killed behind a backgrounded viewer.
 *
 * ## Key moments, end to end
 * Opening a catalogued video (a) reloads [VideoMomentStore] so previously-saved markers for THIS
 * device show up, and (b) fires [VideoMomentIndexer.index] for this asset once playback has been
 * ready for a moment -- idempotent by the indexer's own design (`VideoMomentStore.hasBeenScanned`
 * makes an already-scanned video a fast no-op), so calling it unconditionally is simpler than this
 * file re-implementing that same guard. Once indexing finishes, [VideoMomentStore.replaceAuto] and
 * [VideoMomentStore.markScanned] both refresh the store's own `StateFlow`, which [momentsByAsset]
 * below is already collecting -- the highlighted stretches on [MomentScrubber] and the "Key moment"
 * pill simply appear on their own once detection completes, with no polling or manual refresh wired
 * here for it. Indexing, the manual "mark a key moment" button and the resume position are all
 * skipped for an asset this app does not own ([isCatalogued]: [catalogueActions] false, or
 * `MediaAsset.isExternal`).
 *
 * ## Gestures: why this file's own pointer input never calls `consume()` for taps
 * [ViewerScreen] already owns ONE gesture arbitration loop across the whole photo/video area
 * (pinch/pan/page-swipe and tap-to-toggle-chrome; see that file's own "ONE gesture handler, not
 * three stacked ones" comment) and this composable renders INSIDE that loop's subtree. Rather than
 * fight it with a second, competing tap detector, [detectVideoGestures] below only ever reads taps
 * and long-presses -- it NEVER calls `consume()` for them, so ViewerScreen's own tap detector
 * (single tap toggles chrome; double-tap/long-press are already no-ops for video there) keeps
 * working exactly as it does today, while this file's double-tap-seek and hold-to-2x fire off the
 * same, unconsumed stream in parallel. The one exception is a genuinely vertical drag once past
 * touch slop: that IS consumed, because ViewerScreen's own `detectViewerGestures` checks for
 * exactly that (`if (event.changes.any { it.isConsumed }) break`) and backs off gracefully rather
 * than also trying to page or pinch-zoom the same drag. The centre play glyph, the resume chip and
 * the error panel's buttons are real controls, not video surface, so THEY consume their taps.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
fun VideoPlayer(
    asset: MediaAsset,
    modifier: Modifier = Modifier,
    /** Mirrors every other piece of this viewer's floating chrome (see ViewerScreen's
     *  `chromeVisible`): immersive by default, all controls hidden until asked for. */
    chromeVisible: Boolean = true,
    /** Threaded down from the same preference `ViewerSettingsRoom` already exposes. */
    autoplayVideos: Boolean = true,
    /** Fired on every meaningful playback-state change (and on each position-poll tick while
     *  playing) so a host can hand this exact video off to a `MediaSessionService` for background
     *  playback. No-op by default -- see this file's own doc. */
    onSnapshotChanged: (PlaybackSnapshot) -> Unit = {},
    /** Fired once real track information is known (and again if it changes) for the details room. */
    onTrackInfoChanged: (VideoTrackInfo) -> Unit = {},
    /**
     * False for an ad-hoc asset opened from outside this app's own library (P0-18). Key moments
     * are both read from AND written to `moments.VideoMomentStore` -- indexing one would be
     * exactly the catalogue write this app must never make for an external asset -- so this skips
     * indexing entirely rather than merely hiding the bar afterward, and [KeyMomentBar] never
     * renders since [moments] stays empty. An asset flagged `isExternal` is treated the same way
     * even when this is true (see [isCatalogued]).
     */
    catalogueActions: Boolean = true,
    /** "Open with another app", offered next to Retry when playback fails. Null hides the button. */
    onOpenWith: (() -> Unit)? = null,
    /** Height of whatever the host stacks along the bottom edge (the filmstrip), so the transport
     *  sits clear above it instead of underneath. */
    bottomInset: Dp = 0.dp,
    /** What the host draws along the top, below the status bar (the chip row, the slideshow bar),
     *  so the "Resume from" chip sits beneath it instead of on top of it. */
    topInset: Dp = 0.dp,
    /** True while the user is scrubbing the filmstrip: playback pauses, the transport dims, and a
     *  player that has not started yet does not start. */
    scrubSuspended: Boolean = false,
    /** True while a video is playing (or about to), so a slideshow can wait instead of cutting it
     *  off. Always reports false again when this composable leaves. */
    onPlaybackActive: (Boolean) -> Unit = {},
) {
    // Whether playback may begin at all: the lifecycle is STARTED, or the window is in PiP. Seeded
    // from the current state because the lifecycle events below only report CHANGES, and a player
    // composed into an already-stopped activity gets none.
    val lifecycleOwner = LocalLifecycleOwner.current
    var lifecycleStarted by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { lifecycleStarted = true }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { lifecycleStarted = false }
    val inPip by rememberIsInPictureInPicture()
    val started = playbackMayStart(lifecycleStarted, inPip)

    var armed by remember(asset.id) { mutableStateOf(false) }
    // Keyed on `started` so Home pressed inside the arming delay cancels it, and coming back
    // restarts it.
    LaunchedEffect(asset.id, armed, scrubSuspended, started) {
        if (!mayArm(armed, scrubSuspended, started)) return@LaunchedEffect
        delay(ARMED_START_DELAY_MS)
        armed = true
    }

    if (armed) {
        ArmedVideoPlayer(
            asset = asset,
            modifier = modifier,
            chromeVisible = chromeVisible,
            autoplayVideos = autoplayVideos,
            started = started,
            onSnapshotChanged = onSnapshotChanged,
            onTrackInfoChanged = onTrackInfoChanged,
            catalogueActions = catalogueActions,
            onOpenWith = onOpenWith,
            bottomInset = bottomInset,
            topInset = topInset,
            scrubSuspended = scrubSuspended,
            onPlaybackActive = onPlaybackActive,
        )
    } else {
        // Nothing is built yet -- no player, no query, no codec. Just the thumbnail, which is also
        // what the armed player keeps drawing until its first frame.
        Box(modifier.background(Color.Black)) {
            MediaImage(asset = asset, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
    }
}

/** What to load into the player next: the initial media item, a silent retry without subtitles, or
 *  a manual Retry. A plain class (not a data class) so two requests with the same fields are still
 *  different state values and both get applied. */
private class LoadRequest(val withSubtitles: Boolean, val startMs: Long)

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
private fun ArmedVideoPlayer(
    asset: MediaAsset,
    modifier: Modifier,
    chromeVisible: Boolean,
    autoplayVideos: Boolean,
    started: Boolean,
    onSnapshotChanged: (PlaybackSnapshot) -> Unit,
    onTrackInfoChanged: (VideoTrackInfo) -> Unit,
    catalogueActions: Boolean,
    onOpenWith: (() -> Unit)?,
    bottomInset: Dp,
    topInset: Dp,
    scrubSuspended: Boolean,
    onPlaybackActive: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val activity = remember(context) { context.findActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current

    // One predicate, used everywhere a catalogue store would otherwise be touched.
    val catalogued = isCatalogued(catalogueActions, asset.isExternal)
    val resumeAllowed = resumeEligible(catalogued, asset.id.value)

    var isPlaying by remember(asset.id) { mutableStateOf(false) }
    // Autoplay only if the activity is started right now: the arming delay and the ON_STOP pause
    // both miss a player built after the stop, which would otherwise play (and hold audio focus)
    // behind the home screen. Read once, at build time; a later stop is the ON_STOP pause's job.
    val startPlaying = remember(asset.id) { initialPlayWhenReady(autoplayVideos, started) }
    var playWhenReady by remember(asset.id) { mutableStateOf(startPlaying) }
    var playbackState by remember(asset.id) { mutableStateOf(Player.STATE_IDLE) }
    var positionMs by remember(asset.id) { mutableStateOf(0L) }
    // Seeded from the MediaStore-reported duration so the scrubber has SOMETHING to draw before
    // the player itself has prepared; overwritten by the authoritative value once known.
    var durationMs by remember(asset.id) { mutableStateOf(asset.durationMillis.coerceAtLeast(0L)) }
    var isMuted by remember(asset.id) { mutableStateOf(false) }
    var statusMessage by remember(asset.id) { mutableStateOf<String?>(null) }
    var hasEverPlayed by remember(asset.id) { mutableStateOf(false) }
    // Played OR seeked in this session: the gate on touching the stored resume position at all.
    var engaged by remember(asset.id) { mutableStateOf(false) }
    var firstFrameRendered by remember(asset.id) { mutableStateOf(false) }
    var hasReachedReady by remember(asset.id) { mutableStateOf(false) }
    var playbackFailure by remember(asset.id) { mutableStateOf<PlaybackFailure?>(null) }
    var subtitlesAttached by remember(asset.id) { mutableStateOf(false) }
    var retriedWithoutSubtitles by remember(asset.id) { mutableStateOf(false) }
    var videoWidthPx by remember(asset.id) { mutableStateOf(0) }
    var videoHeightPx by remember(asset.id) { mutableStateOf(0) }
    var speed by remember(asset.id) { mutableStateOf(1f) }
    var keepPitch by remember(asset.id) { mutableStateOf(true) }
    var isLongPressHolding by remember(asset.id) { mutableStateOf(false) }
    var loopStartMs by remember(asset.id) { mutableStateOf<Long?>(null) }
    var loopEndMs by remember(asset.id) { mutableStateOf<Long?>(null) }
    var trackInfo by remember(asset.id) { mutableStateOf(VideoTrackInfo()) }
    var subtitleOptions by remember(asset.id) { mutableStateOf<List<TrackOption>>(emptyList()) }
    var audioOptions by remember(asset.id) { mutableStateOf<List<TrackOption>>(emptyList()) }
    var subtitleMenuOpen by remember { mutableStateOf(false) }
    var audioMenuOpen by remember { mutableStateOf(false) }
    var speedMenuOpen by remember { mutableStateOf(false) }
    var moreOpen by remember(asset.id) { mutableStateOf(false) }
    var brightnessIndicator by remember { mutableStateOf<Float?>(null) }
    var volumeIndicator by remember { mutableStateOf<Float?>(null) }
    var seekFeedback by remember { mutableStateOf<String?>(null) }

    // Nothing is remembered for a video this app does not own: no store is even opened.
    val resumeStore = remember(resumeAllowed) { if (resumeAllowed) ResumePositionStore(context) else null }
    val resumeStart = remember(asset.id, resumeStore) {
        resumeStartFor(resumeStore?.get(asset.id), autoplayVideos)
    }
    var resumeChipMs by remember(asset.id) { mutableStateOf(resumeStart.chipPositionMs) }

    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val isInPip by rememberIsInPictureInPicture()

    val holder = remember(asset.id) {
        ViewerPlayerHolder(context).also { it.player.playWhenReady = startPlaying }
    }
    val player = holder.player

    // Sidecar subtitles are resolved BEFORE the first prepare (a MediaStore query is milliseconds)
    // so the item is set up once with its subtitles, instead of being re-set under a playing video.
    // Bounded: a subtitle is never worth holding the video back.
    var sidecars by remember(asset.id) { mutableStateOf<List<SidecarCandidate>?>(null) }
    LaunchedEffect(asset.id) {
        val lookup = async(Dispatchers.IO) { readableSidecarSubtitlesFor(context, asset) }
        sidecars = withTimeoutOrNull(SIDECAR_QUERY_TIMEOUT_MS) { lookup.await() }.orEmpty()
    }

    var loadRequest by remember(asset.id) { mutableStateOf<LoadRequest?>(null) }
    LaunchedEffect(sidecars) {
        val resolved = sidecars ?: return@LaunchedEffect
        if (loadRequest == null) {
            loadRequest = LoadRequest(withSubtitles = resolved.isNotEmpty(), startMs = resumeStart.startPositionMs)
        }
    }
    LaunchedEffect(loadRequest) {
        val request = loadRequest ?: return@LaunchedEffect
        val subtitles = if (request.withSubtitles) sidecars.orEmpty() else emptyList()
        subtitlesAttached = subtitles.isNotEmpty()
        player.setMediaItem(mediaItemFor(asset, subtitles), request.startMs)
        player.prepare()
    }

    fun refreshDuration() {
        val playerDuration = runCatching { player.duration }.getOrDefault(C.TIME_UNSET)
        durationMs = resolveDurationMs(playerDuration, durationMs, asset.durationMillis)
    }

    /** The single resume write; see [resumeWriteFor] for what each trigger may do. Safe after
     *  release (every player read is guarded) and a no-op for a video this app does not own. */
    fun saveResume() {
        val store = resumeStore ?: return
        val state = runCatching { player.playbackState }.getOrDefault(Player.STATE_IDLE)
        val playerDuration = runCatching { player.duration }.getOrDefault(C.TIME_UNSET)
        val duration = resolveDurationMs(playerDuration, durationMs, asset.durationMillis)
        when (resumeWriteFor(resumeAllowed, state, engaged, duration)) {
            ResumeWrite.SAVE -> runCatching { store.save(asset.id, player.currentPosition, duration) }
            ResumeWrite.CLEAR -> store.clear(asset.id)
            ResumeWrite.SKIP -> Unit
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (playing) {
                    hasEverPlayed = true
                    engaged = true
                } else {
                    saveResume()
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReadyNow: Boolean, reason: Int) {
                playWhenReady = playWhenReadyNow
            }

            override fun onPlaybackStateChanged(state: Int) {
                playbackState = state
                when (state) {
                    Player.STATE_READY -> {
                        hasReachedReady = true
                        playbackFailure = null
                        refreshDuration()
                    }
                    Player.STATE_ENDED -> {
                        isPlaying = false
                        refreshDuration()
                        positionMs = durationMs
                        resumeStore?.clear(asset.id)
                        resumeChipMs = null
                    }
                    else -> Unit
                }
            }

            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                refreshDuration()
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                positionMs = newPosition.positionMs
            }

            override fun onRenderedFirstFrame() {
                firstFrameRendered = true
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                videoWidthPx = (videoSize.width * videoSize.pixelWidthHeightRatio).roundToInt()
                videoHeightPx = videoSize.height
            }

            override fun onPlayerError(error: PlaybackException) {
                // The code name only: a display name or URI in logcat is a privacy leak for no gain.
                Log.w(TAG, "playback error ${error.errorCodeName} (${error.errorCode}) for asset ${asset.id.value}")
                if (shouldRetryWithoutSubtitles(error.errorCode, subtitlesAttached, retriedWithoutSubtitles)) {
                    retriedWithoutSubtitles = true
                    loadRequest = LoadRequest(withSubtitles = false, startMs = player.currentPosition.coerceAtLeast(0L))
                } else {
                    playbackFailure = playbackFailureFor(error.errorCode)
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                subtitleOptions = trackOptionsFor(tracks, C.TRACK_TYPE_TEXT, includeOff = true)
                audioOptions = trackOptionsFor(tracks, C.TRACK_TYPE_AUDIO, includeOff = false)
                trackInfo = videoTrackInfoFrom(player, tracks)
                onTrackInfoChanged(trackInfo)
            }
        }
        player.addListener(listener)
        onDispose {
            // Before release, while the position is still readable. This runs on leaving
            // composition only -- never on process death, which ON_STOP below covers.
            saveResume()
            player.removeListener(listener)
            holder.release()
        }
    }

    // Position is POLLED: ExoPlayer has no continuous "the playhead moved" stream either, only a
    // synchronous getter. Keyed on isPlaying so the loop is cancelled outright the moment playback
    // pauses. Also drives the A-B loop check, the snapshot callback and the resume-position save.
    LaunchedEffect(asset.id, isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        try {
            while (isActive) {
                positionMs = runCatching { player.currentPosition }.getOrDefault(positionMs)
                refreshDuration()
                val loopEnd = loopEndMs
                val loopStart = loopStartMs
                if (loopEnd != null && loopStart != null && positionMs >= loopEnd) {
                    player.seekTo(loopStart)
                    positionMs = loopStart
                }
                onSnapshotChanged(PlaybackSnapshot(asset.contentUri, positionMs, isPlaying))
                delay(POSITION_POLL_INTERVAL_MS)
            }
        } finally {
            // The loop only ever ends by cancellation, which throws out of delay(): a save placed
            // after it is unreachable. A finally block is the one place that does run.
            saveResume()
        }
    }

    // Backgrounded: stop (a video must not keep playing audio behind the home screen, or with the
    // screen off) -- except into picture-in-picture, where the activity is paused but playback is
    // wanted. Also the load-bearing resume save: a backgrounded viewer is exactly what the system
    // kills, and onDispose never runs for that.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        saveResume()
        if (activity?.isInPictureInPictureMode != true) player.pause()
    }
    // A PiP window dismissed with its close button leaves the activity stopped (CREATED), with no
    // ON_STOP to come; expanding it back leaves the activity at least STARTED.
    var wasInPip by remember { mutableStateOf(false) }
    LaunchedEffect(isInPip) {
        if (isInPip) {
            wasInPip = true
        } else if (wasInPip) {
            wasInPip = false
            if (lifecycleOwner.lifecycle.currentState == Lifecycle.State.CREATED) player.pause()
        }
    }

    // Touching the filmstrip while a video plays stops it (and its audio) without resuming
    // afterwards: the user is navigating away from it.
    LaunchedEffect(scrubSuspended) { if (scrubSuspended) player.pause() }

    LaunchedEffect(speed, keepPitch, isLongPressHolding) {
        val effectiveSpeed = if (isLongPressHolding) LONG_PRESS_SPEED else speed
        player.playbackParameters = PlaybackParameters(effectiveSpeed, if (keepPitch) 1f else effectiveSpeed)
    }
    LaunchedEffect(isMuted) { player.volume = if (isMuted) 0f else 1f }

    val playbackActive = isPlaybackActive(playWhenReady, playbackState)
    val currentOnPlaybackActive by rememberUpdatedState(onPlaybackActive)
    LaunchedEffect(playbackActive) { currentOnPlaybackActive(playbackActive) }
    DisposableEffect(Unit) { onDispose { currentOnPlaybackActive(false) } }

    // Picture-in-picture: auto-enter (API 31+) only while actually playing, and always with the
    // real video aspect ratio once known -- a stale 1:1 default would letterbox a 16:9 clip. The
    // player's own video size is preferred over the track Format's, which excludes rotation.
    val pipWidth = if (videoWidthPx > 0) videoWidthPx else trackInfo.width
    val pipHeight = if (videoHeightPx > 0) videoHeightPx else trackInfo.height
    LaunchedEffect(playbackActive, pipWidth, pipHeight) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || activity == null) return@LaunchedEffect
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(safeAspectRatio(pipWidth, pipHeight))
            .setAutoEnterEnabled(playbackActive)
            .build()
        runCatching { activity.setPictureInPictureParams(params) }
    }
    // Armed above, so disarmed here: closing the viewer mid-playback must not leave the activity
    // auto-entering picture-in-picture onto the gallery when Home is pressed.
    DisposableEffect(activity) {
        onDispose {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && activity != null) {
                runCatching {
                    activity.setPictureInPictureParams(
                        PictureInPictureParams.Builder().setAutoEnterEnabled(false).build(),
                    )
                }
            }
        }
    }
    val pipSupported = remember(activity) {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity != null &&
            activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    val momentStore = remember { VideoMomentStore(context) }
    LaunchedEffect(catalogued) { if (catalogued) momentStore.reload() }
    // Indexing decodes up to a few dozen frames through a metadata retriever: wait until the player
    // has its own first frame out and a moment has passed, so the two never fight for a decoder.
    // Never reached for a video that fails to play, which is the point.
    LaunchedEffect(asset.id, catalogued, hasReachedReady) {
        if (!catalogued || !hasReachedReady) return@LaunchedEffect
        delay(INDEXER_DELAY_AFTER_READY_MS)
        // Fire-and-forget: see this file's own KDoc ("Key moments, end to end") for why this is
        // safe to call unconditionally and why no result is surfaced here. A failure here must
        // never interrupt playback, which is already under way by the time this coroutine starts.
        runCatching { VideoMomentIndexer(context, momentStore).index(asset) }
    }
    val momentsByAsset by momentStore.observe().collectAsState()
    val feedbackByMoment by momentStore.observeFeedback().collectAsState()
    val moments = if (catalogued) momentsByAsset[asset.id].orEmpty() else emptyList()
    val activeMoment = remember(moments, positionMs) {
        activeMomentAt(moments, positionMs, MOMENT_PILL_TOLERANCE_MS)
    }

    val exportGateway = remember { DefaultMomentExportGateway(context) }

    LaunchedEffect(statusMessage) {
        if (statusMessage != null) {
            delay(STATUS_MESSAGE_MS)
            statusMessage = null
        }
    }
    LaunchedEffect(seekFeedback) {
        if (seekFeedback != null) {
            delay(SEEK_FEEDBACK_MS)
            seekFeedback = null
        }
    }
    LaunchedEffect(brightnessIndicator) {
        if (brightnessIndicator != null) {
            delay(INDICATOR_HIDE_MS)
            brightnessIndicator = null
        }
    }
    LaunchedEffect(volumeIndicator) {
        if (volumeIndicator != null) {
            delay(INDICATOR_HIDE_MS)
            volumeIndicator = null
        }
    }

    fun seekTo(ms: Long) {
        // Nothing to seek in until the first media item is set; a seek on the empty timeline would
        // be thrown away by the setMediaItem that follows.
        if (player.currentTimeline.isEmpty) return
        val clamped = clampSeekMs(ms, durationMs)
        positionMs = clamped
        engaged = true
        runCatching { player.seekTo(clamped) }
    }

    fun togglePlay() {
        resumeChipMs?.let { resumeChipMs = null }
        playbackFailure = null
        engaged = true
        if (player.currentTimeline.isEmpty) {
            // Before the first load lands (a few milliseconds): just record the intent, which the
            // prepare picks up.
            player.playWhenReady = !player.playWhenReady
        } else {
            // Pause while buffering, re-prepare from idle, replay from the end: the library knows.
            Util.handlePlayPauseButtonAction(player)
        }
    }

    fun retryPlayback() {
        playbackFailure = null
        player.playWhenReady = true
        loadRequest = LoadRequest(
            withSubtitles = !retriedWithoutSubtitles,
            startMs = runCatching { player.currentPosition }.getOrDefault(0L).coerceAtLeast(0L),
        )
    }

    fun seekRelative(deltaMs: Long, label: String) {
        seekTo(positionMs + deltaMs)
        seekFeedback = label
    }

    fun shareUri(uri: android.net.Uri, mimeType: String, chooserTitle: String) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(Intent.createChooser(sendIntent, chooserTitle)) }
            .onFailure { statusMessage = "No compatible app was found" }
    }

    fun selectTrack(option: TrackOption, trackType: Int) {
        val params = player.trackSelectionParameters.buildUpon()
        if (option.group == null) {
            params.setTrackTypeDisabled(trackType, true)
        } else {
            params.setTrackTypeDisabled(trackType, false)
            params.setOverrideForType(TrackSelectionOverride(option.group.mediaTrackGroup, option.trackIndex))
        }
        player.trackSelectionParameters = params.build()
    }

    val showPlay = transportShowsPlay(playWhenReady, playbackState)
    val collapseSecondRow = shouldCollapseSecondRow(LocalConfiguration.current.screenHeightDp, bottomInset.value)

    CompositionLocalProvider(LocalIsInPictureInPicture provides isInPip) {
        Box(modifier = modifier) {
            AndroidView(
                factory = {
                    PlayerView(it).apply {
                        this.player = player
                        useController = false
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                    }
                },
                modifier = Modifier.fillMaxSize(),
                // Re-pointed on every update and cleared on release, so the view never holds a
                // released player and nothing here depends on the host's key(asset.id).
                update = { view ->
                    view.player = player
                    // Set on the PlayerView itself, not on the host's view: the host owns its own
                    // keep-awake preference and the two must not overwrite each other.
                    view.keepScreenOn = playbackActive
                },
                onRelease = { view ->
                    view.keepScreenOn = false
                    view.player = null
                },
            )

            // The still frame stands in until the first rendered frame, so the move from the
            // thumbnail to live video never flashes black. Draws nothing the pointer cares about.
            if (!firstFrameRendered) {
                MediaImage(asset = asset, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }

            // Gesture surface: see this composable's own doc for why this never consumes a tap.
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(asset.id) {
                        detectVideoGestures(
                            onDoubleTapSeek = { rightHalf ->
                                if (rightHalf) seekRelative(SEEK_STEP_MS, "+10s") else seekRelative(-SEEK_STEP_MS, "-10s")
                            },
                            onLongPressHold = { holding -> isLongPressHolding = holding },
                            onVerticalDrag = { leftHalf, deltaY ->
                                val fraction = -deltaY / size.height.coerceAtLeast(1) * DRAG_SENSITIVITY
                                if (leftHalf) {
                                    val next = ((brightnessIndicator ?: DEFAULT_BRIGHTNESS) + fraction).coerceIn(0.02f, 1f)
                                    brightnessIndicator = next
                                    applyBrightness(activity, next)
                                } else {
                                    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                    val current = volumeIndicator
                                        ?: (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume)
                                    val next = (current + fraction).coerceIn(0f, 1f)
                                    volumeIndicator = next
                                    runCatching {
                                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (next * maxVolume).roundToInt(), 0)
                                    }
                                }
                            },
                        )
                    },
            )

            val failure = playbackFailure
            if (failure != null) {
                PlaybackErrorPanel(
                    failure = failure,
                    onRetry = if (failure.canRetry) ({ retryPlayback() }) else null,
                    onOpenWith = if (failure.offerOpenWith) onOpenWith else null,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else if (centreGlyphVisible(playWhenReady, playbackState, failed = false)) {
                // Chrome starts hidden, so a video that is not playing is otherwise just a still
                // frame -- this glyph is not "chrome" and shows regardless of [chromeVisible].
                androidx.compose.material3.Icon(
                    imageVector = Icons.Outlined.PlayCircle,
                    contentDescription = "Play",
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(72.dp)
                        .pointerInput(asset.id) { detectTapForClick(::togglePlay) },
                )
            }

            resumeChipMs
                ?.takeIf { !hasEverPlayed && playbackFailure == null && loadRequest != null }
                ?.let { resumeMs ->
                    ResumeChip(
                        positionMs = resumeMs,
                        onClick = {
                            seekTo(resumeMs)
                            togglePlay()
                        },
                        // Under the status bar and under whatever the host draws there: the chip
                        // row's 48dp close button sits in the middle of this strip when the
                        // chrome is up, and the old fixed 24dp landed on it.
                        modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp + topInset),
                    )
                }

            brightnessIndicator?.let { LevelIndicator(it, Alignment.CenterStart) }
            volumeIndicator?.let { LevelIndicator(it, Alignment.CenterEnd) }
            seekFeedback?.let {
                Text(
                    it,
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.align(Alignment.Center)
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            if (chromeVisible && !isInPip) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        // Clear of the filmstrip band the host stacks along the bottom edge.
                        .padding(bottom = TRANSPORT_PADDING_DP.dp + bottomInset)
                        .fillMaxWidth()
                        .graphicsLayer { alpha = if (scrubSuspended) SCRUB_DIM_ALPHA else 1f },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(TRANSPORT_GAP_DP.dp),
                ) {
                    KeyMomentBar(
                        activeMoment = activeMoment,
                        positionMs = positionMs,
                        durationMs = durationMs,
                        feedback = activeMoment?.let { feedbackByMoment[it.key] },
                        onShareMoment = { moment ->
                            scope.launch {
                                exportGateway.exportFrame(asset, moment.positionMs).fold(
                                    onSuccess = { uri -> shareUri(uri, "image/*", "Share moment") },
                                    onFailure = { error -> statusMessage = error.message ?: "Could not share this moment" },
                                )
                            }
                        },
                        onCreateClip = { moment ->
                            scope.launch {
                                val safeDuration = durationMs.coerceAtLeast(moment.positionMs + 1L)
                                val start = (moment.positionMs - CLIP_HALF_WINDOW_MS).coerceAtLeast(0L)
                                val end = (moment.positionMs + CLIP_HALF_WINDOW_MS).coerceAtMost(safeDuration)
                                exportGateway.exportClip(asset, start, end).fold(
                                    onSuccess = { uri -> shareUri(uri, "video/*", "Share clip") },
                                    onFailure = { error -> statusMessage = error.message ?: "Could not create this clip" },
                                )
                            }
                        },
                        onRemoveMarker = { moment ->
                            scope.launch { momentStore.remove(moment.mediaId, moment.positionMs) }
                        },
                        onFeedback = { moment, value ->
                            scope.launch { momentStore.setFeedback(moment.mediaId, moment.positionMs, value) }
                        },
                    )

                    statusMessage?.let { message ->
                        Text(
                            text = message,
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier
                                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }

                    if (loopStartMs != null || loopEndMs != null) {
                        Text(
                            "A-B loop: ${loopStartMs?.let(::formatChipTime) ?: "?"} → ${loopEndMs?.let(::formatChipTime) ?: "?"}",
                            color = Color.White.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        RoundIconButton(
                            icon = if (showPlay) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                            contentDescription = if (showPlay) "Play" else "Pause",
                            onClick = ::togglePlay,
                        )
                        MomentScrubber(
                            positionMs = positionMs,
                            durationMs = durationMs,
                            moments = moments,
                            onSeek = ::seekTo,
                            modifier = Modifier.weight(1f),
                        )
                        // A key moment is a catalogue write: never for a file this app was handed.
                        if (catalogued && activeMoment == null) {
                            RoundIconButton(
                                icon = Icons.Outlined.BookmarkAdd,
                                contentDescription = "Mark this as a key moment",
                                onClick = {
                                    scope.launch {
                                        momentStore.add(
                                            VideoMoment(
                                                mediaId = asset.id,
                                                positionMs = positionMs,
                                                source = MomentSource.MANUAL,
                                            ),
                                        )
                                    }
                                },
                            )
                        }
                        RoundIconButton(
                            icon = if (isMuted) Icons.Outlined.VolumeOff else Icons.Outlined.VolumeUp,
                            contentDescription = if (isMuted) "Unmute" else "Mute",
                            onClick = { isMuted = !isMuted },
                        )
                        if (collapseSecondRow) {
                            RoundIconButton(
                                icon = Icons.Outlined.MoreHoriz,
                                contentDescription = if (moreOpen) "Fewer controls" else "More controls",
                                onClick = { moreOpen = !moreOpen },
                            )
                        }
                    }

                    if (!collapseSecondRow || moreOpen) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box {
                                RoundIconButton(
                                    icon = Icons.Outlined.Speed,
                                    contentDescription = "Playback speed",
                                    onClick = { speedMenuOpen = true },
                                )
                                DropdownMenu(expanded = speedMenuOpen, onDismissRequest = { speedMenuOpen = false }) {
                                    Row(
                                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text("Keep pitch")
                                        Switch(checked = keepPitch, onCheckedChange = { keepPitch = it })
                                    }
                                    SPEED_PRESETS.forEach { choice ->
                                        DropdownMenuItem(
                                            text = { Text("${trimTrailingZeroLocal(choice)}×" + if (choice == speed) " ✓" else "") },
                                            onClick = { speed = choice; speedMenuOpen = false },
                                        )
                                    }
                                }
                            }
                            Box {
                                RoundIconButton(
                                    icon = Icons.Outlined.ClosedCaption,
                                    contentDescription = "Subtitles",
                                    onClick = { subtitleMenuOpen = true },
                                )
                                DropdownMenu(expanded = subtitleMenuOpen, onDismissRequest = { subtitleMenuOpen = false }) {
                                    if (subtitleOptions.isEmpty()) {
                                        DropdownMenuItem(text = { Text("No subtitles available") }, onClick = {})
                                    }
                                    subtitleOptions.forEach { option ->
                                        DropdownMenuItem(
                                            text = { Text(option.label) },
                                            onClick = { selectTrack(option, C.TRACK_TYPE_TEXT); subtitleMenuOpen = false },
                                        )
                                    }
                                }
                            }
                            Box {
                                RoundIconButton(
                                    icon = Icons.Outlined.Audiotrack,
                                    contentDescription = "Audio track",
                                    onClick = { audioMenuOpen = true },
                                )
                                DropdownMenu(expanded = audioMenuOpen, onDismissRequest = { audioMenuOpen = false }) {
                                    if (audioOptions.size <= 1) {
                                        DropdownMenuItem(text = { Text("Only one audio track") }, onClick = {})
                                    }
                                    audioOptions.forEach { option ->
                                        DropdownMenuItem(
                                            text = { Text(option.label) },
                                            onClick = { selectTrack(option, C.TRACK_TYPE_AUDIO); audioMenuOpen = false },
                                        )
                                    }
                                }
                            }
                            RoundIconButton(
                                icon = Icons.Outlined.Repeat,
                                contentDescription = "Set A-B loop point",
                                tint = if (loopStartMs != null || loopEndMs != null) Color(0xFF8AB4FF) else Color.White,
                                onClick = {
                                    when {
                                        loopStartMs == null -> loopStartMs = positionMs
                                        loopEndMs == null && positionMs > loopStartMs!! -> loopEndMs = positionMs
                                        else -> { loopStartMs = null; loopEndMs = null }
                                    }
                                },
                            )
                            if (pipSupported && activity != null) {
                                RoundIconButton(
                                    icon = Icons.Outlined.PictureInPictureAlt,
                                    contentDescription = "Picture in picture",
                                    onClick = {
                                        val aspect = safeAspectRatio(pipWidth, pipHeight)
                                        runCatching {
                                            activity.enterPictureInPictureMode(
                                                PictureInPictureParams.Builder().setAspectRatio(aspect).build(),
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Shown in place of the play glyph when playback fails. Retry re-prepares the same item; Open with
 * hands the file to another app. Either button is absent when it cannot help (see
 * [playbackFailureFor]) or when the host gave no way to open the file elsewhere.
 */
@Composable
private fun PlaybackErrorPanel(
    failure: PlaybackFailure,
    onRetry: (() -> Unit)?,
    onOpenWith: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .padding(24.dp)
            .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(16.dp))
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        androidx.compose.material3.Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(32.dp),
        )
        Text(
            failure.message,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        if (onRetry != null || onOpenWith != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onRetry != null) {
                    TextButton(
                        onClick = onRetry,
                        colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                    ) { Text("Retry") }
                }
                if (onOpenWith != null) {
                    TextButton(
                        onClick = onOpenWith,
                        colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                    ) { Text("Open with") }
                }
            }
        }
    }
}

/** One selectable track: [group] null means "Off" (subtitles only). */
internal data class TrackOption(val label: String, val group: Tracks.Group?, val trackIndex: Int)

private fun trackOptionsFor(tracks: Tracks, trackType: Int, includeOff: Boolean): List<TrackOption> {
    val options = mutableListOf<TrackOption>()
    if (includeOff) options += TrackOption("Off", null, 0)
    tracks.groups.filter { it.type == trackType }.forEach { group ->
        for (i in 0 until group.length) {
            val format = group.getTrackFormat(i)
            val label = format.label ?: format.language?.uppercase() ?: "Track ${options.size + 1}"
            options += TrackOption(label, group, i)
        }
    }
    return options
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun videoTrackInfoFrom(player: ExoPlayer, tracks: Tracks): VideoTrackInfo {
    val videoFormat = player.videoFormat
    val audioFormat = player.audioFormat
    return VideoTrackInfo(
        videoCodec = videoFormat?.sampleMimeType,
        width = videoFormat?.width ?: 0,
        height = videoFormat?.height ?: 0,
        frameRate = videoFormat?.frameRate?.takeIf { it > 0f },
        videoBitrate = videoFormat?.bitrate?.takeIf { it > 0 },
        audioCodec = audioFormat?.sampleMimeType,
        audioChannels = audioFormat?.channelCount?.takeIf { it > 0 },
    )
}

private fun mediaItemFor(asset: MediaAsset, sidecars: List<SidecarCandidate>): MediaItem {
    val subtitleConfigs = sidecars.mapNotNull { candidate ->
        val mimeType = subtitleMimeTypeFor(candidate.displayName) ?: return@mapNotNull null
        MediaItem.SubtitleConfiguration.Builder(candidate.contentUri)
            .setMimeType(mimeType)
            .setLabel(fileStem(candidate.displayName))
            .build()
    }
    return MediaItem.Builder()
        .setUri(asset.contentUri)
        .setSubtitleConfigurations(subtitleConfigs)
        .build()
}

/** Custom, deliberately non-consuming (for taps/long-press) gesture stream -- see [VideoPlayer]'s
 *  own doc for why. */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectVideoGestures(
    onDoubleTapSeek: (rightHalf: Boolean) -> Unit,
    onLongPressHold: (holding: Boolean) -> Unit,
    onVerticalDrag: (leftHalf: Boolean, deltaY: Float) -> Unit,
) {
    var lastTapUpTimeMs = 0L
    var lastTapWasRightHalf = false
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val downTimeMs = android.os.SystemClock.uptimeMillis()
        val leftHalf = down.position.x < size.width / 2f
        var longPressActive = false
        var dragClaimed = false
        var totalDx = 0f
        var totalDy = 0f
        val slop = viewConfiguration.touchSlop
        while (true) {
            val elapsed = android.os.SystemClock.uptimeMillis() - downTimeMs
            val remaining = LONG_PRESS_TIMEOUT_MS - elapsed
            val event = when {
                longPressActive -> awaitPointerEvent()
                remaining <= 0 -> null
                else -> withTimeoutOrNull(remaining) { awaitPointerEvent() }
            }
            if (event == null) {
                if (!longPressActive) {
                    longPressActive = true
                    onLongPressHold(true)
                }
                continue
            }
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            val delta = change.positionChange()
            totalDx += delta.x
            totalDy += delta.y
            if (!dragClaimed && !longPressActive) {
                if (abs(totalDy) > slop && abs(totalDy) > abs(totalDx)) {
                    dragClaimed = true
                }
            }
            if (dragClaimed) {
                change.consume()
                onVerticalDrag(leftHalf, delta.y)
            }
        }
        if (longPressActive) {
            onLongPressHold(false)
        } else if (!dragClaimed) {
            val heldMs = android.os.SystemClock.uptimeMillis() - downTimeMs
            val moved = abs(totalDx) > slop || abs(totalDy) > slop
            if (heldMs < LONG_PRESS_TIMEOUT_MS && !moved) {
                val now = android.os.SystemClock.uptimeMillis()
                val isRightHalf = !leftHalf
                if (now - lastTapUpTimeMs < DOUBLE_TAP_TIMEOUT_MS && lastTapWasRightHalf == isRightHalf) {
                    onDoubleTapSeek(isRightHalf)
                    lastTapUpTimeMs = 0L
                } else {
                    lastTapUpTimeMs = now
                    lastTapWasRightHalf = isRightHalf
                }
            }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var context = this
    while (context is android.content.ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

private fun applyBrightness(activity: Activity?, fraction: Float) {
    val window = activity?.window ?: return
    runCatching {
        val params = window.attributes
        params.screenBrightness = fraction.coerceIn(0.02f, 1f)
        window.attributes = params
    }
}

private fun safeAspectRatio(width: Int, height: Int): Rational {
    if (width <= 0 || height <= 0) return Rational(16, 9)
    // PictureInPictureParams rejects an aspect ratio outside roughly 0.418..2.39.
    val ratio = width.toFloat() / height
    return when {
        ratio > 2.39f -> Rational(239, 100)
        ratio < 0.42f -> Rational(42, 100)
        else -> Rational(width, height)
    }
}

@Composable
private fun ResumeChip(positionMs: Long, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        "Resume from ${formatChipTime(positionMs)}",
        color = Color.White,
        style = MaterialTheme.typography.labelMedium,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .pointerInput(Unit) { detectTapForClick(onClick) },
    )
}

/**
 * A tap on a real control drawn over the video (the play glyph, the resume chip). Unlike
 * [detectVideoGestures] this DOES consume: the control handled the tap, so the host's own
 * tap-to-toggle-chrome must not also fire and hide the chrome the user just pressed a button in.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectTapForClick(onClick: () -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown()
        down.consume()
        val up = waitForUpOrCancellation()
        if (up != null) {
            up.consume()
            onClick()
        }
    }
}

@Composable
private fun LevelIndicator(level: Float, alignment: Alignment, modifierColumn: Modifier = Modifier) {
    Box(
        modifierColumn
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = alignment,
    ) {
        Column(
            Modifier
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("${(level * 100).roundToInt()}%", color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun formatChipTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

private fun trimTrailingZeroLocal(value: Float): String =
    if (value == value.toLong().toFloat()) value.toLong().toString() else value.toString()

private val SPEED_PRESETS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f)

/** Position polling cadence. Within the task's 100-200ms guidance. */
private const val POSITION_POLL_INTERVAL_MS = 150L

/** How close to a moment (in either direction) the playhead counts as "on" it. */
private const val MOMENT_PILL_TOLERANCE_MS = 1_500L

/** Default clip window either side of a key moment. */
private const val CLIP_HALF_WINDOW_MS = 3_000L

/** Transport opacity while the filmstrip is being scrubbed: still readable, visibly behind it. */
private const val SCRUB_DIM_ALPHA = 0.35f

private const val TAG = "VideoPlayer"

private const val STATUS_MESSAGE_MS = 2_800L
private const val SEEK_FEEDBACK_MS = 500L
private const val INDICATOR_HIDE_MS = 900L
private const val SEEK_STEP_MS = 10_000L
private const val LONG_PRESS_TIMEOUT_MS = 500L
private const val DOUBLE_TAP_TIMEOUT_MS = 300L
private const val LONG_PRESS_SPEED = 2f
private const val DRAG_SENSITIVITY = 2.2f
private const val DEFAULT_BRIGHTNESS = 0.5f
