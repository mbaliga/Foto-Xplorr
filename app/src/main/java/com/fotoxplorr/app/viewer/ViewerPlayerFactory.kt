package com.fotoxplorr.app.viewer

import android.content.Context
import androidx.compose.runtime.RememberObserver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer

/**
 * The one place a foreground video player is configured, shared by [VideoPlayer] and the video
 * editor's preview. It uses movie audio attributes with focus and becoming-noisy handling:
 * starting a video takes audio focus and pauses other apps' music, a phone call or navigation
 * prompt pauses the video, and unplugging headphones pauses it instead of blaring from the speaker.
 *
 *  - decoder fallback: when the preferred hardware decoder for a format fails to initialise, try the
 *    next one (typically the software decoder) before reporting an error. It cannot make an
 *    unsupported codec play, only rescue a busy or flaky hardware decoder.
 *  - no wake mode: every source is a local `content://` URI, so there is nothing to hold a lock for;
 *    [VideoPlayer] keeps the screen on through the view while playback is active.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun buildViewerPlayer(context: Context): ExoPlayer =
    ExoPlayer.Builder(
        context,
        DefaultRenderersFactory(context).setEnableDecoderFallback(true),
    )
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        .build()

/**
 * Owns an [ExoPlayer] for exactly as long as a composition remembers it. `remember { build() }` +
 * `DisposableEffect { onDispose { release() } }` leaks the player when the composition that
 * created it is abandoned before it ever commits (the effect never ran, so its dispose never
 * will); a [RememberObserver] is told about abandonment as well as removal. [release] is
 * idempotent so an explicit release before leaving (after a final resume-position save) and the
 * observer's own can both run.
 */
internal class ViewerPlayerHolder(context: Context) : RememberObserver {
    val player: ExoPlayer = buildViewerPlayer(context)
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
