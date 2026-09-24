package com.fotoxplorr.core.index

import com.fotoxplorr.core.db.assetRevision
import com.fotoxplorr.core.db.baseAssetUser
import com.fotoxplorr.core.db.dao.AssetDao
import com.fotoxplorr.core.db.dao.AssetUserDao
import com.fotoxplorr.core.db.dao.SourceDao
import com.fotoxplorr.core.db.entity.Availability
import com.fotoxplorr.core.db.entity.AssetEntity
import com.fotoxplorr.core.db.entity.SourceEntity
import com.fotoxplorr.core.db.entity.SourceState
import com.fotoxplorr.core.db.folderKey
import com.fotoxplorr.core.formats.MediaFormat
import com.fotoxplorr.core.formats.formatId
import com.fotoxplorr.core.model.AssetId
import com.fotoxplorr.core.model.SourceId

/**
 * WP1.4's ongoing counterpart to [com.fotoxplorr.core.db.migration.MigrationToV2]: keeps
 * `asset`/`source` current against a live [Source], forever, the way `MigrationToV2` populated
 * them once. Same shape for the same reason -- pure logic over injected DAOs, JVM-testable with
 * zero Android dependency; `:app` supplies the real [Source] (`AndroidMediaStoreSource`).
 *
 * Every migrated source/asset starts with no generation baseline at all (`MigrationToV2.
 * runAssets` always writes `msGenerationModified = null`, `runSources` always writes
 * `syncVersion`/`syncGeneration = null`) -- this engine's [sync] treats that exactly like a
 * source it has genuinely never seen before, i.e. its very first pass after cutover is always a
 * full enumeration. That is deliberate, not a gap: there is no cheaper way to establish a
 * trustworthy generation baseline than reading the volume once.
 */
class SyncEngine(
    private val sourceDao: SourceDao,
    private val assetDao: AssetDao,
    private val assetUserDao: AssetUserDao,
) {
    /**
     * One sync pass over [source], whose already-persisted row is [sourceRow]. [partialAccess]
     * mirrors today's `SweepPolicy`/`hasPartialMediaAccess` (MASTER-PLAN.md §2.3: "Partial media
     * access means no sweep") -- when true, mark-and-sweep never runs this pass, regardless of
     * [SourceEvent.Completed.fullyEnumerated]. This module stays unaware of *why* (Android
     * permission state is `:app`'s concern); it only ever receives the answer.
     */
    suspend fun sync(source: Source, sourceRow: SourceEntity, partialAccess: Boolean, nowMs: Long): SyncResult {
        val since = if (sourceRow.syncVersion == null && sourceRow.syncGeneration == null) {
            null
        } else {
            SyncToken(sourceRow.syncVersion, sourceRow.syncGeneration)
        }

        var seen = 0
        var fullyEnumerated = false
        var newToken: SyncToken? = null
        var failure: Throwable? = null
        val foundLocators = HashSet<String>()

        source.enumerate(since).collect { event ->
            when (event) {
                is SourceEvent.ItemFound -> {
                    foundLocators += event.item.locator
                    upsertAsset(sourceRow.sourceId, event.item)
                    seen++
                }
                is SourceEvent.Progress -> Unit
                is SourceEvent.Completed -> {
                    fullyEnumerated = event.fullyEnumerated
                    newToken = event.token
                }
                is SourceEvent.Failed -> failure = event.error
            }
        }

        val failed = failure
        if (failed != null) {
            return SyncResult.Failed(seen, failed)
        }

        newToken?.let { token ->
            sourceDao.setSyncState(
                sourceId = sourceRow.sourceId,
                syncVersion = token.version,
                syncGeneration = token.generation,
                lastFullScanMs = if (fullyEnumerated) nowMs else sourceRow.lastFullScanMs,
            )
        }

        // TRAPS #1/#8: mark-and-sweep only over a completely enumerated, fully-accessible pass.
        // A delta pass, a failed pass (never reaches here -- see the early return above), or a
        // partial-access pass leaves every row exactly where it was; this `if` is the *entire*
        // enforcement mechanism, the same way MediaIndexer's delta branch is structurally `Unit`
        // -- there is no flag to accidentally flip, sweep() is simply never called otherwise.
        if (fullyEnumerated && !partialAccess) {
            sweep(sourceRow.sourceId, foundLocators)
        }

        return SyncResult.Completed(seen, fullyEnumerated)
    }

    /**
     * The whole-source case (ADR-011 §6 / TRAPS #8's "an absent source means OFFLINE, never
     * deleted"): a source no longer reachable at all (an SD card unplugged) needs no
     * enumeration -- every one of its rows, and the source itself, flip state in two statements.
     * The caller (`:app`) invokes this only for a volume `MediaStore.getExternalVolumeNames()`
     * no longer lists; it is never called just because a pass hasn't run recently.
     */
    suspend fun markSourceOffline(sourceId: SourceId) {
        sourceDao.setState(sourceId, SourceState.OFFLINE)
        assetDao.setAvailabilityForSource(sourceId, Availability.OFFLINE)
    }

    /**
     * The reverse: a previously-`OFFLINE` source is reachable again. Flips the source's own
     * state AND clears its generation baseline -- a baseline recorded before the source went
     * away isn't trusted across the gap (MediaStore preserving generation continuity across a
     * real unplug/replug isn't something to bet correctness on), so this forces the caller's
     * next [sync] to do a full enumeration, which is what safely re-validates every previously-
     * `OFFLINE` asset (found again -> `ONLINE`; genuinely gone -> `MISSING_CONFIRMED` once that
     * full pass's own sweep runs). Assets are never optimistically flipped back to `ONLINE` here.
     */
    suspend fun markSourceOnline(sourceId: SourceId) {
        sourceDao.setState(sourceId, SourceState.ONLINE)
        sourceDao.setSyncState(sourceId, syncVersion = null, syncGeneration = null, lastFullScanMs = null)
    }

    private suspend fun upsertAsset(sourceId: SourceId, item: SourceItem) {
        val existing = assetDao.findByLocator(sourceId, item.locator)
        val format = MediaFormat.classify(item.mime, item.displayName)
        val key = folderKey(sourceId, item.relativePath, item.bucketId, item.bucketName)
        val revision = assetRevision(item.dateModifiedMs, item.sizeBytes)

        if (existing != null) {
            // .copy() deliberately leaves fingerprint/contentHash/xmpDocumentId/trashedAtMs/
            // orientation untouched: none of them come from a SourceItem, and a sync pass must
            // never silently wipe a value only a later, more specific pass (fingerprinting,
            // metadata read, trash-confirmation) can fill in.
            assetDao.update(
                existing.copy(
                    contentUri = item.contentUri,
                    displayName = item.displayName,
                    mime = item.mime,
                    formatId = format.formatId,
                    sizeBytes = item.sizeBytes,
                    width = item.width,
                    height = item.height,
                    durationMs = item.durationMs,
                    dateTakenMs = item.dateTakenMs,
                    dateModifiedMs = item.dateModifiedMs,
                    dateAddedMs = item.dateAddedMs,
                    relativePath = item.relativePath,
                    folderKey = key,
                    bucketId = item.bucketId,
                    bucketName = item.bucketName,
                    availability = Availability.ONLINE,
                    trashed = item.trashed,
                    msGenerationModified = item.generationModified,
                    revision = revision,
                ),
            )
        } else {
            val assetId = AssetId(
                assetDao.insert(
                    AssetEntity(
                        assetId = AssetId(0),
                        sourceId = sourceId,
                        locator = item.locator,
                        contentUri = item.contentUri,
                        displayName = item.displayName,
                        mime = item.mime,
                        formatId = format.formatId,
                        sizeBytes = item.sizeBytes,
                        width = item.width,
                        height = item.height,
                        orientation = 0,
                        durationMs = item.durationMs,
                        dateTakenMs = item.dateTakenMs,
                        dateModifiedMs = item.dateModifiedMs,
                        dateAddedMs = item.dateAddedMs,
                        relativePath = item.relativePath,
                        folderKey = key,
                        bucketId = item.bucketId,
                        bucketName = item.bucketName,
                        fingerprint = null,
                        contentHash = null,
                        xmpDocumentId = null,
                        availability = Availability.ONLINE,
                        trashed = item.trashed,
                        trashedAtMs = null,
                        msGenerationModified = item.generationModified,
                        revision = revision,
                    ),
                ),
            )
            // A file sync discovers new was never in any legacy store, so there is no favourite
            // flag to carry forward -- unlike MigrationToV2.runAssets, which passes the old
            // store's own IS_FAVORITE straight through.
            assetUserDao.upsert(baseAssetUser(assetId))
        }
    }

    private suspend fun sweep(sourceId: SourceId, seenLocators: Set<String>) {
        // Reconciles both still-ONLINE rows and any this source's own reconnect (markSourceOnline)
        // left OFFLINE from before the gap -- see idsAndLocatorsForSource's own doc.
        val candidates = assetDao.idsAndLocatorsForSource(sourceId, listOf(Availability.ONLINE, Availability.OFFLINE))
        val missing = candidates.filter { it.locator !in seenLocators }
        if (missing.isNotEmpty()) {
            // ADR-011 §6: a fully-enumerated online source that doesn't find a previously-known
            // file marks it MISSING_CONFIRMED, not OFFLINE (OFFLINE is the whole-source case,
            // markSourceOffline above) and never deletes it. Re-matching by fingerprint (a file
            // that moved rather than vanished, per ADR-011 §2's own fingerprint comment "filled
            // lazily (WP1.4)") is a known, accepted gap here: fingerprint computation is a
            // separate WP1.4 increment this one doesn't yet include -- see MASTER-PROGRESS.md.
            assetDao.setAvailability(missing.map { it.assetId }, Availability.MISSING_CONFIRMED)
        }
    }
}

/** MediaStore's own two-part reset/delta signal (ADR-011 §2: `source.sync_version`/
 *  `sync_generation`) -- `version` resets (a schema/data reset makes prior generations
 *  meaningless), `generation` bounds a delta query once `version` still matches. Deliberately not
 *  a fully opaque cross-source-kind token: Phase 1 has exactly one source kind, and the schema
 *  already commits to this exact two-field shape -- a generic opaque string would just mean
 *  parsing it back out of itself on every persist/read, for no source kind that exists yet to
 *  need it. */
data class SyncToken(val version: String?, val generation: Long?)

sealed interface SyncResult {
    data class Completed(val itemsSeen: Int, val fullyEnumerated: Boolean) : SyncResult
    data class Failed(val itemsSeen: Int, val error: Throwable) : SyncResult
}
