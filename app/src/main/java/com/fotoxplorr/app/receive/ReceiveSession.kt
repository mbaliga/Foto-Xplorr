package com.fotoxplorr.app.receive

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.currentCoroutineContext

/** Where one received file stands in a "Save to library" run. */
sealed interface ReceiveItemState {
    /** Not saved yet: never started, or stopped by a cancel before its row became visible. */
    data object Pending : ReceiveItemState

    data class Saved(val displayName: String) : ReceiveItemState

    data class Failed(val reason: String) : ReceiveItemState
}

/** The counts the progress and the result summary are drawn from. */
data class ReceiveProgress(
    val total: Int,
    val saved: Int,
    val failed: Int,
    val running: Boolean,
) {
    /** Items still waiting, failures excluded. */
    val remaining: Int get() = total - saved - failed

    /** Items a "Save the rest" would try: the waiting ones and the failed ones. */
    val resumable: Int get() = total - saved
}

/**
 * The state of saving [items], one at a time, resumable per item.
 *
 * [run] walks the items that are not [ReceiveItemState.Saved] yet, in order, and hands each to the
 * saver. A saver that throws marks only that item [ReceiveItemState.Failed] and the run goes on. A
 * cancel stops the run at the item in flight, which stays [ReceiveItemState.Pending] (the saver is
 * responsible for leaving no row behind when it is cancelled); calling [run] again picks up from
 * there and never saves an item twice. The session lives as long as the activity's view model, so
 * "resumable" means across a rotation or a Cancel, not across the process: a received uri's
 * read grant belongs to the activity that received it.
 *
 * Pure: no Android, so the order, the failure handling and the cancel are tested on the JVM.
 */
class ReceiveSession(val items: List<ReceiveItem>) {
    private val states = MutableList<ReceiveItemState>(items.size) { ReceiveItemState.Pending }
    private val lock = Any()
    private val _progress = MutableStateFlow(ReceiveProgress(items.size, saved = 0, failed = 0, running = false))
    val progress: StateFlow<ReceiveProgress> = _progress.asStateFlow()

    fun stateOf(index: Int): ReceiveItemState = synchronized(lock) { states[index] }

    fun failures(): List<Pair<ReceiveItem, String>> = synchronized(lock) {
        items.indices.mapNotNull { i -> (states[i] as? ReceiveItemState.Failed)?.let { items[i] to it.reason } }
    }

    /** Saves what is not saved yet; returns when done or throws the cancellation. */
    suspend fun run(save: suspend (ReceiveItem) -> String) {
        _progress.update { it.copy(running = true) }
        try {
            for (index in items.indices) {
                if (synchronized(lock) { states[index] } is ReceiveItemState.Saved) continue
                currentCoroutineContext().ensureActive()
                val outcome: ReceiveItemState = try {
                    ReceiveItemState.Saved(save(items[index]))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ReceiveItemState.Failed(e.message ?: "Could not be saved.")
                }
                synchronized(lock) { states[index] = outcome }
                publish(running = true)
            }
        } finally {
            publish(running = false)
        }
    }

    private fun publish(running: Boolean) {
        val snapshot = synchronized(lock) { states.toList() }
        _progress.value = ReceiveProgress(
            total = items.size,
            saved = snapshot.count { it is ReceiveItemState.Saved },
            failed = snapshot.count { it is ReceiveItemState.Failed },
            running = running,
        )
    }
}
