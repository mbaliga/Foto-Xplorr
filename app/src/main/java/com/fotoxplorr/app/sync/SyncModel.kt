package com.fotoxplorr.app.sync

import com.fotoxplorr.app.gallery.folderIdentity
import com.fotoxplorr.app.media.MediaAsset
import java.io.IOException

/*
 * Optional sync to a cloud of the person's choice (batch 9, owner feedback item 8).
 *
 * SLICE-1 SEMANTICS, stated once here because every file of this package leans on them:
 *  - ONE-WAY MIRROR, "send copies". New and changed ORIGINAL files of the chosen sources are
 *    uploaded under a stable layout (see [RemoteLayout]); nothing is ever downloaded, merged or
 *    reconciled back.
 *  - NEVER DELETES, locally or remotely. The provider interface has no delete. A file removed
 *    from the phone stays at the destination; an edited photo is uploaded as a NEW remote file
 *    beside the old one, never over it.
 *  - OFF by default. Nothing is set up, scheduled or started on app open: a run begins only from
 *    an explicit tap ([SyncService.start]). There is no background schedule in this slice.
 *  - Resume is per file: a run may stop at any file (cancel, lost connection, byte cap) and the
 *    next run continues. A file is recorded as done only after the destination confirmed it whole,
 *    so a half-sent file is simply sent again. Multipart uploads are not resumed part by part
 *    across runs (see [S3CompatibleProvider]).
 *  - Protected, sensitive, locked-folder, trashed and archived photos are never sent: the
 *    candidates come from the browsable list (TRAPS #33) and the list is asked AGAIN just before
 *    each file's bytes are read ([UploadGate]).
 *  - No account, no server of ours. Credentials stay on the phone, encrypted by the Android
 *    Keystore ([SyncSecrets]); the manifest never holds one.
 *
 * Nothing here has talked to a real server. See SyncCopy / the unit report for deviceChecks.
 */

/** Which photos a destination mirrors. Folder keys are `folderIdentity(asset).key.value`. */
data class SyncSelection(
    val allPhotos: Boolean = false,
    val folderKeys: Set<String> = emptySet(),
    val favourites: Boolean = false,
    val includeVideos: Boolean = true,
) {
    /** Nothing chosen means nothing syncs: the safe reading of an empty selection. */
    val isEmpty: Boolean get() = !allPhotos && folderKeys.isEmpty() && !favourites

    fun matches(folderKey: String, isFavourite: Boolean, isVideo: Boolean): Boolean {
        if (isVideo && !includeVideos) return false
        return allPhotos || folderKey in folderKeys || (favourites && isFavourite)
    }
}

/**
 * One file the planner may send. [key] is the local identity the manifest uses: the folder path
 * and file name, NOT the MediaStore id, which changes when a library is restored or re-scanned.
 */
data class SyncCandidate(
    val mediaId: Long,
    val displayName: String,
    val relativePath: String?,
    val mimeType: String,
    val sizeBytes: Long,
    val dateTakenMillis: Long,
    val dateModifiedSeconds: Long,
    val folderKey: String,
    val isFavourite: Boolean,
) {
    val key: String get() = SyncKeys.localKey(relativePath, displayName)
}

object SyncKeys {
    fun localKey(relativePath: String?, displayName: String): String {
        val folder = relativePath.orEmpty().replace('\\', '/').split('/').filter { it.isNotBlank() }.joinToString("/")
        return if (folder.isEmpty()) displayName else "$folder/$displayName"
    }
}

object SyncCandidates {
    /**
     * The library list the sync service must be given (the `library` of [DefaultSyncService]): the
     * browsable list (TRAPS #33) held to the STRICTEST reading. A folder the person has unlocked for
     * this session is still a locked folder here (`unlockedFolders` is empty), and sensitive-marked photos
     * are always left out ([hideSensitive] true): sync is a standing copy to another place, not a view.
     * Derive it once per change of the inputs and keep the instance (see [DefaultSyncService]).
     */
    fun browsableForSync(
        assets: List<MediaAsset>,
        archivedIds: Set<com.fotoxplorr.core.model.MediaId>,
        sensitiveIds: Set<com.fotoxplorr.core.model.MediaId>,
        lockedFolders: Set<String>,
    ): List<MediaAsset> = com.fotoxplorr.app.gallery.browsableAssets(
        assets = assets,
        archivedIds = archivedIds,
        sensitiveIds = sensitiveIds,
        lockedFolders = lockedFolders,
        unlockedFolders = emptySet(),
        hideSensitive = true,
    )

    /**
     * [browsable] MUST already be the browsable list ([com.fotoxplorr.app.gallery.browsableAssets]):
     * this function only applies the person's selection, it does not decide what is protected.
     * Files handed over by another app and files with no known size are not offered.
     */
    fun from(browsable: List<MediaAsset>, selection: SyncSelection): List<SyncCandidate> {
        if (selection.isEmpty) return emptyList()
        return browsable.asSequence()
            .filterNot { it.isExternal || it.isTrashed || it.sizeBytes <= 0L }
            .mapNotNull { asset ->
                val folder = folderIdentity(asset).key.value
                if (!selection.matches(folder, asset.isFavorite, asset.isVideo)) return@mapNotNull null
                SyncCandidate(
                    mediaId = asset.id.value,
                    displayName = asset.displayName,
                    relativePath = asset.relativePath,
                    mimeType = asset.mimeType,
                    sizeBytes = asset.sizeBytes,
                    dateTakenMillis = asset.dateTakenMillis,
                    dateModifiedSeconds = asset.dateModifiedSeconds,
                    folderKey = folder,
                    isFavourite = asset.isFavorite,
                )
            }
            .toList()
    }
}

/**
 * Asked again immediately before a file's bytes are opened (TRAPS #33). The host answers from the
 * live browsable list, so a folder locked or a photo marked sensitive after the run was planned
 * stops that file. False means "skip it, quietly".
 */
fun interface UploadGate {
    fun mayUpload(candidate: SyncCandidate): Boolean
}

/** The bytes of a candidate, or null when it is gone or unreadable. */
fun interface SyncFileSource {
    fun open(candidate: SyncCandidate): OpenedLocalFile?
}

class OpenedLocalFile(val input: java.io.InputStream, val length: Long) : java.io.Closeable {
    override fun close() = input.close()
}

/** One entry of a remote folder listing or stat. [sizeBytes] is -1 when the server would not say. */
data class RemoteEntry(
    val path: String,
    val sizeBytes: Long,
    val isDirectory: Boolean,
    val modifiedMillis: Long? = null,
)

/** Checked between chunks of a blocking transfer. Throws a cancellation when the run was stopped. */
fun interface Cancellation {
    fun throwIfCancelled()

    companion object {
        val NEVER = Cancellation { }
    }
}

/** Why a provider call failed, in the terms the engine acts on. */
enum class SyncFailure(val retryable: Boolean) {
    /** 401/403: the account or password is wrong or lacks rights. Retrying cannot help. */
    AUTH(false),

    /** 507, S3 quota: the destination is full. Stop the run. */
    NO_SPACE(false),

    /** Timeouts, resets, 5xx, 429: try again later in the same run. */
    TRANSIENT(true),

    /** Other 4xx, malformed answers: this file will not go; carry on with the next. */
    REJECTED(false),

    /** A redirect to another host, or plain http where it is not allowed. Never retried. */
    UNSAFE(false),

    /** The Offline build has no network code. */
    UNAVAILABLE_BUILD(false),

    /** The folder is gone or its permission was revoked. */
    DESTINATION_GONE(false),
}

open class SyncException(val failure: SyncFailure, message: String, cause: Throwable? = null) : IOException(message, cause)

/** A [SyncFailure.TRANSIENT] failure where the server said how long to wait (429 / Retry-After); the engine waits at least that long, capped. */
class SyncRateLimited(message: String, val retryAfterMillis: Long) : SyncException(SyncFailure.TRANSIENT, message)
