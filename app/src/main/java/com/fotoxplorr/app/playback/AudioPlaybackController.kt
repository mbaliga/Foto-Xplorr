package com.fotoxplorr.app.playback

import android.content.ComponentName
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.fotoxplorr.app.audio.AudioAsset
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Compose-facing handle onto a [MediaController] bound to [PlaybackService]'s [MediaSession] --
 * the app-wide surface every playback UI drives, and the ONLY thing this app's `MediaPlayer`-based
 * screen never had. All the actual state lives in [PlaybackService]'s player; this class is a thin,
 * short-lived mirror of it, so releasing one (closing the screen that created it) never touches
 * playback itself. See [rememberAudioController].
 */
@Stable
class AudioPlaybackController internal constructor() {

    var controller: MediaController? by mutableStateOf(null)
        internal set

    var mediaId: String? by mutableStateOf(null)
        private set
    var isPlaying: Boolean by mutableStateOf(false)
        private set
    var positionMs: Long by mutableStateOf(0L)
        private set
    var durationMs: Long by mutableStateOf(0L)
        private set
    var bufferedPositionMs: Long by mutableStateOf(0L)
        private set
    var shuffleModeEnabled: Boolean by mutableStateOf(false)
        private set
    var repeatMode: Int by mutableStateOf(Player.REPEAT_MODE_OFF)
        private set
    var speed: Float by mutableStateOf(1f)
        private set
    /** The queue as THIS controller started it — see [playQueue]. Not read back from the
     *  [MediaController]'s own [MediaItem][androidx.media3.common.MediaItem] list: a `MediaItem`
     *  carries only what [toMediaItem] put into its [androidx.media3.common.MediaMetadata], which
     *  is enough for a notification but not the full [AudioAsset] a queue sheet or "now playing"
     *  row wants to render. */
    var queue: List<AudioAsset> by mutableStateOf(emptyList())
        private set
    /** Index into [queue] — [Player.getCurrentMediaItemIndex] is a position in the TIMELINE's
     *  original window order, not the shuffled play order, so it lines up with [queue] directly
     *  regardless of [shuffleModeEnabled]. */
    var currentIndex: Int by mutableStateOf(-1)
        private set
    /** True between [beginScrub] and [endScrub] — see [rememberAudioController]'s polling loop,
     *  which this pauses so a finger on the slider is never fighting a poll pushing it back. */
    var isScrubbing: Boolean by mutableStateOf(false)
        private set

    internal val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            syncFrom(player)
        }
    }

    internal fun syncFrom(player: Player) {
        mediaId = player.currentMediaItem?.mediaId
        isPlaying = player.isPlaying
        durationMs = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L
        if (!isScrubbing) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            bufferedPositionMs = player.bufferedPosition.coerceAtLeast(0L)
        }
        shuffleModeEnabled = player.shuffleModeEnabled
        repeatMode = player.repeatMode
        speed = player.playbackParameters.speed
        if (player.mediaItemCount > 0) currentIndex = player.currentMediaItemIndex
    }

    /** Starts (or restarts) playback of [queue] from [startIndex] — the one command that actually
     *  replaces the session's playlist; every other command below acts on whatever is already
     *  playing. A no-op against an empty queue or before the controller has connected. */
    fun playQueue(queue: List<AudioAsset>, startIndex: Int) {
        val mc = controller ?: return
        if (queue.isEmpty()) return
        this.queue = queue
        mc.setMediaItems(queue.map { it.toMediaItem() }, startIndex.coerceIn(0, queue.lastIndex), 0L)
        mc.prepare()
        mc.play()
        syncFrom(mc)
    }

    fun togglePlayPause() {
        val mc = controller ?: return
        if (mc.isPlaying) mc.pause() else mc.play()
    }

    fun seekTo(targetMs: Long) {
        controller?.seekTo(targetMs.coerceAtLeast(0L))
    }

    fun skipToNext() {
        controller?.takeIf { it.hasNextMediaItem() }?.seekToNextMediaItem()
    }

    fun skipToPrevious() {
        controller?.takeIf { it.hasPreviousMediaItem() }?.seekToPreviousMediaItem()
    }

    /** Jumps straight to a track in [queue] — the queue sheet's tap action. */
    fun jumpToQueueIndex(index: Int) {
        controller?.seekTo(index, 0L)
    }

    fun setShuffleEnabled(enabled: Boolean) {
        controller?.shuffleModeEnabled = enabled
    }

    /** Off -> repeat the whole queue -> repeat one track -> off — the same three-state cycle
     *  every mainstream music app's single repeat button walks through. */
    fun cycleRepeatMode() {
        val mc = controller ?: return
        mc.repeatMode = when (mc.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun setPlaybackSpeed(newSpeed: Float) {
        controller?.setPlaybackSpeed(newSpeed)
    }

    /** Pauses the position poll for a slider drag in progress. */
    fun beginScrub() {
        isScrubbing = true
    }

    /** Resumes the poll and commits the drag's final position in one call, so there is no frame
     *  where a stale, pre-drag [positionMs] could flash before the seek lands. */
    fun endScrub(targetMs: Long) {
        isScrubbing = false
        seekTo(targetMs)
    }
}

/**
 * Connects a fresh [MediaController] to [PlaybackService] for the lifetime of the calling
 * composable, and returns the [AudioPlaybackController] mirroring it.
 *
 * Deliberately NOT a singleton hoisted once for the whole app: [MediaController.Builder.buildAsync]
 * is cheap, connecting to an ALREADY-RUNNING session (or starting the service the first time) is
 * the normal case, and a controller is inert while disconnected -- so every screen that wants
 * playback state (the player screen, the library's "now playing" mini-bar) can call this
 * independently and each gets its own live mirror of the one real session, with no shared object
 * for two unrelated screens to coordinate ownership of. Releasing the controller on dispose (this
 * function's whole point) never stops playback -- see [PlaybackService]'s own doc.
 */
@Composable
fun rememberAudioController(): AudioPlaybackController {
    val context = LocalContext.current
    val holder = remember { AudioPlaybackController() }

    DisposableEffect(Unit) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            {
                val mc = runCatching { future.get() }.getOrNull() ?: return@addListener
                holder.controller = mc
                mc.addListener(holder.listener)
                holder.syncFrom(mc)
            },
            MoreExecutors.directExecutor(),
        )

        onDispose {
            holder.controller?.removeListener(holder.listener)
            MediaController.releaseFuture(future)
            holder.controller = null
        }
    }

    // Position/buffered position advance continuously with no discrete event to react to --
    // onEvents (wired in AudioPlaybackController.listener) covers everything else (track
    // changes, play/pause, shuffle/repeat/speed). Paused while scrubbing so this never fights a
    // finger on the slider; see AudioPlaybackController.isScrubbing.
    LaunchedEffect(holder.controller, holder.isPlaying, holder.isScrubbing) {
        val mc = holder.controller ?: return@LaunchedEffect
        if (!holder.isPlaying || holder.isScrubbing) return@LaunchedEffect
        while (isActive) {
            holder.syncFrom(mc)
            delay(POSITION_POLL_INTERVAL_MS)
        }
    }

    return holder
}

private const val POSITION_POLL_INTERVAL_MS = 250L
