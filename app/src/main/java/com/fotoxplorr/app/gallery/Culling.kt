package com.fotoxplorr.app.gallery

import com.fotoxplorr.app.metadata.SidecarAsset
import com.fotoxplorr.app.metadata.SidecarReadResult
import com.fotoxplorr.app.metadata.SidecarService
import com.fotoxplorr.app.metadata.SidecarWriteResult
import com.fotoxplorr.core.metadata.SidecarAnnotations
import com.fotoxplorr.core.metadata.SidecarFlag

/**
 * Culling (plan WP2.12 / 4.1): single-key flags (P pick, X reject, U unflag), 0-5 stars, 6-9 colour
 * labels, and auto-advance. This file is the pure model; it knows nothing of Compose.
 *
 * WHERE ratings and flags LIVE is the owner's pending data decision, so the model talks to the
 * [AnnotationStore] interface only. It is backed here by [InMemoryAnnotationStore] (lost with the
 * process) or by the existing XMP sidecar service ([SidecarAnnotationStore]); no new persistent
 * store exists and nothing in the library database or SharedPreferences is touched.
 */
interface AnnotationStore {
    /** The annotations known for [id], or null for "none". May block (sidecar file IO). */
    fun get(id: Long): SidecarAnnotations?

    /** Replace the rating / label / flag of [id]. Returns false when it could not be kept. */
    fun put(id: Long, annotations: SidecarAnnotations): Boolean
}

class InMemoryAnnotationStore : AnnotationStore {
    private val map = HashMap<Long, SidecarAnnotations>()
    override fun get(id: Long): SidecarAnnotations? = map[id]
    override fun put(id: Long, annotations: SidecarAnnotations): Boolean {
        map[id] = annotations
        return true
    }
}

/**
 * The XMP sidecar service as an [AnnotationStore]. [resolve] turns the grid's id into the asset
 * the sidecar layer needs (null when the id has no resolvable file). Writes are explicit user
 * actions, which is the one case [com.fotoxplorr.core.metadata.SidecarPolicy] allows; if the
 * policy refuses (sidecars off, no folder grant, read-only) the write reports false and the caller
 * keeps the value in memory. Blocking: call off the main thread.
 */
class SidecarAnnotationStore(
    private val service: SidecarService,
    /**
     * True when [id] certainly has no sidecar, known from a folder listing already held (no file
     * read): the cull's first pass over thousands of photos then reads only the ones that have one.
     */
    private val absent: (Long) -> Boolean = { false },
    private val resolve: (Long) -> SidecarAsset?,
) : AnnotationStore {
    /** What the most recent [put] got back from the service; null before any, or for an unknown id. */
    @Volatile
    var lastWrite: SidecarWriteResult? = null
        private set

    override fun get(id: Long): SidecarAnnotations? {
        if (absent(id)) return null
        val asset = resolve(id) ?: return null
        return (service.read(asset) as? SidecarReadResult.Found)?.annotations
    }

    override fun put(id: Long, annotations: SidecarAnnotations): Boolean {
        val asset = resolve(id)
        if (asset == null) {
            lastWrite = null
            return false
        }
        val result = service.write(asset, annotations, explicitUserAction = true)
        lastWrite = result
        return result is SidecarWriteResult.Written
    }
}

/**
 * In-memory first, durable second: keeps every value in memory so the cull keeps working even when
 * the sidecar refuses, and passes the write on. [durableCount] is what stuck, for an honest summary.
 *
 * The durable side is read at most once per id ([get] remembers a miss too), so walking the cull
 * and counting it never goes back to the files. The pick and reject counts are kept as values
 * change, so [summary] costs nothing per key press. Thread-safe: the first pass reads on one IO
 * thread while key presses write on another; a value put by a key is never overwritten by a
 * durable read that finished after it.
 */
class WriteThroughAnnotationStore(private val durable: AnnotationStore) : AnnotationStore {
    private val lock = Any()
    private val memory = HashMap<Long, SidecarAnnotations>()

    /** Ids whose durable value has been read (or replaced by a put): never read again. */
    private val settled = HashSet<Long>()
    private val kept = HashSet<Long>()
    private var picks = 0
    private var rejects = 0
    val durableCount: Int get() = synchronized(lock) { kept.size }

    /** How many ids have their durable value in memory (read, or known to have none). */
    val settledCount: Int get() = synchronized(lock) { settled.size }

    /** Whether the most recent [put] reached the durable store (true before any put). */
    @Volatile
    var lastPutDurable: Boolean = true
        private set

    override fun get(id: Long): SidecarAnnotations? {
        synchronized(lock) { if (id in settled) return memory[id] }
        val read = durable.get(id)
        synchronized(lock) {
            if (settled.add(id) && read != null) remember(id, read)
            return memory[id]
        }
    }

    override fun put(id: Long, annotations: SidecarAnnotations): Boolean {
        synchronized(lock) {
            settled.add(id)
            remember(id, annotations)
        }
        val ok = durable.put(id, annotations)
        synchronized(lock) { if (ok) kept.add(id) else kept.remove(id) }
        lastPutDurable = ok
        return ok
    }

    /** Picks, rejects and the rest of [total] ids, from memory only (O(1)). */
    internal fun summary(total: Int): CullSummary = synchronized(lock) {
        CullSummary(picks, rejects, (total - picks - rejects).coerceAtLeast(0))
    }

    /**
     * Forgets the durable reads that found nothing, so they are read again: after a folder grant
     * the sidecars that were out of reach can now be seen. Values put by a key are kept.
     */
    fun rereadMisses() {
        synchronized(lock) { settled.retainAll(memory.keys) }
    }

    private fun remember(id: Long, value: SidecarAnnotations) {
        when (memory.put(id, value)?.flag) {
            SidecarFlag.PICK -> picks--
            SidecarFlag.REJECT -> rejects--
            else -> Unit
        }
        when (value.flag) {
            SidecarFlag.PICK -> picks++
            SidecarFlag.REJECT -> rejects++
            else -> Unit
        }
    }
}

/** The colour labels on keys 6-9 (the Lightroom convention the sidecar already reads). */
internal val CULL_LABELS: List<String> = listOf("Red", "Yellow", "Green", "Blue")

/** What one key asks of the current photo. */
internal sealed interface CullAction {
    data class Flag(val flag: SidecarFlag) : CullAction
    data class Rate(val stars: Int) : CullAction
    data class Label(val name: String) : CullAction
    data object Next : CullAction
    data object Previous : CullAction
    data object Undo : CullAction
}

/** The key character -> action table. Unbound characters give null so they fall through. */
internal fun cullActionFor(ch: Char): CullAction? = when (ch.lowercaseChar()) {
    'p' -> CullAction.Flag(SidecarFlag.PICK)
    'x' -> CullAction.Flag(SidecarFlag.REJECT)
    'u' -> CullAction.Flag(SidecarFlag.NONE)
    in '0'..'5' -> CullAction.Rate(ch - '0')
    in '6'..'9' -> CullAction.Label(CULL_LABELS[ch - '6'])
    ']' -> CullAction.Next
    '[' -> CullAction.Previous
    'z' -> CullAction.Undo
    else -> null
}

/**
 * A culling pass over [ids] at [index]. Immutable; [undo] holds the previous annotations of the
 * last change so a slipped key can be taken back (one level, which is what a fast cull needs).
 */
internal data class CullState(
    val ids: List<Long>,
    val index: Int = 0,
    val autoAdvance: Boolean = true,
    val undo: UndoEntry? = null,
) {
    internal data class UndoEntry(val id: Long, val index: Int, val before: SidecarAnnotations?)

    val currentId: Long? get() = ids.getOrNull(index)
    val finished: Boolean get() = ids.isNotEmpty() && index >= ids.size

    /**
     * Folds [result] -- computed off the main thread from an EARLIER state -- into this, the
     * latest one. What the user changed meanwhile without a write (the Auto/Stay toggle) is kept;
     * the position and undo come from the write.
     */
    fun afterWrite(result: CullState): CullState = result.copy(autoAdvance = autoAdvance)
}

private fun CullState.move(delta: Int): CullState =
    copy(index = (index + delta).coerceIn(0, ids.size.coerceAtLeast(1) - 1))

/**
 * Applies [action] to the current photo through [store]. A rating, flag or label is a change and
 * auto-advances when asked; moving and undo never write. A failed write is not an error here: the
 * model's value is still what the store returned from `get` for in-memory stores.
 */
internal fun CullState.apply(action: CullAction, store: AnnotationStore): CullState {
    val id = currentId ?: return this
    return when (action) {
        CullAction.Next -> move(1)
        CullAction.Previous -> move(-1)
        CullAction.Undo -> {
            val u = undo ?: return this
            store.put(u.id, u.before ?: SidecarAnnotations(rating = 0, label = "", flag = SidecarFlag.NONE))
            copy(index = u.index, undo = null)
        }
        else -> {
            val before = store.get(id)
            val base = before ?: SidecarAnnotations()
            val after = when (action) {
                is CullAction.Flag -> base.copy(flag = action.flag)
                is CullAction.Rate -> base.copy(rating = action.stars.coerceIn(0, 5))
                is CullAction.Label -> base.copy(label = action.name)
                else -> base
            }
            store.put(id, after)
            val moved = if (autoAdvance && index < ids.size - 1) index + 1 else index
            copy(index = moved, undo = CullState.UndoEntry(id, index, before))
        }
    }
}

/** How a filter shows the cull. */
internal enum class CullFilter { ALL, PICKS, UNDECIDED, HIDE_REJECTS }

internal fun cullVisible(a: SidecarAnnotations?, filter: CullFilter): Boolean = when (filter) {
    CullFilter.ALL -> true
    CullFilter.PICKS -> a?.flag == SidecarFlag.PICK
    CullFilter.UNDECIDED -> a?.flag == null || a.flag == SidecarFlag.NONE
    CullFilter.HIDE_REJECTS -> a?.flag != SidecarFlag.REJECT
}

internal data class CullSummary(val picks: Int, val rejects: Int, val undecided: Int)

internal fun cullSummary(ids: List<Long>, store: AnnotationStore): CullSummary {
    var p = 0
    var r = 0
    var u = 0
    for (id in ids) when (store.get(id)?.flag) {
        SidecarFlag.PICK -> p++
        SidecarFlag.REJECT -> r++
        else -> u++
    }
    return CullSummary(p, r, u)
}

internal object CullingCopy {
    const val HINT = "P picks, X rejects, 0 to 5 rate, 6 to 9 label."
    const val NEEDS_FOLDER = "Choose the photos' folder to save ratings beside them."
    const val CHOOSE_FOLDER = "Choose folder"
    const val READING = "Reading saved ratings."
    const val DONE = "That was the last photo."
    const val NOT_SAVED = "Ratings are kept for now but could not be saved beside the photos."
    const val MEMORY_ONLY = "Ratings stay in memory and are gone when the app closes."
    const val CLOSE = "Leave culling."
    const val PREVIOUS = "Previous photo."
    const val NEXT = "Next photo."
    const val PICK = "Pick this photo."
    const val REJECT = "Reject this photo."
    const val UNFLAG = "Clear the flag."
    const val UNDO = "Undo the last change."
    const val RATE = "Rate"
    const val LABEL = "Label"
    const val AUTO_ADVANCE = "Move on after each change."
    const val CULL_SELECTION = "Rate and flag these photos one by one."
    const val CULL_VIEW = "Rate and flag every photo in this view."
}
