package com.fotoxplorr.core.index.sources

/**
 * ADR-020 decision 6 / PHASE-3-DESIGN 1.5: a source item that MediaStore already indexes (same
 * volume, relative path, display name and size) is hidden so internal storage is not shown twice.
 * Heuristic by design: only what it can match is hidden, never a whole volume.
 */
data class OverlapKey(val volume: String, val path: String, val name: String, val sizeBytes: Long)

object SourceOverlap {
    fun key(volume: String, relativePath: String?, displayName: String, sizeBytes: Long): OverlapKey =
        OverlapKey(
            volume = volume.trim().lowercase(),
            path = (relativePath ?: "").replace('\\', '/').split('/').filter { it.isNotBlank() }.joinToString("/").lowercase(),
            name = displayName.trim().lowercase(),
            sizeBytes = sizeBytes,
        )

    /** [source] without the items whose key is in [mediaStoreKeys]; order is kept. */
    fun <T> hideDuplicates(source: List<T>, mediaStoreKeys: Set<OverlapKey>, keyOf: (T) -> OverlapKey?): List<T> =
        source.filter { item ->
            val k = keyOf(item)
            k == null || k !in mediaStoreKeys
        }
}
