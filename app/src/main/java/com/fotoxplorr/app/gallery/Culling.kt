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
    private val resolve: (Long) -> SidecarAsset?,
) : AnnotationStore {
    override fun get(id: Long): SidecarAnnotations? {
        val asset = resolve(id) ?: return null
        return (service.read(asset) as? SidecarReadResult.Found)?.annotations
    }

    override fun put(id: Long, annotations: SidecarAnnotations): Boolean {
        val asset = resolve(id) ?: return false
        return service.write(asset, annotations, explicitUserAction = true) is SidecarWriteResult.Written
    }
}

/**
 * In-memory first, durable second: keeps every value in memory so the cull keeps working even when
 * the sidecar refuses, and passes the write on. [durableCount] is what stuck, for an honest summary.
 */
class WriteThroughAnnotationStore(private val durable: AnnotationStore) : AnnotationStore {
    private val memory = InMemoryAnnotationStore()
    private val kept = HashSet<Long>()
    val durableCount: Int get() = kept.size
    override fun get(id: Long): SidecarAnnotations? = memory.get(id) ?: durable.get(id)
    override fun put(id: Long, annotations: SidecarAnnotations): Boolean {
        memory.put(id, annotations)
        val ok = durable.put(id, annotations)
        if (ok) kept.add(id) else kept.remove(id)
        return ok
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
    const val DONE = "That was the last photo."
    const val NOT_SAVED = "Ratings are kept for now but could not be saved beside the photos."
}
