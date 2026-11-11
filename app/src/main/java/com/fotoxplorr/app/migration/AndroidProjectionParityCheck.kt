package com.fotoxplorr.app.migration

import android.content.Context
import com.fotoxplorr.app.ScanState
import com.fotoxplorr.app.favorites.FavoriteStore
import com.fotoxplorr.app.formats.AnimationIndex
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.gallery.GalleryUiState
import com.fotoxplorr.app.gallery.projectionFingerprints
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.SqliteMediaRepository
import com.fotoxplorr.app.organize.LibraryState
import com.fotoxplorr.app.organize.LibraryStore
import com.fotoxplorr.app.privacy.PrivateFolderStore
import com.fotoxplorr.app.privacy.SensitiveStore
import com.fotoxplorr.app.recognition.IdentityVerdict
import com.fotoxplorr.app.recognition.PetVerdict
import com.fotoxplorr.app.recognition.RecognitionIndex
import com.fotoxplorr.app.recognition.RecognitionStore
import com.fotoxplorr.core.db.dao.AssetDao
import com.fotoxplorr.core.db.dao.AssetUserDao
import com.fotoxplorr.core.db.dao.FolderLockDao
import com.fotoxplorr.core.db.dao.KeywordDao
import com.fotoxplorr.core.db.dao.RecognitionDao
import com.fotoxplorr.core.db.dao.TraitDao
import com.fotoxplorr.core.db.entity.AssetEntity
import com.fotoxplorr.core.db.migration.ProjectionParityCheck
import com.fotoxplorr.core.model.AssetId
import com.fotoxplorr.core.model.MediaId
import java.time.ZoneOffset
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * ADR-011 §5 step 8's projection parity. Both sides run the same real projection code
 * ([projectionFingerprints], which runs `destinationAssets`, `smartAlbumAssets`, `everydayAssets`
 * and `timelineStops`). The old side runs it over the live in-memory state the UI draws from. The
 * new side runs it over a `GalleryUiState` rebuilt from `fotoz.db`.
 *
 * ## The WP1.3 simplification (read this before trusting a pass)
 * The new side does NOT use real SQL projections over the v2 schema. WP1.5 owns those (ADR-011
 * Consequences: the in-memory mirror survives WP1.3). Instead it loads rows through the DAOs and
 * rebuilds the old in-memory shapes, then runs the unchanged old projection functions over them:
 * - **Assets**: every `asset` row, of any `availability` (ONLINE/OFFLINE/...), becomes a
 *   [MediaAsset] whose `id` is `MediaId(locator)`, the old MediaStore `_ID`, NOT the new
 *   `asset_id`. That's what makes the id-sequence fingerprints comparable at all, since
 *   `asset_id`s are freshly minted. `dateModifiedSeconds = date_modified_ms / 1000` inverts the
 *   migration's exact `* 1000`. A row whose locator isn't a number is left out (none exist while
 *   every source is a MediaStore volume).
 * - **Favourites / sensitive / archived** come from `asset_user`.
 * - **Tags**: `asset_keyword` joined to root `keyword` names, one `KeywordDao.linksFor` call per
 *   asset. `:core:db` has no bulk-links query, so this is O(assets) small queries. Fine for a
 *   one-off background VERIFY, and worth a `getAllLinks()` DAO method when WP1.5 needs it.
 * - **Locked folders**: every `folder_lock.folder_key`. After migration all of them are
 *   `legacy_key = 1` rows under the OLD key format, which is exactly what `folderIdentity()`
 *   produces for the rebuilt assets. A source-scoped key (`<sourceId>:path:...`, only possible
 *   after the Phase 4 re-lock UI) would never match here. This check is only valid at migration
 *   time.
 * - **Recognition**: People/Pets/Identity only need three per-row predicates, the same ones
 *   `RecognitionIndex.from` applies (`face_count > 0`, `PetVerdict.isPet`,
 *   `IdentityVerdict.isIdentity`, with an unknown verdict name counting as NONE). Face clustering
 *   isn't rebuilt because no projection reads it.
 * - **Animated**: `trait.animated = 1`.
 *
 * ## Deliberate differences the old side is adjusted for
 * `MigrationToV2` changes two things on purpose, per ADR-011. Comparing raw old state with the
 * new database would fail VERIFY on every library that has either one and block cutover
 * forever. So [oldFingerprints] applies the same two transformations to the old state, and
 * computes what the migration is SUPPOSED to produce:
 * 1. **MediaStore IS_FAVORITE is folded into favourites** (ADR-011 §5 step 5). The old app never
 *    showed `MediaAsset.isFavorite` in any projection, so the expected favourites are
 *    `FavoriteStore` ∪ every catalogued asset with `isFavorite`.
 * 2. **Auto-tag members missing from their tag's own member set are linked anyway**
 *    (`MigrationToV2.runUserData`, "survey trap"). The old `LibraryState.tagsByMediaId` doesn't
 *    count them, so the expected tags are `tagsByMediaId` ∪ `autoTagsByMediaId`. This affects
 *    UNTAGGED only.
 * Nothing else is adjusted. Every other difference is a real mismatch.
 *
 * ## View settings
 * Both sides use default [GalleryPreferencesState] (NEWEST, videos shown, sensitive not hidden),
 * as the characterisation goldens do, rather than the user's live view settings. The check is
 * about library DATA, and the defaults hide the least. The other sort orders are covered by the
 * `sort:*` keys. The `timeline:stops` key uses UTC and `Locale.UK` so it can't change with the
 * device's settings between the two calls.
 *
 * ## Two privacy views
 * The projections run twice. The first run has nothing unlocked, which gives the exact golden
 * key set. The second has every locked folder unlocked, with its keys prefixed [UNLOCKED_PREFIX].
 * The first catches a lock that stopped matching, the second catches lost data (say, a
 * favourite) inside a locked folder. The session's real unlocked set can't be seen from here,
 * since it lives in each `PrivateFolderStore` instance's memory. It isn't library data anyway.
 *
 * ## What this does NOT verify
 * Collections, captions, geo (including manual pins), keyword rejections, archive-suggestion
 * memory, video moments and feedback, lock salts and hashes, and embeddings. No destination or
 * smart album reads them. ADR-011 §5 step 8 also asks for "counts per store, old vs new", and
 * `MigrationToV2.runVerify` doesn't do that yet.
 *
 * ## Wiring
 * This takes the six DAOs it reads rather than a `FotozDatabase`. `:core:db` declares Room as an
 * `implementation` dependency, so `androidx.room.RoomDatabase`, `FotozDatabase`'s supertype, isn't
 * on `:app`'s compile classpath, and any `:app` code that touches a `FotozDatabase` member fails
 * to compile ("Cannot access 'androidx.room.RoomDatabase'"). The DAO interfaces extend no Room
 * type, so this file compiles as things stand. The call site that builds the database and hands
 * over `db.assetDao()` and the rest will need Room on `:app`'s compile classpath.
 *
 * [repository] and [animationIndex] are `LibraryRuntime`'s own instances, passed in so that
 * building this from `LibraryRuntime`'s constructor never calls back into `LibraryRuntime.get`.
 * [referenceNowMs] is fixed once, so RECENT's 30-day window is the same instant for both calls.
 */
class AndroidProjectionParityCheck(
    context: Context,
    private val repository: SqliteMediaRepository,
    private val animationIndex: AnimationIndex,
    private val assetDao: AssetDao,
    private val assetUserDao: AssetUserDao,
    private val keywordDao: KeywordDao,
    private val folderLockDao: FolderLockDao,
    private val recognitionDao: RecognitionDao,
    private val traitDao: TraitDao,
    private val referenceNowMs: Long = System.currentTimeMillis(),
) : ProjectionParityCheck {
    private val appContext = context.applicationContext

    override suspend fun oldFingerprints(): Map<String, String> {
        // awaitLoaded, not observeAll().first(): see SqliteMediaRepository.awaitLoaded.
        val assets = repository.awaitLoaded()
        // A fresh store plus reload(), the same way LibraryBackgroundWork reads recognition
        // outside the Activity. The Activity's own instance isn't reachable from here.
        val recognitionStore = withContext(Dispatchers.IO) { RecognitionStore(appContext) }
        recognitionStore.reload()
        val recognition = recognitionStore.observe().value
        // The published set is only filled once a scan-triggered sniff has run. Reloading from
        // disk means an early VERIFY doesn't compare an empty set with trait rows.
        animationIndex.reload()
        val animatedIds = animationIndex.observeAnimatedIds().value

        val inputs = withContext(Dispatchers.IO) {
            val library = LibraryStore.get(appContext).observe().value
            val favoriteStoreIds = FavoriteStore(appContext).observe().first()
            ParityInputs(
                assets = assets,
                // Expected difference 1 (see the class KDoc): MigrationToV2 ORs media.is_favorite in.
                favoriteIds = favoriteStoreIds + assets.asSequence().filter { it.isFavorite }.map { it.id },
                sensitiveIds = SensitiveStore(appContext).observe().value,
                archivedIds = library.archivedIds,
                // Expected difference 2 (see the class KDoc): stray auto-tag members are linked.
                tagsByMediaId = mergeTags(library.tagsByMediaId, library.autoTagsByMediaId),
                lockedFolders = PrivateFolderStore(appContext).observeLockedFolders().value,
                peopleMediaIds = recognition.peopleMediaIds,
                petMediaIds = recognition.petMediaIds,
                identityMediaIds = recognition.identityMediaIds,
                animatedIds = animatedIds,
            )
        }
        return withContext(Dispatchers.Default) { fingerprints(inputs) }
    }

    override suspend fun newFingerprints(): Map<String, String> {
        val entities: List<AssetEntity> = assetDao.getAll()
        val mediaIdOf = HashMap<AssetId, MediaId>(entities.size * 2)
        for (entity in entities) {
            val legacyId = entity.locator.toLongOrNull() ?: continue
            mediaIdOf[entity.assetId] = MediaId(legacyId)
        }
        fun mediaIds(assetIds: Iterable<AssetId>): Set<MediaId> = assetIds.mapNotNullTo(linkedSetOf()) { mediaIdOf[it] }

        val users = assetUserDao.observeAll().first()
        val favoriteAssetIds = users.asSequence().filter { it.favorite }.mapTo(HashSet()) { it.assetId }

        val keywordNames = keywordDao.getAllRoot().associate { it.keywordId to it.name }
        val tags = HashMap<MediaId, Set<String>>()
        for (entity in entities) {
            val mediaId = mediaIdOf[entity.assetId] ?: continue
            val links = keywordDao.linksFor(entity.assetId)
            if (links.isEmpty()) continue
            // A non-root keyword can't exist straight after migration (every tag becomes a root
            // keyword). The placeholder name still counts as "tagged", which is all UNTAGGED
            // reads.
            tags[mediaId] = links.mapTo(linkedSetOf()) { keywordNames[it.keywordId] ?: "#${it.keywordId}" }
        }

        val recognition = recognitionDao.observeAll().first()

        val inputs = ParityInputs(
            assets = entities.mapNotNull { entity ->
                val mediaId = mediaIdOf[entity.assetId] ?: return@mapNotNull null
                entity.toLegacyShape(mediaId, favorite = entity.assetId in favoriteAssetIds)
            },
            favoriteIds = mediaIds(favoriteAssetIds),
            sensitiveIds = mediaIds(users.filter { it.sensitive }.map { it.assetId }),
            archivedIds = mediaIds(users.filter { it.archived }.map { it.assetId }),
            tagsByMediaId = tags,
            lockedFolders = folderLockDao.getAll().mapTo(linkedSetOf()) { it.folderKey },
            peopleMediaIds = mediaIds(recognition.filter { it.faceCount > 0 }.map { it.assetId }),
            petMediaIds = mediaIds(recognition.filter { isPet(it.petVerdict) }.map { it.assetId }),
            identityMediaIds = mediaIds(recognition.filter { isIdentity(it.identityVerdict) }.map { it.assetId }),
            animatedIds = mediaIds(traitDao.getAnimatedIds()),
        )
        return withContext(Dispatchers.Default) { fingerprints(inputs) }
    }

    private fun fingerprints(inputs: ParityInputs): Map<String, String> {
        fun state(unlockedFolders: Set<String>) = GalleryUiState(
            assets = inputs.assets,
            favoriteIds = inputs.favoriteIds,
            sensitiveIds = inputs.sensitiveIds,
            lockedFolders = inputs.lockedFolders,
            unlockedFolders = unlockedFolders,
            library = LibraryState(tagsByMediaId = inputs.tagsByMediaId, archivedIds = inputs.archivedIds),
            permissionGranted = true,
            scanState = ScanState.Idle,
            preferences = GalleryPreferencesState(),
            recognition = RecognitionIndex(
                people = emptyList(),
                peopleMediaIds = inputs.peopleMediaIds,
                petMediaIds = inputs.petMediaIds,
                identityMediaIds = inputs.identityMediaIds,
            ),
            animatedIds = inputs.animatedIds,
        )
        val allLocked = projectionFingerprints(state(emptySet()), referenceNowMs, ZoneOffset.UTC, Locale.UK)
        val allUnlocked = projectionFingerprints(state(inputs.lockedFolders), referenceNowMs, ZoneOffset.UTC, Locale.UK)
        return buildMap {
            putAll(allLocked)
            allUnlocked.forEach { (key, value) -> put("$UNLOCKED_PREFIX$key", value) }
        }
    }

    /** Only the fields the projections read, in the shape they read them. */
    private class ParityInputs(
        val assets: List<MediaAsset>,
        val favoriteIds: Set<MediaId>,
        val sensitiveIds: Set<MediaId>,
        val archivedIds: Set<MediaId>,
        val tagsByMediaId: Map<MediaId, Set<String>>,
        val lockedFolders: Set<String>,
        val peopleMediaIds: Set<MediaId>,
        val petMediaIds: Set<MediaId>,
        val identityMediaIds: Set<MediaId>,
        val animatedIds: Set<MediaId>,
    )

    companion object {
        /** Key prefix for the second, every-folder-unlocked privacy view. */
        const val UNLOCKED_PREFIX = "unlocked/"
    }
}

/** `a ∪ b`, per media id. */
private fun mergeTags(
    tags: Map<MediaId, Set<String>>,
    extra: Map<MediaId, Set<String>>,
): Map<MediaId, Set<String>> {
    if (extra.isEmpty()) return tags
    val merged = HashMap<MediaId, Set<String>>(tags)
    extra.forEach { (id, more) -> merged[id] = merged[id].orEmpty() + more }
    return merged
}

/** The same verdict reading `RecognitionOpenHelper.enumOrNone` + `RecognitionIndex.from` apply. */
private fun isPet(stored: String): Boolean = PetVerdict.entries.firstOrNull { it.name == stored }?.isPet == true

private fun isIdentity(stored: String): Boolean =
    IdentityVerdict.entries.firstOrNull { it.name == stored }?.isIdentity == true

/** A v2 `asset` row in the old [MediaAsset] shape, keyed by its legacy MediaStore id. */
private fun AssetEntity.toLegacyShape(mediaId: MediaId, favorite: Boolean) = MediaAsset(
    id = mediaId,
    contentUriString = contentUri.orEmpty(),
    displayName = displayName,
    mimeType = mime,
    bucketName = bucketName,
    bucketId = bucketId,
    dateTakenMillis = dateTakenMs,
    dateModifiedSeconds = dateModifiedMs / 1_000L,
    width = width,
    height = height,
    sizeBytes = sizeBytes,
    durationMillis = durationMs,
    relativePath = relativePath,
    // No projection reads this: favourites travel as favoriteIds. It's filled from asset_user
    // only so the rebuilt object isn't misleading if someone inspects it.
    isFavorite = favorite,
    isTrashed = trashed,
)
