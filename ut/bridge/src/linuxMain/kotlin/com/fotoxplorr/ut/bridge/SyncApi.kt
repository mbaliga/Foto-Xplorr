@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package com.fotoxplorr.ut.bridge

import com.fotoxplorr.core.db.entity.SourceEntity
import com.fotoxplorr.core.db.entity.SourceKind
import com.fotoxplorr.core.db.entity.SourceState
import com.fotoxplorr.core.index.LocalDirSource
import com.fotoxplorr.core.index.SyncEngine
import com.fotoxplorr.core.index.SyncResult
import com.fotoxplorr.core.model.SourceId
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.invoke
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlin.native.CName
import platform.posix.time

/** [fotoz_sync_start]'s request: only `kind = "LINUX_DIR"` exists on this platform today. */
@Serializable
data class SyncRequestDto(val kind: String? = null, val path: String? = null)

/** What the callback's message carries on success. */
@Serializable
data class SyncResultDto(val sourceId: Long, val itemsSeen: Int, val fullyEnumerated: Boolean)

/**
 * Runs one real [SyncEngine] pass over a [LocalDirSource] (ADR-013 point 5's `LINUX_DIR`) and
 * reports the outcome through [callback].
 *
 * **Blocking.** The pass runs to completion on the calling thread before this function returns,
 * so a UI shell must call it from a worker thread. There is no incremental progress callback yet
 * (the callback fires exactly once, at the end) and no cancellation.
 *
 * [sourceJson] is a [SyncRequestDto]. A well-formed request for any other `kind` is
 * [BridgeStatus.NOT_IMPLEMENTED] (there is no such source on this platform); a missing/blank
 * `path` or malformed JSON is [BridgeStatus.BAD_REQUEST]. The source row is created on first use
 * and reused afterwards (unique on kind + root path), so re-syncing a folder is an incremental
 * upsert-and-sweep, not a duplicate library.
 *
 * Outcomes, each delivered as `(status, jsonMessage)` to [callback] AND returned:
 * - [BridgeStatus.OK] + [SyncResultDto]. `fullyEnumerated = false` means some directory could not
 *   be read or the depth cap was hit, in which case [SyncEngine] performed no mark-and-sweep.
 * - [BridgeStatus.IO_ERROR] + `{"error":...}` when the root itself cannot be read (nothing swept).
 *
 * The message pointer is valid only during the callback (this function frees it right after);
 * copy it, never free it. The callback is invoked at most once and never after return.
 *
 * Not implemented here: inotify watching, mtime-delta passes, removable-drive (UDisks2)
 * sources, Content Hub import. EXIF is not parsed, so `date_taken` is the file mtime for now.
 */
@CName("fotoz_sync_start")
fun fotoz_sync_start(
    handle: COpaquePointer?,
    sourceJson: CPointer<ByteVar>?,
    callback: CPointer<CFunction<(Int, CPointer<ByteVar>?, COpaquePointer?) -> Unit>>?,
    userData: COpaquePointer?,
): Int = runCatchingStatus {
    val library = handle.asLibraryHandleOrNull() ?: return@runCatchingStatus BridgeStatus.INVALID_HANDLE
    val text = sourceJson.readUtf8OrNull() ?: return@runCatchingStatus BridgeStatus.BAD_REQUEST
    val request = try {
        BridgeJson.json.decodeFromString(SyncRequestDto.serializer(), text)
    } catch (t: Throwable) {
        return@runCatchingStatus BridgeStatus.BAD_REQUEST
    }

    fun deliver(status: Int, json: String): Int {
        val message = json.toHeapCString()
        try {
            callback?.invoke(status, message, userData)
        } finally {
            fotoz_free_string(message)
        }
        return status
    }

    val kind = request.kind ?: return@runCatchingStatus BridgeStatus.BAD_REQUEST
    if (kind != SourceKind.LINUX_DIR) {
        return@runCatchingStatus deliver(
            BridgeStatus.NOT_IMPLEMENTED,
            BridgeJson.encodeError("source kind '$kind' is not implemented on this platform; only LINUX_DIR is"),
        )
    }
    val path = request.path?.trim()?.takeIf { it.isNotEmpty() }?.let { it.trimEnd('/').ifEmpty { "/" } }
        ?: return@runCatchingStatus BridgeStatus.BAD_REQUEST

    runBridge {
        val sources = library.db.sourceDao()
        val row = sources.findByLocator(SourceKind.LINUX_DIR, path) ?: run {
            val id = sources.insert(
                SourceEntity(
                    sourceId = SourceId(0),
                    kind = SourceKind.LINUX_DIR,
                    rootLocator = path,
                    volumeUuid = null,
                    displayName = path.substringAfterLast('/').ifEmpty { path },
                    state = SourceState.ONLINE,
                    syncVersion = null,
                    syncGeneration = null,
                    lastFullScanMs = null,
                ),
            )
            requireNotNull(sources.get(SourceId(id)))
        }
        val engine = SyncEngine(sources, library.db.assetDao(), library.db.assetUserDao())
        when (val result = engine.sync(LocalDirSource(path, PosixDirectoryLister), row, partialAccess = false, nowMs = time(null).toLong() * 1000L)) {
            is SyncResult.Completed -> deliver(
                BridgeStatus.OK,
                BridgeJson.json.encodeToString(SyncResultDto(row.sourceId.value, result.itemsSeen, result.fullyEnumerated)),
            )
            is SyncResult.Failed -> deliver(BridgeStatus.IO_ERROR, BridgeJson.encodeError(result.error.message ?: "sync failed"))
        }
    }
}
