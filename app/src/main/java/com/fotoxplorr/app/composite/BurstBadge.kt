package com.fotoxplorr.app.composite

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
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
 * shown by its icon and words as well as its outline. On a tile from [MIN_FOCUS_TILE] up to that it
 * is not a touch target (it would cover the whole tile) but Tab still reaches it and Enter or Space
 * toggles it. Smaller, it is a label only. At every size the stack also opens with Shift+Enter on
 * the focused tile, from the tile's right-click menu, and through the screen reader's action here.
 */
@Composable
internal fun BurstBadge(badge: StackBadge<MediaId>, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val label = CompositeCopy.stackCount(badge.count, badge.expanded)
    BoxWithConstraints(modifier) {
        val tappable = maxWidth >= MIN_TILE
        val keyOnly = !tappable && maxWidth >= MIN_FOCUS_TILE
        var focused by remember { mutableStateOf(false) }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .then(if (tappable) Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp) else Modifier)
                .testTag(burstBadgeTag(badge.representative))
                .then(
                    when {
                        tappable -> Modifier.clickable(role = Role.Button, onClickLabel = label, onClick = onToggle)
                        keyOnly -> Modifier
                            .onFocusChanged { focused = it.isFocused }
                            .onKeyEvent { event ->
                                val activates = event.key == Key.Enter || event.key == Key.NumPadEnter ||
                                    event.key == Key.Spacebar || event.key == Key.DirectionCenter
                                if (activates && event.type == KeyEventType.KeyUp) onToggle()
                                activates
                            }
                            .focusable()
                        else -> Modifier
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
                    .then(
                        when {
                            focused -> Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                            badge.expanded -> Modifier.border(1.5.dp, Color.White, RoundedCornerShape(8.dp))
                            else -> Modifier
                        },
                    )
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
private val MIN_FOCUS_TILE = 48.dp
