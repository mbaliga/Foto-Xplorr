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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.fotoxplorr.app.playback.rememberAudioController

/** How [AudioLibraryScreen] orders (and, for [ARTIST]/[ALBUM], groups under a header) its list. */
enum class AudioSort(val label: String) {
    TITLE("Title"),
    ARTIST("Artist"),
    ALBUM("Album"),
    RECENTLY_ADDED("Recently added"),
}

/**
 * The Audio destination's own content — a searchable, sortable list with album art, plus a "now
 * playing" mini-bar when the background player ([com.fotoxplorr.app.playback.PlaybackService]) has
 * something loaded.
 *
 * The mini-bar reuses [onPlay] rather than taking a separate navigation callback: from this
 * screen's own caller's point of view, "reopen the player for whatever is already playing" and
 * "open the player for a track I just tapped" are the same request — the track is simply already
 * known, from [assets], by matching [com.fotoxplorr.app.playback.AudioPlaybackController.mediaId]
 * back to an id here. This is what keeps the signature the host already calls unchanged.
 */
@Composable
fun AudioLibraryScreen(assets: List<AudioAsset>, onPlay: (AudioAsset) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(AudioSort.TITLE) }
    val controller = rememberAudioController()

    val filtered = remember(assets, query) {
        if (query.isBlank()) {
            assets
        } else {
            val needle = query.trim()
            assets.filter { asset ->
                asset.title.contains(needle, ignoreCase = true) ||
                    asset.artist?.contains(needle, ignoreCase = true) == true ||
                    asset.album?.contains(needle, ignoreCase = true) == true
            }
        }
    }
    val rows = remember(filtered, sort) { buildRows(sortAssets(filtered, sort), sort) }
    val nowPlaying = remember(assets, controller.mediaId) {
        controller.mediaId?.let { id -> assets.firstOrNull { it.id.value.toString() == id } }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search title, artist, album") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                singleLine = true,
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AudioSort.entries.forEach { candidate ->
                    FilterChip(
                        selected = sort == candidate,
                        onClick = { sort = candidate },
                        label = { Text(candidate.label) },
                    )
                }
            }
        }

        if (assets.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "No audio files found on this device",
                    modifier = Modifier.padding(32.dp),
                    style = TextStyle(fontSize = 15.sp),
                    color = Color.White.copy(alpha = 0.55f),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(
                    rows,
                    key = { row ->
                        when (row) {
                            is AudioListRow.Track -> "t${row.asset.id.value}"
                            is AudioListRow.Header -> "h${row.label}"
                        }
                    },
                ) { row ->
                    when (row) {
                        is AudioListRow.Header -> GroupHeader(row.label)
                        is AudioListRow.Track -> AudioRow(asset = row.asset, onClick = { onPlay(row.asset) })
                    }
                }
            }
        }

        if (nowPlaying != null) {
            NowPlayingBar(
                asset = nowPlaying,
                isPlaying = controller.isPlaying,
                onTogglePlay = controller::togglePlayPause,
                onOpen = { onPlay(nowPlaying) },
            )
        }
    }
}

private sealed interface AudioListRow {
    data class Header(val label: String) : AudioListRow
    data class Track(val asset: AudioAsset) : AudioListRow
}

private fun sortAssets(assets: List<AudioAsset>, sort: AudioSort): List<AudioAsset> = when (sort) {
    AudioSort.TITLE -> assets.sortedBy { it.title.lowercase() }
    AudioSort.ARTIST -> assets.sortedWith(
        compareBy({ (it.artist?.takeIf(String::isNotBlank) ?: "￿").lowercase() }, { it.title.lowercase() }),
    )
    AudioSort.ALBUM -> assets.sortedWith(
        compareBy(
            { (it.album?.takeIf(String::isNotBlank) ?: "￿").lowercase() },
            { it.trackNumber ?: Int.MAX_VALUE },
            { it.title.lowercase() },
        ),
    )
    AudioSort.RECENTLY_ADDED -> assets.sortedByDescending { it.dateAddedSeconds }
}

/** Only [AudioSort.ARTIST] and [AudioSort.ALBUM] group under a header — "Title" and "Recently
 *  added" are both already a single meaningful order with no natural group to head a section
 *  with. */
private fun buildRows(sorted: List<AudioAsset>, sort: AudioSort): List<AudioListRow> {
    val keyOf: ((AudioAsset) -> String)? = when (sort) {
        AudioSort.ARTIST -> { asset -> asset.artist?.takeIf(String::isNotBlank) ?: "Unknown artist" }
        AudioSort.ALBUM -> { asset -> asset.album?.takeIf(String::isNotBlank) ?: "Unknown album" }
        else -> null
    }
    if (keyOf == null) return sorted.map { AudioListRow.Track(it) }

    val rows = mutableListOf<AudioListRow>()
    var lastKey: String? = null
    sorted.forEach { asset ->
        val key = keyOf(asset)
        if (key != lastKey) {
            rows += AudioListRow.Header(key)
            lastKey = key
        }
        rows += AudioListRow.Track(asset)
    }
    return rows
}

@Composable
private fun GroupHeader(label: String) {
    Text(
        label,
        color = Color.White.copy(alpha = 0.55f),
        style = TextStyle(fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp, start = 4.dp),
    )
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
        AlbumArt(asset = asset, size = 44.dp)
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

@Composable
private fun NowPlayingBar(asset: AudioAsset, isPlaying: Boolean, onTogglePlay: () -> Unit, onOpen: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AlbumArt(asset = asset, size = 36.dp)
        Column(Modifier.weight(1f)) {
            Text(
                asset.title,
                color = Color.White,
                style = TextStyle(fontSize = 14.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            asset.artist?.let {
                Text(
                    it,
                    color = Color.White.copy(alpha = 0.5f),
                    style = TextStyle(fontSize = 12.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onTogglePlay) {
            Icon(
                if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = Color.White,
            )
        }
    }
}

/** Album art thumbnail shared by the library list, its now-playing bar, and the full player
 *  screen — [AudioAsset.albumArtUri] resolves to nothing for a track with no album grouping at
 *  all, and Coil simply renders nothing over this glyph background when a Uri fails to load
 *  (no embedded art for that specific album), so one glyph box underneath the request covers
 *  both cases with no separate error state to wire up. */
@Composable
internal fun AlbumArt(asset: AudioAsset, size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.10f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Outlined.MusicNote,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.5f),
            modifier = Modifier.size(size / 2),
        )
        if (asset.albumArtUri != null) {
            AsyncImage(
                model = asset.albumArtUri,
                contentDescription = null,
                modifier = Modifier.size(size),
            )
        }
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
