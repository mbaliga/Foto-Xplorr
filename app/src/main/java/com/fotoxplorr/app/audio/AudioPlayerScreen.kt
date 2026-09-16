@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.fotoxplorr.app.audio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.RepeatOne
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.fotoxplorr.app.audiotags.AudioTagEditorSheet
import com.fotoxplorr.app.audiotags.AudioTagWriter
import com.fotoxplorr.app.playback.rememberAudioController
import kotlinx.coroutines.launch

/**
 * The audio player — now backed by [com.fotoxplorr.app.playback.PlaybackService] through
 * [rememberAudioController] rather than a screen-owned [android.media.MediaPlayer]: playback
 * continues in the background with system transport controls after this screen closes, which a
 * bare `MediaPlayer` (this screen's previous implementation) could never do. This screen itself
 * holds none of the actual playback state any more — every field below mirrors the controller,
 * and closing this screen (or the whole app) never stops a track that is actually playing.
 *
 * [queue]/[asset] are still how the CALLER hands this screen a track and its play order — matching
 * [com.fotoxplorr.app.audio.AudioLibraryComposition]'s existing contract exactly, so nothing about
 * how a caller starts playback needed to change. [onSelect] is now driven by the SERVICE's own
 * track transitions (auto-advance, shuffle, a notification's skip action), not by this screen
 * guessing "next" from [queue] itself — see the `mediaId`-watching effect below.
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
    val controller = rememberAudioController()
    val tagWriter = remember { AudioTagWriter(context) }
    val scope = rememberCoroutineScope()

    var isDragging by remember { mutableStateOf(false) }
    var dragPositionMs by remember { mutableStateOf(0L) }
    var showQueueSheet by remember { mutableStateOf(false) }
    var showTagEditor by remember { mutableStateOf(false) }

    val latestOnSelect by rememberUpdatedState(onSelect)
    val latestQueue by rememberUpdatedState(queue)

    // Starts (or restarts) the service's playlist only when this screen is handed a track the
    // controller is not ALREADY on -- reopening the player for a session that is already running
    // (the common case: the mini-bar, or navigating back into a still-playing track) must not
    // restart it from position zero.
    LaunchedEffect(controller.controller, asset.id, queue) {
        val mc = controller.controller ?: return@LaunchedEffect
        if (controller.mediaId != asset.id.value.toString()) {
            val startIndex = queue.indexOfFirst { it.id == asset.id }.coerceAtLeast(0)
            controller.playQueue(queue, startIndex)
        }
    }

    // The service, not this screen, decides what plays next (auto-advance, shuffle, a
    // notification's skip action) -- this mirrors that decision back into the host's own
    // selection state so the rest of the app (the title bar, the library's now-playing row)
    // agrees with what is actually playing.
    LaunchedEffect(controller.mediaId) {
        val id = controller.mediaId ?: return@LaunchedEffect
        if (id == asset.id.value.toString()) return@LaunchedEffect
        latestQueue.firstOrNull { it.id.value.toString() == id }?.let(latestOnSelect)
    }

    BackHandler(onBack = onClose)

    val displayPositionMs = if (isDragging) dragPositionMs else controller.positionMs
    val durationMs = controller.durationMs.takeIf { it > 0L } ?: asset.durationMillis.coerceAtLeast(0L)

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Row {
                IconButton(onClick = { showTagEditor = true }) {
                    Icon(Icons.Outlined.Edit, contentDescription = "Edit tags", tint = Color.White)
                }
                if (onConvertToAac != null) {
                    Row(
                        Modifier
                            .clickable(enabled = !isConverting, onClick = onConvertToAac)
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.SwapHoriz,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = if (isConverting) 0.4f else 1f),
                        )
                        Text(
                            if (isConverting) "Converting…" else "Convert to AAC",
                            color = Color.White.copy(alpha = if (isConverting) 0.4f else 1f),
                            style = TextStyle(fontSize = 13.sp),
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }
            Row {
                IconButton(onClick = { showQueueSheet = true }) {
                    Icon(Icons.Outlined.QueueMusic, contentDescription = "Queue", tint = Color.White)
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close", tint = Color.White)
                }
            }
        }

        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AlbumArt(asset = asset, size = 220.dp)
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
                value = displayPositionMs.toFloat().coerceIn(0f, durationMs.toFloat().coerceAtLeast(0f)),
                onValueChange = {
                    isDragging = true
                    dragPositionMs = it.toLong()
                    controller.beginScrub()
                },
                onValueChangeFinished = {
                    controller.endScrub(dragPositionMs)
                    isDragging = false
                },
                valueRange = 0f..durationMs.toFloat().coerceAtLeast(1f),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatDurationMs(displayPositionMs), color = Color.White.copy(alpha = 0.6f), style = TextStyle(fontSize = 12.sp))
                Text(formatDurationMs(durationMs), color = Color.White.copy(alpha = 0.6f), style = TextStyle(fontSize = 12.sp))
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(onClick = { controller.setShuffleEnabled(!controller.shuffleModeEnabled) }) {
                    Icon(
                        Icons.Outlined.Shuffle,
                        contentDescription = if (controller.shuffleModeEnabled) "Shuffle on" else "Shuffle off",
                        tint = Color.White.copy(alpha = if (controller.shuffleModeEnabled) 1f else 0.4f),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = controller::skipToPrevious) {
                        Icon(Icons.Outlined.SkipPrevious, contentDescription = "Previous", tint = Color.White, modifier = Modifier.size(36.dp))
                    }
                    IconButton(onClick = controller::togglePlayPause, modifier = Modifier.padding(horizontal = 16.dp)) {
                        Icon(
                            if (controller.isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                            contentDescription = if (controller.isPlaying) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(48.dp),
                        )
                    }
                    IconButton(onClick = controller::skipToNext) {
                        Icon(Icons.Outlined.SkipNext, contentDescription = "Next", tint = Color.White, modifier = Modifier.size(36.dp))
                    }
                }
                IconButton(onClick = controller::cycleRepeatMode) {
                    Icon(
                        if (controller.repeatMode == Player.REPEAT_MODE_ONE) Icons.Outlined.RepeatOne else Icons.Outlined.Repeat,
                        contentDescription = "Repeat",
                        tint = Color.White.copy(alpha = if (controller.repeatMode == Player.REPEAT_MODE_OFF) 0.4f else 1f),
                    )
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
                AssistChip(
                    onClick = { controller.setPlaybackSpeed(nextSpeed(controller.speed)) },
                    label = { Text(formatSpeed(controller.speed)) },
                )
            }
        }
    }

    if (showQueueSheet) {
        QueueSheet(
            queue = controller.queue.ifEmpty { queue },
            currentIndex = controller.currentIndex,
            onDismiss = { showQueueSheet = false },
            onPick = { index ->
                controller.jumpToQueueIndex(index)
                showQueueSheet = false
            },
        )
    }

    if (showTagEditor) {
        AudioTagEditorSheet(
            asset = asset,
            onDismiss = { showTagEditor = false },
            onSave = { tags ->
                showTagEditor = false
                scope.launch { tagWriter.write(asset, tags) }
            },
        )
    }
}

@Composable
private fun QueueSheet(
    queue: List<AudioAsset>,
    currentIndex: Int,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 8.dp).navigationBarsPadding()) {
            items(queue.size, key = { index -> queue[index].id.value }) { index ->
                val trackAsset = queue[index]
                val isCurrent = index == currentIndex
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onPick(index) }
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AlbumArt(asset = trackAsset, size = 40.dp)
                    Column(Modifier.weight(1f)) {
                        Text(
                            trackAsset.title,
                            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        trackAsset.artist?.let {
                            Text(it, style = TextStyle(fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

private val SPEED_STEPS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

private fun nextSpeed(current: Float): Float {
    val index = SPEED_STEPS.indexOfFirst { kotlin.math.abs(it - current) < 0.01f }
    return SPEED_STEPS[(index + 1) % SPEED_STEPS.size]
}

private fun formatSpeed(speed: Float): String =
    if (speed == speed.toLong().toFloat()) "${speed.toLong()}x" else "${"%.2f".format(speed).trimEnd('0').trimEnd('.')}x"
