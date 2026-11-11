package com.fotoxplorr.app.reel

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue

/** The tool panels under the preview. */
internal enum class ReelPanel { NONE, ITEM, ADD, REEL, SOUND, MAKE }

/**
 * The reel being edited: its place in the library (null until the first save), the project, and [token], which
 * tells one editing session from the next so a save that finishes late is never applied to a different reel.
 * [loaded] is the project as its file had it (null for a new reel, or one brought back after the process was
 * killed), so the autosaver does not write a reel that was only opened. [notice] is a line about how it was
 * opened (an item that could not be read, a newer file).
 */
internal class OpenReel(
    val token: Long,
    val storeId: String?,
    val project: ReelProject,
    val notice: String? = null,
    val loaded: ReelProject? = null,
) {
    fun withProject(next: ReelProject) = OpenReel(token, storeId, next, notice, loaded)

    fun saved(id: String) = OpenReel(token, id, project, notice, loaded)
}

/**
 * What survives the process being killed: the reel as text and its place in the library. NOT which tool was
 * open on a sub-step, and not the preview's place. A reel whose text is longer than
 * [ReelProjectStore.SAVED_STATE_SAFE_CHARS] is not put in the saved-state bundle (which has a limit of about a megabyte
 * for the whole screen); it comes back empty-handed and the screen offers the autosaved file in the list instead.
 */
internal val OpenReelSaver: Saver<OpenReel?, Any> = Saver(
    save = { open ->
        open?.let {
            val text = ReelCodec.encode(it.project)
            listOf(it.storeId.orEmpty(), if (text.length <= ReelProjectStore.SAVED_STATE_SAFE_CHARS) text else "")
        }
    },
    restore = { saved ->
        val parts = saved as? List<*>
        val text = parts?.getOrNull(1) as? String
        val decoded = text?.takeIf { it.isNotEmpty() }?.let(ReelCodec::decode)
        if (parts == null || decoded == null) {
            null
        } else {
            OpenReel(token = nextReelToken(), storeId = (parts[0] as? String)?.takeIf { it.isNotEmpty() }, project = decoded.project)
        }
    },
)

private var tokens = 0L

@Synchronized
internal fun nextReelToken(): Long = ++tokens

/** How the last attempt to make the video ended. */
internal sealed interface ReelResult {
    class Saved(val done: ReelOutcome.Done) : ReelResult

    class Failed(val sentence: String) : ReelResult

    /** The sound could not be used; [sentence] says why and the person decides. */
    class AskSilent(val sentence: String) : ReelResult

    data object Cancelled : ReelResult
}

/**
 * The reel screen's state, in one place so the composables that draw it are plain. The values that must come
 * back after the process is killed are handed in as saveable states; the rest is here and is lost with the
 * process: the saved-reels list, the picker, the preview's place, a video being made.
 */
@Stable
internal class ReelScreenState(
    openState: MutableState<OpenReel?>,
    selectedState: MutableState<String?>,
    panelState: MutableState<ReelPanel>,
    sizeState: MutableState<Int>,
    plainCutsState: MutableState<Boolean>,
    clipSoundState: MutableState<Boolean>,
    draftPageState: MutableState<Boolean>,
    monthsState: MutableState<List<String>>,
    lengthState: MutableState<Long>,
) {
    var open by openState
    var selectedId by selectedState
    var panel by panelState

    /** The frame's short side the video is started at: 1080, 720 or 480. */
    var shortSide by sizeState
    var plainCuts by plainCutsState
    var keepClipSound by clipSoundState

    /** True on the page that sets a draft up. */
    var draftPage by draftPageState
    var months by monthsState
    var targetMs by lengthState

    var picker by mutableStateOf(false)
    var list by mutableStateOf<SavedReels?>(null)
    var openFailed by mutableStateOf(false)
    var deleting by mutableStateOf<SavedReel?>(null)
    var saveNote by mutableStateOf<String?>(null)
    var drafting by mutableStateOf(false)
    var draftNote by mutableStateOf<String?>(null)
    var addNote by mutableStateOf<String?>(null)
    var soundName by mutableStateOf<String?>(null)
    var soundMayStop by mutableStateOf(false)
    var watchNote by mutableStateOf<String?>(null)

    var result by mutableStateOf<ReelResult?>(null)
    var leaveHeld by mutableStateOf(false)

    val project: ReelProject? get() = open?.project

    /** Where the picked item is in the reel, or -1. */
    val selectedIndex: Int get() = ReelEditing.indexOf(project ?: ReelProject(), selectedId)

    /** Runs [change] on the open reel's project; nothing happens when no reel is open or the change did nothing. */
    fun edit(change: (ReelProject) -> ReelProject) {
        val o = open ?: return
        val next = change(o.project)
        if (next != o.project) open = o.withProject(next)
    }

    /** Picks the item with [id] (or none) and, picking one, shows its panel unless another is open. */
    fun select(id: String?) {
        selectedId = id
        if (id != null && panel == ReelPanel.NONE) panel = ReelPanel.ITEM
    }

    /** Opens [target], or closes it if it is the one open. */
    fun toggle(target: ReelPanel) {
        panel = if (panel == target) ReelPanel.NONE else target
    }

    /** The picked item moves to [to] (a position), staying picked. */
    fun moveSelected(to: Int) {
        val from = selectedIndex
        if (from >= 0) edit { it.moved(from, to) }
    }

    fun removeSelected() {
        val index = selectedIndex
        if (index < 0) return
        var after: String? = null
        edit { project ->
            project.removed(index).also { after = ReelEditing.pickAfterRemoval(it, index) }
        }
        selectedId = after
        if (after == null && panel == ReelPanel.ITEM) panel = ReelPanel.NONE
    }

    fun duplicateSelected() {
        val index = selectedIndex
        if (index < 0) return
        val before = project?.items?.size ?: return
        edit { ReelEditing.duplicate(it, index) }
        // The copy is the one right after, and is the one picked; a full reel makes no copy and keeps the pick.
        if ((project?.items?.size ?: 0) > before) project?.items?.getOrNull(index + 1)?.let { selectedId = it.id }
    }

    /**
     * The first things Back undoes, innermost first: the picker, an open panel. True when one was undone;
     * false leaves Back to the caller (leave the reel, or the draft page).
     */
    fun backOneStep(): Boolean = when {
        picker -> { picker = false; true }
        open != null && panel != ReelPanel.NONE -> { panel = ReelPanel.NONE; true }
        else -> false
    }
}
