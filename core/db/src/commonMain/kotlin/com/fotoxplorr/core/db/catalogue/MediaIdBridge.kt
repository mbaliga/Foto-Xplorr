package com.fotoxplorr.core.db.catalogue

import com.fotoxplorr.core.db.dao.AssetDao
import com.fotoxplorr.core.model.AssetId
import com.fotoxplorr.core.model.MediaId

/**
 * The [MediaId] <-> [AssetId] bridge every `Catalogue*Store` facade in this package shares --
 * see [AssetDao.findByLocatorAnySource] for why a plain `locator` lookup (no `source_id`) is
 * safe in Phase 1.
 */
internal suspend fun AssetDao.resolveAssetId(mediaId: MediaId): AssetId? =
    findByLocatorAnySource(mediaId.value.toString())?.assetId

internal suspend fun AssetDao.resolveAssetIds(mediaIds: Collection<MediaId>): List<AssetId> =
    mediaIds.mapNotNull { resolveAssetId(it) }

internal suspend fun AssetDao.resolveMediaIds(assetIds: Collection<AssetId>): Set<MediaId> =
    getByIds(assetIds.toList()).mapNotNullTo(linkedSetOf()) { it.locator.toLongOrNull()?.let(::MediaId) }
