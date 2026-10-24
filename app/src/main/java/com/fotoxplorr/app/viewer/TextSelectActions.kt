package com.fotoxplorr.app.viewer

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import com.fotoxplorr.app.lens.rememberLensTextActions

/**
 * Which of the toolbar's own actions (Share, Search, Listen, Translate) can be used right now.
 * Pure, so the rules are pinned without a screen.
 *
 * Every one of them acts on the SELECTED words, so each waits for a selection ([hasSelection]).
 * Search also needs somewhere to send the query ([searchAvailable]) and is left out entirely
 * when there is none, rather than shown dead. Listen, while it is already reading, is the Stop
 * button, which must always be usable: the selection can change under a running voice.
 */
internal data class SelectionActionStates(
    val shareEnabled: Boolean,
    /** Null: the host has no library search to hand the words to, so the button is not drawn. */
    val searchEnabled: Boolean?,
    val listenIsStop: Boolean,
    val listenEnabled: Boolean,
    val translateEnabled: Boolean,
) {
    companion object {
        fun of(
            hasSelection: Boolean,
            searchAvailable: Boolean,
            speaking: Boolean,
            listenReady: Boolean,
        ) = SelectionActionStates(
            shareEnabled = hasSelection,
            searchEnabled = if (searchAvailable) hasSelection else null,
            listenIsStop = speaking,
            listenEnabled = speaking || (hasSelection && listenReady),
            translateEnabled = hasSelection,
        )
    }
}

/**
 * The Select text toolbar with its actions: [TextSelectToolbar] plus Share, Search, Listen and
 * Translate acting on the selected words, through the same [com.fotoxplorr.app.lens.LensTextActions]
 * the details room's card uses on the whole text.
 *
 * Composed only while the mode is on, so the speech engine exists only then and is shut down
 * (stopping any reading) when the mode ends. The language the recogniser tagged the selected words
 * with goes to Translate as its source and to Listen as the voice, instead of the English
 * default. A translation or failure notice belongs to the words it was made for, so it is dropped
 * when the selection changes.
 *
 * @param assetKey the photo, so a fresh engine and a clean slate for each.
 * @param onSearchLibrary where Search sends the selected words; null leaves the button out.
 */
@Composable
internal fun TextSelectBar(
    state: TextSelectState,
    assetKey: Any,
    onSearchLibrary: ((String) -> Unit)?,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val actions = rememberLensTextActions(key = assetKey, onSearchLibrary = onSearchLibrary)
    val layout = state.layout
    val selection = state.selection
    val text = selection.text(layout)
    val language = selection.dominantLanguage(layout)
    LaunchedEffect(text) { actions.reset() }

    val states = SelectionActionStates.of(
        hasSelection = text.isNotBlank(),
        searchAvailable = actions.searchAvailable,
        speaking = actions.speaking,
        listenReady = actions.plan.listenEnabled,
    )
    val extras = buildList {
        add(TextSelectAction("Share", Icons.Outlined.Share, states.shareEnabled) { actions.share(text) })
        states.searchEnabled?.let { enabled ->
            add(TextSelectAction("Search", Icons.Outlined.Search, enabled) { actions.search(text) })
        }
        add(
            TextSelectAction(
                label = if (states.listenIsStop) "Stop" else "Listen",
                icon = if (states.listenIsStop) Icons.Outlined.Stop else Icons.Outlined.VolumeUp,
                enabled = states.listenEnabled,
            ) { actions.listen(text, language) },
        )
        add(TextSelectAction("Translate", Icons.Outlined.Translate, states.translateEnabled) { actions.translate(text, language) })
    }

    TextSelectToolbar(
        state = state,
        onDone = onDone,
        modifier = modifier,
        extraActions = extras,
        notes = actions.notes.map { it.text },
    )
}
