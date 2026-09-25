package com.fotoxplorr.core.index

import kotlinx.coroutines.flow.flow

/**
 * A hand-built [Source] test double, the same role [com.fotoxplorr.core.db.migration.
 * FakeLegacySource] plays for `MigrationToV2Test`: drives [SyncEngine] end to end with no
 * Android dependency. Models MediaStore's real generation semantics closely enough to exercise
 * them precisely -- [put] bumps a single volume-wide generation counter and stamps the changed
 * item with it (mirroring `GENERATION_MODIFIED`), [reset] simulates a `MediaStore.getVersion`
 * change (a schema/data reset that invalidates every prior generation), and [failNext] simulates
 * an enumeration that dies partway through (TRAPS #8: [SyncEngine] must never sweep on this).
 */
class FakeSource : Source {
    override val capabilities = SourceCapabilities(canOpen = true, canThumbnail = false)

    private val items = LinkedHashMap<String, SourceItem>()
    private var version: String = "v1"
    private var generation: Long = 0L
    private var pendingFailure: Throwable? = null

    fun put(item: SourceItem) {
        generation += 1
        items[item.locator] = item.copy(generationModified = generation)
    }

    fun remove(locator: String) {
        generation += 1
        items.remove(locator)
    }

    /** Simulates `MediaStore.getVersion` changing -- every prior generation becomes untrustworthy,
     *  forcing [SyncEngine]'s next pass to be a full enumeration regardless of its stored token. */
    fun reset(newVersion: String) {
        version = newVersion
        generation = 0
        items.clear()
    }

    fun failNext(error: Throwable) {
        pendingFailure = error
    }

    override fun enumerate(since: SyncToken?): kotlinx.coroutines.flow.Flow<SourceEvent> = flow {
        val failure = pendingFailure
        if (failure != null) {
            pendingFailure = null
            emit(SourceEvent.Failed(failure))
            return@flow
        }

        val startVersion = version
        val startGeneration = generation
        val full = since == null || since.version != startVersion || since.generation == null
        val toEmit = if (full) {
            items.values
        } else {
            items.values.filter { it.generationModified!! > since!!.generation!! }
        }

        for (item in toEmit) emit(SourceEvent.ItemFound(item))
        emit(SourceEvent.Completed(SyncToken(startVersion, startGeneration), fullyEnumerated = full))
    }

    override suspend fun open(locator: String): SourceHandle =
        SourceHandle(items[locator]?.contentUri ?: locator)

    override suspend fun thumbnail(locator: String, size: Int): SourceHandle? = null
}

fun testItem(
    locator: String,
    displayName: String = "IMG_$locator.jpg",
    sizeBytes: Long = 1024,
    dateModifiedMs: Long = 1_600_000_000_000,
    dateTakenMs: Long = dateModifiedMs,
    trashed: Boolean = false,
) = SourceItem(
    locator = locator,
    displayName = displayName,
    mime = "image/jpeg",
    sizeBytes = sizeBytes,
    width = 100,
    height = 100,
    durationMs = 0,
    dateTakenMs = dateTakenMs,
    dateModifiedMs = dateModifiedMs,
    dateAddedMs = dateModifiedMs,
    relativePath = "DCIM/Camera/",
    bucketId = 100,
    bucketName = "Camera",
    contentUri = "content://media/external/images/media/$locator",
    trashed = trashed,
    generationModified = null,
)
