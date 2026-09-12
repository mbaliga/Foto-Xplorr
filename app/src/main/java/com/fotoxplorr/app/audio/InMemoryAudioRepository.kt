package com.fotoxplorr.app.audio

import com.fotoxplorr.app.media.MediaId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [com.fotoxplorr.app.media.InMemoryMediaRepository]'s exact shape, for [AudioAsset].
 *
 * Deliberately NOT backed by a persisted SQLite table the way
 * [com.fotoxplorr.app.media.SqliteMediaRepository] is for photos/videos: that persistence exists
 * to survive a 22,000-photo library needing THUMBNAILS decoded, which is genuinely expensive
 * enough to be worth caching across app restarts (see task history on the jank that persistence
 * fixed). A `MediaStore.Audio.Media` query returns plain rows — no thumbnail decode, no bitmap
 * work — so re-querying on every cold start costs a fraction of what the photo scan does even for
 * a large music collection, and a second SQLite schema/migration is not worth taking on just to
 * cache something already cheap to rebuild. A persisted cache is a reasonable future change if a
 * real device shows otherwise; this in-memory store is not a placeholder for one so much as a
 * scoped decision this class's own doc names rather than hides.
 */
class InMemoryAudioRepository : AudioRepository {
    private val mutex = Mutex()
    private val state = MutableStateFlow<List<AudioAsset>>(emptyList())

    override fun observeAll(): Flow<List<AudioAsset>> = state.asStateFlow()

    override suspend fun replaceAll(items: List<AudioAsset>) {
        mutex.withLock {
            state.value = items.distinctBy { it.id }.sortedWith(assetOrdering)
        }
    }

    override suspend fun upsert(items: List<AudioAsset>) {
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

    override suspend fun count(): Int = state.value.size

    private companion object {
        // Alphabetical by title, not the gallery's newest-first convention: there is no "date
        // taken" for a song, and "most recently added to this device" is a far less useful
        // default for browsing a music collection than being able to find a track by name.
        val assetOrdering = compareBy<AudioAsset> { it.title.lowercase() }
    }
}
