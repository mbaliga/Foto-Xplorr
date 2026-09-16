package com.fotoxplorr.app.viewer

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.util.Rational
import androidx.activity.ComponentActivity
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.moments.MomentSource
import com.fotoxplorr.app.moments.VideoMoment
import com.fotoxplorr.app.moments.VideoMomentIndexer
import com.fotoxplorr.app.moments.VideoMomentStore
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 * than also trying to page or pinch-zoom the same drag.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(
    asset: MediaAsset,
    modifier: Modifier = Modifier,
    /** Mirrors every other piece of this viewer's floating chrome (see ViewerScreen's
     *  `chromeVisible`): immersive by default, all controls hidden until asked for. */
    chromeVisible: Boolean = true,
    /** Threaded down from the same preference `ViewerSettingsRoom` already exposes. */
    autoplayVideos: Boolean = false,
    /** Fired on every meaningful playback-state change (and on each position-poll tick while
     *  playing) so a host can hand this exact video off to a `MediaSessionService` for background
     *  playback. No-op by default -- see this file's own doc. */
    onSnapshotChanged: (PlaybackSnapshot) -> Unit = {},
    /** Fired once real track information is known (and again if it changes) for the details room. */
    onTrackInfoChanged: (VideoTrackInfo) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val activity = remember(context) { context.findActivity() }

    var isPlaying by remember(asset.id) { mutableStateOf(false) }
    var positionMs by remember(asset.id) { mutableStateOf(0L) }
    // Seeded from the MediaStore-reported duration so the scrubber has SOMETHING to draw before
    // the player itself has prepared; overwritten by the authoritative value once known.
    var durationMs by remember(asset.id) { mutableStateOf(asset.durationMillis.coerceAtLeast(0L)) }
    var isMuted by remember(asset.id) { mutableStateOf(false) }
    var statusMessage by remember(asset.id) { mutableStateOf<String?>(null) }
    var hasEverPlayed by remember(asset.id) { mutableStateOf(false) }
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
    var brightnessIndicator by remember { mutableStateOf<Float?>(null) }
    var volumeIndicator by remember { mutableStateOf<Float?>(null) }
    var seekFeedback by remember { mutableStateOf<String?>(null) }

    val resumeStore = remember { ResumePositionStore(context) }
    var resumeChipMs by remember(asset.id) { mutableStateOf(resumeStore.get(asset.id)) }

    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val isInPip by rememberIsInPictureInPicture()

    var sidecarSubtitles by remember(asset.id) { mutableStateOf<List<SidecarCandidate>>(emptyList()) }
    LaunchedEffect(asset.id) {
        sidecarSubtitles = withContext(Dispatchers.IO) { querySidecarSubtitlesFor(context, asset) }
    }

    val player = remember(asset.id) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(mediaItemFor(asset, emptyList()))
            prepare()
            playWhenReady = autoplayVideos
        }
    }

    // Sidecar subtitles usually resolve well after the player has already started preparing (a
    // MediaStore query, however fast, is never synchronous with player construction) -- attach
    // them with a seamless re-set, preserving position and play state, rather than requiring the
    // player to wait on them before playback can start at all.
    LaunchedEffect(sidecarSubtitles) {
        if (sidecarSubtitles.isEmpty()) return@LaunchedEffect
        val position = player.currentPosition
        val wasPlaying = player.playWhenReady
        player.setMediaItem(mediaItemFor(asset, sidecarSubtitles))
        player.prepare()
        player.seekTo(position)
        player.playWhenReady = wasPlaying
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (playing) hasEverPlayed = true
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    isPlaying = false
                    resumeStore.clear(asset.id)
                    resumeChipMs = null
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
        onDispose { player.removeListener(listener); player.release() }
    }

    // Position is POLLED: ExoPlayer has no continuous "the playhead moved" stream either, only a
    // synchronous getter. Keyed on isPlaying so the loop is cancelled outright the moment playback
    // pauses. Also drives the A-B loop check, the snapshot callback and the resume-position save.
    LaunchedEffect(asset.id, isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        while (isActive) {
            positionMs = runCatching { player.currentPosition }.getOrDefault(positionMs)
            val preparedDuration = runCatching { player.duration }.getOrDefault(C.TIME_UNSET)
            if (preparedDuration > 0L) durationMs = preparedDuration
            val loopEnd = loopEndMs
            val loopStart = loopStartMs
            if (loopEnd != null && loopStart != null && positionMs >= loopEnd) {
                player.seekTo(loopStart)
                positionMs = loopStart
            }
            onSnapshotChanged(PlaybackSnapshot(asset.contentUri, positionMs, isPlaying))
            delay(POSITION_POLL_INTERVAL_MS)
        }
        resumeStore.save(asset.id, positionMs, durationMs)
    }

    LaunchedEffect(speed, keepPitch, isLongPressHolding) {
        val effectiveSpeed = if (isLongPressHolding) LONG_PRESS_SPEED else speed
        player.playbackParameters = PlaybackParameters(effectiveSpeed, if (keepPitch) 1f else effectiveSpeed)
    }
    LaunchedEffect(isMuted) { player.volume = if (isMuted) 0f else 1f }

    // Picture-in-picture: auto-enter (API 31+) only while actually playing, and always with the
    // real video aspect ratio once known -- a stale 1:1 default would letterbox a 16:9 clip.
    LaunchedEffect(isPlaying, trackInfo.width, trackInfo.height) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || activity == null) return@LaunchedEffect
        val aspect = safeAspectRatio(trackInfo.width, trackInfo.height)
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(aspect)
            .setAutoEnterEnabled(isPlaying)
            .build()
        runCatching { activity.setPictureInPictureParams(params) }
    }

    val momentStore = remember { VideoMomentStore(context) }
    LaunchedEffect(Unit) { momentStore.reload() }
    LaunchedEffect(asset.id) {
        runCatching { VideoMomentIndexer(context, momentStore).index(asset) }
    }
    val momentsByAsset by momentStore.observe().collectAsState()
    val feedbackByMoment by momentStore.observeFeedback().collectAsState()
    val moments = momentsByAsset[asset.id].orEmpty()
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
        val clamped = ms.coerceIn(0L, durationMs.coerceAtLeast(0L))
        positionMs = clamped
        runCatching { player.seekTo(clamped) }
    }

    fun togglePlay() {
        if (isPlaying) {
            player.pause()
        } else {
            if (durationMs > 0L && positionMs >= durationMs - END_OF_CLIP_REPLAY_SLACK_MS) {
                seekTo(0)
            }
            resumeChipMs?.let { resumeChipMs = null }
            player.play()
        }
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
            )

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

            if (!hasEverPlayed && !isPlaying) {
                // Chrome starts hidden and autoplay defaults off, so a freshly opened video is
                // otherwise just a black rectangle -- this glyph is not "chrome" and shows
                // regardless of [chromeVisible].
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

            resumeChipMs?.takeIf { !hasEverPlayed }?.let { resumeMs ->
                ResumeChip(
                    positionMs = resumeMs,
                    onClick = {
                        seekTo(resumeMs)
                        togglePlay()
                    },
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp),
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
                        .padding(bottom = 14.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
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
                            icon = if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            onClick = ::togglePlay,
                        )
                        MomentScrubber(
                            positionMs = positionMs,
                            durationMs = durationMs,
                            moments = moments,
                            onSeek = ::seekTo,
                            modifier = Modifier.weight(1f),
                        )
                        if (activeMoment == null) {
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
                    }

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
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity != null) {
                            RoundIconButton(
                                icon = Icons.Outlined.PictureInPictureAlt,
                                contentDescription = "Picture in picture",
                                onClick = {
                                    val aspect = safeAspectRatio(trackInfo.width, trackInfo.height)
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

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectTapForClick(onClick: () -> Unit) {
    awaitEachGesture {
        awaitFirstDown()
        val up = waitForUpOrCancellation()
        if (up != null) onClick()
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

/** How close to the very end counts as "finished" for the replay-from-start behaviour. */
private const val END_OF_CLIP_REPLAY_SLACK_MS = 400L

private const val STATUS_MESSAGE_MS = 2_800L
private const val SEEK_FEEDBACK_MS = 500L
private const val INDICATOR_HIDE_MS = 900L
private const val SEEK_STEP_MS = 10_000L
private const val LONG_PRESS_TIMEOUT_MS = 500L
private const val DOUBLE_TAP_TIMEOUT_MS = 300L
private const val LONG_PRESS_SPEED = 2f
private const val DRAG_SENSITIVITY = 2.2f
private const val DEFAULT_BRIGHTNESS = 0.5f
