package com.fotoxplorr.app.media

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class InMemoryMediaRepository : MediaRepository {
    private val mutex = Mutex()
    private val state = MutableStateFlow<List<MediaAsset>>(emptyList())

    override fun observeAll(): Flow<List<MediaAsset>> = state.asStateFlow()

    override suspend fun replaceAll(items: List<MediaAsset>) {
        mutex.withLock {
            state.value = items
                .distinctBy { it.id }
                .sortedWith(assetOrdering)
        }
    }

    override suspend fun upsert(items: List<MediaAsset>) {
        if (items.isEmpty()) return

        mutex.withLock {
            val merged = state.value.associateByTo(linkedMapOf()) { it.id }
            items.forEach { merged[it.id] = it }
            state.value = merged.values.sortedWith(assetOrdering)
        }
    }

    override suspend fun remove(ids: Set<MediaId>) {
        if (ids.isEmpty()) return

        mutex.withLock {
            state.value = state.value.filterNot { it.id in ids }
        }
    }

    private companion object {
        val assetOrdering = compareByDescending<MediaAsset> { it.dateTakenMillis }
            .thenByDescending { it.dateModifiedSeconds }
            .thenByDescending { it.id.value }
    }
}
