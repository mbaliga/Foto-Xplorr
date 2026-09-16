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
 * [SqliteAudioRepository] is the persisted counterpart this app actually ships with now — see its
 * own doc for why background playback ended up needing one after all, which this class's original
 * doc (a `MediaStore.Audio.Media` re-query being cheap enough not to bother) reasoned about a
 * different requirement than "a cold [com.fotoxplorr.app.playback.PlaybackService] needs a queue's
 * assets before any scan has run". This class is kept anyway: it needs no [android.content.Context]
 * and no real SQLite, which is exactly what a fast, hermetic unit test (see [AudioIndexer]'s own
 * tests) wants, and it remains a perfectly correct [AudioRepository] for anything that genuinely
 * does not need the state to survive a process restart.
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
