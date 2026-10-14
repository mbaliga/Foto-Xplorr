@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package com.fotoxplorr.ut.bridge

import com.fotoxplorr.core.db.entity.AssetUserEntity
import com.fotoxplorr.core.model.AssetId
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.serialization.encodeToString
import kotlin.native.CName

/**
 * Real, backed entirely by `:core:db`'s own [com.fotoxplorr.core.db.dao.AssetDao] and
 * [com.fotoxplorr.core.db.dao.AssetUserDao] -- both native-ready, same as [fotoz_query_page]'s
 * DAO. `user` is `null` when the asset has no `asset_user` row at all (a real, normal state: a
 * row only gets one once something is actually set on it -- [AssetUserEntity] has no default
 * all-zero row created on insert, matching `:core:db`'s own catalogue-store facades' "silently
 * no-op for an asset with no row yet" honesty, MASTER-PROGRESS.md's WP1.3 Decisions).
 *
 * @return a heap-allocated JSON string. `null` only for an invalid [handle]; a well-formed
 * request for an [assetId] that does not exist returns a real JSON `{"error": "not_found"}`
 * string, never `null` (per `BridgeJson.kt`'s convention).
 */
@CName("fotoz_asset_detail")
fun fotoz_asset_detail(handle: COpaquePointer?, assetId: Long): CPointer<ByteVar>? {
    val library = handle.asLibraryHandleOrNull() ?: return null
    return runCatchingJson {
        val id = AssetId(assetId)
        val (asset, user) = runBridge {
            val a = library.db.assetDao().get(id)
            val u = if (a != null) library.db.assetUserDao().get(id) else null
            a to u
        }
        if (asset == null) {
            BridgeJson.json.encodeToString(ErrorResponseDto("not_found"))
        } else {
            val dto = AssetDetailResponseDto(asset = asset.toDto(), user = user?.toDto())
            BridgeJson.json.encodeToString(dto)
        }
    }
}

private fun AssetUserEntity.toDto() = AssetUserDto(
    favorite = favorite,
    rating = rating,
    flag = flag,
    colorLabel = colorLabel,
    archived = archived,
    sensitive = sensitive,
    caption = caption,
    captionIsMachine = captionIsMachine,
)
