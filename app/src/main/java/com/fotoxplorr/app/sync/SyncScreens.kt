package com.fotoxplorr.app.sync

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddToDrive
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.hyle.HyleTextField
import com.fotoxplorr.app.hyle.HyleToggle

/** One result sentence (a test, a save, a refusal). [ok] only picks the icon; the sentence says it in words. */
internal data class SyncOutcomeLine(val ok: Boolean, val text: String)

/** The Preview answer: the headline line, the size-limit notes, and whether it worked. */
internal data class SyncPreviewShown(val line: String, val notes: List<String>, val ok: Boolean)

/** What a per-kind form composable gets: the form, its errors, and the callbacks. */
internal class SyncFormScope(
    val form: SyncForm,
    val errors: SyncFormErrors,
    val secret: String,
    val onForm: (SyncForm) -> Unit,
    val onSecret: (String) -> Unit,
    val onPickFolder: () -> Unit,
    /** Google Drive / Dropbox: an account is shown and a token is held (in memory or saved). */
    val signedIn: Boolean = false,
    val signingIn: Boolean = false,
    val oauthMessage: SyncOutcomeLine? = null,
    val onSignIn: () -> Unit = {},
    val onSignOut: () -> Unit = {},
)

/**
 * One destination kind on screen. A NEW kind is one entry here plus one form composable; the choice cards,
 * the form dispatch and the "supported" filtering all read this list.
 */
internal class SyncKindUi(
    val kind: SyncDestinationKind,
    val title: String,
    val about: String,
    val icon: ImageVector,
    val form: @Composable (SyncFormScope) -> Unit,
)

internal val syncKindRegistry: List<SyncKindUi> = listOf(
    SyncKindUi(SyncDestinationKind.FOLDER, SyncCopy.KIND_FOLDER, SyncCopy.KIND_FOLDER_ABOUT, Icons.Outlined.Folder) { SyncFolderForm(it) },
    SyncKindUi(SyncDestinationKind.WEBDAV, SyncCopy.KIND_WEBDAV, SyncCopy.KIND_WEBDAV_ABOUT, Icons.Outlined.CloudQueue) { SyncWebDavForm(it) },
    SyncKindUi(SyncDestinationKind.S3, SyncCopy.KIND_S3, SyncCopy.KIND_S3_ABOUT, Icons.Outlined.Storage) { SyncS3Form(it) },
    SyncKindUi(SyncDestinationKind.GDRIVE, SyncCopy.KIND_DRIVE, SyncCopy.KIND_DRIVE_ABOUT, Icons.Outlined.AddToDrive) { SyncOAuthForm(it, google = true) },
    SyncKindUi(SyncDestinationKind.DROPBOX, SyncCopy.KIND_DROPBOX, SyncCopy.KIND_DROPBOX_ABOUT, Icons.Outlined.Cloud) { SyncOAuthForm(it, google = false) },
)

internal fun Modifier.minTarget(): Modifier = this.heightIn(min = 48.dp)

@Composable
private fun SyncCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() } }
}

@Composable
internal fun SyncStatusCardView(card: SyncStatusCard) {
    SyncCard(Modifier.testTag("sync-status")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (card.stage == SyncStage.FAILED) Icons.Outlined.ErrorOutline else Icons.Outlined.Cloud,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                card.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite },
            )
        }
        Text(card.line, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun SyncPrivacyLine() {
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(SyncCopy.PRIVACY, style = MaterialTheme.typography.bodySmall)
            Text(SyncCopy.ONE_WAY, style = MaterialTheme.typography.bodySmall)
            Text(SyncCopy.NEVER_PROTECTED, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun SyncRunningCard(run: SyncRunState, onPause: () -> Unit) {
    val progress = (run as? SyncRunState.Running)?.progress
    SyncCard(Modifier.testTag("sync-running")) {
        val text = SyncUi.progressText(run) ?: "Getting ready…"
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Progress, ${(progress * 100).toInt()} percent" },
            )
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "Getting ready" })
        }
        // The status card above already says "12 of 340 files"; saying it twice would be read out twice.
        (run as? SyncRunState.Running)?.let { r -> SyncUi.tallyLines(r.tally).forEach { Text(it, style = MaterialTheme.typography.bodySmall) } }
        Text(SyncCopy.RUNNING_NOTE, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onPause, modifier = Modifier.minTarget().testTag("sync-pause")) { Text(SyncCopy.PAUSE) }
    }
}

@Composable
internal fun SyncOutcomeCard(run: SyncRunState) {
    val (tally, headline) = when (run) {
        is SyncRunState.Finished -> run.tally to null
        is SyncRunState.Paused -> run.tally to SyncCopy.CANCELLED
        is SyncRunState.Failed -> run.tally to null // the status card above already says why
        else -> return
    }
    SyncCard(Modifier.testTag("sync-outcome")) {
        if (headline != null) Text(headline, style = MaterialTheme.typography.bodyMedium)
        SyncUi.tallyLines(tally).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
internal fun SyncStepTitle(number: String, title: String) {
    Text(
        "$number. $title",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 6.dp).semantics { heading() },
    )
}

@Composable
internal fun SyncKindChoices(supported: Set<SyncDestinationKind>, selected: SyncDestinationKind?, onChoose: (SyncDestinationKind) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        syncKindRegistry.filter { it.kind in supported }.forEach { entry ->
            SyncChoiceCard(
                title = entry.title,
                about = entry.about,
                icon = entry.icon,
                selected = entry.kind == selected,
                tag = "sync-kind-${entry.kind.code}",
                onClick = { onChoose(entry.kind) },
            )
        }
        if (syncKindRegistry.any { it.kind !in supported }) {
            Text(SyncCopy.NEEDS_CONNECT_BUILD, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** A choice card; [onClick] null draws it disabled ("Coming next"). The chosen one carries a tick and "Selected", not only a colour. */
@Composable
private fun SyncChoiceCard(title: String, about: String, icon: ImageVector, selected: Boolean, tag: String, onClick: (() -> Unit)?) {
    val enabled = onClick != null
    val border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = border,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .selectable(selected = selected, enabled = enabled, role = androidx.compose.ui.semantics.Role.RadioButton, onClick = onClick ?: {})
            .semantics { stateDescription = if (!enabled) SyncCopy.COMING_NEXT else if (selected) SyncCopy.SELECTED else SyncCopy.NOT_SELECTED },
    ) {
        Row(Modifier.minTarget().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.4f))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.5f))
                Text(about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.8f else 0.5f))
            }
            if (selected) Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

// ---- the per-kind forms -------------------------------------------------------------------------------

@Composable
private fun SyncField(
    label: String,
    value: String,
    error: String?,
    tag: String,
    onChange: (String) -> Unit,
    password: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
) {
    HyleTextField(
        value = value,
        onValueChange = onChange,
        label = label,
        errorText = error,
        fieldTag = tag,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboard),
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
internal fun SyncSwitchRow(label: String, checked: Boolean, tag: String, note: String? = null, onChange: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().minTarget().testTag(tag).toggleable(value = checked, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onChange),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            HyleToggle(checked = checked, onCheckedChange = null)
        }
        if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 4.dp))
    }
}

@Composable
internal fun SyncFolderForm(s: SyncFormScope) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val chosen = SyncUi.folderName(s.form.folderUri)
        Text(
            if (chosen.isEmpty()) SyncCopy.NO_FOLDER_YET else chosen,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.testTag("sync-folder-name"),
        )
        OutlinedButton(onClick = s.onPickFolder, modifier = Modifier.minTarget().testTag("sync-choose-folder")) {
            Text(if (chosen.isEmpty()) SyncCopy.CHOOSE_FOLDER else SyncCopy.CHANGE_FOLDER)
        }
        s.errors.folder?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Text(SyncCopy.FOLDER_NEEDS_NO_NETWORK, style = MaterialTheme.typography.bodySmall)
        Text(SyncCopy.FOLDER_CLOUD_APP, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun SyncWebDavForm(s: SyncFormScope) {
    Column {
        SyncField(SyncCopy.SERVER_ADDRESS, s.form.webDavUrl, s.errors.webDavUrl, "sync-webdav-url", { s.onForm(s.form.copy(webDavUrl = it)) }, keyboard = KeyboardType.Uri)
        SyncUi.webDavPreview(s.form)?.let {
            Text("Fotoz will use $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("sync-webdav-preview").padding(bottom = 6.dp))
        }
        SyncField(SyncCopy.USER_NAME, s.form.webDavUser, s.errors.webDavUser, "sync-webdav-user", { s.onForm(s.form.copy(webDavUser = it)) })
        SyncField(SyncCopy.APP_PASSWORD, s.secret, s.errors.secret, "sync-secret", s.onSecret, password = true)
        SyncSwitchRow(SyncCopy.ALLOW_PLAIN_HTTP, s.form.webDavPlainHttp, "sync-webdav-http", note = if (s.form.webDavPlainHttp) SyncCopy.PLAIN_HTTP_WARNING else null) {
            s.onForm(s.form.copy(webDavPlainHttp = it))
        }
    }
}

@Composable
internal fun SyncS3Form(s: SyncFormScope) {
    Column {
        SyncField(SyncCopy.S3_ENDPOINT, s.form.s3Endpoint, s.errors.s3Endpoint, "sync-s3-endpoint", { s.onForm(s.form.copy(s3Endpoint = it)) }, keyboard = KeyboardType.Uri)
        SyncField(SyncCopy.S3_REGION, s.form.s3Region, s.errors.s3Region, "sync-s3-region", { s.onForm(s.form.copy(s3Region = it)) })
        SyncField(SyncCopy.S3_BUCKET, s.form.s3Bucket, s.errors.s3Bucket, "sync-s3-bucket", { s.onForm(s.form.copy(s3Bucket = it)) })
        SyncField(SyncCopy.S3_ACCESS_KEY, s.form.s3Access, s.errors.s3Access, "sync-s3-access", { s.onForm(s.form.copy(s3Access = it)) })
        SyncField(SyncCopy.S3_SECRET_KEY, s.secret, s.errors.secret, "sync-secret", s.onSecret, password = true)
        SyncField(SyncCopy.S3_PREFIX, s.form.s3Prefix, null, "sync-s3-prefix", { s.onForm(s.form.copy(s3Prefix = it)) })
        SyncSwitchRow(SyncCopy.S3_PATH_STYLE, s.form.s3PathStyle, "sync-s3-pathstyle") { s.onForm(s.form.copy(s3PathStyle = it)) }
        SyncSwitchRow(SyncCopy.ALLOW_PLAIN_HTTP, s.form.s3PlainHttp, "sync-s3-http", note = if (s.form.s3PlainHttp) SyncCopy.PLAIN_HTTP_WARNING else null) {
            s.onForm(s.form.copy(s3PlainHttp = it))
        }
    }
}

/**
 * Google Drive / Dropbox: the person's own client ID (or app key), the redirect address to register, and Sign in /
 * Sign out. Sign in opens the system browser from this explicit tap and nowhere else.
 */
@Composable
internal fun SyncOAuthForm(s: SyncFormScope, google: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SyncField(
            label = if (google) SyncCopy.GOOGLE_CLIENT_ID else SyncCopy.DROPBOX_APP_KEY,
            value = s.form.oauthClientId,
            error = s.errors.oauthClientId,
            tag = "sync-oauth-client",
            onChange = { s.onForm(s.form.copy(oauthClientId = it, oauthAccount = "")) },
        )
        Text(if (google) SyncCopy.HOWTO_GOOGLE else SyncCopy.HOWTO_DROPBOX, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("sync-oauth-howto"))
        Text(SyncCopy.ONE_TIME_SETUP, style = MaterialTheme.typography.bodySmall)
        Text(SyncCopy.REGISTER_REDIRECT, style = MaterialTheme.typography.bodySmall)
        Text(
            if (google) OAuthRedirect.GOOGLE_URI else OAuthRedirect.DROPBOX_URI,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.testTag("sync-oauth-redirect"),
        )
        Text(if (google) SyncCopy.GOOGLE_SCOPE_NOTE else SyncCopy.DROPBOX_SCOPE_NOTE, style = MaterialTheme.typography.bodySmall)
        if (s.signedIn) {
            Text(SyncCopy.signedInAs(s.form.oauthAccount), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("sync-oauth-account"))
            OutlinedButton(onClick = s.onSignOut, modifier = Modifier.minTarget().testTag("sync-signout")) { Text(SyncCopy.SIGN_OUT) }
            Text(SyncCopy.SIGN_OUT_NOTE, style = MaterialTheme.typography.bodySmall)
        } else {
            Text(SyncCopy.NOT_SIGNED_IN, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("sync-oauth-account"))
            Button(onClick = s.onSignIn, enabled = !s.signingIn, modifier = Modifier.minTarget().testTag("sync-signin")) { Text(SyncCopy.SIGN_IN) }
            s.errors.secret?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        s.oauthMessage?.let { SyncResultLine(it, "sync-oauth-message") }
    }
}

@Composable
internal fun SyncTestRow(result: SyncOutcomeLine?, enabled: Boolean, onTest: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = onTest, enabled = enabled, modifier = Modifier.minTarget().testTag("sync-test")) { Text(SyncCopy.TEST_CONNECTION) }
        if (result != null) SyncResultLine(result, "sync-test-result")
    }
}

@Composable
internal fun SyncResultLine(line: SyncOutcomeLine, tag: String) {
    Row(Modifier.testTag(tag).semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.Top) {
        Icon(
            if (line.ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
            contentDescription = if (line.ok) "Worked" else "Problem",
            modifier = Modifier.size(20.dp),
            tint = if (line.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.width(8.dp))
        Text(line.text, style = MaterialTheme.typography.bodyMedium)
    }
}

// ---- what to send and options -------------------------------------------------------------------------

@Composable
internal fun SyncWhatToSend(form: SyncForm, folders: List<SyncFolderChoice>, error: String?, onForm: (SyncForm) -> Unit) {
    Column {
        SyncSwitchRow(SyncCopy.ALL_PHOTOS, form.allPhotos, "sync-all") { onForm(form.copy(allPhotos = it)) }
        if (!form.allPhotos) {
            Text(SyncCopy.CHOOSE_FOLDERS, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
            if (folders.isEmpty()) {
                Text(SyncCopy.NO_FOLDERS_FOUND, style = MaterialTheme.typography.bodySmall)
            }
            folders.forEach { folder ->
                val checked = folder.key in form.folderKeys
                Row(
                    Modifier.fillMaxWidth().minTarget().testTag("sync-folder-${folder.key}").toggleable(
                        value = checked,
                        role = androidx.compose.ui.semantics.Role.Checkbox,
                        onValueChange = { on -> onForm(form.copy(folderKeys = if (on) form.folderKeys + folder.key else form.folderKeys - folder.key)) },
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = checked, onCheckedChange = null)
                    Spacer(Modifier.width(8.dp))
                    Text(folder.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Text("${folder.photoCount}", style = MaterialTheme.typography.bodyMedium)
                }
            }
            SyncSwitchRow(SyncCopy.FAVOURITES, form.favourites, "sync-fav") { onForm(form.copy(favourites = it)) }
        }
        SyncSwitchRow(SyncCopy.INCLUDE_VIDEOS, form.includeVideos, "sync-videos") { onForm(form.copy(includeVideos = it)) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
internal fun SyncOptions(
    form: SyncForm,
    passphrase: String,
    confirm: String,
    needConfirm: Boolean,
    passphraseError: String?,
    onForm: (SyncForm) -> Unit,
    onPassphrase: (String) -> Unit,
    onConfirm: (String) -> Unit,
) {
    Column {
        SyncSwitchRow(SyncCopy.WIFI_ONLY, form.wifiOnly, "sync-wifi") { onForm(form.copy(wifiOnly = it)) }
        Text(SyncCopy.SIZE_LIMIT, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
        SyncByteCap.entries.forEach { cap ->
            val selected = cap == form.cap
            Row(
                Modifier.fillMaxWidth().minTarget().testTag("sync-cap-${cap.name}")
                    .selectable(selected = selected, role = androidx.compose.ui.semantics.Role.RadioButton, onClick = { onForm(form.copy(cap = cap)) }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected, onClick = null)
                Spacer(Modifier.width(8.dp))
                Text(cap.label, style = MaterialTheme.typography.bodyLarge)
            }
        }
        SyncSwitchRow(SyncCopy.ENCRYPT_LABEL, form.encrypt, "sync-encrypt") { onForm(form.copy(encrypt = it)) }
        if (!form.encrypt) Text(SyncCopy.LOCATION_NOTE, style = MaterialTheme.typography.bodySmall)
        if (form.encrypt) {
            Text(SyncCopy.ENCRYPT_NAMES_VISIBLE, style = MaterialTheme.typography.bodySmall)
            Text(SyncCopy.ENCRYPT_NO_RECOVERY, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 6.dp))
            SyncField(SyncCopy.PASSPHRASE, passphrase, if (needConfirm) null else passphraseError, "sync-passphrase", onPassphrase, password = true)
            if (needConfirm) {
                SyncField(SyncCopy.PASSPHRASE_AGAIN, confirm, passphraseError, "sync-passphrase-confirm", onConfirm, password = true)
            }
        }
    }
}

@Composable
internal fun SyncActions(
    gate: SyncStartGate,
    preview: SyncPreviewShown?,
    message: SyncOutcomeLine?,
    alreadyShown: Set<String?>,
    onSave: () -> Unit,
    onPreview: () -> Unit,
    onSend: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onSave, modifier = Modifier.minTarget().testTag("sync-save")) { Text(SyncCopy.SAVE) }
            OutlinedButton(onClick = onPreview, enabled = gate.enabled, modifier = Modifier.minTarget().testTag("sync-preview")) { Text(SyncCopy.PREVIEW) }
        }
        if (preview != null) {
            SyncResultLine(SyncOutcomeLine(preview.ok, preview.line), "sync-preview-result")
            preview.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Button(onClick = onSend, enabled = gate.enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("sync-send")) {
            Text(SyncCopy.SEND_NOW, style = MaterialTheme.typography.titleMedium)
        }
        gate.reason?.takeIf { it !in alreadyShown }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("sync-send-reason")) }
        if (message != null) SyncResultLine(message, "sync-message")
    }
}

@Composable
internal fun SyncForgetButton(onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().minTarget().testTag("sync-forget")) { Text(SyncCopy.FORGET) }
}
