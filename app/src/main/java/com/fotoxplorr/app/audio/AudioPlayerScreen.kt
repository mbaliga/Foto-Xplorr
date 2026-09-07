package com.fotoxplorr.app.audio

import android.media.MediaPlayer
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * The standalone audio player — play/pause/seek and a queue (its position within [queue], not a
 * separately editable playlist; see [AudioLibraryComposition]'s own doc). Built on a plain
 * [MediaPlayer], mirroring [com.fotoxplorr.app.viewer.VideoPlayer]'s exact lifecycle pattern
 * (state remembered per-track, position POLLED not pushed, every getter call wrapped in
 * `runCatching`) with one real simplification: there is no picture, so this needs no
 * `android.widget.VideoView`/`AndroidView` at all — a bare `MediaPlayer` plus
 * `setDataSource(Context, Uri)` is the whole surface this screen drives.
 *
 * No [android.media.session.MediaSession] / lock-screen or notification controls yet — playback
 * stops the moment this screen is left, matching this app's existing video/photo viewers, which
 * are equally screen-bound. Background playback is a real, separately-scoped follow-up, not
 * something this first pass claims to do.
 */
@Composable
fun AudioPlayerScreen(
    asset: AudioAsset,
    queue: List<AudioAsset>,
    onClose: () -> Unit,
    onSelect: (AudioAsset) -> Unit,
    /** Re-encodes this file to AAC/M4A as a new file beside it — audio's own Save As, see
     *  [AudioConversionWriter]. Null leaves the action hidden, the same optional-action shape
     *  [com.fotoxplorr.app.viewer.ViewerActionsRoom.onConvertToMp4] already uses for video. */
    onConvertToAac: (() -> Unit)? = null,
    isConverting: Boolean = false,
) {
    val context = LocalContext.current

    var isPlaying by remember(asset.id) { mutableStateOf(true) }
    var positionMs by remember(asset.id) { mutableStateOf(0L) }
    var durationMs by remember(asset.id) { mutableStateOf(asset.durationMillis.coerceAtLeast(0L)) }
    var statusMessage by remember(asset.id) { mutableStateOf<String?>(null) }

    val index = queue.indexOfFirst { it.id == asset.id }
    val previousTrack = queue.getOrNull(index - 1)
    val nextTrack = queue.getOrNull(index + 1)
    // rememberUpdatedState: the completion listener below is captured once, inside `remember`,
    // but which track comes next can change if the queue itself changes while this one is
    // playing -- reading a stale `nextTrack` closed over at construction time would auto-advance
    // to the wrong track (or fail to advance at all) after such a change.
    val latestNextTrack by rememberUpdatedState(nextTrack)
    val latestOnSelect by rememberUpdatedState(onSelect)

    val mediaPlayer = remember(asset.id) {
        MediaPlayer().apply {
            setOnPreparedListener { player ->
                val preparedDuration = player.duration.toLong()
                if (preparedDuration > 0L) durationMs = preparedDuration
                player.start()
            }
            setOnCompletionListener {
                isPlaying = false
                // Auto-advance, the one piece of "queue" behaviour a plain tap-to-play list needs:
                // without it, reaching the end of a track would just stop, which is not how any
                // music app behaves when there is an obvious next track sitting right there.
                latestNextTrack?.let(latestOnSelect)
            }
            setOnErrorListener { _, _, _ ->
                statusMessage = "Could not play \"${asset.title}\""
                isPlaying = false
                true // handled: suppresses the framework's own onCompletion-as-fallback behaviour
            }
            runCatching {
                setDataSource(context, asset.contentUri)
                prepareAsync()
            }.onFailure {
                statusMessage = "Could not open \"${asset.title}\""
            }
        }
    }

    DisposableEffect(mediaPlayer) {
        onDispose { runCatching { mediaPlayer.release() } }
    }

    LaunchedEffect(asset.id, isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        while (isActive) {
            // See VideoPlayer's identical guard: getCurrentPosition() can throw between the
            // player being released and this coroutine's cancellation actually landing.
            positionMs = runCatching { mediaPlayer.currentPosition.toLong() }.getOrDefault(positionMs)
            delay(POSITION_POLL_INTERVAL_MS)
        }
    }

    LaunchedEffect(statusMessage) {
        if (statusMessage != null) {
            delay(STATUS_MESSAGE_MS)
            statusMessage = null
        }
    }

    fun togglePlay() {
        val playing = runCatching { mediaPlayer.isPlaying }.getOrDefault(false)
        if (playing) {
            runCatching { mediaPlayer.pause() }
            isPlaying = false
        } else {
            runCatching { mediaPlayer.start() }
            isPlaying = true
        }
    }

    fun seekTo(ms: Long) {
        positionMs = ms
        runCatching { mediaPlayer.seekTo(ms.toInt()) }
    }

    BackHandler(onBack = onClose)

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) {
            if (onConvertToAac != null) {
                IconButton(onClick = onConvertToAac, enabled = !isConverting) {
                    Icon(
                        Icons.Outlined.SwapHoriz,
                        contentDescription = if (isConverting) "Converting…" else "Convert to AAC",
                        tint = Color.White.copy(alpha = if (isConverting) 0.4f else 1f),
                    )
                }
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Outlined.Close, contentDescription = "Close", tint = Color.White)
            }
        }

        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(220.dp)
                    .background(Color.White.copy(alpha = 0.08f), androidx.compose.foundation.shape.RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.MusicNote,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(72.dp),
                )
            }
            Text(
                asset.title,
                color = Color.White,
                style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Medium),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 24.dp),
            )
            val subtitle = listOfNotNull(asset.artist, asset.album).joinToString(" — ")
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    color = Color.White.copy(alpha = 0.6f),
                    style = TextStyle(fontSize = 15.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
            Slider(
                value = positionMs.toFloat().coerceIn(0f, durationMs.toFloat().coerceAtLeast(0f)),
                onValueChange = { seekTo(it.toLong()) },
                valueRange = 0f..durationMs.toFloat().coerceAtLeast(1f),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatDurationMs(positionMs), color = Color.White.copy(alpha = 0.6f), style = TextStyle(fontSize = 12.sp))
                Text(formatDurationMs(durationMs), color = Color.White.copy(alpha = 0.6f), style = TextStyle(fontSize = 12.sp))
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                IconButton(onClick = { previousTrack?.let(onSelect) }, enabled = previousTrack != null) {
                    Icon(
                        Icons.Outlined.SkipPrevious,
                        contentDescription = "Previous",
                        tint = Color.White.copy(alpha = if (previousTrack != null) 1f else 0.3f),
                        modifier = Modifier.size(36.dp),
                    )
                }
                IconButton(onClick = ::togglePlay, modifier = Modifier.padding(horizontal = 24.dp)) {
                    Icon(
                        if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(48.dp),
                    )
                }
                IconButton(onClick = { nextTrack?.let(onSelect) }, enabled = nextTrack != null) {
                    Icon(
                        Icons.Outlined.SkipNext,
                        contentDescription = "Next",
                        tint = Color.White.copy(alpha = if (nextTrack != null) 1f else 0.3f),
                        modifier = Modifier.size(36.dp),
                    )
                }
            }

            statusMessage?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = TextStyle(fontSize = 13.sp),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

private const val POSITION_POLL_INTERVAL_MS = 150L
private const val STATUS_MESSAGE_MS = 3_000L
