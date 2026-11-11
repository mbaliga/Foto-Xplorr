package com.fotoxplorr.core.index.sources

import com.fotoxplorr.core.model.MediaId

/**
 * The Phase 3 vocabulary of a source. The wire strings are exactly the ones stored in
 * `source.kind` / `source.state` (`:core:db`'s `SourceKind` / `SourceState` objects, ADR-011 §2);
 * this enum extends that list with the two kinds Phase 3 writes, it does not replace it.
 */
enum class SourceKind(val wire: String) {
    MEDIASTORE_VOLUME("MEDIASTORE_VOLUME"),
    SAF_TREE("SAF_TREE"),
    USB_SAF("USB_SAF"),
    ;

    companion object {
        fun fromWire(wire: String): SourceKind? = entries.firstOrNull { it.wire == wire }
    }
}

enum class SourceState(val wire: String) {
    ONLINE("ONLINE"),
    OFFLINE("OFFLINE"),
    REVOKED("REVOKED"),
    ERROR("ERROR"),
    ;

    companion object {
        fun fromWire(wire: String): SourceState? = entries.firstOrNull { it.wire == wire }
    }
}

/** A source row as the app sees it (a `fotoz-sources.db` `source` row). [row] is its stable number. */
data class SourceInfo(
    val row: Long,
    val kind: SourceKind,
    val rootLocator: String,
    val volumeUuid: String?,
    val displayName: String,
    val state: SourceState,
)

/** One media file of a source, the pure shape of a `source_asset` row. */
data class SourceAssetRow(
    val row: Long,
    val sourceRow: Long,
    val documentId: String,
    val displayName: String,
    val mime: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val dateTakenMs: Long,
    val dateModifiedMs: Long,
    /** Directory path inside the tree, no leading or trailing slash; empty for the root. */
    val pathInsideTree: String,
    val contentUri: String?,
    /** False once a completed full walk no longer saw it. The row itself is never deleted. */
    val present: Boolean,
) {
    val mediaId: MediaId get() = SourceAssetIds.fromRow(row)
    val relativePath: String get() = SourceFolderKey.relativePath(sourceRow, pathInsideTree)
}

/** What a state-changing event did to the source. */
sealed interface SourceStateEvent {
    /** The volume or provider is reachable and the grant answers. */
    data object Reachable : SourceStateEvent

    /** A drive was unplugged or its provider is not answering: not a revoke, not an error. */
    data object Unreachable : SourceStateEvent

    /** The platform no longer lists the persisted tree grant. */
    data object GrantRevoked : SourceStateEvent

    /** The person granted the tree again. */
    data object GrantRestored : SourceStateEvent

    /** A walk failed for a reason other than the above. */
    data object WalkFailed : SourceStateEvent
}

/** The outcome of a walk, which is all the sweep decision looks at (TRAPS #8). */
enum class WalkOutcome { COMPLETED_FULL, COMPLETED_PARTIAL, FAILED, CANCELLED }

/**
 * ONLINE / OFFLINE / REVOKED / ERROR transitions. Deliberately returns only the next state:
 * no transition ever carries a delete, so nothing can be deleted by going OFFLINE, REVOKED or
 * ERROR (ADR-020, PHASE-3-DESIGN 1.x). Rows leave the store only through an explicit person
 * action (remove source), which is not a state transition.
 */
object SourceStateMachine {
    fun next(current: SourceState, event: SourceStateEvent): SourceState = when (event) {
        SourceStateEvent.GrantRevoked -> SourceState.REVOKED
        // Only a fresh grant lifts REVOKED; mere reachability does not.
        SourceStateEvent.GrantRestored -> SourceState.ONLINE
        SourceStateEvent.Reachable -> if (current == SourceState.REVOKED) SourceState.REVOKED else SourceState.ONLINE
        SourceStateEvent.Unreachable -> if (current == SourceState.REVOKED) SourceState.REVOKED else SourceState.OFFLINE
        SourceStateEvent.WalkFailed -> if (current == SourceState.REVOKED) SourceState.REVOKED else SourceState.ERROR
    }

    /**
     * Whether a mark-and-sweep (marking unseen assets as no longer present) may run. Only a
     * completed full walk of a source that is ONLINE; a partial, failed or cancelled walk, or a
     * source in any other state, never sweeps (TRAPS #8).
     */
    fun maySweep(state: SourceState, outcome: WalkOutcome): Boolean =
        state == SourceState.ONLINE && outcome == WalkOutcome.COMPLETED_FULL
}
