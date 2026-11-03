package com.fotoxplorr.app.sync

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * The "Send copies to the cloud" section of Settings > Services: status, where to, the details of that
 * place, what to send, the options, Preview and Send now, the live run, and Forget.
 *
 * Nothing here talks to the network or starts a run on its own: Test connection, Preview, Save and Send now
 * are each an explicit tap. [pickedFolderUri] is the result of the system folder picker that [onPickFolder]
 * opened (the host keeps it and passes it back; this screen takes the persistable grant itself). The form
 * lives in `rememberSaveable` WITHOUT any secret; a password, secret key or passphrase is kept in memory only
 * (Compose text fields hold a String, so the clearing here is of the CharArray handed to the service, which
 * zeroes it) and is asked for again after the process dies.
 *
 * Back is the hub's: this content has no screen of its own to pop.
 */
@Composable
fun SyncSettingsContent(
    service: SyncService,
    folders: List<SyncFolderChoice>,
    onPickFolder: () -> Unit,
    modifier: Modifier = Modifier,
    pickedFolderUri: String? = null,
) {
    val config by service.config.collectAsState()
    val run by service.state.collectAsState()
    var form by rememberSaveable(stateSaver = SyncFormSaver) { mutableStateOf(SyncForm.from(config)) }
    var secret by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var attempted by rememberSaveable { mutableStateOf(false) }
    var testState by remember { mutableStateOf<SyncOutcomeLine?>(null) }
    var previewState by remember { mutableStateOf<SyncPreviewShown?>(null) }
    var message by remember { mutableStateOf<SyncOutcomeLine?>(null) }
    var askMobile by remember { mutableStateOf(false) }
    var askForget by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(pickedFolderUri) {
        if (!pickedFolderUri.isNullOrBlank() && pickedFolderUri != form.folderUri) {
            runCatching { SyncAndroid.takeFolderGrant(context, Uri.parse(pickedFolderUri)) }
            form = form.copy(kind = SyncDestinationKind.FOLDER, folderUri = pickedFolderUri)
            testState = null
        }
    }

    val existing = config
    val built = SyncUi.buildConfig(form, existing) { "pending" }
    val sameAsSaved = existing != null && built != null && built.id == existing.id
    val facts = SyncSecretFacts(
        secretEntered = secret.isNotEmpty(),
        storedSecretKept = sameAsSaved,
        passphrase = SyncUi.passphraseStatus(form, passphrase, confirm, needConfirm = !(sameAsSaved && existing?.encryption != null)),
    )
    val errors = SyncUi.validate(form, facts, attempted)
    val gate = SyncUi.startGate(service.supportedKinds, form, SyncUi.validate(form, facts, true), facts, run)

    fun edit(next: SyncForm) {
        form = next
        testState = null
        previewState = null
        message = null
    }

    /** Saves what the form says (no network). The typed secret is handed over and cleared. */
    suspend fun save(): Boolean {
        attempted = true
        val now = SyncUi.validate(form, facts, true)
        val cfg = SyncUi.buildConfig(form, config, service::newDestinationId)
        if (!now.destinationOk || cfg == null) return false
        val chars = secret.toCharArray().takeIf { it.isNotEmpty() }
        val result = service.saveDestination(cfg, chars)
        chars?.fill('\u0000')
        return if (result.isSuccess) {
            secret = ""
            true
        } else {
            message = SyncOutcomeLine(false, result.exceptionOrNull()?.message ?: SyncCopy.SAVE_FAILED)
            false
        }
    }

    fun send(allowMobileData: Boolean) {
        scope.launch {
            if (!save()) return@launch
            val chars = passphrase.toCharArray().takeIf { form.encrypt && it.isNotEmpty() }
            when (val result = service.start(chars, allowMobileData)) {
                SyncStartResult.Started -> {
                    passphrase = ""
                    confirm = ""
                    message = null
                    previewState = null
                }
                is SyncStartResult.Refused ->
                    if (result.reason == SyncStartRefusal.WAITING_FOR_WIFI) askMobile = true else message = SyncOutcomeLine(false, result.reason.sentence)
            }
            chars?.fill('\u0000')
        }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SyncStatusCardView(SyncUi.status(config, run))
        SyncPrivacyLine()
        if (run is SyncRunState.Preparing || run is SyncRunState.Running) {
            SyncRunningCard(run, onPause = service::pause)
        }
        if (run is SyncRunState.Finished || run is SyncRunState.Failed || run is SyncRunState.Paused) {
            SyncOutcomeCard(run)
        }

        SyncStepTitle("1", SyncCopy.STEP_WHERE)
        SyncKindChoices(service.supportedKinds, form.kind) { kind -> edit(form.copy(kind = kind)) }

        val entry = form.kind?.let { kind -> syncKindRegistry.firstOrNull { it.kind == kind } }
        if (entry != null && entry.kind in service.supportedKinds) {
            SyncStepTitle("2", SyncCopy.STEP_FORM)
            val scopeForForm = SyncFormScope(
                form = form,
                errors = errors,
                secret = secret,
                onForm = ::edit,
                onSecret = { secret = it; testState = null },
                onPickFolder = onPickFolder,
            )
            entry.form(scopeForForm)
            run {
                SyncTestRow(
                    result = testState,
                    enabled = true,
                    onTest = {
                        attempted = true
                        val now = SyncUi.validate(form, facts, true)
                        val cfg = SyncUi.buildConfig(form, config) { "test" }
                        if (!now.destinationOk || cfg == null) {
                            testState = SyncOutcomeLine(false, SyncCopy.FIX_DETAILS_FIRST)
                        } else {
                            testState = SyncOutcomeLine(true, SyncCopy.TESTING)
                            scope.launch {
                                val chars = secret.toCharArray().takeIf { it.isNotEmpty() }
                                val result = service.testConnection(cfg, chars)
                                chars?.fill('\u0000')
                                testState = result.fold(
                                    onSuccess = { SyncOutcomeLine(true, it) },
                                    onFailure = { SyncOutcomeLine(false, it.message?.takeIf { m -> m.isNotBlank() } ?: SyncCopy.TEST_FAILED) },
                                )
                            }
                        }
                    },
                )
            }

            SyncStepTitle("3", SyncCopy.STEP_WHAT)
            SyncWhatToSend(form, folders, errors.selection, ::edit)

            SyncStepTitle("4", SyncCopy.STEP_OPTIONS)
            SyncOptions(
                form = form,
                passphrase = passphrase,
                confirm = confirm,
                needConfirm = !(sameAsSaved && existing?.encryption != null),
                passphraseError = errors.passphrase,
                onForm = ::edit,
                onPassphrase = { passphrase = it },
                onConfirm = { confirm = it },
            )

            SyncActions(
                gate = gate,
                preview = previewState,
                message = message,
                alreadyShown = setOf(errors.passphrase, testState?.text),
                onSave = {
                    scope.launch {
                        if (save()) message = SyncOutcomeLine(true, SyncCopy.SAVED)
                    }
                },
                onPreview = {
                    scope.launch {
                        if (!save()) return@launch
                        previewState = service.preview().fold(
                            onSuccess = { SyncPreviewShown(SyncUi.previewLine(it), SyncUi.previewNotes(it), true) },
                            onFailure = { SyncPreviewShown(it.message?.takeIf { m -> m.isNotBlank() } ?: SyncCopy.PREVIEW_FAILED, emptyList(), false) },
                        )
                    }
                },
                onSend = { send(false) },
            )
        }

        if (config != null) {
            SyncForgetButton { askForget = true }
        }
    }

    if (askMobile) {
        AlertDialog(
            onDismissRequest = { askMobile = false },
            title = { Text(SyncCopy.WIFI_NEEDED) },
            confirmButton = {
                TextButton(onClick = { askMobile = false; send(true) }, modifier = Modifier.minTarget()) { Text(SyncCopy.ALLOW_MOBILE_ONCE) }
            },
            dismissButton = {
                TextButton(onClick = { askMobile = false }, modifier = Modifier.minTarget()) { Text(SyncCopy.NOT_NOW) }
            },
        )
    }
    if (askForget) {
        AlertDialog(
            onDismissRequest = { askForget = false },
            title = { Text(SyncCopy.FORGET_ASK) },
            text = { Text(SyncCopy.FORGET_DELETES_NOTHING) },
            confirmButton = {
                TextButton(
                    onClick = {
                        askForget = false
                        service.forgetDestination()
                        form = SyncForm()
                        secret = ""
                        passphrase = ""
                        confirm = ""
                        attempted = false
                        testState = null
                        previewState = null
                        message = null
                    },
                    modifier = Modifier.minTarget(),
                ) { Text(SyncCopy.FORGET_CONFIRM) }
            },
            dismissButton = {
                TextButton(onClick = { askForget = false }, modifier = Modifier.minTarget()) { Text(SyncCopy.CANCEL) }
            },
        )
    }
    Spacer(Modifier.height(8.dp))
}

/** Saves [SyncForm] through process death: plain values only, never a secret. */
internal val SyncFormSaver: Saver<SyncForm, Any> = Saver(
    save = { it.toSaveable() },
    restore = { saved ->
        @Suppress("UNCHECKED_CAST")
        SyncForm.fromSaveable(saved as List<Any>)
    },
)

/** A library folder the person can tick for sync: [key] is `folderIdentity(asset).key.value`. */
data class SyncFolderChoice(val key: String, val label: String, val photoCount: Int)
