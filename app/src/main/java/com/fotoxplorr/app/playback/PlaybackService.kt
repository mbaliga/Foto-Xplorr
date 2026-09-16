package com.fotoxplorr.app.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Keeps a track playing after [com.fotoxplorr.app.audio.AudioPlayerScreen] is closed or the app is
 * backgrounded, with system (notification / lock-screen) transport controls — the one thing a
 * screen-bound `MediaPlayer` could never do. This service owns the single [ExoPlayer] instance for
 * the whole app; every UI surface (the player screen, the library's "now playing" mini-bar) talks
 * to it through its own short-lived `MediaController`
 * ([com.fotoxplorr.app.playback.rememberAudioController]), never directly, so closing one screen's
 * controller never touches playback itself.
 *
 * ## Foreground lifecycle
 * - **Starts foreground** automatically: `MediaSessionService` puts itself in the foreground with
 *   the session's notification the moment the player starts actually playing, and Media3's default
 *   notification provider (below) supplies that notification — nothing here calls
 *   `startForeground` directly.
 * - **Stops when idle or the queue ends.** [Player.Listener.onPlaybackStateChanged] only fires on a
 *   genuine state CHANGE, never for the state the player already has when the listener is
 *   attached — so this does not fire the instant the service is created, before anything has ever
 *   been queued. It fires later, for real: `STATE_IDLE` after an explicit `stop()` or an
 *   unrecoverable error, `STATE_ENDED` once the whole queue has played through with repeat off.
 *   Both mean "nothing left to do", so both call [stopSelf].
 * - **`onTaskRemoved`: pause is stopped, playing keeps going.** Swiping this app out of the
 *   recents list is not "stop my music" for any mainstream music app, and stopping playback there
 *   would be a straight regression from the screen-bound player this replaces (which, for better
 *   or worse, played on until its Activity died anyway). But a session that is not actively
 *   playing has no notification worth keeping alive for and no reason to hold a foreground
 *   service open, so that case stops immediately rather than lingering until Android decides to
 *   reclaim it.
 *
 * ## What this class does NOT do
 * No custom [MediaSession.Callback] — the default one already accepts every standard command
 * (play/pause/seek/skip/shuffle/repeat/speed) an [ExoPlayer] supports, and every [android.net.Uri]
 * this app ever hands it (see [buildMediaItem]) already carries its own playable
 * `LocalConfiguration`, so the default `onSetMediaItems`/`onAddMediaItems` resolution — which only
 * exists to resolve items that do NOT already carry a URI — never needs overriding here.
 */
class PlaybackService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var session: MediaSession? = null

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
                stopSelf()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()

        val exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            // LOCAL, never NETWORK: every source this app hands the player is a local
            // content:// Uri (see buildMediaItem) — there is nothing to hold a network
            // (Wi-Fi) lock open for, and requesting one would be a permission this offline-
            // first app has no other reason to declare.
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
            .also { it.addListener(playerListener) }
        player = exoPlayer

        session = MediaSession.Builder(this, exoPlayer).build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(NOTIFICATION_CHANNEL_ID)
                .build(),
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val exoPlayer = player
        if (exoPlayer == null || !exoPlayer.isPlaying) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        session?.run {
            player?.removeListener(playerListener)
            player?.release()
            release()
        }
        session = null
        player = null
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Playback",
                // LOW: a running-transport notification, not an alert -- no sound, no heads-up.
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private companion object {
        const val NOTIFICATION_CHANNEL_ID = "audio_playback"
    }
}
