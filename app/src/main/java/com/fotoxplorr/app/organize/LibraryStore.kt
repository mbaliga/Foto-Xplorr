package com.fotoxplorr.app.organize

import android.content.Context
import android.util.Base64
import com.fotoxplorr.app.media.MediaId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.UUID

data class MediaCollection(
    val id: String,
    val name: String,
    val mediaIds: Set<MediaId>,
    val createdAtMillis: Long,
)

data class LibraryState(
    val collections: List<MediaCollection> = emptyList(),
    val tagsByMediaId: Map<MediaId, Set<String>> = emptyMap(),
    val archivedIds: Set<MediaId> = emptySet(),
) {
    val allTags: List<String>
        get() = tagsByMediaId.values.flatten().distinct().sortedBy(String::lowercase)

    fun tagsFor(id: MediaId): Set<String> = tagsByMediaId[id].orEmpty()
}

class LibraryStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())

    fun observe(): StateFlow<LibraryState> = state.asStateFlow()

    fun createCollection(name: String): MediaCollection? {
        val cleanName = name.trim().takeIf { it.isNotEmpty() } ?: return null
        val collection = MediaCollection(
            id = UUID.randomUUID().toString(),
            name = cleanName,
            mediaIds = emptySet(),
            createdAtMillis = System.currentTimeMillis(),
        )
        preferences.edit()
            .putStringSet(KEY_COLLECTION_IDS, collectionIds() + collection.id)
            .putString(collectionNameKey(collection.id), collection.name)
            .putStringSet(collectionMediaKey(collection.id), emptySet())
            .putLong(collectionCreatedKey(collection.id), collection.createdAtMillis)
            .apply()
        refresh()
        return collection
    }

    fun renameCollection(collectionId: String, name: String): Boolean {
        val cleanName = name.trim().takeIf { it.isNotEmpty() } ?: return false
        if (collectionId !in collectionIds()) return false
        preferences.edit().putString(collectionNameKey(collectionId), cleanName).apply()
        refresh()
        return true
    }

    fun deleteCollection(collectionId: String) {
        preferences.edit()
            .putStringSet(KEY_COLLECTION_IDS, collectionIds() - collectionId)
            .remove(collectionNameKey(collectionId))
            .remove(collectionMediaKey(collectionId))
            .remove(collectionCreatedKey(collectionId))
            .apply()
        refresh()
    }

    fun addToCollection(collectionId: String, ids: Set<MediaId>) {
        if (ids.isEmpty() || collectionId !in collectionIds()) return
        val updated = collectionMedia(collectionId) + ids
        preferences.edit()
            .putStringSet(collectionMediaKey(collectionId), encodeIds(updated))
            .apply()
        refresh()
    }

    fun removeFromCollection(collectionId: String, ids: Set<MediaId>) {
        if (ids.isEmpty()) return
        preferences.edit()
            .putStringSet(collectionMediaKey(collectionId), encodeIds(collectionMedia(collectionId) - ids))
            .apply()
        refresh()
    }

    fun setArchived(ids: Set<MediaId>, archived: Boolean) {
        if (ids.isEmpty()) return
        val current = decodeIds(preferences.getStringSet(KEY_ARCHIVED_IDS, emptySet()))
        val updated = if (archived) current + ids else current - ids
        preferences.edit().putStringSet(KEY_ARCHIVED_IDS, encodeIds(updated)).apply()
        refresh()
    }

    fun addTag(ids: Set<MediaId>, tag: String) {
        val cleanTag = tag.trim().replace(Regex("\\s+"), " ").takeIf { it.isNotEmpty() } ?: return
        if (ids.isEmpty()) return
        val tagNames = tagNames() + cleanTag
        val updatedIds = tagMedia(cleanTag) + ids
        preferences.edit()
            .putStringSet(KEY_TAG_NAMES, tagNames)
            .putStringSet(tagMediaKey(cleanTag), encodeIds(updatedIds))
            .apply()
        refresh()
    }

    fun removeTag(ids: Set<MediaId>, tag: String) {
        if (ids.isEmpty()) return
        val remaining = tagMedia(tag) - ids
        val editor = preferences.edit()
        if (remaining.isEmpty()) {
            editor
                .putStringSet(KEY_TAG_NAMES, tagNames() - tag)
                .remove(tagMediaKey(tag))
        } else {
            editor.putStringSet(tagMediaKey(tag), encodeIds(remaining))
        }
        editor.apply()
        refresh()
    }

    fun removeMissingMedia(availableIds: Set<MediaId>) {
        val snapshot = state.value
        val editor = preferences.edit()
        snapshot.collections.forEach { collection ->
            editor.putStringSet(
                collectionMediaKey(collection.id),
                encodeIds(collection.mediaIds.intersect(availableIds)),
            )
        }
        snapshot.allTags.forEach { tag ->
            val remaining = tagMedia(tag).intersect(availableIds)
            if (remaining.isEmpty()) {
                editor.remove(tagMediaKey(tag))
            } else {
                editor.putStringSet(tagMediaKey(tag), encodeIds(remaining))
            }
        }
        val remainingTags = snapshot.allTags.filterTo(linkedSetOf()) { tag ->
            tagMedia(tag).any { it in availableIds }
        }
        editor
            .putStringSet(KEY_TAG_NAMES, remainingTags)
            .putStringSet(KEY_ARCHIVED_IDS, encodeIds(snapshot.archivedIds.intersect(availableIds)))
            .apply()
        refresh()
    }

    fun exportJson(): JSONObject = JSONObject().apply {
        put("schema", BACKUP_SCHEMA)
        put("collections", JSONArray().apply {
            state.value.collections.forEach { collection ->
                put(JSONObject().apply {
                    put("id", collection.id)
                    put("name", collection.name)
                    put("createdAtMillis", collection.createdAtMillis)
                    put("mediaIds", JSONArray(collection.mediaIds.map { it.value }))
                })
            }
        })
        put("tags", JSONObject().apply {
            state.value.allTags.forEach { tag ->
                put(tag, JSONArray(tagMedia(tag).map { it.value }))
            }
        })
        put("archivedIds", JSONArray(state.value.archivedIds.map { it.value }))
    }

    fun importJson(root: JSONObject): Result<Unit> = runCatching {
        require(root.optInt("schema", 0) == BACKUP_SCHEMA) { "Unsupported metadata backup" }
        val editor = preferences.edit().clear()
        val collectionIds = linkedSetOf<String>()
        root.optJSONArray("collections")?.let { collections ->
            for (index in 0 until collections.length()) {
                val item = collections.getJSONObject(index)
                val id = item.optString("id").ifBlank { UUID.randomUUID().toString() }
                val name = item.getString("name").trim()
                if (name.isEmpty()) continue
                collectionIds += id
                editor
                    .putString(collectionNameKey(id), name)
                    .putLong(collectionCreatedKey(id), item.optLong("createdAtMillis", System.currentTimeMillis()))
                    .putStringSet(collectionMediaKey(id), encodeIds(item.optJSONArray("mediaIds").toMediaIds()))
            }
        }
        editor.putStringSet(KEY_COLLECTION_IDS, collectionIds)

        val tagNames = linkedSetOf<String>()
        root.optJSONObject("tags")?.let { tags ->
            tags.keys().forEach { tag ->
                val cleanTag = tag.trim()
                if (cleanTag.isNotEmpty()) {
                    tagNames += cleanTag
                    editor.putStringSet(tagMediaKey(cleanTag), encodeIds(tags.optJSONArray(tag).toMediaIds()))
                }
            }
        }
        editor
            .putStringSet(KEY_TAG_NAMES, tagNames)
            .putStringSet(KEY_ARCHIVED_IDS, encodeIds(root.optJSONArray("archivedIds").toMediaIds()))
            .apply()
        refresh()
    }

    private fun refresh() {
        state.value = load()
    }

    private fun load(): LibraryState {
        val tagsById = linkedMapOf<MediaId, MutableSet<String>>()
        tagNames().forEach { tag ->
            tagMedia(tag).forEach { id -> tagsById.getOrPut(id, ::linkedSetOf).add(tag) }
        }
        return LibraryState(
            collections = collectionIds().mapNotNull { id ->
                val name = preferences.getString(collectionNameKey(id), null) ?: return@mapNotNull null
                MediaCollection(
                    id = id,
                    name = name,
                    mediaIds = collectionMedia(id),
                    createdAtMillis = preferences.getLong(collectionCreatedKey(id), 0L),
                )
            }.sortedWith(compareBy<MediaCollection> { it.name.lowercase() }.thenBy { it.createdAtMillis }),
            tagsByMediaId = tagsById.mapValues { it.value.toSet() },
            archivedIds = decodeIds(preferences.getStringSet(KEY_ARCHIVED_IDS, emptySet())),
        )
    }

    private fun collectionIds(): Set<String> =
        preferences.getStringSet(KEY_COLLECTION_IDS, emptySet()).orEmpty().toSet()

    private fun collectionMedia(collectionId: String): Set<MediaId> =
        decodeIds(preferences.getStringSet(collectionMediaKey(collectionId), emptySet()))

    private fun tagNames(): Set<String> =
        preferences.getStringSet(KEY_TAG_NAMES, emptySet()).orEmpty().toSet()

    private fun tagMedia(tag: String): Set<MediaId> =
        decodeIds(preferences.getStringSet(tagMediaKey(tag), emptySet()))

    private fun tagMediaKey(tag: String): String {
        val encoded = Base64.encodeToString(
            tag.toByteArray(StandardCharsets.UTF_8),
            Base64.NO_WRAP or Base64.URL_SAFE,
        )
        return "$TAG_MEDIA_PREFIX$encoded"
    }

    private companion object {
        const val PREFERENCES_NAME = "foto_xplorr_library"
        const val BACKUP_SCHEMA = 1
        const val KEY_COLLECTION_IDS = "collection_ids"
        const val KEY_TAG_NAMES = "tag_names"
        const val KEY_ARCHIVED_IDS = "archived_ids"
        const val TAG_MEDIA_PREFIX = "tag_media:"
        fun collectionNameKey(id: String) = "collection_name:$id"
        fun collectionMediaKey(id: String) = "collection_media:$id"
        fun collectionCreatedKey(id: String) = "collection_created:$id"
    }
}

internal fun encodeIds(ids: Set<MediaId>): Set<String> = ids
    .asSequence()
    .map { it.value }
    .filter { it >= 0L }
    .sorted()
    .mapTo(linkedSetOf(), Long::toString)

internal fun decodeIds(values: Set<String>?): Set<MediaId> = values
    .orEmpty()
    .mapNotNullTo(linkedSetOf()) { raw -> raw.toLongOrNull()?.takeIf { it >= 0L }?.let(::MediaId) }

private fun JSONArray?.toMediaIds(): Set<MediaId> {
    if (this == null) return emptySet()
    return buildSet {
        for (index in 0 until length()) {
            val value = optLong(index, -1L)
            if (value >= 0L) add(MediaId(value))
        }
    }
}
