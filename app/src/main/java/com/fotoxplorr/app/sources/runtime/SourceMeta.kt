package com.fotoxplorr.app.sources.runtime

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What the person chose for one source, and what the last index said, kept beside (not inside)
 * `fotoz-sources.db`: the database has no columns for these and it is not this unit's to change.
 * Rebuildable: losing it resets the choices to their defaults and loses no photo.
 *
 * @param included whether the source's photos are shown in the gallery (the rows stay either way).
 * @param indexOnConnect re-read the source on its own when it is there (ADR-020, owner Q12: default off).
 * @param tookGrant true when adding the source is what made Android keep the folder grant, so
 *   removing the source may give it back; false when another feature already held it, and it is then never released here.
 * @param lastIndexedMs end of the last COMPLETED walk, 0 when there has been none.
 * @param lastError one sentence about the last walk that did not finish, null after a good one.
 */
data class SourceMeta(
    val included: Boolean = true,
    val indexOnConnect: Boolean = false,
    val tookGrant: Boolean = false,
    val lastIndexedMs: Long = 0L,
    val lastError: String? = null,
)

/** Where [SourceMeta] lives. An interface so the runtime's rules run in JVM tests without a preferences file. */
interface SourceMetaStore {
    fun all(): StateFlow<Map<Long, SourceMeta>>
    fun get(sourceRow: Long): SourceMeta
    fun update(sourceRow: Long, change: (SourceMeta) -> SourceMeta)
    fun remove(sourceRow: Long)
}

/** A [SourceMetaStore] that only remembers in memory; what tests use. */
class InMemorySourceMetaStore : SourceMetaStore {
    private val state = MutableStateFlow<Map<Long, SourceMeta>>(emptyMap())
    override fun all(): StateFlow<Map<Long, SourceMeta>> = state.asStateFlow()
    override fun get(sourceRow: Long): SourceMeta = state.value[sourceRow] ?: SourceMeta()
    override fun update(sourceRow: Long, change: (SourceMeta) -> SourceMeta) {
        state.update { it + (sourceRow to change(it[sourceRow] ?: SourceMeta())) }
    }
    override fun remove(sourceRow: Long) {
        state.update { it - sourceRow }
    }
}

/**
 * [SourceMetaStore] over one SharedPreferences file of its own, `fotoz-sources-meta`. Reading it
 * writes nothing, and nothing is written until a source exists, so a phone with no sources never
 * gets the file. A new file: no existing store is read or changed.
 */
class PrefsSourceMetaStore(context: Context) : SourceMetaStore {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val state: MutableStateFlow<Map<Long, SourceMeta>> by lazy { MutableStateFlow(load()) }

    override fun all(): StateFlow<Map<Long, SourceMeta>> = state.asStateFlow()

    override fun get(sourceRow: Long): SourceMeta = state.value[sourceRow] ?: SourceMeta()

    @Synchronized
    override fun update(sourceRow: Long, change: (SourceMeta) -> SourceMeta) {
        val next = change(get(sourceRow))
        prefs.edit()
            .putBoolean("$sourceRow.included", next.included)
            .putBoolean("$sourceRow.onConnect", next.indexOnConnect)
            .putBoolean("$sourceRow.tookGrant", next.tookGrant)
            .putLong("$sourceRow.lastIndexed", next.lastIndexedMs)
            .apply { if (next.lastError == null) remove("$sourceRow.error") else putString("$sourceRow.error", next.lastError) }
            .apply()
        state.update { it + (sourceRow to next) }
    }

    @Synchronized
    override fun remove(sourceRow: Long) {
        if (sourceRow !in state.value) return
        val e = prefs.edit()
        for (k in KEYS) e.remove("$sourceRow.$k")
        e.apply()
        state.update { it - sourceRow }
    }

    private fun load(): Map<Long, SourceMeta> {
        val rows = prefs.all.keys.mapNotNull { it.substringBefore('.', "").toLongOrNull() }.toSet()
        return rows.associateWith { r ->
            SourceMeta(
                included = prefs.getBoolean("$r.included", true),
                indexOnConnect = prefs.getBoolean("$r.onConnect", false),
                tookGrant = prefs.getBoolean("$r.tookGrant", false),
                lastIndexedMs = prefs.getLong("$r.lastIndexed", 0L),
                lastError = prefs.getString("$r.error", null),
            )
        }
    }

    private companion object {
        const val FILE = "fotoz-sources-meta"
        val KEYS = listOf("included", "onConnect", "tookGrant", "lastIndexed", "error")
    }
}
