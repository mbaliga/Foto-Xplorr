package com.fotoxplorr.app.reel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.ui.RoomStyle

/*
 * The panels under the preview, one per tool. Each takes the state and draws the controls for it; every number a
 * button changes is a ReelEditing function with its own test, and nothing here does any work.
 */

@Composable
private fun ReelTextField(label: String, value: String, onChange: (String) -> Unit, singleLine: Boolean = true) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = RoomStyle.Ink,
            unfocusedTextColor = RoomStyle.Ink,
            focusedBorderColor = Color.White,
            unfocusedBorderColor = RoomStyle.InkFaint,
            focusedLabelColor = RoomStyle.InkMuted,
            unfocusedLabelColor = RoomStyle.InkMuted,
            cursorColor = Color.White,
        ),
    )
}

// ---- the picked item -------------------------------------------------------------------------------------

/** What can be changed about the picked item: its time (or the part of a clip), its move, its words, how it hands over. */
@Composable
internal fun ItemPanel(state: ReelScreenState, project: ReelProject, byId: Map<Long, MediaAsset>, frameAspect: Float) {
    val index = state.selectedIndex
    val item = project.items.getOrNull(index)
    if (item == null) {
        Text(ReelCopy.PICK_AN_ITEM, style = MaterialTheme.typography.bodyMedium, color = RoomStyle.InkMuted)
        return
    }
    ReelColumn {
        Text(
            ReelCopy.itemPosition(index + 1, project.items.size) + ", " + ReelEditing.kindWord(item),
            modifier = Modifier.polite(),
            style = MaterialTheme.typography.bodyMedium,
        )
        when (item) {
            is StillItem -> {
                DurationControls(item.durationMs, { delta -> state.edit { ReelEditing.nudgeDuration(it, index, delta) } })
                val original = project.aspect == ReelAspect.ORIGINAL
                ReelSectionTitle(ReelCopy.MOVE)
                val current = ReelMoves.choiceOf(item.motion)
                val imageAspect = byId[item.mediaId]?.aspectRatio ?: frameAspect
                ReelWrap {
                    MotionChoice.entries.forEach { choice ->
                        ReelChoice(
                            ReelCopy.motionChoice(choice),
                            selected = !original && current == choice,
                            onClick = { state.edit { ReelEditing.setMotion(it, index, ReelMoves.motionFor(choice, imageAspect, frameAspect)) } },
                            enabled = !original,
                        )
                    }
                }
                if (original) Text(ReelCopy.MOVE_STAYS_STILL, style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkMuted)
            }
            is ClipItem -> {
                ReelSectionTitle(ReelCopy.CLIP_PART)
                ReelStepper(
                    valueText = ReelCopy.clipStarts(item.inMs),
                    lessLabel = ReelCopy.START_EARLIER,
                    moreLabel = ReelCopy.START_LATER,
                    onLess = { state.edit { ReelEditing.trim(it, index, -ReelEditing.STEP_MS, 0L) } },
                    onMore = { state.edit { ReelEditing.trim(it, index, ReelEditing.STEP_MS, 0L) } },
                    canLess = item.inMs > 0L,
                    canMore = item.outMs - item.inMs > ReelLimits.MIN_ITEM_MS,
                )
                ReelStepper(
                    valueText = ReelCopy.clipEnds(item.outMs),
                    lessLabel = ReelCopy.END_EARLIER,
                    moreLabel = ReelCopy.END_LATER,
                    onLess = { state.edit { ReelEditing.trim(it, index, 0L, -ReelEditing.STEP_MS) } },
                    onMore = { state.edit { ReelEditing.trim(it, index, 0L, ReelEditing.STEP_MS) } },
                    canLess = item.outMs - item.inMs > ReelLimits.MIN_ITEM_MS,
                    canMore = item.sourceDurationMs <= 0L || item.outMs < item.sourceDurationMs,
                )
                Text(ReelCopy.lastsFor(item.durationMs), style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkMuted)
            }
            is TitleItem -> {
                var words by remember(item.id) { mutableStateOf(item.text) }
                ReelSectionTitle(ReelCopy.TITLE_WORDS)
                ReelTextField(ReelCopy.TITLE_WORDS, words, { words = ReelEditing.cleanText(it); state.edit { p -> ReelEditing.setCardText(p, index, words) } }, singleLine = false)
                DurationControls(item.durationMs, { delta -> state.edit { ReelEditing.nudgeDuration(it, index, delta) } })
                ReelSectionTitle(ReelCopy.TITLE_COLOUR)
                ReelWrap {
                    ReelEditing.CARD_COLOURS.forEach { c ->
                        CardSwatch(c, selected = item.backgroundArgb == c.argb) { state.edit { p -> ReelEditing.setCardColour(p, index, c.argb) } }
                    }
                }
            }
        }
        ReelSectionTitle(ReelCopy.HAND_OVER)
        val last = index == project.items.lastIndex
        ReelWrap {
            ReelTransition.entries.forEach { t ->
                ReelChoice(ReelCopy.transition(t), selected = !last && item.transition == t, onClick = { state.edit { ReelEditing.setTransition(it, index, t) } }, enabled = !last)
            }
        }
        if (last) Text(ReelCopy.LAST_ITEM, style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkMuted)
    }
}

@Composable
private fun DurationControls(durationMs: Long, nudge: (Long) -> Unit) {
    ReelSectionTitle(ReelCopy.TIME_ON_SCREEN)
    ReelStepper(
        valueText = ReelCopy.lastsFor(durationMs),
        lessLabel = ReelCopy.SHORTER,
        moreLabel = ReelCopy.LONGER,
        onLess = { nudge(-ReelEditing.STEP_MS) },
        onMore = { nudge(ReelEditing.STEP_MS) },
        canLess = durationMs > ReelLimits.MIN_ITEM_MS,
        canMore = durationMs < ReelEditing.EDIT_MAX_MS,
    )
}

/** A card colour: the colour is in the square and its name in the words a screen reader hears; the chosen one has a check mark. */
@Composable
private fun CardSwatch(colour: CardColour, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .size(48.dp)
            .background(Color(colour.argb), shape)
            .border(if (selected) 3.dp else 1.dp, if (selected) Color.White else RoomStyle.InkFaint, shape)
            .semantics { contentDescription = colour.name }
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Outlined.Check, contentDescription = null, tint = Color(ReelTitleCard.readableOn(colour.argb)))
    }
}

// ---- add ----------------------------------------------------------------------------------------------------

@Composable
internal fun AddPanel(state: ReelScreenState, project: ReelProject, onPickItems: () -> Unit) {
    val full = ReelEditing.room(project) == 0
    ReelColumn {
        ReelButton(ReelCopy.ADD_ITEMS, onPickItems, enabled = !full)
        ReelButton(
            ReelCopy.ADD_CARD,
            {
                val after = state.selectedIndex.takeIf { it >= 0 }
                state.edit { ReelEditing.addCard(it, ReelCopy.NEW_CARD_WORDS, after) }
                // The new card is the one right after the pick (or the last), and is picked so its words can be written.
                val items = state.project?.items.orEmpty()
                val at = if (after == null) items.lastIndex else after + 1
                items.getOrNull(at)?.let { state.selectedId = it.id }
                state.panel = ReelPanel.ITEM
            },
            enabled = !full,
        )
        state.addNote?.let { Text(it, modifier = Modifier.polite(), style = MaterialTheme.typography.bodySmall) }
    }
}

// ---- the reel as a whole --------------------------------------------------------------------------------------

@Composable
internal fun ReelWholePanel(state: ReelScreenState, project: ReelProject, totalMs: Long) {
    ReelColumn {
        ReelTextField(ReelCopy.REEL_NAME, project.name, { name -> state.edit { ReelEditing.setName(it, name.take(ReelEditing.NAME_MAX)) } })
        Text(ReelCopy.reelLength(totalMs), modifier = Modifier.polite(), style = MaterialTheme.typography.bodyMedium)
        ReelSectionTitle(ReelCopy.SHAPE)
        ReelWrap {
            ReelAspect.entries.forEach { a ->
                ReelChoice(ReelCopy.aspect(a), selected = project.aspect == a, onClick = { state.edit { ReelEditing.setAspect(it, a) } })
            }
        }
        ReelSectionTitle(ReelCopy.EVERY_HAND_OVER)
        val joins = project.items.dropLast(1)
        ReelWrap {
            ReelTransition.entries.forEach { t ->
                ReelChoice(
                    ReelCopy.transition(t),
                    selected = joins.isNotEmpty() && joins.all { it.transition == t },
                    onClick = { state.edit { it.withTransitions(t) } },
                    enabled = joins.isNotEmpty(),
                )
            }
        }
    }
}

// ---- sound ------------------------------------------------------------------------------------------------------

/**
 * The sound file: pick one with the phone's own file picker, take it away, how long the fade is, where in the file
 * the music starts, and whether the clips keep their own sound. Nothing here plays anything.
 */
@Composable
internal fun SoundPanel(state: ReelScreenState, project: ReelProject, onPick: () -> Unit) {
    val sound = project.soundtrack
    ReelColumn {
        Text(ReelCopy.MUSIC_NOTE, style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkMuted)
        if (sound == null) {
            Text(ReelCopy.SOUND_NONE, modifier = Modifier.polite(), style = MaterialTheme.typography.bodyMedium)
            ReelButton(ReelCopy.SOUND_PICK, onPick)
        } else {
            Text(ReelCopy.soundFile(state.soundName ?: ReelCopy.UNNAMED_SOUND), modifier = Modifier.polite(), style = MaterialTheme.typography.bodyMedium)
            if (state.soundMayStop) Text(ReelCopy.SOUND_MAY_STOP, modifier = Modifier.polite(), style = MaterialTheme.typography.bodySmall)
            ReelWrap {
                ReelButton(ReelCopy.SOUND_PICK, onPick)
                ReelButton(ReelCopy.SOUND_REMOVE, { state.edit { ReelEditing.removeSoundtrack(it) }; state.soundName = null; state.soundMayStop = false })
            }
            ReelSectionTitle(ReelCopy.FADE_OUT)
            ReelStepper(
                valueText = ReelCopy.seconds(sound.fadeOutMs),
                lessLabel = ReelCopy.SHORTER_FADE,
                moreLabel = ReelCopy.LONGER_FADE,
                onLess = { state.edit { ReelEditing.nudgeFade(it, -ReelEditing.FADE_STEP_MS) } },
                onMore = { state.edit { ReelEditing.nudgeFade(it, ReelEditing.FADE_STEP_MS) } },
                canLess = sound.fadeOutMs > 0L,
                canMore = sound.fadeOutMs < ReelLimits.FADE_OUT_MAX_MS,
            )
            ReelSectionTitle(ReelCopy.MUSIC_STARTS)
            ReelStepper(
                valueText = ReelCopy.clock(sound.trimStartMs),
                lessLabel = ReelCopy.EARLIER_IN_SONG,
                moreLabel = ReelCopy.LATER_IN_SONG,
                onLess = { state.edit { ReelEditing.nudgeMusicStart(it, -ReelEditing.MUSIC_STEP_MS) } },
                onMore = { state.edit { ReelEditing.nudgeMusicStart(it, ReelEditing.MUSIC_STEP_MS) } },
                canLess = sound.trimStartMs > 0L,
                canMore = sound.trimStartMs < ReelEditing.MUSIC_START_MAX_MS,
            )
        }
        if (project.items.any { it is ClipItem }) {
            ReelToggle(ReelCopy.CLIP_SOUND, state.keepClipSound, { state.keepClipSound = it })
        }
    }
}

// ---- make ---------------------------------------------------------------------------------------------------------

/** Size, plain cuts, what is wrong with the plan, and the button that makes the video. */
@Composable
internal fun MakePanel(
    state: ReelScreenState,
    issues: List<ReelIssue>,
    missing: Int,
    canMake: Boolean,
    makerAvailable: Boolean,
    making: Boolean,
    onMake: () -> Unit,
) {
    ReelColumn {
        ReelSectionTitle(ReelCopy.SIZE)
        ReelWrap {
            listOf(1080, 720, 480).forEach { side ->
                ReelChoice(ReelCopy.sizeChoice(side), selected = state.shortSide == side, onClick = { state.shortSide = side })
            }
        }
        ReelToggle(ReelCopy.PLAIN_CUTS, state.plainCuts, { state.plainCuts = it })
        issues.take(MAX_ISSUES_SHOWN).forEach { Text(ReelCopy.issue(it), modifier = Modifier.polite(), style = MaterialTheme.typography.bodySmall) }
        if (missing > 0) Text(ReelCopy.missingItems(missing), modifier = Modifier.polite(), style = MaterialTheme.typography.bodySmall)
        if (!makerAvailable) Text(ReelCopy.CANNOT_MAKE_HERE, modifier = Modifier.polite(), style = MaterialTheme.typography.bodySmall)
        ReelButton(ReelCopy.MAKE, onMake, enabled = canMake && !making, primary = true)
    }
}

private const val MAX_ISSUES_SHOWN = 4
