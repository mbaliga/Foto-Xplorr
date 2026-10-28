package com.fotoxplorr.app.backup

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.favorites.FavoriteStore
import com.fotoxplorr.app.hyle.ActivityKind
import com.fotoxplorr.app.jobs.JobRunner
import com.fotoxplorr.app.jobs.humanMessage
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.organize.LibraryStore
import com.fotoxplorr.app.privacy.SensitiveStore
import kotlinx.coroutines.launch

/** The two entries Settings > Data offers for "Tags and collections". */
class MetadataBackupActions internal constructor(
    val saveToFile: () -> Unit,
    val restoreFromFile: () -> Unit,
)

/**
 * Everything the plain tags-and-collections backup needs on screen: the two document pickers, the
 * review dialog a file must pass before anything is restored, and the second confirmation in front
 * of a Replace. The activity only wires [MetadataBackupActions] to its two Settings rows.
 *
 * Nothing here writes a store until the person has seen what the file holds. Restore is two steps:
 * [MetadataBackupIo.inspect] reads, checks and matches (no store is touched), the dialog shows the
 * result, and only a tap on Merge, or on Replace and then its own confirmation, runs
 * [MetadataBackupIo.apply] as a job.
 *
 * @param photos this phone's photos, read when a button is tapped (not captured), so a scan that
 *   finished a second ago counts.
 * @param onMessage where a failure is shown (the activity's message dialog).
 */
@Composable
fun rememberMetadataBackup(
    jobRunner: JobRunner,
    libraryStore: LibraryStore,
    favoriteStore: FavoriteStore,
    sensitiveStore: SensitiveStore,
    photos: () -> List<MediaAsset>,
    onMessage: (String) -> Unit,
): MetadataBackupActions {
    val context: Context = LocalContext.current
    val io = remember(context, libraryStore, favoriteStore, sensitiveStore) {
        MetadataBackupIo(context.contentResolver, libraryStore, favoriteStore, sensitiveStore)
    }
    val currentPhotos by rememberUpdatedState(photos)
    val currentOnMessage by rememberUpdatedState(onMessage)
    val scope = rememberCoroutineScope()

    var review by remember { mutableStateOf<MetadataBackup.PreparedRestore?>(null) }
    var confirmReplace by remember { mutableStateOf<MetadataBackup.PreparedRestore?>(null) }
    var reading by remember { mutableStateOf(false) }

    fun restore(prepared: MetadataBackup.PreparedRestore, mode: ImportMode) {
        jobRunner.launch(title = "Restoring tags and collections", kind = ActivityKind.BACKING_UP) { onProgress ->
            onProgress(null)
            attempt { io.apply(prepared, mode) }
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val snapshot = currentPhotos()
        jobRunner.launch(title = "Saving tags and collections", kind = ActivityKind.BACKING_UP) { onProgress ->
            onProgress(null)
            attempt { io.save(uri, snapshot, System.currentTimeMillis()) }
        }
    }

    val openLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val snapshot = currentPhotos()
        reading = true
        scope.launch {
            val result = attempt { io.inspect(uri, snapshot) }
            reading = false
            result.fold(
                onSuccess = { review = it },
                onFailure = { currentOnMessage(humanMessage(it, "This file could not be read.")) },
            )
        }
    }

    review?.let { prepared ->
        RestoreReviewDialog(
            prepared = prepared,
            onMerge = {
                review = null
                restore(prepared, ImportMode.MERGE)
            },
            onReplace = {
                review = null
                confirmReplace = prepared
            },
            onCancel = { review = null },
        )
    }
    confirmReplace?.let { prepared ->
        AlertDialog(
            onDismissRequest = { confirmReplace = null },
            title = { Text(MetadataBackupCopy.REPLACE_TITLE) },
            text = { Text(MetadataBackupCopy.REPLACE_BODY) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReplace = null
                    restore(prepared, ImportMode.REPLACE)
                }) { Text("Replace", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmReplace = null }) { Text("Cancel") } },
        )
    }
    if (reading) {
        AlertDialog(
            onDismissRequest = {},
            text = { Text("Reading the file…") },
            confirmButton = {},
        )
    }

    return remember(saveLauncher, openLauncher) {
        MetadataBackupActions(
            saveToFile = { saveLauncher.launch(MetadataBackup.SUGGESTED_FILE_NAME) },
            restoreFromFile = { openLauncher.launch(arrayOf("application/json", "text/json", "text/plain")) },
        )
    }
}

@Composable
private fun RestoreReviewDialog(
    prepared: MetadataBackup.PreparedRestore,
    onMerge: () -> Unit,
    onReplace: () -> Unit,
    onCancel: () -> Unit,
) {
    val summary = prepared.summary
    val nothingFound = summary.hasIdentity && summary.photosInFile > 0 && summary.photosFound == 0
    val empty = summary.nothingToRestore && !nothingFound
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(MetadataBackupCopy.TITLE) },
        text = {
            Column {
                if (nothingFound || empty) {
                    Text(if (nothingFound) MetadataBackupCopy.NOTHING_TO_RESTORE else "This file holds nothing to restore.")
                } else {
                    MetadataBackupCopy.savedOn(summary)?.let { Text(it) }
                    Text("It holds " + MetadataBackupCopy.contents(summary) + ".")
                    MetadataBackupCopy.matching(summary)?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it)
                    }
                    MetadataBackupCopy.unusable(summary)?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it)
                    }
                    if (!summary.hasIdentity) {
                        Spacer(Modifier.height(12.dp))
                        Text(MetadataBackupCopy.LEGACY_WARNING, color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(MetadataBackupCopy.MERGE_EXPLAINED)
                    TextButton(onClick = onReplace) { Text("Replace everything instead…") }
                }
            }
        },
        confirmButton = {
            if (nothingFound || empty) {
                TextButton(onClick = onCancel) { Text("OK") }
            } else {
                TextButton(onClick = onMerge) { Text(if (summary.hasIdentity) "Merge" else "Merge on this phone") }
            }
        },
        dismissButton = if (nothingFound || empty) null else {
            { TextButton(onClick = onCancel) { Text("Cancel") } }
        },
    )
}
