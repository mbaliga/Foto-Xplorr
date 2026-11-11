package com.fotoxplorr.app.sources.gallery

import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.index.sources.SourceAssetRow
import com.fotoxplorr.core.index.sources.SourceInfo
import com.fotoxplorr.core.index.sources.SourceKind
import com.fotoxplorr.core.index.sources.SourceState
import com.fotoxplorr.core.model.MediaId

internal const val PRIMARY_TREE = "content://com.android.externalstorage.documents/tree/primary%3ADCIM"
internal const val USB_TREE = "content://com.android.externalstorage.documents/tree/1A2B-3C4D%3A"

internal fun sourceInfo(
    row: Long = 3,
    tree: String = USB_TREE,
    state: SourceState = SourceState.ONLINE,
    name: String = "Backup drive",
) = SourceInfo(row, SourceKind.SAF_TREE, tree, null, name, state)

internal fun sourceRow(
    row: Long,
    sourceRow: Long = 3,
    name: String = "IMG_$row.jpg",
    path: String = "DCIM/Camera",
    uri: String? = "content://fotoz.test.docs/tree/root/document/doc$row",
    size: Long = 1_000L + row,
    dateTaken: Long = 0,
    modified: Long = 1_700_000_000_000L + row * 1000,
    width: Int = 0,
    height: Int = 0,
    present: Boolean = true,
) = SourceAssetRow(
    row = row, sourceRow = sourceRow, documentId = "doc$row", displayName = name, mime = "image/jpeg",
    sizeBytes = size, width = width, height = height, durationMs = 0, dateTakenMs = dateTaken,
    dateModifiedMs = modified, pathInsideTree = path, contentUri = uri, present = present,
)

internal fun entry(row: SourceAssetRow, source: SourceInfo = sourceInfo(row.sourceRow)) = SourceAssetEntry(row, source)

internal fun libraryAsset(
    id: Long,
    name: String = "IMG_$id.jpg",
    path: String? = "DCIM/Camera/",
    size: Long = 1_000L + id,
    taken: Long = 1_600_000_000_000L + id,
    uri: String = "content://media/external/images/media/$id",
) = MediaAsset(
    id = MediaId(id), contentUriString = uri, displayName = name, mimeType = "image/jpeg",
    bucketName = "Camera", bucketId = 100L, dateTakenMillis = taken, dateModifiedSeconds = taken / 1000,
    width = 4000, height = 3000, sizeBytes = size, relativePath = path, isFavorite = false, isTrashed = false,
)
