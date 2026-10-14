package com.fotoxplorr.core.db

import com.fotoxplorr.core.db.entity.AssetUserEntity
import com.fotoxplorr.core.db.entity.SourceEntity
import com.fotoxplorr.core.db.entity.SourceKind
import com.fotoxplorr.core.db.entity.SourceState
import com.fotoxplorr.core.model.AssetId
import com.fotoxplorr.core.model.SourceId

/**
 * Shared by [com.fotoxplorr.core.db.migration.MigrationToV2] (the one-time cutover) and
 * `com.fotoxplorr.core.index.SyncEngine` (WP1.4's ongoing sync, `:core:index`): both populate
 * `asset`/`source`/`asset_user` from a MediaStore-shaped row and must compute the exact same
 * `folder_key`/`revision`/baseline-row shape, or an asset migrated by one and later touched by
 * the other would disagree with itself. Lives in `:core:db` proper (not `migration/`) so
 * `:core:index` can depend on it without pulling in migration-only types.
 */

/**
 * ADR-011 §4: source-scoped `folder_key`, mirroring `gallery/FolderIdentity.kt`'s precedence
 * exactly (path, then bucket-id, then bucket-name, then a fourth `other` fallback the ADR's own
 * prose doesn't mention but the real code has -- see the WP1.3 survey in MASTER-PROGRESS.md).
 */
fun folderKey(sourceId: SourceId, relativePath: String?, bucketId: Long?, bucketName: String?): String {
    val normalizedPath = relativePath
        ?.trim()
        ?.replace('\\', '/')
        ?.split('/')
        ?.filter { it.isNotBlank() }
        ?.joinToString("/")
        .orEmpty()
    val suffix = when {
        normalizedPath.isNotEmpty() -> "path:${normalizedPath.lowercase()}"
        bucketId != null -> "bucket-id:$bucketId"
        !bucketName.isNullOrBlank() -> "bucket-name:${bucketName.trim().lowercase()}"
        else -> "other"
    }
    return "${sourceId.value}:$suffix"
}

/** ADR-011 §2's `asset.revision`: "bumps whenever size/date_modified/content changes" --
 *  `content_hash` is filled lazily (WP1.4) so isn't available yet; a file's own modification
 *  timestamp already changes whenever its content does in every case this app can observe, so
 *  mixing in size is extra sensitivity, not a substitute for a real content hash. */
fun assetRevision(dateModifiedMs: Long, sizeBytes: Long): Long = dateModifiedMs xor sizeBytes

/** A fresh `MEDIASTORE_VOLUME` [SourceEntity] for a volume neither migration nor sync has seen
 *  before. `syncVersion`/`syncGeneration`/`lastFullScanMs` start null -- the caller (migration or
 *  [com.fotoxplorr.core.index.SyncEngine]) is responsible for filling them in once it actually
 *  reads the volume's generation state; a freshly-migrated source has none of that yet (see
 *  MASTER-PROGRESS.md's WP1.3 Decisions on why WP1.4's sync engine must treat every migrated
 *  source as an unbaselined one). */
fun newMediaStoreSource(volumeName: String) = SourceEntity(
    sourceId = SourceId(0),
    kind = SourceKind.MEDIASTORE_VOLUME,
    rootLocator = volumeName,
    volumeUuid = null,
    displayName = volumeName,
    state = SourceState.ONLINE,
    syncVersion = null,
    syncGeneration = null,
    lastFullScanMs = null,
)

/** The baseline `asset_user` row every asset gets the moment it's first known, whether by
 *  migration or by sync discovering a genuinely new file. [favorite] only ever comes from the
 *  legacy migration path (carrying forward the old store's own favourite flag); sync-discovered
 *  assets have no legacy favourite to carry forward, so it defaults false. */
fun baseAssetUser(assetId: AssetId, favorite: Boolean = false) = AssetUserEntity(
    assetId = assetId,
    favorite = favorite,
    rating = 0,
    flag = 0,
    colorLabel = 0,
    archived = false,
    everUnarchived = false,
    sensitive = false,
    caption = null,
    captionIsMachine = false,
    captionMachineSuppressed = false,
    archiveSuggestionRejected = false,
    sidecarState = null,
)
