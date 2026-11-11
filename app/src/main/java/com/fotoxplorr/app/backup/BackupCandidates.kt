package com.fotoxplorr.app.backup

import com.fotoxplorr.app.gallery.folderIdentity
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.model.MediaId

/**
 * What the planner is offered, built from the library's own catalogue (never a rescan of storage)
 * and the three states that matter to a backup: a locked folder, a sensitive mark, an archive.
 * A file shared in from another app ([MediaAsset.isExternal]) is not part of the library and is
 * not offered. Trashed files are passed on flagged, and the planner skips them by name.
 */
object BackupCandidates {
    fun from(
        assets: List<MediaAsset>,
        lockedFolders: Set<String>,
        sensitiveIds: Set<MediaId>,
        archivedIds: Set<MediaId>,
    ): List<BackupCandidate> = assets.asSequence()
        .filterNot { it.isExternal }
        .map { asset ->
            BackupCandidate(
                mediaId = asset.id.value,
                displayName = asset.displayName,
                relativePath = asset.relativePath,
                mimeType = asset.mimeType,
                sizeBytes = asset.sizeBytes,
                dateTakenMillis = asset.dateTakenMillis,
                dateModifiedSeconds = asset.dateModifiedSeconds,
                isTrashed = asset.isTrashed,
                isProtected = folderIdentity(asset).key.value in lockedFolders,
                isSensitive = asset.id in sensitiveIds,
                isArchived = asset.id in archivedIds,
            )
        }
        .toList()
}
