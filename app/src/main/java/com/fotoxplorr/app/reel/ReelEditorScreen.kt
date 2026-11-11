package com.fotoxplorr.app.reel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.experimental.ShellEdges
import com.fotoxplorr.app.jobs.RunningJob
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaImage
import com.fotoxplorr.app.ui.RoomStyle

/** What the editor asks its screen to do, apart from changing the project (that goes through [ReelScreenState.edit]). */
internal class ReelEditorActions(
    val onBack: () -> Unit,
    val onMake: () -> Unit,
    val onCancelMake: () -> Unit,
    val onMakeSilent: () -> Unit,
    val onWatch: (String) -> Unit,
    val onPickSound: () -> Unit,
    val onPickItems: () -> Unit,
)

/**
 * The editor: the title bar (Back, the name, Make), the progress strip of a video being made, the preview with its
 * buttons, the strip of items, the panel of the open tool and the tool chips. Everything that changes the project
 * goes through [ReelScreenState.edit]; what runs work (the video, the sound file) is passed in as [actions].
 *
 * Everything between the title bar and the tool chips scrolls up and down together, so a large font or a small
 * phone costs scrolling, not controls. The strip of items scrolls sideways and is kept [ShellEdges.inset] from
 * the left and right edges, because the shell takes a horizontal drag that starts in its edge zone before the
 * strip sees it (TRAPS #72); the previous and next buttons and the move buttons reach every item without it. The
 * chips at the bottom are taps, not drags, and stop short of the bottom edge.
 */
@Composable
internal fun ReelEditorScreen(
    state: ReelScreenState,
    open: OpenReel,
    byId: Map<Long, MediaAsset>,
    making: RunningJob?,
    makerAvailable: Boolean,
    reduceMotion: Boolean,
    actions: ReelEditorActions,
    modifier: Modifier = Modifier,
) {
    val project = open.project
    val issues = remember(project) { ReelPlan.validate(project) }
    // An empty list is a library still loading, not "everything is gone".
    val missing = remember(project, byId) { if (byId.isEmpty()) emptyList() else ReelEditing.missing(project, byId.keys) }
    val problems = remember(issues, missing) { (issues.mapNotNull { it.itemIndex } + missing).toSet() }
    val firstRatio = project.items.firstNotNullOfOrNull { item ->
        when (item) {
            is StillItem -> byId[item.mediaId]?.aspectRatio
            is ClipItem -> byId[item.mediaId]?.aspectRatio
            is TitleItem -> null
        }
    }
    val plan = remember(project, state.plainCuts, firstRatio) { ReelPreviewMath.planFor(project, state.plainCuts, firstRatio) }
    val clock = remember { PreviewClock(startPlaying = !reduceMotion) }
    val latestPlan by rememberUpdatedState(plan)
    // Picking an item shows it: the preview jumps to where it starts.
    LaunchedEffect(state.selectedId) {
        val index = state.selectedIndex
        if (index >= 0) clock.timeUs = ReelPreviewMath.startOf(latestPlan, index)
    }

    Column(modifier.fillMaxSize()) {
        EditorHeader(
            name = project.name,
            makeOpen = state.panel == ReelPanel.MAKE,
            onBack = actions.onBack,
            onRename = { state.panel = ReelPanel.REEL },
            onMake = { state.toggle(ReelPanel.MAKE) },
        )
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            open.notice?.let { Text(it, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).polite(), style = MaterialTheme.typography.bodySmall) }
            state.saveNote?.let { Text(it, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).polite(), style = MaterialTheme.typography.bodySmall) }
            MakeStrip(state, making, actions)

            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                if (project.items.isEmpty()) {
                    Text(ReelCopy.EMPTY_REEL, modifier = Modifier.heightIn(min = 96.dp).polite(), style = MaterialTheme.typography.bodyMedium, color = RoomStyle.InkMuted)
                } else {
                    ReelPreview(plan, byId, clock)
                }
            }
            PlayRow(state, project, plan, clock)
            Text(ReelCopy.PREVIEW_NOTE, modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkFaint)

            Strip(state, project, byId, missing.toSet(), problems, reduceMotion)
            StripActions(state, project)

            if (state.panel != ReelPanel.NONE) {
                Column(Modifier.fillMaxWidth().background(RoomStyle.Surface).padding(horizontal = 16.dp, vertical = 8.dp)) {
                    PanelContent(state, project, byId, plan, issues, missing.size, makerAvailable, making != null, actions)
                }
            }
        }
        ToolBar(state.panel) { state.toggle(it) }
    }
}

@Composable
private fun PanelContent(
    state: ReelScreenState,
    project: ReelProject,
    byId: Map<Long, MediaAsset>,
    plan: ReelRenderPlan,
    issues: List<ReelIssue>,
    missing: Int,
    makerAvailable: Boolean,
    making: Boolean,
    actions: ReelEditorActions,
) {
    when (state.panel) {
        ReelPanel.NONE -> Unit
        ReelPanel.ITEM -> ItemPanel(state, project, byId, project.aspect.ratio ?: plan.frame.ratio)
        ReelPanel.ADD -> AddPanel(state, project, actions.onPickItems)
        ReelPanel.REEL -> ReelWholePanel(state, project, plan.totalMs)
        ReelPanel.SOUND -> SoundPanel(state, project, actions.onPickSound)
        ReelPanel.MAKE -> MakePanel(
            state = state,
            issues = issues,
            missing = missing,
            canMake = makerAvailable && ReelPlan.canRender(issues) && missing == 0,
            makerAvailable = makerAvailable,
            making = making,
            onMake = actions.onMake,
        )
    }
}

// ---- the title bar ------------------------------------------------------------------------------------------------

@Composable
private fun EditorHeader(name: String, makeOpen: Boolean, onBack: () -> Unit, onRename: () -> Unit, onMake: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(RoomStyle.Surface).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ReelIconButton(Icons.AutoMirrored.Outlined.ArrowBack, ReelCopy.BACK, onBack)
        Text(
            name.trim().ifBlank { ReelCopy.DEFAULT_NAME },
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClickLabel = ReelCopy.RENAME, onClick = onRename)
                .padding(horizontal = 8.dp, vertical = 12.dp)
                .semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        ReelToggle(ReelCopy.TOOL_MAKE, makeOpen, { onMake() })
    }
}

// ---- the video being made, or how the last one ended ----------------------------------------------------------------

/** The video's progress and Cancel while it runs, or how the last one ended. Shown whatever panel is open. */
@Composable
private fun MakeStrip(state: ReelScreenState, making: RunningJob?, actions: ReelEditorActions) {
    val result = state.result
    if (making == null && result == null) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (making != null) {
            val progress = making.progress
            if (progress != null) {
                LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = Color.White, trackColor = RoomStyle.InkFaint)
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Color.White, trackColor = RoomStyle.InkFaint)
            }
            Text(ReelCopy.MAKING, modifier = Modifier.polite(), style = MaterialTheme.typography.bodySmall)
            Text(
                ReelCopy.LEAVING_CANCELS,
                modifier = if (state.leaveHeld) Modifier.polite() else Modifier,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.leaveHeld) RoomStyle.Ink else RoomStyle.InkFaint,
            )
            ReelButton(ReelCopy.CANCEL, actions.onCancelMake)
        } else when (result) {
            is ReelResult.Saved -> {
                Text(ReelCopy.SAVED, modifier = Modifier.polite(), style = MaterialTheme.typography.bodyMedium)
                result.done.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkMuted) }
                Text(ReelCopy.madeNote(result.done.width, result.done.height, result.done.durationMs), style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkMuted)
                ReelButton(ReelCopy.WATCH_IT, { actions.onWatch(result.done.uri) })
                state.watchNote?.let { Text(it, modifier = Modifier.polite(), style = MaterialTheme.typography.bodySmall) }
            }
            is ReelResult.Failed -> Text(result.sentence, modifier = Modifier.polite(), style = MaterialTheme.typography.bodyMedium)
            is ReelResult.AskSilent -> {
                Text(result.sentence, modifier = Modifier.polite(), style = MaterialTheme.typography.bodyMedium)
                Text(ReelCopy.SOUND_ASK, modifier = Modifier.polite(), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReelButton(ReelCopy.SOUND_ASK_YES, actions.onMakeSilent)
                    ReelButton(ReelCopy.SOUND_ASK_NO, { state.result = null })
                }
            }
            ReelResult.Cancelled -> Text(ReelCopy.CANCELLED, modifier = Modifier.polite(), style = MaterialTheme.typography.bodyMedium)
            null -> Unit
        }
    }
}

// ---- the preview's buttons ------------------------------------------------------------------------------------------

@Composable
private fun PlayRow(state: ReelScreenState, project: ReelProject, plan: ReelRenderPlan, clock: PreviewClock) {
    val seconds by remember(plan) { derivedStateOf { clock.timeUs / 1_000_000L } }
    val index = state.selectedIndex
    fun step(delta: Int) {
        val last = project.items.lastIndex
        if (last < 0) return
        val target = if (index < 0) (if (delta > 0) 0 else last) else (index + delta).coerceIn(0, last)
        state.select(project.items[target].id)
        // Stepping to the item already picked still shows it from its start.
        clock.timeUs = ReelPreviewMath.startOf(plan, target)
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        ReelIconButton(Icons.Outlined.SkipPrevious, ReelCopy.PREVIOUS_ITEM, { step(-1) }, enabled = project.items.isNotEmpty() && index != 0)
        ReelIconButton(
            if (clock.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
            if (clock.playing) ReelCopy.PAUSE else ReelCopy.PLAY,
            { clock.playing = !clock.playing },
            enabled = project.items.isNotEmpty(),
        )
        ReelIconButton(Icons.Outlined.SkipNext, ReelCopy.NEXT_ITEM, { step(1) }, enabled = project.items.isNotEmpty() && index != project.items.lastIndex)
        Text(
            ReelCopy.clockOf(seconds * 1000L, plan.totalMs),
            modifier = Modifier.padding(start = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = RoomStyle.InkMuted,
        )
    }
}

// ---- the strip of items ---------------------------------------------------------------------------------------------------

private val CELL_WIDTH = 76.dp
private val CELL_HEIGHT = 96.dp

@Composable
private fun Strip(state: ReelScreenState, project: ReelProject, byId: Map<Long, MediaAsset>, missing: Set<Int>, problems: Set<Int>, reduceMotion: Boolean) {
    if (project.items.isEmpty()) return
    val listState = rememberLazyListState()
    val selected = state.selectedIndex
    // Under the system's remove-animations setting the strip jumps to the picked item instead of gliding.
    LaunchedEffect(selected) {
        if (selected >= 0) if (reduceMotion) listState.scrollToItem(selected) else listState.animateScrollToItem(selected)
    }
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = ShellEdges.inset, vertical = 8.dp),
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(project.items, key = { _, item -> item.id }) { index, item ->
            val asset = when (item) {
                is StillItem -> byId[item.mediaId]
                is ClipItem -> byId[item.mediaId]
                is TitleItem -> null
            }
            StripCell(
                item = item,
                number = index + 1,
                asset = asset,
                lost = index in missing,
                problem = index in problems,
                showHandOver = index < project.items.lastIndex && item.transition != ReelTransition.CUT,
                selected = index == selected,
                onClick = { state.select(item.id) },
            )
        }
    }
}

/**
 * One item: its picture (or the card's colour), its number, a clip's camera mark, its time, and the hand-over
 * into the next when there is one. The picked item has a white border and a check mark as well as being selected
 * for a screen reader. The words a screen reader hears are the kind, the number and the time, then anything
 * that needs attention.
 */
@Composable
private fun StripCell(
    item: ReelItem,
    number: Int,
    asset: MediaAsset?,
    lost: Boolean,
    problem: Boolean,
    showHandOver: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    val words = listOfNotNull(
        ReelCopy.itemLabel(ReelEditing.kindWord(item), number, item.durationMs),
        if (showHandOver) ReelCopy.transition(item.transition) else null,
        if (lost) ReelCopy.NOT_IN_LIBRARY else null,
        if (problem && !lost) ReelCopy.NEEDS_ATTENTION else null,
    ).joinToString(", ")
    Column(
        Modifier
            .width(CELL_WIDTH)
            .semantics(mergeDescendants = true) { contentDescription = words }
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
    ) {
        Box(
            Modifier
                .size(CELL_WIDTH, CELL_HEIGHT - 20.dp)
                .background(Color(0xFF2A2A2A), shape)
                .border(if (selected) 3.dp else 1.dp, if (selected) Color.White else RoomStyle.InkFaint, shape),
        ) {
            when {
                item is TitleItem -> {
                    Box(Modifier.fillMaxSize().background(Color(item.backgroundArgb or 0xFF000000.toInt()), shape), contentAlignment = Alignment.Center) {
                        Text(
                            item.text.lineSequence().firstOrNull().orEmpty(),
                            color = Color(ReelTitleCard.readableOn(item.backgroundArgb)),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(4.dp),
                        )
                    }
                }
                asset != null && !lost -> MediaImage(asset, Modifier.fillMaxSize(), ContentScale.Crop)
                else -> Icon(Icons.Outlined.Warning, contentDescription = null, modifier = Modifier.align(Alignment.Center), tint = RoomStyle.InkMuted)
            }
            Text(
                "$number",
                modifier = Modifier.align(Alignment.TopStart).padding(3.dp).background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
            )
            if (item is ClipItem) {
                Icon(Icons.Outlined.Videocam, contentDescription = null, modifier = Modifier.align(Alignment.BottomStart).padding(3.dp).size(16.dp), tint = Color.White)
            }
            if (problem) {
                Icon(Icons.Outlined.Warning, contentDescription = null, modifier = Modifier.align(Alignment.TopEnd).padding(3.dp).size(16.dp), tint = Color.White)
            }
            if (selected) {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = null,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp).size(18.dp).background(Color.White, RoundedCornerShape(9.dp)).padding(2.dp),
                    tint = Color.Black,
                )
            }
        }
        Text(
            ReelCopy.seconds(item.durationMs) + if (showHandOver) " " + ReelCopy.transition(item.transition) else "",
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) RoomStyle.Ink else RoomStyle.InkMuted,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun Modifier.fillMaxSize(): Modifier = this.then(Modifier.fillMaxSize())

/** The picked item's place and the buttons that move, copy or remove it: the way to do all of those without a drag. */
@Composable
private fun StripActions(state: ReelScreenState, project: ReelProject) {
    val index = state.selectedIndex
    if (project.items.isEmpty()) return
    if (index < 0) {
        Text(ReelCopy.PICK_AN_ITEM, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkMuted)
        return
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        ReelIconButton(Icons.AutoMirrored.Outlined.ArrowBack, ReelCopy.MOVE_EARLIER, { state.moveSelected(index - 1) }, enabled = index > 0)
        ReelIconButton(Icons.AutoMirrored.Outlined.ArrowForward, ReelCopy.MOVE_LATER, { state.moveSelected(index + 1) }, enabled = index < project.items.lastIndex)
        ReelIconButton(Icons.Outlined.ContentCopy, ReelCopy.DUPLICATE, { state.duplicateSelected() }, enabled = ReelEditing.room(project) > 0)
        ReelIconButton(Icons.Outlined.Delete, ReelCopy.REMOVE, { state.removeSelected() })
        Spacer(Modifier.width(8.dp))
        Text(
            ReelCopy.itemPosition(index + 1, project.items.size),
            modifier = Modifier.polite(),
            style = MaterialTheme.typography.bodySmall,
            color = RoomStyle.InkMuted,
        )
    }
}

// ---- the tool chips -------------------------------------------------------------------------------------------------------------

@Composable
private fun ToolBar(open: ReelPanel, onToggle: (ReelPanel) -> Unit) {
    // Clear of the bottom edge zone, as the panorama's bar is; the chips are taps, not drags.
    Row(
        Modifier
            .fillMaxWidth()
            .background(RoomStyle.Surface)
            .navigationBarsPadding()
            .heightIn(min = 72.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ToolChip(ReelCopy.TOOL_ITEM, open == ReelPanel.ITEM, { onToggle(ReelPanel.ITEM) }, Modifier.weight(1f))
        ToolChip(ReelCopy.TOOL_ADD, open == ReelPanel.ADD, { onToggle(ReelPanel.ADD) }, Modifier.weight(1f))
        ToolChip(ReelCopy.TOOL_REEL, open == ReelPanel.REEL, { onToggle(ReelPanel.REEL) }, Modifier.weight(1f))
        ToolChip(ReelCopy.TOOL_SOUND, open == ReelPanel.SOUND, { onToggle(ReelPanel.SOUND) }, Modifier.weight(1f))
    }
}

/** A tool's button: the open one is bold and has a line under it as well as being selected for a screen reader. */
@Composable
private fun ToolChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) RoomStyle.Ink else RoomStyle.InkMuted,
            maxLines = 1,
        )
        Box(
            Modifier
                .padding(top = 4.dp)
                .height(3.dp)
                .fillMaxWidth(0.6f)
                .background(if (selected) Color.White else Color.Transparent, RoundedCornerShape(2.dp)),
        )
    }
}
