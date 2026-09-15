package com.fotoxplorr.app.ai

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

private val PANEL_BACKGROUND = Color.Black
private val CARD_BACKGROUND = Color(0xFF141414)
private val PRIMARY_TEXT = Color.White
private val SECONDARY_TEXT = Color.White.copy(alpha = 0.65f)
private val MUTED_TEXT = Color.White.copy(alpha = 0.45f)
private val DANGER = Color(0xFFE5564B)

/**
 * "AI and similarity" -- on-device photo-similarity search, plus an entirely optional way to
 * connect an outside AI service under the user's own key.
 *
 * Rebuilt for two reasons the owner gave together (2026-08-15): *"The configure ai provider
 * popup is hideous"* and *"The AI and similarity screen is just very non-intuitive and hard for
 * a layman to use."* Both trace to the same root cause. Every surface here used to be a plain
 * Material3 `Card`/`AlertDialog`/`OutlinedTextField`, which follows `MaterialTheme.colorScheme`
 * -- and that scheme is genuinely light under the app's own Light or System theme setting
 * (`FotoXplorrTheme.kt`). Every OTHER surface in this app (the viewer's rooms, the settings
 * tabs, the rail) is hand-painted black regardless of that setting; this screen was the one
 * place that was not, which is what made a routine Material dialog read as "hideous" sitting
 * inside it. The fix is the same one already established everywhere else in the app: hardcode
 * the dark surface, and take only the accent colour from the theme.
 *
 * The "non-intuitive" half is copy and structure, not colour. Both sections now lead with one
 * plain sentence about what they DO, one clear primary action, and put every technical reading
 * (device capability, model hash and size, the exact HTTP request a test will send) behind an
 * explicit "Technical details" / "What exactly gets sent" disclosure -- present for anyone who
 * wants it, out of the way for anyone who does not.
 */
@Composable
fun AiSettingsScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    // The flavor picks the implementation: offline's bindings refuse every network call
    // with a typed failure, connect's are backed by :feature:ai-remote. No BuildConfig.
    val bindings = remember { AppConnectivityBindings() }
    val modelManager = remember(context) { LocalModelManager(context.applicationContext, bindings.remoteAi) }
    val providerStore = remember(context) { AiProviderStore(context.applicationContext) }
    val modelState by modelManager.observe().collectAsStateWithLifecycle()
    val providers by providerStore.observe().collectAsStateWithLifecycle()
    val capability = remember { modelManager.capability() }
    val scope = rememberCoroutineScope()

    var addPickerVisible by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AiProviderConfig?>(null) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testingId by remember { mutableStateOf<String?>(null) }
    var showModelDetails by remember { mutableStateOf(false) }

    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { modelManager.installFromUri(uri) }
    }

    Column(modifier = Modifier.fillMaxSize().background(PANEL_BACKGROUND).statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Outlined.Close, contentDescription = "Close", tint = PRIMARY_TEXT)
            }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text("AI and similarity", color = PRIMARY_TEXT, style = MaterialTheme.typography.titleLarge)
                Text(
                    "Everything here runs on this device unless you choose otherwise",
                    color = SECONDARY_TEXT,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                SimilaritySection(
                    state = modelState,
                    capability = capability,
                    downloadAvailable = bindings.remoteAi.available,
                    detailsExpanded = showModelDetails,
                    onToggleDetails = { showModelDetails = !showModelDetails },
                    onInstall = { scope.launch { modelManager.installRecommendedModel() } },
                    onImport = { modelPicker.launch(arrayOf("application/octet-stream", "*/*")) },
                    onDelete = modelManager::deleteModel,
                )
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Connect your own AI service",
                        color = PRIMARY_TEXT,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Optional. Adds things like AI-written descriptions using a service " +
                            "you already have an account with -- ChatGPT, Claude and others all " +
                            "work. Nothing is sent until you turn a service on and ask for " +
                            "something; your key is encrypted and never leaves this device on " +
                            "its own.",
                        color = SECONDARY_TEXT,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            if (providers.isEmpty()) {
                item {
                    Text(
                        "No AI service connected yet.",
                        color = MUTED_TEXT,
                        style = MaterialTheme.typography.bodyMedium,
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
                            testResult = "Add a key for ${provider.label} first."
                        } else {
                            testingId = provider.id
                            scope.launch {
                                val result = bindings.remoteAi.testConnection(provider, secret)
                                testResult = result.fold(
                                    onSuccess = { "${provider.label} is working." },
                                    onFailure = { "${provider.label} could not connect: ${it.message ?: "unknown error"}" },
                                )
                                testingId = null
                            }
                        }
                    },
                )
            }

            item {
                DarkButton(
                    label = "Add a service",
                    icon = Icons.Outlined.Add,
                    onClick = { addPickerVisible = true },
                )
            }
        }
    }

    if (addPickerVisible) {
        AddProviderPicker(
            onDismiss = { addPickerVisible = false },
            onChoose = { preset ->
                addPickerVisible = false
                editing = preset
            },
        )
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
        SimpleMessageDialog(text = result, onDismiss = { testResult = null })
    }
}

/**
 * On-device photo similarity, led by what it does rather than by what it needs.
 *
 * Device capability and the model's own hash/size are real facts a curious or troubleshooting
 * user may want, but they are not what a first-time reader needs to decide anything -- so they
 * sit behind [detailsExpanded] rather than in the primary flow.
 */
@Composable
private fun SimilaritySection(
    state: LocalModelState,
    capability: DeviceAiCapability,
    downloadAvailable: Boolean,
    detailsExpanded: Boolean,
    onToggleDetails: () -> Unit,
    onInstall: () -> Unit,
    onImport: () -> Unit,
    onDelete: () -> Unit,
) {
    DarkCard {
        Text("Find similar photos", color = PRIMARY_TEXT, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "Foto Xplorr can look for photos that look alike -- entirely on this device, " +
                "nothing uploaded. It needs a small on-device model to do that comparison.",
            color = SECONDARY_TEXT,
            style = MaterialTheme.typography.bodyMedium,
        )

        when (state) {
            LocalModelState.NotInstalled -> {
                Text(
                    if (downloadAvailable) {
                        "Not set up yet."
                    } else {
                        "Not set up yet. This offline build cannot download it for you -- " +
                            "import one instead."
                    },
                    color = MUTED_TEXT,
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (downloadAvailable) {
                        DarkButton(label = "Set up", filled = true, onClick = onInstall)
                    }
                    DarkButton(label = "Import a model file", onClick = onImport)
                }
            }
            is LocalModelState.Downloading -> {
                LinearProgressIndicator(
                    progress = {
                        val total = state.totalBytes ?: 0L
                        if (total == 0L) 0f else (state.bytesRead.toFloat() / total).coerceIn(0f, 1f)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.White.copy(alpha = 0.15f),
                )
                Text("Setting up… ${state.bytesRead / 1024L} KB so far", color = MUTED_TEXT, style = MaterialTheme.typography.bodySmall)
            }
            is LocalModelState.Ready -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusDot(color = MaterialTheme.colorScheme.primary)
                    Text("Ready", color = PRIMARY_TEXT, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DarkButton(label = "Replace", onClick = onImport)
                    DarkButton(label = "Remove", danger = true, icon = Icons.Outlined.Delete, onClick = onDelete)
                }
            }
            is LocalModelState.Failed -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusDot(color = DANGER)
                    Text(state.message, color = DANGER, style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (downloadAvailable) DarkButton(label = "Try again", onClick = onInstall)
                    DarkButton(label = "Import a model file", onClick = onImport)
                }
            }
        }

        DetailsToggle(expanded = detailsExpanded, onToggle = onToggleDetails)
        if (detailsExpanded) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Device: Android ${capability.androidVersion} · ${capability.memoryClassMb} MB app memory · " +
                        "OpenGL ES ${capability.openGlEsVersion} · ${capability.recommendedConcurrentWorkers} worker(s)",
                    color = MUTED_TEXT,
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    if (capability.canRunImageEmbedding) "This device can run on-device similarity search." else "This device is not recommended for on-device similarity search.",
                    color = if (capability.canRunImageEmbedding) SECONDARY_TEXT else DANGER,
                    style = MaterialTheme.typography.labelSmall,
                )
                capability.warning?.let { Text(it, color = MUTED_TEXT, style = MaterialTheme.typography.labelSmall) }
                if (state is LocalModelState.Ready) {
                    Text("Size: ${state.sizeBytes / (1024L * 1024L)} MB", color = MUTED_TEXT, style = MaterialTheme.typography.labelSmall)
                    Text("Checksum: ${state.sha256.take(16)}…", color = MUTED_TEXT, style = MaterialTheme.typography.labelSmall)
                    Text("Source: ${state.source}", color = MUTED_TEXT, style = MaterialTheme.typography.labelSmall)
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
    DarkCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(provider.label, color = PRIMARY_TEXT, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    if (provider.hasSecret) "Key saved" else "No key yet -- add one to turn this on",
                    color = if (provider.hasSecret) SECONDARY_TEXT else DANGER,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            com.fotoxplorr.app.hyle.HyleToggle(
                checked = provider.enabled,
                enabled = provider.hasSecret,
                onCheckedChange = onEnabledChange,
                description = provider.label,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DarkButton(
                label = if (testing) "Testing…" else "Test",
                onClick = onTest,
                enabled = provider.hasSecret && !testing,
                leading = if (testing) {
                    { CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = PRIMARY_TEXT) }
                } else {
                    null
                },
            )
            IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, contentDescription = "Edit ${provider.label}", tint = SECONDARY_TEXT) }
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = "Remove ${provider.label}", tint = SECONDARY_TEXT) }
        }
    }
}

/**
 * A dark, plain-language stand-in for the old [androidx.compose.material3.DropdownMenu], which
 * -- like every other Material default here -- would have followed the light theme.
 */
@Composable
private fun AddProviderPicker(onDismiss: () -> Unit, onChoose: (AiProviderConfig) -> Unit) {
    DarkDialog(onDismiss = onDismiss) {
        Text("Add a service", color = PRIMARY_TEXT, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "Pick which one -- you can fill in the details on the next step.",
            color = SECONDARY_TEXT,
            style = MaterialTheme.typography.bodySmall,
        )
        PickerRow("ChatGPT (OpenAI)") { onChoose(AiProviderPresets.openAi()) }
        PickerRow("Claude (Anthropic)") { onChoose(AiProviderPresets.anthropic()) }
        PickerRow("Google Gemini") { onChoose(AiProviderPresets.gemini()) }
        PickerRow("Something else (OpenAI-compatible)") { onChoose(AiProviderPresets.openAiCompatible()) }
    }
}

@Composable
private fun PickerRow(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        color = PRIMARY_TEXT,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    )
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
    var showRequestPreview by remember(initial.id) { mutableStateOf(false) }

    DarkDialog(onDismiss = onDismiss, scrollable = true) {
        Text(
            if (initial.hasSecret) "Edit ${initial.label}" else "Set up ${initial.label}",
            color = PRIMARY_TEXT,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Your key is encrypted on this device and sent only to the address below, " +
                if (initial.hasSecret) "and only when you use this service. Leave the key field blank to keep the one you already saved." else "and only when you use this service.",
            color = SECONDARY_TEXT,
            style = MaterialTheme.typography.bodySmall,
        )

        DarkField(label, { label = it }, "Name", "A name you'll recognise, e.g. \"Work ChatGPT\"")
        DarkField(baseUrl, { baseUrl = it }, "Address", "The web address this service uses")
        DarkField(model, { model = it }, "Model", "Which model to use, e.g. gpt-5-mini")
        DarkField(
            secret,
            { secret = it },
            if (initial.hasSecret) "Replace key (optional)" else "API key",
            "Found on the service's own website, under API keys",
            password = true,
        )
        DarkField(
            timeout,
            { timeout = it.filter(Char::isDigit).take(3) },
            "Timeout (seconds)",
            "How long to wait for a reply before giving up",
        )

        DetailsToggle(
            expanded = showRequestPreview,
            onToggle = { showRequestPreview = !showRequestPreview },
            subject = "exactly what gets sent",
        )
        if (showRequestPreview) {
            Text(
                "A test sends this, and nothing else:\n" +
                    "POST ${initial.kind.endpointPreview(baseUrl, model)}\n" +
                    "Authorization: your encrypted key (never shown here)\n" +
                    "Body: a short line of text -- no photo is included.",
                color = MUTED_TEXT,
                style = MaterialTheme.typography.labelSmall,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            DarkButton(label = "Cancel", onClick = onDismiss)
            Box(Modifier.padding(start = 8.dp)) {
                DarkButton(
                    label = "Save",
                    filled = true,
                    enabled = label.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank() &&
                        (initial.hasSecret || secret.isNotBlank()),
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
                    },
                )
            }
        }
    }
}

@Composable
private fun SimpleMessageDialog(text: String, onDismiss: () -> Unit) {
    DarkDialog(onDismiss = onDismiss) {
        Text(text, color = PRIMARY_TEXT, style = MaterialTheme.typography.bodyLarge)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            DarkButton(label = "OK", filled = true, onClick = onDismiss)
        }
    }
}

// ---------------- shared dark-surface building blocks ----------------

/**
 * The dialog itself: a raw [Dialog], not [androidx.compose.material3.AlertDialog]. `AlertDialog`
 * follows `MaterialTheme.colorScheme`, which is genuinely light under this app's own Light/System
 * theme setting; a raw `Dialog` gives a bare window with no enforced surface colour at all, which
 * is what lets [CARD_BACKGROUND] actually stick regardless of that setting -- exactly the
 * approach every other room in this app already takes.
 */
@Composable
private fun DarkDialog(
    onDismiss: () -> Unit,
    scrollable: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val shape = RoundedCornerShape(20.dp)
        Column(
            modifier = Modifier
                .padding(24.dp)
                .background(CARD_BACKGROUND, shape)
                .then(if (scrollable) Modifier.heightIn(max = 560.dp) else Modifier)
                .padding(20.dp)
                // A capped-height dialog needs its own scroll: `Dialog` does not give its content
                // any, and the provider editor's five fields plus the request preview can exceed
                // 560dp on a small phone. A no-op when the content is short enough not to need it.
                .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun DarkCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CARD_BACKGROUND, RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
private fun DarkField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    caption: String,
    password: Boolean = false,
) {
    Column {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = PRIMARY_TEXT,
                unfocusedTextColor = PRIMARY_TEXT,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = Color.White.copy(alpha = 0.25f),
                focusedLabelColor = MaterialTheme.colorScheme.primary,
                unfocusedLabelColor = SECONDARY_TEXT,
                cursorColor = MaterialTheme.colorScheme.primary,
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
            ),
        )
        Text(caption, color = MUTED_TEXT, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 4.dp, top = 2.dp))
    }
}

@Composable
private fun DarkButton(
    label: String,
    onClick: () -> Unit,
    filled: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    val tint = when {
        danger -> DANGER
        filled -> MaterialTheme.colorScheme.primary
        else -> PRIMARY_TEXT
    }
    val alpha = if (enabled) 1f else 0.35f
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .background(
                if (filled) tint.copy(alpha = 0.16f * alpha) else Color.Transparent,
                RoundedCornerShape(50),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        leading?.invoke()
        icon?.let { Icon(it, contentDescription = null, tint = tint.copy(alpha = alpha), modifier = Modifier.size(16.dp)) }
        Text(label, color = tint.copy(alpha = alpha), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    }
}

/**
 * A single "Show X" / "Hide X" disclosure row. [subject] is a bare noun phrase -- "technical
 * details", "exactly what gets sent" -- and this is the ONLY place that decides whether the
 * word in front of it is "Show" or "Hide". An earlier version let call sites build that whole
 * sentence themselves while this composable ALSO prepended "Hide" whenever `expanded` was true,
 * which for a caller that had already written its own "Hide ..." string produced "Hide Hide ...".
 * Centralising it here is what makes that class of bug impossible rather than merely unlikely.
 */
@Composable
private fun DetailsToggle(expanded: Boolean, onToggle: () -> Unit, subject: String = "technical details") {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.clickable(onClick = onToggle).padding(vertical = 4.dp),
    ) {
        Text(
            if (expanded) "Hide $subject" else "Show $subject",
            color = MUTED_TEXT,
            style = MaterialTheme.typography.labelMedium,
        )
        Icon(
            if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = null,
            tint = MUTED_TEXT,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun StatusDot(color: Color) {
    Box(Modifier.size(8.dp).background(color, RoundedCornerShape(50)))
}

private fun AiProviderKind.endpointPreview(baseUrl: String, model: String): String = when (this) {
    AiProviderKind.OPENAI_RESPONSES -> "${baseUrl.trimEnd('/')}/v1/responses"
    AiProviderKind.OPENAI_COMPATIBLE_CHAT -> "${baseUrl.trimEnd('/')}/v1/chat/completions"
    AiProviderKind.ANTHROPIC_MESSAGES -> "${baseUrl.trimEnd('/')}/v1/messages"
    AiProviderKind.GEMINI_GENERATE_CONTENT -> "${baseUrl.trimEnd('/')}/v1beta/models/$model:generateContent"
}
