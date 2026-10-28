package com.fotoxplorr.app.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Grain
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The editor's tool chrome: a centre of the tools this person uses most, a ring of five group
 * buttons below it, a group's page when a group is tapped, and the tool itself when one is opened.
 * It replaces the one long strip of tabs; the controls inside a tool are the editor's existing
 * ones, drawn by [toolContent].
 *
 * Where the thumb is: the centre and the ring sit at the bottom of the screen, above the
 * navigation bar (the caller pads that), and the ring curves like a smile so the middle button
 * is the lowest. Everything is a tap; nothing here takes a drag, so TRAPS #72 (the shell's edge
 * zones take horizontal drags that start near the sides) has nothing to take. The editor is also
 * a screen of its own, not drawn inside the shell, so those zones are not there; the ring still
 * leaves the 16 dp gutter the strip it replaces had. The drags that do exist (crop handles, curve
 * points) are the editor's and unchanged.
 *
 * A non-drag path to everything: every tool is a button on the centre or on exactly one group's
 * page (tested), "More" is a button, and Back is a button as well as the system Back.
 *
 * Landscape, a tablet, a narrow window or a large font get [HubLayout.LIST] instead: every group
 * as a heading with its tools under it, scrolling inside [controlsMaxHeight].
 *
 * Accessibility: every target is at least 48 dp; the ring buttons say their group and how many
 * tools sit inside; the current group is marked by fill, bold and underline together, and exposes
 * `selected`; TalkBack meets the centre first, then the ring (tree order, grouped); the region
 * announces its page when it changes. Nothing here has been run with TalkBack.
 *
 * @param toolContent draws the controls of the open tool, under the tool's title and Back.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HubRingPanel(
    session: HubSession,
    controlsMaxHeight: Dp,
    modifier: Modifier = Modifier,
    toolContent: @Composable (ToolSpec) -> Unit,
) {
    // The hub's own Back is registered after (so asked before) the host's, which leaves the editor.
    BackHandler(enabled = session.canGoBack) { session.back() }

    val view = session.view
    val configuration = LocalConfiguration.current
    val layout = HubRingLayout.kindFor(
        configuration.screenWidthDp, configuration.screenHeightDp, LocalDensity.current.fontScale,
    )
    // The list has no group pages; a group page restored from a portrait run must not sit hidden
    // behind it and swallow a Back press.
    LaunchedEffect(layout, view.groupPage != null, view.openTool == null) {
        if (layout == HubLayout.LIST && view.openTool == null && view.groupPage != null) session.home()
    }

    val open = view.openTool
    val page = view.groupPage.takeIf { layout == HubLayout.RING }
    val title = when {
        open != null -> open.title
        page != null -> HubCopy.groupTitle(page.group)
        else -> HubCopy.HUB_LABEL
    }
    val usage = session.state.memory.usage
    Column(
        modifier.fillMaxWidth().semantics { paneTitle = title },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            open != null -> {
                PageHeader(open.title, onBack = session::closeTool)
                toolContent(open)
                if (layout == HubLayout.RING) {
                    GroupRing(view.activeGroup, usage, compact = true, onTap = session::ring)
                }
            }

            layout == HubLayout.LIST -> HubList(session, view, controlsMaxHeight)

            page != null -> {
                PageHeader(HubCopy.groupTitle(page.group), onBack = { session.back() })
                FlowRow(
                    Modifier.fillMaxWidth().semantics { isTraversalGroup = true },
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    page.front.forEach { ToolButton(it) { session.open(it.id) } }
                    if (page.deeperCount > 0) {
                        MoreButton(page.deeperCount, shown = session.state.deeperShown, onClick = session::toggleMore)
                    }
                    page.deeper.forEach { ToolButton(it) { session.open(it.id) } }
                }
                GroupRing(view.activeGroup, usage, compact = false, onTap = session::ring)
            }

            else -> {
                CentreHub(view.centre, onOpen = { session.open(it.id) })
                GroupRing(view.activeGroup, usage, compact = false, onTap = session::ring)
            }
        }
    }
}

/** The centre: the few tools used most, in slots that only move when the model says so. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CentreHub(tools: List<ToolSpec>, onOpen: (ToolSpec) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .semantics { isTraversalGroup = true }
            .clip(RoundedCornerShape(24.dp))
            .background(Color.White.copy(alpha = 0.07f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            HubCopy.CENTRE_LABEL,
            color = Color.White.copy(alpha = 0.6f),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.semantics { heading() },
        )
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            tools.forEach { ToolButton(it) { onOpen(it) } }
        }
    }
}

/**
 * The ring: one button per group, in [ToolGroup] order. Full size it is icon over a short label on
 * a smile-shaped arc; [compact] (a tool is open and wants the room) is the icons alone in a plain
 * row, each with its spoken name.
 */
@Composable
private fun GroupRing(active: ToolGroup?, usage: ToolUsage, compact: Boolean, onTap: (ToolGroup) -> Unit) {
    val depth = if (compact) 0.dp else HubRingLayout.ARC_DEPTH_DP.dp
    ArcRow(depth, Modifier.fillMaxWidth().semantics { isTraversalGroup = true }) {
        ToolGroup.entries.forEach { group ->
            val deeper = HubModel.groupPage(group, usage, deeperShown = false).deeperCount
            RingButton(group, selected = group == active, deeperCount = deeper, compact = compact) { onTap(group) }
        }
    }
}

@Composable
private fun RingButton(group: ToolGroup, selected: Boolean, deeperCount: Int, compact: Boolean, onClick: () -> Unit) {
    val ink = if (selected) MaterialTheme.colorScheme.onPrimary else Color.White
    Column(
        Modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = if (compact) 48.dp else 56.dp)
            .padding(horizontal = 2.dp)
            .clip(RoundedCornerShape(16.dp))
            // Fill, bold and underline together: the current group is never marked by colour alone.
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.12f))
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = HubCopy.groupDescription(group, deeperCount) }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(groupIcon(group), contentDescription = null, tint = ink, modifier = Modifier.size(24.dp))
        if (!compact) {
            Text(
                HubRingCopy.shortGroupTitle(group),
                color = ink,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.Bold else null,
                textDecoration = if (selected) TextDecoration.Underline else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                // The button already speaks the group's full name; the label is not a second node.
                modifier = Modifier.clearAndSetSemantics { },
            )
        } else if (selected) {
            // With no label to bold, the selected icon button carries a bar under it.
            Box(Modifier.padding(top = 2.dp).size(width = 16.dp, height = 2.dp).background(ink))
        }
    }
}

/** Lays [content] in equal columns, each button dropped by [HubRingLayout.arcOffsets]. */
@Composable
private fun ArcRow(depth: Dp, modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val count = measurables.size
        val cell = if (count == 0) 0 else constraints.maxWidth / count
        val placeables = measurables.map {
            it.measure(constraints.copy(minWidth = cell, maxWidth = cell, minHeight = 0))
        }
        val depthPx = depth.roundToPx()
        val offsets = HubRingLayout.arcOffsets(count, depthPx.toFloat())
        val rowHeight = (placeables.maxOfOrNull { it.height } ?: 0) + depthPx
        val left = (constraints.maxWidth - cell * count) / 2
        layout(constraints.maxWidth, rowHeight) {
            placeables.forEachIndexed { i, p -> p.placeRelative(left + i * cell, offsets[i].roundToInt()) }
        }
    }
}

/** Every group with all its tools, front ones first; for landscape, tablets and large fonts. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HubList(session: HubSession, view: HubView, controlsMaxHeight: Dp) {
    val usage = session.state.memory.usage
    Column(
        Modifier.fillMaxWidth().heightIn(max = controlsMaxHeight).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ListHeading(HubCopy.CENTRE_LABEL)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            view.centre.forEach { ToolButton(it) { session.open(it.id) } }
        }
        ToolGroup.entries.forEach { group ->
            val page = HubModel.groupPage(group, usage, deeperShown = true)
            ListHeading(HubCopy.groupTitle(group))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (page.front + page.deeper).forEach { ToolButton(it) { session.open(it.id) } }
            }
        }
    }
}

@Composable
private fun ListHeading(text: String) {
    Text(
        text,
        color = Color.White.copy(alpha = 0.6f),
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(top = 4.dp).semantics { heading() },
    )
}

/** The title row of a page: Back, then what this page is. */
@Composable
private fun PageHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(24.dp))
                .clickable(role = Role.Button, onClick = onBack)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            Text(HubCopy.BACK, color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
        Text(
            title,
            color = Color.White,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp).semantics { heading() },
        )
    }
}

/** A tool, as a pill at least 48 dp tall. */
@Composable
private fun ToolButton(spec: ToolSpec, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .defaultMinSize(minWidth = 48.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.14f))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(spec.title, color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}

/** "More (3)" / "Fewer" at the end of a group's front tools. */
@Composable
private fun MoreButton(deeperCount: Int, shown: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .defaultMinSize(minWidth = 48.dp)
            .clip(RoundedCornerShape(50))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = HubRingCopy.moreDescription(deeperCount, shown) }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            HubRingCopy.moreLabel(deeperCount, shown),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

private fun groupIcon(group: ToolGroup): ImageVector = when (group) {
    ToolGroup.LIGHT -> Icons.Outlined.WbSunny
    ToolGroup.COLOUR -> Icons.Outlined.Palette
    ToolGroup.DETAIL -> Icons.Outlined.Grain
    ToolGroup.CROP_STRAIGHTEN -> Icons.Outlined.Crop
    ToolGroup.EFFECTS -> Icons.Outlined.AutoFixHigh
}
