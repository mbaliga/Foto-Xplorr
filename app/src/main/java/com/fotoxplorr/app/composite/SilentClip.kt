package com.fotoxplorr.app.composite

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * A short clip played WITHOUT SOUND, for motion photos and Live Photos. The owner removed audio
 * features, so this player is built never to make a sound: its volume is 0, the audio track type is
 * disabled in track selection (no audio decoder is even started), and it does not request audio
 * focus, so music another app is playing carries on. There is no volume control, by design.
 *
 * It starts at [startMs] (the frame the still was taken from, when the file says), plays once and
 * reports [onEnded]; a clip that will not play reports [onError]. The view draws video only: no
 * controller, and it takes no touches, so the viewer's own gestures are untouched.
 */
// PlayerView's shutter colour is media3 unstable API (TRAPS #135).
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun SilentClip(
    uri: Uri,
    startMs: Long,
    onEnded: () -> Unit,
    onError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val holder = remember(uri) { SilentPlayerHolder(context) }
    val ended by rememberUpdatedState(onEnded)
    val failed by rememberUpdatedState(onError)
    DisposableEffect(holder) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) ended()
            }

            override fun onPlayerError(error: PlaybackException) = failed()
        }
        holder.player.addListener(listener)
        onDispose { holder.player.removeListener(listener) }
    }
    LaunchedEffect(holder) {
        holder.player.setMediaItem(MediaItem.fromUri(uri), startMs.coerceAtLeast(0L))
        holder.player.prepare()
        holder.player.playWhenReady = true
    }
    AndroidView(
        factory = {
            PlayerView(it).apply {
                useController = false
                isClickable = false
                isFocusable = false
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                player = holder.player
            }
        },
        modifier = modifier,
        update = { view -> view.player = holder.player },
        onRelease = { view -> view.player = null },
    )
}

/** Owns the silent player for as long as a composition remembers it (released on abandon too). */
internal class SilentPlayerHolder(context: Context) : RememberObserver {
    val player: ExoPlayer = buildSilentPlayer(context)
    private var released = false

    fun release() {
        if (released) return
        released = true
        runCatching { player.release() }
    }

    override fun onRemembered() = Unit
    override fun onForgotten() = release()
    override fun onAbandoned() = release()
}

/** No audio focus (the builder's default), volume 0, audio tracks disabled. */
internal fun buildSilentPlayer(context: Context): ExoPlayer =
    ExoPlayer.Builder(context).build().also { player ->
        player.volume = 0f
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .build()
        player.repeatMode = Player.REPEAT_MODE_OFF
    }
