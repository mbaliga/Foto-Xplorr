package com.fotoxplorr.app.privacy

import android.content.Context
import com.fotoxplorr.core.model.MediaId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Collections
import java.util.WeakHashMap

/**
 * The photos marked sensitive. The activity, the picker and a restore each build their own
 * instance over the same preferences file, and each keeps the set in memory: a write through one
 * would leave the others showing (and later writing back) the old set. Every write therefore tells
 * the other live instances to read the file again ([persist]), so a restore's marks are the ones
 * the gallery hides and the next toggle starts from, instead of being silently lost.
 */
class SensitiveStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())

    init {
        peers.add(this)
    }

    fun observe(): StateFlow<Set<MediaId>> = state.asStateFlow()

    fun toggle(id: MediaId) {
        val updated = state.value.toMutableSet().apply {
            if (!add(id)) remove(id)
        }
        persist(updated)
    }

    fun setSensitive(id: MediaId, sensitive: Boolean) {
        setSensitive(setOf(id), sensitive)
    }

    fun setSensitive(ids: Set<MediaId>, sensitive: Boolean) {
        if (ids.isEmpty()) return
        val updated = state.value.toMutableSet().apply {
            if (sensitive) addAll(ids) else removeAll(ids)
        }
        persist(updated)
    }

    /** Makes [ids] exactly the sensitive photos, in one write; a restore that replaces what was here. */
    fun replaceAll(ids: Set<MediaId>) = persist(ids)

    private fun persist(ids: Set<MediaId>) {
        val immutable = ids.toSet()
        state.value = immutable
        preferences.edit()
            .putStringSet(KEY_SENSITIVE_IDS, SensitiveIdCodec.encode(immutable))
            .apply()
        synchronized(peers) { peers.toList() }.forEach { if (it !== this) it.state.value = it.load() }
    }

    private fun load(): Set<MediaId> = SensitiveIdCodec.decode(
        preferences.getStringSet(KEY_SENSITIVE_IDS, emptySet()).orEmpty(),
    )

    private companion object {
        /** Live instances, weakly held, so one that is garbage collected is simply gone. */
        val peers: MutableSet<SensitiveStore> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

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
