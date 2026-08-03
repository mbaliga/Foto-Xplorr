package com.fotoxplorr.app.viewer

import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.fotoxplorr.app.media.MediaAsset

@Composable
fun VideoPlayer(
    asset: MediaAsset,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val videoView = remember(asset.id) {
        VideoView(context).apply {
            val controls = MediaController(context)
            controls.setAnchorView(this)
            setMediaController(controls)
            setVideoURI(asset.contentUri)
            setOnPreparedListener { player ->
                player.isLooping = false
                start()
            }
        }
    }

    DisposableEffect(videoView) {
        onDispose { videoView.stopPlayback() }
    }

    AndroidView(
        factory = { videoView },
        modifier = modifier,
    )
}
