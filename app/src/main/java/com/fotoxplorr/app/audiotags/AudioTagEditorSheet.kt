@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.fotoxplorr.app.audiotags

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.audio.AudioAsset

/**
 * "Edit tags" — reads [asset]'s file-embedded tags through [AudioTagWriter], lets a person change
 * title/artist/album/year/track/genre, and hands the result to [onSave] (which is expected to call
 * [AudioTagWriter.write], the same permission-consent dance
 * [com.fotoxplorr.app.metadata.MetadataWriter] already goes through — see [AudioTagWriter]'s own
 * doc). Cover art is shown read-only here (there is no pick-a-new-image flow in this first pass);
 * whatever cover the file already carries is what gets written back unchanged.
 *
 * A blank field on save means "no value" ([AudioTags]' own null, per-field), NOT "clear the
 * existing value" — see [AudioTagCodec.write]'s doc for why this codec cannot yet express that
 * distinction, and `docs/audio-playback.md` for it stated as a limit.
 */
@Composable
fun AudioTagEditorSheet(
    asset: AudioAsset,
    onSave: (AudioTags) -> Unit,
    onDismiss: () -> Unit = {},
) {
    val context = LocalContext.current
    val writer = remember { AudioTagWriter(context) }

    var loaded by remember(asset.id) { mutableStateOf<AudioTags?>(null) }
    var title by remember(asset.id) { mutableStateOf(asset.title) }
    var artist by remember(asset.id) { mutableStateOf(asset.artist.orEmpty()) }
    var album by remember(asset.id) { mutableStateOf(asset.album.orEmpty()) }
    var year by remember(asset.id) { mutableStateOf("") }
    var track by remember(asset.id) { mutableStateOf("") }
    var genre by remember(asset.id) { mutableStateOf("") }

    LaunchedEffect(asset.id) {
        val tags = writer.read(asset) ?: AudioTags()
        loaded = tags
        tags.title?.let { title = it }
        tags.artist?.let { artist = it }
        tags.album?.let { album = it }
        tags.year?.let { year = it.toString() }
        tags.trackNumber?.let { track = it.toString() }
        tags.genre?.let { genre = it }
    }

    val coverBitmap = remember(loaded?.coverArt) {
        loaded?.coverArt?.let { bytes ->
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    Modifier.size(56.dp).clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    if (coverBitmap != null) {
                        Image(bitmap = coverBitmap, contentDescription = null)
                    } else {
                        Icon(Icons.Outlined.MusicNote, contentDescription = null)
                    }
                }
                Column {
                    Text("Edit tags", style = MaterialTheme.typography.titleMedium)
                    Text(
                        asset.displayName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = artist,
                onValueChange = { artist = it },
                label = { Text("Artist") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = album,
                onValueChange = { album = it },
                label = { Text("Album") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = year,
                    onValueChange = { value -> if (value.all(Char::isDigit)) year = value },
                    label = { Text("Year") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = track,
                    onValueChange = { value -> if (value.all(Char::isDigit)) track = value },
                    label = { Text("Track #") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(
                value = genre,
                onValueChange = { genre = it },
                label = { Text("Genre") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                TextButton(
                    onClick = {
                        onSave(
                            AudioTags(
                                title = title.trim().takeIf(String::isNotBlank),
                                artist = artist.trim().takeIf(String::isNotBlank),
                                album = album.trim().takeIf(String::isNotBlank),
                                year = year.toIntOrNull(),
                                trackNumber = track.toIntOrNull(),
                                genre = genre.trim().takeIf(String::isNotBlank),
                                coverArt = loaded?.coverArt,
                            ),
                        )
                    },
                ) { Text("Save") }
            }
        }
    }
}
