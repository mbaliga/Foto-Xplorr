package com.fotoxplorr.app.gallery

import com.fotoxplorr.app.media.MediaAsset

@JvmInline
value class FolderKey(val value: String)

data class FolderIdentity(
    val key: FolderKey,
    val displayName: String,
)

fun folderIdentity(asset: MediaAsset): FolderIdentity = folderIdentity(
    bucketId = asset.bucketId,
    bucketName = asset.bucketName,
    relativePath = asset.relativePath,
)

fun folderIdentity(
    bucketName: String?,
    relativePath: String?,
): FolderIdentity = folderIdentity(
    bucketId = null,
    bucketName = bucketName,
    relativePath = relativePath,
)

fun folderIdentity(
    bucketId: Long?,
    bucketName: String?,
    relativePath: String?,
): FolderIdentity {
    val normalizedPath = relativePath
        ?.trim()
        ?.replace('\\', '/')
        ?.split('/')
        ?.filter { it.isNotBlank() }
        ?.joinToString("/")
        .orEmpty()

    val displayName = bucketName
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: normalizedPath.substringAfterLast('/').takeIf { it.isNotEmpty() }
        ?: "Other"

    val key = when {
        normalizedPath.isNotEmpty() -> "path:${normalizedPath.lowercase()}"
        bucketId != null -> "bucket-id:$bucketId"
        bucketName?.isNotBlank() == true -> "bucket-name:${bucketName.trim().lowercase()}"
        else -> "other"
    }

    return FolderIdentity(FolderKey(key), displayName)
}
