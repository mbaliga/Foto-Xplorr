package com.fotoxplorr.app.sync

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * PLACEHOLDER for the stage-2 UI unit, which replaces this file's body and keeps the signature:
 *
 * ```
 * @Composable fun SyncSettingsContent(service: SyncService, folders: List<SyncFolderChoice>, onPickFolder: () -> Unit, modifier: Modifier = Modifier)
 * ```
 *
 * The screen it draws is the "Send copies to the cloud" section of Settings: pick a destination kind
 * (folder / WebDAV / S3; Drive and Dropbox come later), fill in its fields, Test connection, choose what
 * to send (all photos, folders, favourites), the Wi-Fi only / size limit / encrypt options, a Start
 * button with the [SyncService.preview] line above it, and the live [SyncService.state]. [onPickFolder]
 * opens the system folder picker; its result goes to [SyncAndroid.takeFolderGrant] and then into a
 * [SyncDestinationConfig.folderTreeUri].
 * Until then it shows one line, so nothing implies sync is running.
 */
@Composable
fun SyncSettingsContent(
    service: SyncService,
    folders: List<SyncFolderChoice>,
    onPickFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(SyncCopy.OFF_BY_DEFAULT, modifier)
}

/** A library folder the person can tick for sync: [key] is `folderIdentity(asset).key.value`. */
data class SyncFolderChoice(val key: String, val label: String, val photoCount: Int)
