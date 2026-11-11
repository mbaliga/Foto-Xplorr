package com.fotoxplorr.core.db.catalogue

import com.fotoxplorr.core.db.dao.AssetDao
import com.fotoxplorr.core.db.dao.AssetUserDao
import com.fotoxplorr.core.model.MediaId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * ADR-011 Consequences' facade over `asset_user.favorite`, replacing the app's old
 * SharedPreferences-backed `FavoriteStore` (`app/.../favorites/FavoriteStore.kt`) once it is
 * safe to wire in live.
 *
 * **Not yet constructed by the app's `LibraryRuntime` or read by any Composable.** `asset` only
 * gets new rows from [com.fotoxplorr.core.db.migration.MigrationToV2]'s one-time ASSETS step
 * today, so a photo taken after that step ran has no row here at all -- [toggle] and
 * [setFavorite] silently no-op for a [MediaId] [AssetDao.findByLocatorAnySource] can't resolve.
 * WP1.4's sync engine is what keeps `asset` current going forward; wiring this store into the
 * live UI before WP1.4 lands would regress favoriting for any newly-added photo. See
 * MASTER-PROGRESS.md's WP1.3 Decisions for the full reasoning.
 */
class CatalogueFavoriteStore(
    private val assetDao: AssetDao,
    private val assetUserDao: AssetUserDao,
) {
    fun observe(): Flow<Set<MediaId>> = assetUserDao.observeFavoriteIds().map { assetDao.resolveMediaIds(it) }

    suspend fun toggle(id: MediaId) {
        val assetId = assetDao.resolveAssetId(id) ?: return
        val current = assetUserDao.get(assetId)?.favorite ?: false
        assetUserDao.setFavorite(assetId, !current)
    }

    suspend fun setFavorite(ids: Set<MediaId>, favorite: Boolean) {
        if (ids.isEmpty()) return
        val assetIds = assetDao.resolveAssetIds(ids)
        if (assetIds.isEmpty()) return
        assetUserDao.setFavorite(assetIds, favorite)
    }
}
