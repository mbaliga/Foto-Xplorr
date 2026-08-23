package com.fotoxplorr.app.viewer

import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.fotoxplorr.app.media.MediaAsset

/**
 * In-app video playback, on ExoPlayer.
 *
 * This replaced the original `android.widget.VideoView`, which is a thin veneer over the
 * platform `MediaPlayer`: no sane error surface, seek-accuracy at the mercy of the OEM, and a
 * container/codec matrix far narrower than Media3's extractors — a gallery whose direction is
 * "handle every format we can" cannot stand on it. ExoPlayer is also the exact engine the video
 * editor previews with, so playback here and preview there can never disagree about what a file
 * contains.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(
    asset: MediaAsset,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val player = remember(asset.id) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(asset.contentUri))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    AndroidView(
        factory = { viewContext ->
            PlayerView(viewContext).apply {
                this.player = player
                useController = true
            }
        },
        modifier = modifier,
    )
}
