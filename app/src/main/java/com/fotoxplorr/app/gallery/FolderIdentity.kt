package com.fotoxplorr.app.gallery

import com.fotoxplorr.app.media.MediaAsset

@JvmInline
value class FolderKey(val value: String)

data class FolderIdentity(
    val key: FolderKey,
    val displayName: String,
)

fun folderIdentity(asset: MediaAsset): FolderIdentity = folderIdentity(
    bucketName = asset.bucketName,
    relativePath = asset.relativePath,
)

fun folderIdentity(
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
        bucketName?.isNotBlank() == true -> "bucket:${bucketName.trim().lowercase()}"
        else -> "other"
    }

    return FolderIdentity(FolderKey(key), displayName)
}
