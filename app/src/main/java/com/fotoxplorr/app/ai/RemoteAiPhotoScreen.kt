@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.fotoxplorr.app.ai

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun RemoteAiPhotoScreen(
    assets: List<MediaAsset>,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val providerStore = remember(context) { AiProviderStore(context.applicationContext) }
    val providers by providerStore.observe().collectAsStateWithLifecycle()
    val imagePreparer = remember(context) { AiImagePreparer(context.applicationContext) }
    val client = remember { RemoteImageAnalysisClient() }
    val scope = rememberCoroutineScope()
    val enabledProviders = providers.filter { it.enabled && it.hasSecret }
    val stillImages = remember(assets) {
        assets.filter { !it.isVideo && !it.isTrashed }.take(MAX_PICKER_ASSETS)
    }

    var selectedProviderId by remember(enabledProviders) {
        mutableStateOf(enabledProviders.firstOrNull()?.id)
    }
    var selectedAsset by remember(stillImages) { mutableStateOf(stillImages.firstOrNull()) }
    var prompt by remember { mutableStateOf(DEFAULT_PROMPT) }
    var preparedImage by remember { mutableStateOf<PreparedAiImage?>(null) }
    var preview by remember { mutableStateOf<AiRequestPreview?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busyLabel by remember { mutableStateOf<String?>(null) }
    var requestJob by remember { mutableStateOf<Job?>(null) }

    val selectedProvider = enabledProviders.firstOrNull { it.id == selectedProviderId }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Outlined.Close, contentDescription = "Close remote photo analysis")
            }
            Column(Modifier.weight(1f)) {
                Text("Ask your provider about a photo", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Nothing is transmitted until the final Send confirmation",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        if (enabledProviders.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No remote provider is enabled", style = MaterialTheme.typography.titleLarge)
                Text("Add an encrypted key, test it, and enable the provider first.")
                Button(onClick = onOpenSettings) { Text("Open AI settings") }
            }
            return@Column
        }

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(enabledProviders, key = { it.id }) { provider ->
                FilterChip(
                    selected = selectedProviderId == provider.id,
                    onClick = {
                        selectedProviderId = provider.id
                        preview = null
                        preparedImage = null
                    },
                    label = { Text(provider.label) },
                )
            }
        }

        Text("Choose one still image", modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        LazyRow(
            modifier = Modifier.fillMaxWidth().height(112.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            items(stillImages, key = { it.id.value }) { asset ->
                MediaImage(
                    asset = asset,
                    modifier = Modifier
                        .size(96.dp)
                        .border(
                            if (selectedAsset?.id == asset.id) 4.dp else 1.dp,
                            if (selectedAsset?.id == asset.id) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                        )
                        .combinedClickable(
                            onClick = {
                                selectedAsset = asset
                                preview = null
                                preparedImage = null
                            },
                            onLongClick = { selectedAsset = asset },
                        ),
                    contentScale = ContentScale.Crop,
                )
            }
        }

        selectedAsset?.let { asset ->
            Text(
                asset.displayName,
                modifier = Modifier.padding(horizontal = 12.dp),
                style = MaterialTheme.typography.labelSmall,
            )
        }

        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it; preview = null },
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            minLines = 3,
            maxLines = 8,
            label = { Text("Question or instruction") },
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                enabled = selectedProvider != null && selectedAsset != null && prompt.isNotBlank() &&
                    requestJob?.isActive != true,
                onClick = {
                    val provider = selectedProvider ?: return@Button
                    val asset = selectedAsset ?: return@Button
                    requestJob = scope.launch {
                        busyLabel = "Preparing a bounded in-memory copy…"
                        error = null
                        imagePreparer.prepare(asset).fold(
                            onSuccess = { prepared ->
                                preparedImage = prepared
                                preview = client.preview(provider, prompt, prepared)
                                busyLabel = null
                            },
                            onFailure = {
                                error = it.message ?: "Unable to prepare image"
                                busyLabel = null
                            },
                        )
                    }
                },
            ) {
                Text("Preview request")
            }
            if (requestJob?.isActive == true) {
                IconButton(onClick = { requestJob?.cancel(); busyLabel = null }) {
                    Icon(Icons.Outlined.Stop, contentDescription = "Cancel AI request")
                }
            }
            busyLabel?.let {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }

        error?.let {
            Text(it, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
        }
        result?.let {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Provider response", style = MaterialTheme.typography.titleMedium)
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    val requestPreview = preview
    val image = preparedImage
    val provider = selectedProvider
    if (requestPreview != null && image != null && provider != null) {
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text("Confirm remote image transmission") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Provider: ${requestPreview.providerLabel}")
                    Text("Endpoint: ${requestPreview.endpoint}", style = MaterialTheme.typography.labelSmall)
                    Text("Model: ${requestPreview.model}")
                    Text(
                        "Image: ${requestPreview.imageWidth} × ${requestPreview.imageHeight}, " +
                            "${formatBytes(requestPreview.imageBytes)}, ${requestPreview.imageMimeType}",
                    )
                    Text("Prompt: ${requestPreview.promptCharacters} characters")
                    Text(
                        "The prepared image and prompt will leave this device. Foto Xplorr does not proxy or retain the provider response.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    preview = null
                    val secret = providerStore.secret(provider.id)
                    if (secret == null) {
                        error = "The encrypted provider key is unavailable."
                        return@TextButton
                    }
                    requestJob = scope.launch {
                        busyLabel = "Waiting for ${provider.label}…"
                        result = null
                        error = null
                        client.analyze(provider, secret, prompt, image).fold(
                            onSuccess = { result = it },
                            onFailure = { error = it.message ?: "Provider analysis failed" },
                        )
                        busyLabel = null
                        preparedImage = null
                    }
                }) {
                    Icon(Icons.Outlined.Send, contentDescription = null)
                    Text(" Send")
                }
            },
            dismissButton = {
                TextButton(onClick = { preview = null; preparedImage = null }) { Text("Cancel") }
            },
        )
    }
}

private fun formatBytes(bytes: Int): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private const val MAX_PICKER_ASSETS = 500
private const val DEFAULT_PROMPT =
    "Describe this image factually. Separate visible evidence from uncertainty, and do not infer sensitive personal attributes."
