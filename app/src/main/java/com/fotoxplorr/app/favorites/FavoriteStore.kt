package com.fotoxplorr.app.favorites

import android.content.Context
import com.fotoxplorr.app.media.MediaId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class FavoriteStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())

    fun observe(): Flow<Set<MediaId>> = state.asStateFlow()

    fun toggle(id: MediaId) {
        val updated = state.value.toMutableSet().apply {
            if (!add(id)) remove(id)
        }
        persist(updated)
    }

    fun setFavorite(ids: Set<MediaId>, favorite: Boolean) {
        if (ids.isEmpty()) return
        val updated = state.value.toMutableSet().apply {
            if (favorite) addAll(ids) else removeAll(ids)
        }
        persist(updated)
    }

    private fun persist(ids: Set<MediaId>) {
        val immutable = ids.toSet()
        state.value = immutable
        preferences.edit()
            .putStringSet(KEY_FAVORITES, FavoriteIdCodec.encode(immutable))
            .apply()
    }

    private fun load(): Set<MediaId> = FavoriteIdCodec.decode(
        preferences.getStringSet(KEY_FAVORITES, emptySet()),
    )

    private companion object {
        const val PREFERENCES_NAME = "foto_xplorr_favorites"
        const val KEY_FAVORITES = "favorite_media_ids"
    }
}
