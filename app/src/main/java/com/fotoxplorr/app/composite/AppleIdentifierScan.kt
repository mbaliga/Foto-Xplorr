package com.fotoxplorr.app.composite

import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.formats.composite.AppleIdentifiers
import com.fotoxplorr.core.model.MediaId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * What has been read for Apple identifiers in this process, positives and negatives alike, keyed by
 * media id and checked against the file's size and modification second (a re-saved file is read
 * again). In memory only. [capacity] is far above [CompositeGrouping.SCAN_CAP], so one pass never
 * pushes out what an earlier pass read: a library is opened once per process, not once per change.
 * Past [capacity] the least recently used entry goes. Thread-safe.
 */
internal class AppleIdentifierMemory(private val capacity: Int = CAPACITY) {
    private class Entry(val size: Long, val modified: Long, val value: AppleIdentifiers?)

    private val entries = object : LinkedHashMap<Long, Entry>(1024, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Entry>?): Boolean = size > capacity
    }

    val size: Int @Synchronized get() = entries.size

    /** What was read for this version of [asset]; null when it has not been read yet. */
    @Synchronized
    fun get(asset: MediaAsset): CompositeProbe.Optional? {
        val e = entries[asset.id.value] ?: return null
        if (e.size != asset.sizeBytes || e.modified != asset.dateModifiedSeconds) return null
        return if (e.value == null) NONE else CompositeProbe.Optional(e.value)
    }

    @Synchronized
    fun put(asset: MediaAsset, value: AppleIdentifiers?) {
        entries[asset.id.value] = Entry(asset.sizeBytes, asset.dateModifiedSeconds, value)
    }

    /** The cached value of [asset], or [read] it once and remember the answer (a failed read counts as none). */
    fun getOrRead(asset: MediaAsset, read: (MediaAsset) -> AppleIdentifiers?): AppleIdentifiers? {
        get(asset)?.let { return it.value }
        val value = runCatching { read(asset) }.getOrNull()
        put(asset, value)
        return value
    }

    @Synchronized
    fun clear() = entries.clear()

    companion object {
        /** Ids are 8 bytes and most entries are negatives: 100k entries cost a few MB at worst. */
        const val CAPACITY = 100_000
        private val NONE = CompositeProbe.Optional(null)
    }
}

/**
 * One regrouping pass of the grid over [library]: groups from what [memory] already knows are
 * published at once, then only the candidates never read in this process are opened (newest first,
 * at most [CompositeGrouping.SCAN_CAP]), and the groups are published again after each
 * [PUBLISH_EVERY] files that found something. A search keystroke or a time-range change narrows
 * [library] to files already read, so it opens nothing. [read] opens one file; it runs on
 * Dispatchers.IO, grouping runs on Dispatchers.Default, and [publish] runs in the caller's context.
 */
internal class AppleIdentifierPass(
    private val memory: AppleIdentifierMemory,
    private val read: (MediaAsset) -> AppleIdentifiers?,
) {
    suspend fun run(library: List<MediaAsset>, pairLive: Boolean, groupBursts: Boolean, publish: (CompositeGroups) -> Unit) {
        val apple = HashMap<MediaId, AppleIdentifiers>()
        val known = HashSet<MediaId>()
        withContext(Dispatchers.Default) {
            for (asset in library) {
                val cached = memory.get(asset) ?: continue
                known += asset.id
                cached.value?.let { apple[asset.id] = it }
            }
        }
        publish(withContext(Dispatchers.Default) { CompositeGrouping.groups(library, apple, pairLive, groupBursts) })
        val toScan = withContext(Dispatchers.Default) { CompositeGrouping.scanCandidates(library, known) }
        for (batch in toScan.chunked(PUBLISH_EVERY)) {
            val found = withContext(Dispatchers.IO) {
                var any = false
                for (asset in batch) {
                    ensureActive()
                    memory.getOrRead(asset, read)?.let {
                        apple[asset.id] = it
                        any = true
                    }
                }
                any
            }
            if (found) publish(withContext(Dispatchers.Default) { CompositeGrouping.groups(library, HashMap(apple), pairLive, groupBursts) })
        }
    }

    companion object {
        const val PUBLISH_EVERY = 2_000
    }
}
