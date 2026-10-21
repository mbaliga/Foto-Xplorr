package com.fotoxplorr.core.db.migration

import com.fotoxplorr.core.model.SourceId

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
