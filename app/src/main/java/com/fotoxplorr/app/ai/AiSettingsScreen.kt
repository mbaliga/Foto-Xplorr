package com.fotoxplorr.app.ai

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@Composable
fun AiSettingsScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val modelManager = remember(context) { LocalModelManager(context.applicationContext) }
    val providerStore = remember(context) { AiProviderStore(context.applicationContext) }
    val providerClient = remember { AiProviderClient() }
    val modelState by modelManager.observe().collectAsStateWithLifecycle()
    val providers by providerStore.observe().collectAsStateWithLifecycle()
    val capability = remember { modelManager.capability() }
    val scope = rememberCoroutineScope()

    var addMenuVisible by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AiProviderConfig?>(null) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testingId by remember { mutableStateOf<String?>(null) }

    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { modelManager.installFromUri(uri) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Outlined.Close, contentDescription = "Close AI settings")
            }
            Column(Modifier.weight(1f)) {
                Text("AI and similarity", style = MaterialTheme.typography.titleLarge)
                Text("Local by default · remote providers are optional", style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Outlined.Security, contentDescription = null)
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                CapabilityCard(capability)
            }
            item {
                LocalModelCard(
                    state = modelState,
                    onInstall = { scope.launch { modelManager.installRecommendedModel() } },
                    onImport = { modelPicker.launch(arrayOf("application/octet-stream", "*/*")) },
                    onDelete = modelManager::deleteModel,
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("User-supplied providers", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Nothing is sent unless you explicitly enable a provider and initiate an AI action. Keys are encrypted with Android Keystore and excluded from backups.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            items(providers, key = { it.id }) { provider ->
                ProviderCard(
                    provider = provider,
                    testing = testingId == provider.id,
                    onEnabledChange = { providerStore.setEnabled(provider.id, it) },
                    onEdit = { editing = provider },
                    onDelete = { providerStore.remove(provider.id) },
                    onTest = {
                        val secret = providerStore.secret(provider.id)
                        if (secret == null) {
                            testResult = "Add a key before testing ${provider.label}."
                        } else {
                            testingId = provider.id
                            scope.launch {
                                val result = providerClient.testConnection(provider, secret)
                                testResult = result.fold(
                                    onSuccess = { "${provider.label}: ${it.take(240)}" },
                                    onFailure = { "${provider.label}: ${it.message ?: "connection failed"}" },
                                )
                                testingId = null
                            }
                        }
                    },
                )
            }
            item {
                Column {
                    Button(onClick = { addMenuVisible = true }) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Text(" Add provider")
                    }
                    DropdownMenu(
                        expanded = addMenuVisible,
                        onDismissRequest = { addMenuVisible = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("OpenAI") },
                            onClick = { addMenuVisible = false; editing = AiProviderPresets.openAi() },
                        )
                        DropdownMenuItem(
                            text = { Text("OpenAI-compatible endpoint") },
                            onClick = { addMenuVisible = false; editing = AiProviderPresets.openAiCompatible() },
                        )
                        DropdownMenuItem(
                            text = { Text("Anthropic") },
                            onClick = { addMenuVisible = false; editing = AiProviderPresets.anthropic() },
                        )
                        DropdownMenuItem(
                            text = { Text("Google Gemini") },
                            onClick = { addMenuVisible = false; editing = AiProviderPresets.gemini() },
                        )
                    }
                }
            }
            item {
                Text(
                    "Request preview: provider actions show which provider, endpoint, model and media fields will be sent before transmission. Local similarity indexing never calls these providers.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    editing?.let { provider ->
        ProviderEditorDialog(
            initial = provider,
            onDismiss = { editing = null },
            onSave = { updated, secret ->
                runCatching { providerStore.upsert(updated, secret) }
                    .onFailure { testResult = it.message }
                editing = null
            },
        )
    }

    testResult?.let { result ->
        AlertDialog(
            onDismissRequest = { testResult = null },
            title = { Text("Provider result") },
            text = { Text(result) },
            confirmButton = { TextButton(onClick = { testResult = null }) { Text("OK") } },
        )
    }
}

@Composable
private fun CapabilityCard(capability: DeviceAiCapability) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Memory, contentDescription = null)
                Text("Device capability", style = MaterialTheme.typography.titleMedium)
            }
            Text("Android API ${capability.androidVersion} · ${capability.memoryClassMb} MB app memory")
            Text("OpenGL ES ${capability.openGlEsVersion} · ${capability.recommendedConcurrentWorkers} indexing worker(s)")
            Text(
                if (capability.canRunImageEmbedding) "Local image embedding supported" else "Local embedding not recommended",
                color = if (capability.canRunImageEmbedding) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            capability.warning?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun LocalModelCard(
    state: LocalModelState,
    onInstall: () -> Unit,
    onImport: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Download, contentDescription = null)
                Text("Local image embedding model", style = MaterialTheme.typography.titleMedium)
            }
            when (state) {
                LocalModelState.NotInstalled -> {
                    Text("Not installed. The recommended model is downloaded only after you choose to install it.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onInstall) { Text("Install") }
                        OutlinedButton(onClick = onImport) { Text("Import .tflite") }
                    }
                }
                is LocalModelState.Downloading -> {
                    LinearProgressIndicator(
                        progress = {
                            val total = state.totalBytes ?: 0L
                            if (total == 0L) 0f else (state.bytesRead.toFloat() / total).coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Downloaded ${state.bytesRead / 1024L} KB")
                }
                is LocalModelState.Ready -> {
                    Text("Installed · ${state.sizeBytes / (1024L * 1024L)} MB")
                    Text("SHA-256 ${state.sha256.take(16)}…", style = MaterialTheme.typography.labelSmall)
                    Text("Source: ${state.source}", style = MaterialTheme.typography.labelSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onImport) { Text("Replace") }
                        OutlinedButton(onClick = onDelete) {
                            Icon(Icons.Outlined.Delete, contentDescription = null)
                            Text(" Remove")
                        }
                    }
                }
                is LocalModelState.Failed -> {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onInstall) { Text("Retry") }
                        OutlinedButton(onClick = onImport) { Text("Import model") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderCard(
    provider: AiProviderConfig,
    testing: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Key, contentDescription = null)
                Column(Modifier.weight(1f)) {
                    Text(provider.label, style = MaterialTheme.typography.titleSmall)
                    Text(provider.model, style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = provider.enabled,
                    enabled = provider.hasSecret,
                    onCheckedChange = onEnabledChange,
                )
            }
            Text(provider.baseUrl, style = MaterialTheme.typography.labelSmall)
            Text(
                if (provider.hasSecret) "Encrypted key stored" else "No key stored",
                color = if (provider.hasSecret) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = onTest, enabled = provider.hasSecret && !testing) {
                    if (testing) CircularProgressIndicator(modifier = Modifier.padding(end = 6.dp))
                    else Icon(Icons.Outlined.NetworkCheck, contentDescription = null)
                    Text(" Test")
                }
                IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, contentDescription = "Edit provider") }
                IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = "Delete provider and key") }
            }
        }
    }
}

@Composable
private fun ProviderEditorDialog(
    initial: AiProviderConfig,
    onDismiss: () -> Unit,
    onSave: (AiProviderConfig, CharArray?) -> Unit,
) {
    var label by remember(initial.id) { mutableStateOf(initial.label) }
    var baseUrl by remember(initial.id) { mutableStateOf(initial.baseUrl) }
    var model by remember(initial.id) { mutableStateOf(initial.model) }
    var secret by remember(initial.id) { mutableStateOf("") }
    var timeout by remember(initial.id) { mutableStateOf(initial.timeoutSeconds.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.hasSecret) "Edit provider" else "Configure provider") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text(
                        "${initial.kind.displayName()} · the key is sent only to the endpoint below. Leave the key blank to keep an existing one.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                item { OutlinedTextField(label, { label = it }, label = { Text("Name") }, singleLine = true) }
                item { OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("Base URL") }, singleLine = true) }
                item { OutlinedTextField(model, { model = it }, label = { Text("Model") }, singleLine = true) }
                item {
                    OutlinedTextField(
                        value = secret,
                        onValueChange = { secret = it },
                        label = { Text(if (initial.hasSecret) "Replace key (optional)" else "API key or token") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                }
                item {
                    OutlinedTextField(
                        value = timeout,
                        onValueChange = { timeout = it.filter(Char::isDigit).take(3) },
                        label = { Text("Timeout seconds") },
                        singleLine = true,
                    )
                }
                item {
                    Text(
                        "Request preview: POST ${initial.kind.endpointPreview(baseUrl, model)}\nAuthorization: encrypted key (redacted)\nTest body: a short text-only prompt; no photo is included.",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = label.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank() && (initial.hasSecret || secret.isNotBlank()),
                onClick = {
                    onSave(
                        initial.copy(
                            label = label,
                            baseUrl = baseUrl,
                            model = model,
                            timeoutSeconds = timeout.toIntOrNull() ?: initial.timeoutSeconds,
                        ),
                        secret.takeIf(String::isNotBlank)?.toCharArray(),
                    )
                    secret = ""
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun AiProviderKind.displayName(): String = when (this) {
    AiProviderKind.OPENAI_RESPONSES -> "OpenAI Responses API"
    AiProviderKind.OPENAI_COMPATIBLE_CHAT -> "OpenAI-compatible Chat Completions"
    AiProviderKind.ANTHROPIC_MESSAGES -> "Anthropic Messages API"
    AiProviderKind.GEMINI_GENERATE_CONTENT -> "Gemini generateContent"
}

private fun AiProviderKind.endpointPreview(baseUrl: String, model: String): String = when (this) {
    AiProviderKind.OPENAI_RESPONSES -> "${baseUrl.trimEnd('/')}/v1/responses"
    AiProviderKind.OPENAI_COMPATIBLE_CHAT -> "${baseUrl.trimEnd('/')}/v1/chat/completions"
    AiProviderKind.ANTHROPIC_MESSAGES -> "${baseUrl.trimEnd('/')}/v1/messages"
    AiProviderKind.GEMINI_GENERATE_CONTENT -> "${baseUrl.trimEnd('/')}/v1beta/models/$model:generateContent"
}
