package com.fotoxplorr.app.editor

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/** Where the hub's [HubMemory] lives between launches. One string; see [HubMemory.encode]. */
internal interface HubMemoryStore {
    fun load(): HubMemory
    fun save(memory: HubMemory)
}

/**
 * [HubMemoryStore] on a private SharedPreferences file. `apply()` writes in the background, and a
 * write that is lost to a kill costs at most the last use. Nothing here touches the network.
 */
internal class PrefsHubMemoryStore(context: Context) : HubMemoryStore {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun load(): HubMemory = HubMemory.decode(prefs.getString(KEY, null))

    override fun save(memory: HubMemory) {
        prefs.edit().putString(KEY, memory.encode()).apply()
    }

    private companion object {
        const val FILE = "editor_hub"
        const val KEY = "memory"
    }
}

/**
 * The hub's state as Compose sees it: [HubModel]'s pure transitions behind one observable value,
 * plus the two things the model leaves to the caller (see its KDoc): settling the centre when the
 * hub comes back into view, and recording a use at the first change of a tool's opening.
 *
 * What is kept where:
 *  - [HubMemory] (use counts and the centre) goes to [store] on every change, so a kill loses at
 *    most the last use.
 *  - The navigation part (open tool, open group, "More" shown) and whether the open tool has been
 *    counted yet ride in `rememberSaveable` through [rememberHubSession], as plain strings and
 *    booleans, so rotation and process death return to the same page.
 *
 * Not thread-safe; used from the main thread like any Compose state.
 */
@Stable
internal class HubSession(
    initial: HubState,
    private val store: HubMemoryStore? = null,
    countedAlready: Boolean = false,
) {
    var state: HubState by mutableStateOf(initial)
        private set

    /** True once the open tool has been counted as used: one use per opening, however many changes. */
    var counted: Boolean = countedAlready
        private set

    val view: HubView get() = HubModel.view(state)

    /** Whether Back has anything of the hub's to close; false means Back should leave the editor. */
    val canGoBack: Boolean get() = HubModel.back(state) != null

    /** A tool button, from the centre or from a group page. */
    fun open(id: String) {
        if (!ToolCatalog.has(id)) return
        counted = false
        state = HubModel.openTool(state, id)
    }

    /**
     * Tell the hub that the open tool just changed the photo. The first call of an opening counts
     * one use; later calls (every frame of a slider drag) cost a comparison. No tool open: nothing.
     */
    fun applied() {
        val id = state.openTool ?: return
        if (counted) return
        counted = true
        state = HubModel.record(state, id)
        persist()
    }

    /** The tool's own "Back": the hub catches up with use and returns to the page the tool came from. */
    fun closeTool() {
        counted = false
        state = HubModel.closeTool(state)
        persist()
    }

    /**
     * A ring button. A group that is already showing its page (no tool open) goes back to the
     * hub's home, the way tapping the selected word does elsewhere (TRAPS #61); any other tap shows
     * that group's page and closes an open tool.
     */
    fun ring(group: ToolGroup) {
        val showing = state.openGroup == group && state.openTool == null
        counted = false
        state = if (showing) state.copy(openGroup = null, deeperShown = false) else HubModel.showGroup(state, group)
        persist()
    }

    /** The "More" / "Fewer" button of a group page. */
    fun toggleMore() {
        state = HubModel.toggleDeeper(state)
    }

    /** Drop a group page that has no screen of its own (the list layout), without touching an open tool. */
    fun home() {
        if (state.openTool != null || (state.openGroup == null && !state.deeperShown)) return
        state = state.copy(openGroup = null, deeperShown = false)
    }

    /** One step back through the hub. False when there was none, so the caller leaves the editor. */
    fun back(): Boolean {
        val next = HubModel.back(state) ?: return false
        if (state.openTool != null) counted = false
        state = next
        persist()
        return true
    }

    private fun persist() {
        store?.save(state.memory)
    }

    internal companion object {
        /** A new editor opening: the remembered memory, with the centre caught up with use. */
        fun fresh(store: HubMemoryStore?): HubSession {
            val settled = HubModel.settle(HubState(memory = store?.load() ?: HubMemory.EMPTY))
            return HubSession(settled, store).also { it.persist() }
        }
    }
}

/**
 * The session for this composition. A fresh one settles the centre (the hub coming into view);
 * one restored after rotation or process death does not: the centre is frozen while a tool is open,
 * and the saved memory is read back from [store] so it is the latest, not the one at save time.
 */
@Composable
internal fun rememberHubSession(store: HubMemoryStore?): HubSession = rememberSaveable(
    inputs = arrayOf<Any?>(store),
    saver = listSaver<HubSession, Any>(
        save = {
            val s = it.state
            listOf(s.openTool.orEmpty(), s.openGroup?.name.orEmpty(), s.deeperShown, it.counted)
        },
        restore = { saved ->
            val state = HubState.restore(
                memory = store?.load() ?: HubMemory.EMPTY,
                toolId = (saved[0] as String).ifEmpty { null },
                groupName = (saved[1] as String).ifEmpty { null },
                deeperShown = saved[2] as Boolean,
            )
            HubSession(state, store, countedAlready = saved[3] as Boolean)
        },
    ),
) { HubSession.fresh(store) }

/** The default store for [context]; remembered so one composition shares one SharedPreferences. */
@Composable
internal fun rememberHubMemoryStore(context: Context): HubMemoryStore =
    remember(context) { PrefsHubMemoryStore(context) }
