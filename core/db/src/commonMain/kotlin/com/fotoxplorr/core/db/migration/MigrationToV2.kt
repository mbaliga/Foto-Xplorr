package com.fotoxplorr.core.db.migration

import com.fotoxplorr.core.db.FotozDatabase
import com.fotoxplorr.core.db.FotozVectorsDatabase
import com.fotoxplorr.core.db.assetRevision
import com.fotoxplorr.core.db.baseAssetUser
import com.fotoxplorr.core.db.entity.Availability
import com.fotoxplorr.core.db.entity.AssetEntity
import com.fotoxplorr.core.db.entity.AssetKeywordEntity
import com.fotoxplorr.core.db.entity.AssetKeywordRejectionEntity
import com.fotoxplorr.core.db.entity.AssetUserEntity
import com.fotoxplorr.core.db.entity.CollectionEntity
import com.fotoxplorr.core.db.entity.CollectionMemberEntity
import com.fotoxplorr.core.db.entity.FaceEntity
import com.fotoxplorr.core.db.entity.FolderLockEntity
import com.fotoxplorr.core.db.entity.GeoEntity
import com.fotoxplorr.core.db.entity.IndexFailureEntity
import com.fotoxplorr.core.db.entity.Indexer
import com.fotoxplorr.core.db.entity.KeywordEntity
import com.fotoxplorr.core.db.entity.KeywordOrigin
import com.fotoxplorr.core.db.entity.LegacyIdMapEntity
import com.fotoxplorr.core.db.entity.LegacyOrphanEntity
import com.fotoxplorr.core.db.entity.MigrationProgressEntity
import com.fotoxplorr.core.db.entity.MigrationStep
import com.fotoxplorr.core.db.entity.MigrationStepState
import com.fotoxplorr.core.db.entity.OrphanKind
import com.fotoxplorr.core.db.entity.RecognitionEntity
import com.fotoxplorr.core.db.entity.SourceEntity
import com.fotoxplorr.core.db.entity.SourceKind
import com.fotoxplorr.core.db.entity.TextBlockEntity
import com.fotoxplorr.core.db.entity.TraitEntity
import com.fotoxplorr.core.db.entity.VideoMomentEntity
import com.fotoxplorr.core.db.entity.VideoMomentFeedbackEntity
import com.fotoxplorr.core.db.entity.VideoMomentScanEntity
import com.fotoxplorr.core.db.entity.EmbeddingEntity
import com.fotoxplorr.core.db.entity.encodeRecognitionList
import com.fotoxplorr.core.db.folderKey
import com.fotoxplorr.core.db.newMediaStoreSource
import com.fotoxplorr.core.formats.MediaFormat
import com.fotoxplorr.core.formats.formatId
import com.fotoxplorr.core.model.AssetId
import com.fotoxplorr.core.model.SourceId

/**
 * ADR-011 §5: forward-only, resumable, verified migration off the seven old stores. One call to
 * [run] drives steps `EXPORT` through `CUTOVER` in order, skipping any step already
 * [MigrationStepState.DONE] and resuming a `RUNNING` one (each step's own body is written to be
 * safe to re-run from the start, or -- `ASSETS` only -- to resume from its recorded cursor). Call
 * [runCleanupIfDue] separately, once per app start, for step 10 (`CLEANUP`): that step is gated
 * on "the second successful start after cutover", an app-lifecycle condition this class has no
 * way to observe on its own.
 *
 * The UI keeps working against the old stores until [MigrationStep.CUTOVER] flips
 * [MigrationEnvironment.markCutoverComplete] -- nothing here writes to `fotoz.db` and expects it
 * to be read before that point.
 */
class MigrationToV2(
    private val db: FotozDatabase,
    private val vectorsDb: FotozVectorsDatabase,
    private val legacy: LegacySource,
    private val environment: MigrationEnvironment,
    private val projectionParity: ProjectionParityCheck,
    private val assetBatchSize: Int = 2000,
) {

    sealed interface Result {
        data object Completed : Result
        data object AlreadyCutOver : Result
        data class VerifyFailed(val detail: String) : Result
    }

    suspend fun run(): Result {
        if (environment.isCutoverComplete()) return Result.AlreadyCutOver

        for (step in MigrationStep.ORDER) {
            if (step == MigrationStep.CLEANUP) break // runCleanupIfDue's job, not run()'s.
            val existing = db.migrationProgressDao().get(step)
            if (existing?.state == MigrationStepState.DONE) continue

            markRunning(step)
            val detail = try {
                when (step) {
                    MigrationStep.EXPORT -> runExport()
                    MigrationStep.SOURCES -> runSources()
                    MigrationStep.ASSETS -> runAssets()
                    MigrationStep.ID_MAP -> null // populated as part of ASSETS; see runAssets().
                    MigrationStep.USER_DATA -> runUserData()
                    MigrationStep.LOCKS -> runLocks()
                    MigrationStep.DERIVED -> runDerived()
                    MigrationStep.VERIFY -> {
                        // ADR-011 §5's "catch-up pass": re-run ASSETS (picks up a new photo taken
                        // during migration, via its own saved cursor -- see runAssets), then
                        // USER_DATA and DERIVED (both reconciling -- add AND remove -- for the
                        // fields users are realistically likely to touch mid-migration: favorite,
                        // sensitive, archived, tags, locks; see runUserData's and runLocks' own
                        // docs for exactly which fields stay additive-only and why). No extra
                        // last_write_ms tracking needed in the old writers -- see
                        // MASTER-PROGRESS.md's Decisions for the full reasoning, including the
                        // known residual gap (a file edited or deleted mid-migration, as opposed
                        // to a user-data toggle, isn't caught up -- WP1.4's sync engine corrects
                        // that on its first post-cutover pass).
                        runAssets()
                        runUserData()
                        runLocks()
                        runDerived()
                        val verifyDetail = runVerify()
                        if (verifyDetail != null) {
                            markFailed(step, verifyDetail)
                            return Result.VerifyFailed(verifyDetail)
                        }
                        "clean"
                    }
                    MigrationStep.CUTOVER -> runCutover()
                    MigrationStep.CLEANUP -> error("unreachable, see the loop break above")
                    else -> error("unknown migration step: $step")
                }
            } catch (t: Throwable) {
                markFailed(step, t.message ?: t::class.simpleName ?: "unknown error")
                throw t
            }
            markDone(step, detail)
        }
        return Result.Completed
    }

    suspend fun runCleanupIfDue(): Boolean {
        if (!environment.isCutoverComplete()) return false
        if (db.migrationProgressDao().get(MigrationStep.CLEANUP)?.state == MigrationStepState.DONE) return false
        val starts = environment.recordSuccessfulStartSinceCutover()
        if (starts < 2) return false

        markRunning(MigrationStep.CLEANUP)
        environment.deleteOldStoresAtLiveLocation()
        db.migrationProgressDao().clearIdMap()
        markDone(MigrationStep.CLEANUP, "cleaned up on start #$starts since cutover")
        return true
    }

    // ---- EXPORT --------------------------------------------------------------------------

    private suspend fun runExport(): String {
        val library = legacy.libraryData()
        val geo = legacy.geoRows().filter { it.manual }
        val json = buildString {
            append('{')
            append("\"schema\":1,")
            append("\"exportedAtMillis\":${legacy.nowMs()},")
            append("\"favoriteIds\":${jsonLongArray(legacy.favoriteIds())},")
            append("\"sensitiveIds\":${jsonLongArray(legacy.sensitiveIds())},")
            append("\"archivedIds\":${jsonLongArray(library.archivedIds)},")
            append("\"everUnarchivedIds\":${jsonLongArray(library.everUnarchivedIds)},")
            append("\"rejectedArchiveSuggestionIds\":${jsonLongArray(library.rejectedArchiveSuggestionIds)},")
            append("\"machineCaptionIds\":${jsonLongArray(library.machineCaptionIds)},")
            append("\"suppressedMachineCaptionIds\":${jsonLongArray(library.suppressedMachineCaptionIds)},")
            append("\"captions\":{${library.captions.entries.joinToString(",") { "\"${it.key}\":${jsonString(it.value)}" }}},")
            append("\"tags\":{${library.tagMembers.entries.joinToString(",") { "${jsonString(it.key)}:${jsonLongArray(it.value)}" }}},")
            append("\"autoTags\":{${library.autoTagMembers.entries.joinToString(",") { "${jsonString(it.key)}:${jsonLongArray(it.value)}" }}},")
            append("\"rejectedAutoTags\":{${library.rejectedAutoTagMembers.entries.joinToString(",") { "${jsonString(it.key)}:${jsonLongArray(it.value)}" }}},")
            append(
                "\"collections\":[${
                    library.collections.joinToString(",") {
                        "{\"id\":${jsonString(it.id)},\"name\":${jsonString(it.name)}," +
                            "\"createdAtMillis\":${it.createdAtMillis},\"mediaIds\":${jsonLongArray(it.mediaIds)}}"
                    }
                }],",
            )
            append(
                "\"manualGeoPins\":[${
                    geo.joinToString(",") {
                        "{\"mediaId\":${it.mediaId},\"latitude\":${it.latitude},\"longitude\":${it.longitude}}"
                    }
                }]",
            )
            append('}')
        }
        environment.writeExportJson(json, legacy.nowMs())
        environment.copyOldStoreFiles()
        return "exported"
    }

    private fun jsonLongArray(values: Collection<Long>) = values.sorted().joinToString(",", "[", "]")
    private fun jsonString(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    // ---- SOURCES ---------------------------------------------------------------------------

    /** The [SourceEntity] every unmapped media id (API 26-28, or an id MediaStore no longer
     *  resolves) falls back to. */
    private suspend fun primarySourceId(): SourceId {
        val volumes = legacy.externalVolumeNames()
        val primaryLocator = volumes.firstOrNull { it == "external_primary" } ?: volumes.firstOrNull() ?: "external"
        return db.sourceDao().findByLocator(SourceKind.MEDIASTORE_VOLUME, primaryLocator)?.sourceId
            ?: SourceId(db.sourceDao().insert(newMediaStoreSource(primaryLocator)))
    }

    private suspend fun runSources(): String {
        val volumes = legacy.externalVolumeNames()
        if (volumes.isEmpty()) {
            primarySourceId() // API 26-28: exactly one fallback source, created lazily below too.
            return "0 volumes (API < 29); single fallback source"
        }
        var created = 0
        for (volume in volumes) {
            if (db.sourceDao().findByLocator(SourceKind.MEDIASTORE_VOLUME, volume) == null) {
                db.sourceDao().insert(newMediaStoreSource(volume))
                created++
            }
        }
        return "$created source(s) created for ${volumes.size} volume(s)"
    }

    // ---- ASSETS + ID_MAP -------------------------------------------------------------------

    private suspend fun runAssets(): String {
        val primary = primarySourceId()
        val sourceByVolume = HashMap<String, SourceId>()
        var cursor = db.migrationProgressDao().get(MigrationStep.ASSETS)?.cursor?.toLongOrNull() ?: 0L
        var total = 0

        while (true) {
            val batch = legacy.mediaRowsAfter(cursor, assetBatchSize)
            if (batch.isEmpty()) break

            for (row in batch) {
                val volume = legacy.volumeForMediaId(row.mediaId)
                val sourceId = when {
                    volume == null -> primary
                    else -> sourceByVolume.getOrPut(volume) {
                        db.sourceDao().findByLocator(SourceKind.MEDIASTORE_VOLUME, volume)?.sourceId
                            ?: SourceId(db.sourceDao().insert(newMediaStoreSource(volume)))
                    }
                }

                val existing = db.assetDao().findByLocator(sourceId, row.mediaId.toString())
                val assetId = if (existing != null) {
                    existing.assetId
                } else {
                    val format = MediaFormat.classify(row.mimeType, row.displayName)
                    val dateModifiedMs = row.dateModifiedSeconds * 1000
                    val entity = AssetEntity(
                        assetId = AssetId(0),
                        sourceId = sourceId,
                        locator = row.mediaId.toString(),
                        contentUri = row.contentUri,
                        displayName = row.displayName,
                        mime = row.mimeType,
                        formatId = format.formatId,
                        sizeBytes = row.sizeBytes,
                        width = row.width,
                        height = row.height,
                        orientation = 0,
                        durationMs = row.durationMillis,
                        dateTakenMs = row.dateTakenMs,
                        dateModifiedMs = dateModifiedMs,
                        dateAddedMs = null,
                        relativePath = row.relativePath,
                        folderKey = folderKey(sourceId, row.relativePath, row.bucketId, row.bucketName),
                        bucketId = row.bucketId,
                        bucketName = row.bucketName,
                        fingerprint = null,
                        contentHash = null,
                        xmpDocumentId = null,
                        availability = if (volume != null) Availability.ONLINE else Availability.OFFLINE,
                        trashed = row.isTrashed,
                        trashedAtMs = null,
                        msGenerationModified = null,
                        revision = assetRevision(dateModifiedMs, row.sizeBytes),
                    )
                    AssetId(db.assetDao().insert(entity))
                }

                db.migrationProgressDao().mapId(LegacyIdMapEntity(mediaId = row.mediaId, assetId = assetId))
                if (db.assetUserDao().get(assetId) == null) {
                    db.assetUserDao().upsert(baseAssetUser(assetId, favorite = row.isFavorite))
                }

                total++
                cursor = row.mediaId
            }

            db.migrationProgressDao().upsert(
                MigrationProgressEntity(
                    step = MigrationStep.ASSETS,
                    state = MigrationStepState.RUNNING,
                    cursor = cursor.toString(),
                    updatedMs = legacy.nowMs(),
                    detail = "$total copied so far",
                ),
            )
        }

        markDone(MigrationStep.ID_MAP, "${db.migrationProgressDao().idMapCount()} mapped (populated during ASSETS)")
        return "$total asset(s) copied"
    }

    // ---- USER_DATA -------------------------------------------------------------------------

    /**
     * ADR-011 §5 step 5, plus its own "catch-up pass" requirement: this step runs at least
     * twice (once in normal step order, once more as part of VERIFY's catch-up -- see
     * `run()`'s own comment), so it must be safe to re-run against a live old store that's kept
     * changing in between. That safety isn't uniform:
     *
     * - **Fully reconciled (added AND removed on every call), because these are the toggles a
     *   person is realistically going to flip while just using the app during a migration
     *   window:** [com.fotoxplorr.core.db.entity.AssetUserEntity.favorite]/`sensitive`/
     *   `archived`, and tags (`asset_keyword`, including origin correction and unlinking a
     *   removed tag membership).
     * - **Additive-only, deliberately:** captions, collections, manual geo pins, rejected
     *   auto-tags. These are lower-frequency edits than a favourite/tag toggle, and reconciling
     *   them fully (a removed caption, a deleted collection, an unpinned location) needs
     *   materially more bookkeeping for a rarer edge case -- a real, scoped limitation, not an
     *   oversight; a value removed from one of these mid-migration survives into `fotoz.db`
     *   until corrected by hand or a later real edit.
     * - **Additive-only, correctly, because the flag itself is monotonic in this app's actual
     *   code (a "has this ever happened" record, never unset):** `ever_unarchived`,
     *   `archive_suggestion_rejected`.
     *
     * Not covered at all by catch-up: a *tag deleted outright* from `LibraryStore` (as opposed
     * to a membership change on a tag that still exists) between passes -- reconciliation here
     * is keyed on `legacy.libraryData().tagMembers`'s current key set, so a tag no longer in
     * that map is never revisited. Same reasoning as the bullet above: a real, narrow, documented
     * gap, not a silent one.
     */
    private suspend fun runUserData(): String {
        val orphans = ArrayList<LegacyOrphanEntity>()
        val now = legacy.nowMs()

        suspend fun assetIdOrOrphan(mediaId: Long, kind: String, detail: String): AssetId? {
            val assetId = db.migrationProgressDao().assetIdFor(mediaId)
            if (assetId == null) orphans += LegacyOrphanEntity(kind = kind, mediaId = mediaId, detail = detail, atMs = now)
            return assetId
        }

        suspend fun reconcileFlag(
            desiredMediaIds: Set<Long>,
            kind: String,
            detail: String,
            currentAssetIds: suspend () -> List<AssetId>,
            setFlag: suspend (List<AssetId>, Boolean) -> Unit,
        ) {
            val desiredAssetIds = HashSet<AssetId>(desiredMediaIds.size)
            for (mediaId in desiredMediaIds) {
                assetIdOrOrphan(mediaId, kind, detail)?.let { desiredAssetIds += it }
            }
            val actualAssetIds = currentAssetIds().toHashSet()
            val toEnable = desiredAssetIds - actualAssetIds
            val toDisable = actualAssetIds - desiredAssetIds
            if (toEnable.isNotEmpty()) setFlag(toEnable.toList(), true)
            if (toDisable.isNotEmpty()) setFlag(toDisable.toList(), false)
        }

        reconcileFlag(legacy.favoriteIds(), OrphanKind.FAVORITE, "FavoriteStore", { db.assetUserDao().getFavoriteIds() }) { ids, v -> db.assetUserDao().setFavorite(ids, v) }
        reconcileFlag(legacy.sensitiveIds(), OrphanKind.SENSITIVE, "SensitiveStore", { db.assetUserDao().getSensitiveIds() }) { ids, v -> db.assetUserDao().setSensitive(ids, v) }

        val library = legacy.libraryData()
        reconcileFlag(library.archivedIds, OrphanKind.ARCHIVED, "LibraryStore archived_ids", { db.assetUserDao().getArchivedIds() }) { ids, v -> db.assetUserDao().setArchived(ids, v) }

        for (id in library.everUnarchivedIds) {
            assetIdOrOrphan(id, OrphanKind.EVER_UNARCHIVED, "LibraryStore ever_unarchived_ids")?.let {
                db.assetUserDao().markEverUnarchived(it)
            }
        }
        for (id in library.rejectedArchiveSuggestionIds) {
            assetIdOrOrphan(id, OrphanKind.REJECTED_ARCHIVE_SUGGESTION, "LibraryStore rejected_archive_suggestion_ids")?.let {
                db.assetUserDao().setArchiveSuggestionRejected(it, true)
            }
        }
        for ((mediaId, caption) in library.captions) {
            val isMachine = mediaId in library.machineCaptionIds
            val suppressed = mediaId in library.suppressedMachineCaptionIds
            assetIdOrOrphan(mediaId, OrphanKind.CAPTION, "LibraryStore caption")?.let {
                db.assetUserDao().setCaption(it, caption, isMachine, suppressed)
            }
        }
        // suppressedMachineCaptionIds entries usually have no caption text (per the survey) --
        // still record the suppression flag for those.
        for (mediaId in library.suppressedMachineCaptionIds - library.captions.keys) {
            assetIdOrOrphan(mediaId, OrphanKind.CAPTION, "LibraryStore suppressed_machine_caption_ids (no caption text)")?.let {
                db.assetUserDao().setCaption(it, null, isMachine = false, machineSuppressed = true)
            }
        }

        // Tags -> root keywords, fully reconciled per tag (link, unlink, and correct origin) on
        // every call -- see this function's own doc for the "not covered" case (a tag deleted
        // outright). Every key in tagMembers becomes a keyword, even with zero members left (see
        // LegacyLibraryData's own doc).
        val keywordIdByName = HashMap<String, Long>()
        suspend fun rootKeywordId(name: String): Long = keywordIdByName.getOrPut(name) {
            db.keywordDao().findRoot(name)?.keywordId ?: db.keywordDao().insert(KeywordEntity(keywordId = 0, parentId = null, name = name))
        }
        for ((tag, members) in library.tagMembers) {
            val keywordId = rootKeywordId(tag)
            val autoMembers = library.autoTagMembers[tag].orEmpty()
            // Survey trap: auto-tag ids not present in the tag's own member set are linked too
            // (AUTO) rather than silently dropped, matching the un-reconciled behaviour this
            // migration had before catch-up existed.
            val desiredOriginByMediaId = HashMap<Long, String>()
            for (mediaId in members) desiredOriginByMediaId[mediaId] = if (mediaId in autoMembers) KeywordOrigin.AUTO else KeywordOrigin.USER
            for (mediaId in autoMembers - members) desiredOriginByMediaId[mediaId] = KeywordOrigin.AUTO

            val desiredByAssetId = HashMap<AssetId, String>(desiredOriginByMediaId.size)
            for ((mediaId, origin) in desiredOriginByMediaId) {
                assetIdOrOrphan(mediaId, OrphanKind.TAG, "LibraryStore tag \"$tag\"")?.let { desiredByAssetId[it] = origin }
            }

            val actual = db.keywordDao().linksForKeyword(keywordId).associateBy { it.assetId }
            for ((assetId, origin) in desiredByAssetId) {
                if (actual[assetId]?.origin != origin) db.keywordDao().link(AssetKeywordEntity(assetId = assetId, keywordId = keywordId, origin = origin))
            }
            for (assetId in actual.keys - desiredByAssetId.keys) {
                db.keywordDao().unlink(assetId, keywordId)
            }
        }
        for ((tag, members) in library.rejectedAutoTagMembers) {
            val keywordId = rootKeywordId(tag)
            for (mediaId in members) {
                assetIdOrOrphan(mediaId, OrphanKind.REJECTED_AUTO_TAG, "LibraryStore rejected_auto_tag_media \"$tag\"")?.let {
                    db.keywordDao().reject(AssetKeywordRejectionEntity(assetId = it, keywordId = keywordId))
                }
            }
        }

        for (collection in library.collections) {
            db.collectionDao().upsert(
                CollectionEntity(collectionId = collection.id, name = collection.name, createdMs = collection.createdAtMillis, sortOrder = 0),
            )
            for (mediaId in collection.mediaIds) {
                assetIdOrOrphan(mediaId, OrphanKind.COLLECTION_MEMBER, "collection \"${collection.name}\" (${collection.id})")?.let {
                    db.collectionDao().addMember(CollectionMemberEntity(collectionId = collection.id, assetId = it, position = 0))
                }
            }
        }

        for (row in legacy.geoRows().filter { it.manual }) {
            val assetId = assetIdOrOrphan(row.mediaId, OrphanKind.GEO_MANUAL_PIN, "manual geo pin") ?: continue
            db.geoDao().upsert(
                GeoEntity(
                    assetId = assetId,
                    revision = null,
                    hasLocation = row.hasLocation,
                    latitude = row.latitude,
                    longitude = row.longitude,
                    altitude = row.altitude,
                    direction = row.direction,
                    manual = true,
                    checkedWithOriginal = row.checkedWithOriginal,
                ),
            )
        }

        if (orphans.isNotEmpty()) db.migrationProgressDao().addOrphans(orphans)
        return "${orphans.size} orphan(s) recorded"
    }

    // ---- LOCKS -----------------------------------------------------------------------------

    /** Fully reconciled (added AND removed) on every call -- a folder unlocked mid-migration
     *  must not leave a stale `folder_lock` row protecting it in `fotoz.db`. Only ever touches
     *  rows this migration itself created ([FolderLockEntity.legacyKey] `true`); a real,
     *  source-scoped lock can't exist yet at this point in the migration (cutover hasn't
     *  happened), but this scoping is cheap insurance against ever removing one anyway. */
    private suspend fun runLocks(): String {
        val locks = legacy.folderLocks()
        val desiredKeys = locks.mapTo(HashSet()) { it.folderKey }
        for (lock in locks) {
            db.folderLockDao().upsert(
                FolderLockEntity(folderKey = lock.folderKey, salt = lock.salt, hash = lock.hash, iterations = lock.iterations, legacyKey = true),
            )
        }
        var removed = 0
        for (existing in db.folderLockDao().getAll()) {
            if (existing.legacyKey && existing.folderKey !in desiredKeys) {
                db.folderLockDao().remove(existing.folderKey)
                removed++
            }
        }
        return "${locks.size} lock(s), $removed removed"
    }

    // ---- DERIVED ---------------------------------------------------------------------------

    private suspend fun runDerived(): String {
        val now = legacy.nowMs()
        val orphans = ArrayList<LegacyOrphanEntity>()
        val droppedByKind = HashMap<String, Int>()
        fun drop(kind: String) {
            droppedByKind[kind] = (droppedByKind[kind] ?: 0) + 1
        }

        suspend fun assetIdOf(mediaId: Long): AssetId? = db.migrationProgressDao().assetIdFor(mediaId)
        suspend fun revisionOf(assetId: AssetId): Long = db.assetDao().get(assetId)?.revision ?: 0

        for (row in legacy.geoRows().filterNot { it.manual }) {
            val assetId = assetIdOf(row.mediaId) ?: run { drop(DroppedKind.GEO); null } ?: continue
            db.geoDao().upsert(
                GeoEntity(
                    assetId = assetId,
                    revision = revisionOf(assetId),
                    hasLocation = row.hasLocation,
                    latitude = row.latitude,
                    longitude = row.longitude,
                    altitude = row.altitude,
                    direction = row.direction,
                    manual = false,
                    checkedWithOriginal = row.checkedWithOriginal,
                ),
            )
        }

        for (row in legacy.recognitionRows()) {
            val assetId = assetIdOf(row.mediaId) ?: run { drop(DroppedKind.RECOGNITION); null } ?: continue
            db.recognitionDao().upsertRecognition(
                RecognitionEntity(
                    assetId = assetId,
                    revision = revisionOf(assetId),
                    faceCount = row.faceCount,
                    petVerdict = row.petVerdict,
                    identityVerdict = row.identityVerdict,
                    labels = encodeRecognitionList(row.labels),
                    categories = encodeRecognitionList(row.categories),
                    caption = row.caption,
                    hashtags = encodeRecognitionList(row.hashtags),
                ),
            )
        }
        val faces = legacy.faceRows().mapNotNull { row ->
            val assetId = assetIdOf(row.mediaId) ?: run { drop(DroppedKind.FACE); null } ?: return@mapNotNull null
            FaceEntity(assetId = assetId, faceIndex = row.faceIndex, relativeArea = row.relativeArea, vector = row.vector)
        }
        if (faces.isNotEmpty()) db.recognitionDao().insertFaces(faces)
        val textBlocks = legacy.textBlockRows().mapNotNull { row ->
            val assetId = assetIdOf(row.mediaId) ?: run { drop(DroppedKind.TEXT_BLOCK); null } ?: return@mapNotNull null
            TextBlockEntity(assetId = assetId, blockIndex = row.blockIndex, text = row.text, left = row.left, top = row.top, right = row.right, bottom = row.bottom)
        }
        if (textBlocks.isNotEmpty()) db.recognitionDao().insertTextBlocks(textBlocks)
        for (failure in legacy.recognitionFailures()) {
            val assetId = assetIdOf(failure.mediaId) ?: run { drop(DroppedKind.RECOGNITION_FAILURE); null } ?: continue
            db.indexFailureDao().upsert(
                IndexFailureEntity(assetId = assetId, indexer = Indexer.RECOGNITION, revision = failure.revision, attempts = failure.attempts, lastAttemptMs = failure.lastAttemptMs),
            )
        }

        for (row in legacy.traitRows()) {
            val assetId = assetIdOf(row.mediaId) ?: run { drop(DroppedKind.TRAIT); null } ?: continue
            db.traitDao().upsert(
                TraitEntity(
                    assetId = assetId, revision = revisionOf(assetId), animated = row.animated,
                    hdrGainmap = null, motionPhoto = null, panorama = null, depth = null, stereo = null, burstId = null,
                ),
            )
        }

        for (row in legacy.videoMomentRows()) {
            val assetId = assetIdOf(row.mediaId) ?: run { drop(DroppedKind.VIDEO_MOMENT); null } ?: continue
            db.videoMomentDao().insert(VideoMomentEntity(assetId = assetId, positionMs = row.positionMs, source = row.source, confidence = row.confidence, label = row.label))
        }
        for (mediaId in legacy.videoScannedIds()) {
            val assetId = assetIdOf(mediaId) ?: run { drop(DroppedKind.VIDEO_MOMENT_SCAN); null } ?: continue
            db.videoMomentDao().markScanned(VideoMomentScanEntity(assetId = assetId, revision = revisionOf(assetId)))
        }
        for (row in legacy.videoMomentFeedbackRows()) {
            // User-authored (MANUAL moments + feedback verdicts): unmapped goes to orphans, per
            // the survey, not silently dropped like the rest of DERIVED.
            val assetId = assetIdOf(row.mediaId)
            if (assetId == null) {
                orphans += LegacyOrphanEntity(kind = OrphanKind.VIDEO_MOMENT_FEEDBACK, mediaId = row.mediaId, detail = "verdict=${row.verdict}", atMs = now)
                continue
            }
            db.videoMomentDao().setFeedback(VideoMomentFeedbackEntity(assetId = assetId, positionMs = row.positionMs, verdict = row.verdict))
        }

        for (row in legacy.embeddingRows()) {
            val assetId = assetIdOf(row.mediaId) ?: run { drop(DroppedKind.EMBEDDING); null } ?: continue
            vectorsDb.embeddingDao().upsert(
                EmbeddingEntity(
                    assetId = assetId, modelSha = row.modelSha, revision = revisionOf(assetId),
                    vector = row.vector, signature = row.signature, x = row.x, y = row.y,
                ),
            )
        }
        for (failure in legacy.embeddingFailures()) {
            val assetId = assetIdOf(failure.mediaId) ?: run { drop(DroppedKind.EMBEDDING_FAILURE); null } ?: continue
            db.indexFailureDao().upsert(
                IndexFailureEntity(
                    assetId = assetId, indexer = Indexer.embedding(failure.modelSha ?: "unknown"),
                    revision = failure.revision, attempts = failure.attempts, lastAttemptMs = failure.lastAttemptMs,
                ),
            )
        }

        if (orphans.isNotEmpty()) db.migrationProgressDao().addOrphans(orphans)
        lastDerivedDroppedByKind = droppedByKind
        val droppedTotal = droppedByKind.values.sum()
        return "dropped=$droppedTotal (unmapped, rebuildable: $droppedByKind), orphans=${orphans.size} (user-authored, kept)"
    }

    /** Set at the end of every [runDerived] call, read by [runVerify]'s count check --
     *  [runDerived] always runs immediately before [runVerify] within the same [run] call (first
     *  pass via the step dispatch, or the catch-up call), so this is never stale when read. */
    private var lastDerivedDroppedByKind: Map<String, Int> = emptyMap()

    // ---- VERIFY ----------------------------------------------------------------------------

    /** Returns null when clean, else a detail string describing the failure. */
    private suspend fun runVerify(): String? {
        val integrity = db.rawCheckDao().integrityCheck()
        if (integrity.size != 1 || integrity[0].result != "ok") {
            return "integrity_check failed: ${integrity.joinToString { it.result }}"
        }
        val fkViolations = db.rawCheckDao().foreignKeyCheck()
        if (fkViolations.isNotEmpty()) {
            return "foreign_key_check failed: ${fkViolations.size} violation(s), e.g. ${fkViolations.first()}"
        }

        // ADR-011 §5 step 8's "counts per store, old vs new". A derived store's new count can
        // legitimately be less than its old count (unmapped rows are dropped, tracked in
        // lastDerivedDroppedByKind -- see runDerived's own doc) or than old count minus orphans
        // (tracked in legacy_orphans for the few DERIVED kinds that keep unmapped rows instead of
        // dropping them). It must never *exceed* old count -- that would mean duplication, a real
        // bug this check exists to catch. counts() computes the check for the whole module below.
        val countMismatch = countMismatches()
        if (countMismatch != null) return countMismatch

        val oldFp = projectionParity.oldFingerprints()
        val newFp = projectionParity.newFingerprints()
        val mismatches = (oldFp.keys + newFp.keys).filter { key -> oldFp[key] != newFp[key] }
        if (mismatches.isNotEmpty()) {
            return "projection parity mismatch on: ${mismatches.joinToString()}"
        }

        return null
    }

    /** Returns null when every store's new count is explained by its old count minus tracked
     *  drops/orphans, else a detail string naming the first store whose new count exceeds what
     *  old-minus-dropped-minus-orphans allows (a real bug, not an expected gap). */
    private suspend fun countMismatches(): String? {
        val orphanCounts = db.migrationProgressDao().orphanCountsByKind().associate { it.kind to it.count }

        suspend fun check(store: String, oldCount: Int, newCount: Int, droppedKind: String? = null, orphanKind: String? = null): String? {
            val dropped = droppedKind?.let { lastDerivedDroppedByKind[it] ?: 0 } ?: 0
            val orphaned = orphanKind?.let { orphanCounts[it] ?: 0 } ?: 0
            val expectedMax = oldCount - dropped - orphaned
            return if (newCount > expectedMax) {
                "$store count mismatch: old=$oldCount new=$newCount dropped=$dropped orphaned=$orphaned (expected new <= $expectedMax)"
            } else {
                null
            }
        }

        val assetTotal = db.migrationProgressDao().idMapCount() // every mapped media id got exactly one asset row.
        check("asset", assetTotal, db.assetDao().count())?.let { return it }
        check("geo", legacy.geoRows().size, db.geoDao().count(), droppedKind = DroppedKind.GEO, orphanKind = OrphanKind.GEO_MANUAL_PIN)?.let { return it }
        check("recognition", legacy.recognitionRows().size, db.recognitionDao().countRecognition(), droppedKind = DroppedKind.RECOGNITION)?.let { return it }
        check("face", legacy.faceRows().size, db.recognitionDao().countFaces(), droppedKind = DroppedKind.FACE)?.let { return it }
        check("trait", legacy.traitRows().size, db.traitDao().count(), droppedKind = DroppedKind.TRAIT)?.let { return it }
        check("video_moment", legacy.videoMomentRows().size, db.videoMomentDao().countMoments(), droppedKind = DroppedKind.VIDEO_MOMENT)?.let { return it }
        check("embedding", legacy.embeddingRows().size, vectorsDb.embeddingDao().count(), droppedKind = DroppedKind.EMBEDDING)?.let { return it }

        return null
    }

    // ---- CUTOVER ---------------------------------------------------------------------------

    private suspend fun runCutover(): String {
        environment.markCutoverComplete()
        return "cut over at ${legacy.nowMs()}"
    }

    // ---- progress bookkeeping --------------------------------------------------------------

    private suspend fun markRunning(step: String) {
        val existing = db.migrationProgressDao().get(step)
        db.migrationProgressDao().upsert(
            MigrationProgressEntity(step = step, state = MigrationStepState.RUNNING, cursor = existing?.cursor, updatedMs = legacy.nowMs(), detail = null),
        )
    }

    private suspend fun markDone(step: String, detail: String?) {
        db.migrationProgressDao().upsert(
            MigrationProgressEntity(step = step, state = MigrationStepState.DONE, cursor = null, updatedMs = legacy.nowMs(), detail = detail),
        )
    }

    private suspend fun markFailed(step: String, detail: String) {
        val existing = db.migrationProgressDao().get(step)
        db.migrationProgressDao().upsert(
            MigrationProgressEntity(step = step, state = MigrationStepState.FAILED, cursor = existing?.cursor, updatedMs = legacy.nowMs(), detail = detail),
        )
    }
}

/** [MigrationToV2.runDerived]'s per-kind "unmapped, dropped, rebuildable" counters -- see that
 *  function's own doc. Not [OrphanKind]: these rows are never recorded anywhere (they're
 *  rebuildable derived data, not user-authored), only counted. */
private object DroppedKind {
    const val GEO = "GEO"
    const val RECOGNITION = "RECOGNITION"
    const val FACE = "FACE"
    const val TEXT_BLOCK = "TEXT_BLOCK"
    const val RECOGNITION_FAILURE = "RECOGNITION_FAILURE"
    const val TRAIT = "TRAIT"
    const val VIDEO_MOMENT = "VIDEO_MOMENT"
    const val VIDEO_MOMENT_SCAN = "VIDEO_MOMENT_SCAN"
    const val EMBEDDING = "EMBEDDING"
    const val EMBEDDING_FAILURE = "EMBEDDING_FAILURE"
}
