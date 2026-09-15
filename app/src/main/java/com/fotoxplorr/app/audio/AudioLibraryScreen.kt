package com.fotoxplorr.app.audio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The Audio destination's own content — a plain list, not the photo/video grid
 * `com.fotoxplorr.app.gallery.TimelineScreen` renders for every other destination.
 *
 * A list rather than a grid on purpose: a grid mosaic is for something with a picture to show,
 * and a track has none — MediaStore's embedded album art is a real thing this could show later,
 * but a generic note glyph in every tile would not be a mosaic, it would be the same icon
 * repeated as far as the eye can scroll. Title/artist/duration in a row is what every dedicated
 * music app converges on for exactly this reason.
 */
@Composable
fun AudioLibraryScreen(assets: List<AudioAsset>, onPlay: (AudioAsset) -> Unit) {
    if (assets.isEmpty()) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Text(
                "No audio files found on this device",
                modifier = Modifier.padding(32.dp),
                style = TextStyle(fontSize = 15.sp),
                color = Color.White.copy(alpha = 0.55f),
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(assets, key = { it.id.value }) { asset ->
            AudioRow(asset = asset, onClick = { onPlay(asset) })
        }
    }
}

@Composable
private fun AudioRow(asset: AudioAsset, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier
                .background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                .padding(10.dp),
        ) {
            Icon(Icons.Outlined.MusicNote, contentDescription = null, tint = Color.White.copy(alpha = 0.7f))
        }
        Column(Modifier.weight(1f)) {
            Text(
                asset.title,
                color = Color.White,
                style = TextStyle(fontSize = 15.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = listOfNotNull(asset.artist, asset.album).joinToString(" — ")
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    color = Color.White.copy(alpha = 0.5f),
                    style = TextStyle(fontSize = 13.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            formatDurationMs(asset.durationMillis),
            color = Color.White.copy(alpha = 0.4f),
            style = TextStyle(fontSize = 13.sp),
        )
    }
}

/** "3:07" for anything under an hour, "1:03:07" past it — a music library is far more likely to
 *  hit the hour mark than a single photo's video clip, so unlike this app's other duration
 *  formatters this one does not assume minutes:seconds is always enough. */
internal fun formatDurationMs(durationMillis: Long): String {
    val totalSeconds = (durationMillis / 1_000).coerceAtLeast(0L)
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
