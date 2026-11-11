package com.fotoxplorr.core.index

import kotlinx.coroutines.flow.Flow

/**
 * ADR-011 §2 / MASTER-PLAN.md §2.3: a place files live, enumerated by [SyncEngine]. Phase 1's
 * only implementation is a MediaStore volume (`com.fotoxplorr.app.index.AndroidMediaStoreSource`,
 * Android glue in `:app` -- this interface itself is Android-free per ADR-010 §2); Phase 3 adds
 * SAF trees, USB, and network sources behind the same contract.
 */
interface Source {
    val capabilities: SourceCapabilities

    /**
     * Enumerate this source's current contents. [since] is `null` for a full enumeration (every
     * item this source currently has); a non-null token asks for only what's changed since that
     * token was issued by an earlier [SourceEvent.Completed] -- what "changed since" means is
     * entirely up to the [Source] (MediaStore: `GENERATION_MODIFIED` > the token's recorded
     * generation; SAF: a resumable walk position). [SyncEngine] only ever treats a pass as safe
     * to mark-and-sweep when [SourceEvent.Completed.fullyEnumerated] says so (TRAPS #8) -- it
     * never infers completeness from whether [since] was null.
     */
    fun enumerate(since: SyncToken?): Flow<SourceEvent>

    /** An opaque, source-kind-specific way to reach [locator]'s bytes -- a content URI string for
     *  MediaStore, a tree-document URI for SAF, etc. Deliberately not a platform file handle:
     *  this module is KMP-common and must stay Android-free. */
    suspend fun open(locator: String): SourceHandle

    /** `null` when [SourceCapabilities.canThumbnail] is false, or this specific [locator] has no
     *  cheaper-than-[open] thumbnail path. */
    suspend fun thumbnail(locator: String, size: Int): SourceHandle?
}

data class SourceCapabilities(
    val canOpen: Boolean = true,
    val canThumbnail: Boolean = false,
)

data class SourceHandle(val uri: String)

sealed interface SourceEvent {
    data class ItemFound(val item: SourceItem) : SourceEvent
    data class Progress(val scanned: Int) : SourceEvent

    /**
     * [fullyEnumerated]: true only when this pass read the source's *entire current extent* --
     * a full pass by definition, or a delta pass whose own bookkeeping can still account for
     * every item the source currently has (Phase 1's MediaStore source never claims this on a
     * delta; a future source kind might). [SyncEngine.sync] gates mark-and-sweep on this flag
     * alone (TRAPS #8's generalisation of TRAPS #1) -- never on whether [since] was null, so a
     * source that starts claiming delta-completeness later doesn't need any change on the
     * [SyncEngine] side.
     */
    data class Completed(val token: SyncToken?, val fullyEnumerated: Boolean) : SourceEvent

    /** However far enumeration got before failing, nothing found is trusted: [SyncEngine] never
     *  reaches its mark-and-sweep step for a pass that ends in [Failed] (there is no [Completed]
     *  event to gate on), matching [MediaIndexer]'s existing de facto safety net -- a scan that
     *  fails never sweeps because sweeping only ever happens in the `Completed` branch. */
    data class Failed(val error: Throwable) : SourceEvent
}

data class SourceItem(
    val locator: String,
    val displayName: String,
    val mime: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val dateTakenMs: Long,
    val dateModifiedMs: Long,
    val dateAddedMs: Long?,
    val relativePath: String?,
    val bucketId: Long?,
    val bucketName: String?,
    val contentUri: String?,
    val trashed: Boolean,
    /** MediaStore's `GENERATION_MODIFIED` for this row, when the source tracks one (null for a
     *  source kind that doesn't, or on API < 30 -- see `AndroidMediaStoreSource`). Carried onto
     *  `asset.ms_generation_modified` verbatim; [SyncEngine] itself never interprets it. */
    val generationModified: Long?,
)
