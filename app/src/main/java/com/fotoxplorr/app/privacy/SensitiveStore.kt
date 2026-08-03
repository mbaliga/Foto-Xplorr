package com.fotoxplorr.app.privacy

import android.content.Context
import com.fotoxplorr.app.media.MediaId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SensitiveStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())

    fun observe(): StateFlow<Set<MediaId>> = state.asStateFlow()

    fun toggle(id: MediaId) {
        val updated = state.value.toMutableSet().apply {
            if (!add(id)) remove(id)
        }
        persist(updated)
    }

    fun setSensitive(id: MediaId, sensitive: Boolean) {
        val updated = state.value.toMutableSet().apply {
            if (sensitive) add(id) else remove(id)
        }
        persist(updated)
    }

    private fun persist(ids: Set<MediaId>) {
        val immutable = ids.toSet()
        state.value = immutable
        preferences.edit()
            .putStringSet(KEY_SENSITIVE_IDS, SensitiveIdCodec.encode(immutable))
            .apply()
    }

    private fun load(): Set<MediaId> = SensitiveIdCodec.decode(
        preferences.getStringSet(KEY_SENSITIVE_IDS, emptySet()).orEmpty(),
    )

    private companion object {
        const val PREFERENCES_NAME = "foto_xplorr_sensitive"
        const val KEY_SENSITIVE_IDS = "sensitive_media_ids"
    }
}

internal object SensitiveIdCodec {
    fun encode(ids: Set<MediaId>): Set<String> = ids
        .mapTo(linkedSetOf()) { it.value.toString() }

    fun decode(values: Set<String>): Set<MediaId> = values
        .mapNotNullTo(linkedSetOf()) { value -> value.toLongOrNull()?.let(::MediaId) }
}
