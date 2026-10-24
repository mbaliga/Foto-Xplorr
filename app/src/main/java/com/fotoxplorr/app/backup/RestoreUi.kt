package com.fotoxplorr.app.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.ui.RoomStyle

/**
 * The two cards of the encrypted restore (the passphrase form is the backup's own,
 * [BackupStage.Passphrase]). The first says what the backup holds and asks what to put back; the
 * second says what that would do on THIS phone (restored, already here, renamed, missing,
 * skipped) and only its button writes anything. Nothing is saved across a rotation: the flow
 * starts again, as the backup's does.
 */
@Composable
internal fun RestoreChooseDialog(flow: BackupFlow, stage: BackupStage.RestoreChoose) {
    val info = stage.set.info
    val hasTags = info.metadata != null
    val hasFiles = info.files > 0
    var choice by remember(stage) { mutableStateOf(if (hasFiles) RestoreScope.EVERYTHING else RestoreScope.TAGS_ONLY) }
    val lines = remember(stage) { RestoreCopy.summary(info) }

    AlertDialog(
        onDismissRequest = flow::cancel,
        title = { Text("Restore from ${stage.folderLabel}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                lines.forEach { line -> LineText(line) }
                if (!hasTags) Text(RestoreCopy.NO_TAGS_IN_BACKUP, style = RoomStyle.Caption)
                Text("What do you want to restore?", style = MaterialTheme.typography.titleSmall)
                ScopeOption(
                    selected = choice == RestoreScope.EVERYTHING,
                    enabled = hasFiles,
                    label = RestoreCopy.OPTION_EVERYTHING,
                    caption = RestoreCopy.OPTION_EVERYTHING_CAPTION,
                    onSelect = { choice = RestoreScope.EVERYTHING },
                )
                ScopeOption(
                    selected = choice == RestoreScope.TAGS_ONLY,
                    enabled = hasTags,
                    label = RestoreCopy.OPTION_TAGS,
                    caption = RestoreCopy.OPTION_TAGS_CAPTION,
                    onSelect = { choice = RestoreScope.TAGS_ONLY },
                )
            }
        },
        confirmButton = {
            val allowed = when (choice) {
                RestoreScope.EVERYTHING -> hasFiles
                RestoreScope.TAGS_ONLY -> hasTags
            }
            TextButton(enabled = allowed, onClick = { flow.prepareRestore(choice) }) { Text("Continue") }
        },
        dismissButton = { TextButton(onClick = flow::cancel) { Text("Cancel") } },
    )
}

@Composable
private fun ScopeOption(selected: Boolean, enabled: Boolean, label: String, caption: String, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, enabled = enabled, onClick = null)
        Column(Modifier.padding(start = 12.dp)) {
            Text(label, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(caption, style = RoomStyle.Caption)
        }
    }
}

@Composable
internal fun RestoreReviewDialog(flow: BackupFlow, stage: BackupStage.RestoreReview, requestLocation: () -> Unit) {
    // Read again on every permission change, so Allow turns the warning into the all-clear.
    val lines = remember(stage, flow.permissionRevision) { RestoreCopy.review(stage.preparation, flow.reviewFacts(stage)) }
    val locationMissing = lines.any { it.text == RestoreCopy.LOCATION_MISSING_RESTORE }
    val nothingToDo = stage.preparation.isNoOp

    AlertDialog(
        onDismissRequest = flow::cancel,
        title = { Text(if (stage.preparation.scope == RestoreScope.TAGS_ONLY) "Add tags and collections" else "Restore to this phone") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                lines.forEach { line -> LineText(line) }
                if (locationMissing) TextButton(onClick = requestLocation) { Text("Allow reading photo locations") }
            }
        },
        confirmButton = {
            TextButton(enabled = !nothingToDo, onClick = flow::startRestore) {
                Text(if (stage.preparation.scope == RestoreScope.TAGS_ONLY) "Add" else "Restore")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = flow::backToRestoreChoice) { Text("Back") }
                TextButton(onClick = flow::cancel) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun LineText(line: BackupCopy.Line) {
    Text(
        line.text,
        color = when (line.severity) {
            BackupCopy.Severity.INFO -> MaterialTheme.colorScheme.onSurface
            BackupCopy.Severity.WARNING -> MaterialTheme.colorScheme.tertiary
            BackupCopy.Severity.PROBLEM -> MaterialTheme.colorScheme.error
        },
    )
}
