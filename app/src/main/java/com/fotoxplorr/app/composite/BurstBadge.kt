package com.fotoxplorr.app.composite

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BurstMode
import androidx.compose.material.icons.outlined.UnfoldLess
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.fotoxplorr.core.formats.composite.StackBadge
import com.fotoxplorr.core.model.MediaId

/** Test tag of a stack's badge, by its representative's id. */
internal fun burstBadgeTag(id: MediaId) = "burstBadge-${id.value}"

/**
 * The count badge on a burst stack's tile, bottom-right. On a tile at least [MIN_TILE] wide it is a
 * 48 dp button (tap, or Tab to it and press Enter) that opens or closes the stack; the open state is
 * shown by its icon and words as well as its outline. On a smaller tile the badge is a label only
 * and the stack opens through the screen reader's action on it; widen the grid to tap it.
 */
@Composable
internal fun BurstBadge(badge: StackBadge<MediaId>, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val label = CompositeCopy.stackCount(badge.count, badge.expanded)
    BoxWithConstraints(modifier) {
        val tappable = maxWidth >= MIN_TILE
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .then(if (tappable) Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp) else Modifier)
                .testTag(burstBadgeTag(badge.representative))
                .then(
                    if (tappable) {
                        Modifier.clickable(role = Role.Button, onClickLabel = label, onClick = onToggle)
                    } else {
                        Modifier
                    },
                )
                .semantics {
                    contentDescription = label
                    stateDescription = if (badge.expanded) "Open" else "Closed"
                    customActions = listOf(CustomAccessibilityAction(label) { onToggle(); true })
                },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(8.dp))
                    .then(if (badge.expanded) Modifier.border(1.5.dp, Color.White, RoundedCornerShape(8.dp)) else Modifier)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            ) {
                Icon(
                    if (badge.expanded) Icons.Outlined.UnfoldLess else Icons.Outlined.BurstMode,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    " ${badge.count}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

private val MIN_TILE = 64.dp
