package com.fotoxplorr.app.spatial.offline

import kotlinx.coroutines.flow.Flow

/** Why the engine reported a problem with a request, as far as it can tell. */
enum class DownloadErrorKind {
    /** One tile or file does not exist at the provider. Expected at the edge of the data; harmless. */
    NOT_FOUND,

    /** The provider answered with an error. */
    SERVER,

    /** The request never got an answer: no network, a timeout, a dropped connection. */
    CONNECTION,

    /** Anything else the engine could not classify. */
    OTHER,
}

/**
 * Where a region's download stands, as plain numbers the engine copies out of its own status.
 *
 * [requiredResources] is only a lower bound until [requiredIsPrecise] is true, so [fraction] stays
 * null until then rather than showing a bar that moves backwards. [error] is the newest problem
 * since the previous emission, or null; it is carried here so the whole feed is one value type.
 * Counts and byte figures are the engine's own, not this package's estimates.
 */
data class RegionProgress(
    val completedResources: Long,
    val requiredResources: Long,
    val requiredIsPrecise: Boolean,
    val completedBytes: Long,
    val completedTiles: Long,
    val complete: Boolean = false,
    val error: DownloadErrorKind? = null,
) {
    /** 0..1 once the total is known, otherwise null. */
    val fraction: Float?
        get() = when {
            complete -> 1f
            !requiredIsPrecise || requiredResources <= 0L -> null
            else -> (completedResources.toFloat() / requiredResources.toFloat()).coerceIn(0f, 1f)
        }

    companion object {
        val EMPTY = RegionProgress(
            completedResources = 0, requiredResources = 0, requiredIsPrecise = false,
            completedBytes = 0, completedTiles = 0,
        )
    }
}

/** A region the engine holds: its engine-assigned [id], what we recorded about it, and its status. */
data class StoredRegion(
    val id: Long,
    val record: RegionRecord,
    val progress: RegionProgress,
    /** True while the engine is fetching for this region right now. */
    val running: Boolean,
)

/** Why an engine call did not do what was asked. */
enum class EngineFailure {
    /** The parallel-request cap is not in force, so the call that would use the network is refused. */
    THROTTLE_NOT_INSTALLED,
    NO_SUCH_REGION,
    ENGINE_ERROR,
}

/** The outcome of an engine call. Not [Result]: a refusal is an expected answer, not an exception. */
sealed interface EngineResult<out T> {
    data class Ok<T>(val value: T) : EngineResult<T>
    data class Failure(val reason: EngineFailure, val detail: String? = null) : EngineResult<Nothing>
}

/**
 * The seam between this package's pure logic and whatever downloads map regions.
 *
 * The real implementation lives in the Connect flavour only and wraps the map library's own
 * offline manager; the Offline flavour has no implementation at all. Nothing here names a map
 * library, a thread or a network type, so the logic above it is tested with a fake.
 *
 * Rules every implementation keeps:
 * - [create] stores a region and fetches NOTHING. [resume] is the only call that may use the
 *   network, and the UI may call it only from an explicit tap on Download or Resume (TRAPS #61,
 *   #71), never from opening a pane, a rail commit, a scrub or a settled signal.
 * - [resume] returns [EngineFailure.THROTTLE_NOT_INSTALLED] and fetches nothing unless
 *   [throttleInstalled] is true. Callers also check it to disable the button, so a build where
 *   the provider's parallel-request cap cannot be applied simply offers no download.
 * - [pause] keeps the region and what was fetched. Only [delete] removes data, and only when the
 *   person asked.
 * - Regions persist across process death in the engine's own store; [list] after a relaunch is how
 *   they come back, paused.
 */
interface OfflineRegionEngine {

    /** True only while the provider's cap on parallel requests is actually applied to downloads. */
    val throttleInstalled: Boolean

    /** Every stored region, in no promised order: sort on the record. */
    suspend fun list(): EngineResult<List<StoredRegion>>

    /** Stores [record] as a new, paused region. Fetches nothing. */
    suspend fun create(record: RegionRecord): EngineResult<StoredRegion>

    /** Starts or continues the download of region [id]. May use the network. */
    suspend fun resume(id: Long): EngineResult<Unit>

    /** Stops the download of region [id], keeping the region and what it has fetched. */
    suspend fun pause(id: Long): EngineResult<Unit>

    /** Removes region [id] and what it fetched. */
    suspend fun delete(id: Long): EngineResult<Unit>

    /**
     * Progress of region [id]: the current figures first, then each change. Collecting does not
     * start or stop a download. The flow ends when the region is deleted, and is empty for an
     * unknown id.
     */
    fun progress(id: Long): Flow<RegionProgress>
}
