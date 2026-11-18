@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package com.fotoxplorr.ut.bridge

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlin.native.CName

@Serializable
data class FolderDto(val folderKey: String, val name: String, val itemCount: Int, val newestMs: Long)

@Serializable
data class FoldersResponseDto(val folders: List<FolderDto>, val error: String? = null)

/**
 * The folders the library currently shows (newest content first), for a shell's folder list --
 * pair each [FolderDto.folderKey] with `fotoz_query_page` `{"album":"FOLDER","folderKey":...}`.
 * [requestJson] is optional-in-practice: `{"showVideos":false}` hides video-only folders' videos;
 * `null` or `{}` means defaults. Returns `null` only for an invalid handle (BridgeJson.kt rule).
 */
@CName("fotoz_folders")
fun fotoz_folders(handle: COpaquePointer?, requestJson: CPointer<ByteVar>?): CPointer<ByteVar>? {
    val library = handle.asLibraryHandleOrNull() ?: return null
    return runCatchingJson {
        val showVideos = requestJson.readUtf8OrNull()?.let {
            try {
                BridgeJson.json.decodeFromString(FoldersRequestDto.serializer(), it).showVideos
            } catch (t: Throwable) {
                return@runCatchingJson BridgeJson.encodeError("malformed request: ${t.message}")
            }
        } ?: true
        val rows = runBridge { library.db.galleryProjectionDao().folders(showVideos) }
        BridgeJson.json.encodeToString(
            FoldersResponseDto(rows.map { FolderDto(it.folderKey, it.name, it.itemCount, it.newestMs) }),
        )
    }
}

@Serializable
data class FoldersRequestDto(val showVideos: Boolean = true)
