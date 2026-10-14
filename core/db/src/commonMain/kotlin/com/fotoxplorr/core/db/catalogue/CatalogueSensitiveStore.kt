package com.fotoxplorr.core.db.catalogue

import com.fotoxplorr.core.db.dao.AssetDao
import com.fotoxplorr.core.db.dao.AssetUserDao
import com.fotoxplorr.core.model.MediaId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * ADR-011 Consequences' facade over `asset_user.sensitive`, replacing the app's old
 * SharedPreferences-backed `SensitiveStore` (`app/.../privacy/SensitiveStore.kt`) once it is
 * safe to wire in live. See [CatalogueFavoriteStore]'s KDoc -- same not-yet-wired reasoning
 * applies here unchanged.
 */
class CatalogueSensitiveStore(
    private val assetDao: AssetDao,
    private val assetUserDao: AssetUserDao,
) {
    fun observe(): Flow<Set<MediaId>> = assetUserDao.observeSensitiveIds().map { assetDao.resolveMediaIds(it) }

    suspend fun toggle(id: MediaId) {
        val assetId = assetDao.resolveAssetId(id) ?: return
        val current = assetUserDao.get(assetId)?.sensitive ?: false
        assetUserDao.setSensitive(assetId, !current)
    }

    suspend fun setSensitive(id: MediaId, sensitive: Boolean) = setSensitive(setOf(id), sensitive)

    suspend fun setSensitive(ids: Set<MediaId>, sensitive: Boolean) {
        if (ids.isEmpty()) return
        val assetIds = assetDao.resolveAssetIds(ids)
        if (assetIds.isEmpty()) return
        assetUserDao.setSensitive(assetIds, sensitive)
    }
}
