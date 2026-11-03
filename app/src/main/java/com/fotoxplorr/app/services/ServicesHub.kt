package com.fotoxplorr.app.services

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.ai.AiSettingsScreen

/** The panes the hub can show. [HUB] is the three-card list. */
enum class ServicesPane { HUB, KEYS, MODELS, SYNC }

/** How the Sync card behaves; decided by [syncCardMode] so a test can pin it. */
enum class SyncCardMode { OPENS_SYNC, COMING, HIDDEN }

/**
 * [hasSyncContent]: a sync screen was plugged in. [syncIntegrated]: the sync unit has landed in
 * this build. A plugged-in screen always wins; with none, "Sync is coming" shows only while the
 * unit has not landed, and nothing shows after it has.
 */
fun syncCardMode(hasSyncContent: Boolean, syncIntegrated: Boolean): SyncCardMode = when {
    hasSyncContent -> SyncCardMode.OPENS_SYNC
    !syncIntegrated -> SyncCardMode.COMING
    else -> SyncCardMode.HIDDEN
}

/**
 * Services: optional connections (AI keys, cloud sync) and the on-device model catalogue, behind
 * three iconed cards.
 *
 * **Seam for the sync unit (one line):** call
 * `ServicesHub(onBack, syncContent = { SyncScreen(...) }, syncIntegrated = true)`.
 * [syncContent] is shown full-screen under the hub's own back handling when the Sync card is
 * tapped. Until then the card reads "Sync is coming" and does nothing.
 *
 * @param startAt open straight on one pane (for example [ServicesPane.MODELS] from a "model
 *   missing" message). Back from that pane leaves the hub, not to the card list.
 *
 * TODO(remove after the Settings entry lands): the Experimental host still has an "AI and
 * provider keys" row in its Setup group (BuiltInExperiments id "ai"); delete it once Settings
 * opens this hub, and update ExperimentalHostTest's expected last id.
 */
@Composable
fun ServicesHub(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    startAt: ServicesPane = ServicesPane.HUB,
    syncContent: (@Composable () -> Unit)? = null,
    syncIntegrated: Boolean = false,
) {
    var pane by rememberSaveable { mutableStateOf(startAt.name) }
    val current = ServicesPane.valueOf(pane)
    val leave: () -> Unit = if (startAt == ServicesPane.HUB) {
        { pane = ServicesPane.HUB.name }
    } else {
        onBack
    }
    when (current) {
        ServicesPane.HUB -> HubList(onBack, modifier, syncCardMode(syncContent != null, syncIntegrated)) { pane = it.name }
        ServicesPane.KEYS -> AiSettingsScreen(onClose = leave, showModels = false)
        ServicesPane.MODELS -> ModelsPane(onBack = leave, modifier = modifier)
        ServicesPane.SYNC -> {
            BackHandler(onBack = leave)
            Column(modifier.fillMaxSize().background(SERVICES_BACKGROUND)) {
                ServicesHeader(ServicesCopy.SYNC_TITLE, ServicesCopy.SYNC_BLURB, leave)
                syncContent?.invoke()
            }
        }
    }
}

@Composable
private fun HubList(
    onBack: () -> Unit,
    modifier: Modifier,
    syncMode: SyncCardMode,
    open: (ServicesPane) -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(modifier.fillMaxSize().background(SERVICES_BACKGROUND)) {
        ServicesHeader(ServicesCopy.TITLE, ServicesCopy.HUB_BLURB, onBack)
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HubCard(Icons.Outlined.Key, ServicesCopy.KEYS_TITLE, ServicesCopy.KEYS_BLURB) { open(ServicesPane.KEYS) }
            HubCard(Icons.Outlined.Memory, ServicesCopy.MODELS_TITLE, ServicesCopy.MODELS_BLURB) { open(ServicesPane.MODELS) }
            when (syncMode) {
                SyncCardMode.OPENS_SYNC -> HubCard(Icons.Outlined.CloudSync, ServicesCopy.SYNC_TITLE, ServicesCopy.SYNC_BLURB) { open(ServicesPane.SYNC) }
                SyncCardMode.COMING -> HubCard(Icons.Outlined.CloudSync, ServicesCopy.SYNC_TITLE, ServicesCopy.SYNC_COMING, onClick = null)
                SyncCardMode.HIDDEN -> Unit
            }
        }
    }
}

@Composable
private fun HubCard(icon: ImageVector, title: String, blurb: String, onClick: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .background(SERVICES_CARD, RoundedCornerShape(14.dp))
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = SERVICES_PRIMARY, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(blurb, color = SERVICES_SECONDARY, style = MaterialTheme.typography.bodySmall)
        }
        if (onClick != null) {
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = SERVICES_MUTED)
        }
    }
}
