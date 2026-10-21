package com.fotoxplorr.app.backup

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.fotoxplorr.app.jobs.RunningJob
import com.fotoxplorr.app.shell.AppStateViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import com.fotoxplorr.app.ui.HelpCopy
import com.fotoxplorr.app.ui.RoomRow
import com.fotoxplorr.app.ui.RoomStyle

/**
 * The two Settings > Data rows of the encrypted backup, and every dialog behind them. Self-contained
 * on purpose: it takes no argument and adds no field to `GalleryActions` or `GalleryUiState`. It
 * finds the activity's `JobRunner` through the view-model store (the same instance the activity
 * uses), and the stores it needs it opens itself when a button is tapped. See [BackupFlow].
 *
 * Nothing here is saved across a rotation or process death (no `rememberSaveable`): the passphrase
 * must not reach a Bundle. A rotation while a dialog is open starts the flow again; a backup that is
 * already running is a job, and carries on.
 */
private val NO_JOBS = MutableStateFlow<List<RunningJob>>(emptyList())

@Composable
fun EncryptedBackupRows() {
    val context = LocalContext.current
    val owner = LocalViewModelStoreOwner.current
    val scope = rememberCoroutineScope()
    val flow = remember(owner) {
        BackupFlow(
            context = context,
            scope = scope,
            jobRunner = { owner?.let { ViewModelProvider(it)[AppStateViewModel::class.java].jobRunner } },
        )
    }
    DisposableEffect(flow) { onDispose { flow.dispose() } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { flow.onFolderPicked(it) }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { flow.permissionMayHaveChanged() }
    flow.pickFolder = { picker.launch(null) }

    // Re-read the location permission whenever the person returns from system settings.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { flow.permissionMayHaveChanged() }

    val jobs by (flow.runner()?.jobs ?: NO_JOBS).collectAsState()
    val running = jobs.any { it.title == BackupCopy.JOB_TITLE || it.title == BackupCopy.CHECK_JOB_TITLE }
    RoomRow(
        label = BackupCopy.OPTION_LABEL,
        caption = HelpCopy.Hint.BACKUP_ENCRYPTED,
        enabled = !running,
        onClick = { flow.begin(BackupPurpose.BACKUP) },
    )
    RoomRow(
        label = BackupCopy.CHECK_LABEL,
        caption = HelpCopy.Hint.BACKUP_CHECK,
        onClick = { flow.begin(BackupPurpose.CHECK) },
    )

    BackupDialogs(flow, requestLocation = { locationPermission.launch(Manifest.permission.ACCESS_MEDIA_LOCATION) })
}

@Composable
private fun BackupDialogs(flow: BackupFlow, requestLocation: () -> Unit) {
    when (val stage = flow.stage) {
        BackupStage.Idle -> Unit
        is BackupStage.Working -> WorkingDialog(stage.message, onStop = flow::cancel)
        is BackupStage.Passphrase -> PassphraseDialog(
            stage = stage,
            onSubmit = flow::submit,
            onCancel = flow::cancel,
        )
        is BackupStage.Preflight -> PreflightDialog(
            flow = flow,
            stage = stage,
            requestLocation = requestLocation,
        )
        is BackupStage.Message -> AlertDialog(
            onDismissRequest = flow::dismissMessage,
            title = { Text(stage.title) },
            text = { Text(stage.text) },
            confirmButton = { TextButton(onClick = flow::dismissMessage) { Text("OK") } },
        )
    }
}

@Composable
private fun WorkingDialog(message: String, onStop: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        text = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                Text(message)
            }
        },
        confirmButton = { TextButton(onClick = onStop) { Text("Stop") } },
    )
}

/**
 * Choosing a passphrase (a new backup) or entering one (an existing backup). For a new one: the
 * minimum length, the same text twice, and the tick that says there is no recovery. The sentence
 * `HelpCopy.Note.BACKUP_NO_RECOVERY` is the first thing in the body, where it cannot be missed.
 */
@Composable
private fun PassphraseDialog(stage: BackupStage.Passphrase, onSubmit: (CharArray) -> Unit, onCancel: () -> Unit) {
    var passphrase by remember(stage) { mutableStateOf("") }
    var confirm by remember(stage) { mutableStateOf("") }
    var understood by remember(stage) { mutableStateOf(false) }
    var visible by remember(stage) { mutableStateOf(false) }
    val problem = PassphraseForm.problem(passphrase, confirm, understood, stage.newSet)
    val strength = PassphraseStrength.of(passphrase)
    val transformation = if (visible) VisualTransformation.None else PasswordVisualTransformation()
    val options = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (stage.newSet) BackupCopy.PASSPHRASE_NEW_TITLE else BackupCopy.PASSPHRASE_EXISTING_TITLE) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (stage.newSet) {
                    Text(HelpCopy.Note.BACKUP_NO_RECOVERY, color = MaterialTheme.colorScheme.error)
                    Text(BackupCopy.PASSPHRASE_SHARING)
                } else {
                    Text("Backup in ${stage.folderLabel}.")
                    if (stage.wrong) Text(BackupCopy.WRONG_PASSPHRASE, color = MaterialTheme.colorScheme.error)
                }
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("Passphrase") },
                    singleLine = true,
                    visualTransformation = transformation,
                    keyboardOptions = options,
                )
                if (stage.newSet) {
                    Text(BackupCopy.strengthHint(strength), style = RoomStyle.Caption)
                    OutlinedTextField(
                        value = confirm,
                        onValueChange = { confirm = it },
                        label = { Text("Passphrase again") },
                        singleLine = true,
                        visualTransformation = transformation,
                        keyboardOptions = options,
                        isError = problem == PassphraseProblem.MISMATCH,
                        supportingText = if (problem == PassphraseProblem.MISMATCH) {
                            { Text(BackupCopy.MISMATCH) }
                        } else {
                            null
                        },
                    )
                }
                TextButton(onClick = { visible = !visible }) { Text(if (visible) "Hide" else "Show") }
                if (stage.newSet) {
                    Row(
                        Modifier.toggleable(value = understood, role = Role.Checkbox, onValueChange = { understood = it }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = understood, onCheckedChange = null)
                        Text(HelpCopy.Hint.BACKUP_UNDERSTAND, Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = problem == null,
                onClick = {
                    // A CharArray, so the key derivation can wipe it; the typed String stays until collected.
                    val chars = passphrase.toCharArray()
                    onSubmit(chars)
                },
            ) { Text(if (stage.newSet) "Continue" else "Open") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/** What is about to happen, before anything is written. */
@Composable
private fun PreflightDialog(flow: BackupFlow, stage: BackupStage.Preflight, requestLocation: () -> Unit) {
    // Read again on every permission change, so Allow turns the warning into the all-clear.
    val lines = remember(stage, flow.permissionRevision) { BackupCopy.preflight(flow.factsFor(stage)) }
    val leftovers = stage.preparation.loaded.leftovers
    val locationMissing = lines.any { it.text == BackupCopy.LOCATION_MISSING }

    AlertDialog(
        onDismissRequest = flow::cancel,
        title = { Text("Back up to ${stage.folderLabel}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                lines.forEach { line ->
                    Text(
                        line.text,
                        color = when (line.severity) {
                            BackupCopy.Severity.INFO -> MaterialTheme.colorScheme.onSurface
                            BackupCopy.Severity.WARNING -> MaterialTheme.colorScheme.tertiary
                            BackupCopy.Severity.PROBLEM -> MaterialTheme.colorScheme.error
                        },
                    )
                }
                if (locationMissing) TextButton(onClick = requestLocation) { Text("Allow reading photo locations") }
                if (leftovers.isNotEmpty()) TextButton(onClick = flow::askRemoveLeftovers) { Text("Remove the leftover files…") }
                Spacer(Modifier.height(4.dp))
            }
        },
        confirmButton = { TextButton(onClick = flow::start) { Text("Start backup") } },
        dismissButton = { TextButton(onClick = flow::cancel) { Text("Cancel") } },
    )

    if (stage.leftoversConfirm) {
        AlertDialog(
            onDismissRequest = flow::keepLeftovers,
            title = { Text("Remove leftover files?") },
            text = {
                Text(
                    "${BackupCopy.count(leftovers.size, "file")} (${BackupCopy.size(stage.preparation.loaded.leftoverBytes)}) " +
                        "from an interrupted backup. They cannot be opened or used. Nothing else in the folder is touched.",
                )
            },
            confirmButton = { TextButton(onClick = flow::removeLeftovers) { Text("Remove", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = flow::keepLeftovers) { Text("Keep them") } },
        )
    }
}
