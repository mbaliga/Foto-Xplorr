package com.fotoxplorr.app.viewer.deepzoom

/**
 * A least-recently-used cache of decoded tiles, budgeted in BYTES rather than count (a tile at a
 * cut-off edge is smaller than one in the middle). Generic over the value so the eviction order
 * and the budget are checked on the JVM with plain objects; the viewer instantiates it with
 * bitmaps and a size of `allocationByteCount`. Not thread-safe: the viewer touches it only from
 * the main thread (decoded bitmaps are handed over, then stored).
 *
 * A value bigger than the whole budget is not stored (it would evict everything and then itself).
 * Evicted bitmaps are NOT recycled by the cache: one may still be mid-draw, so the garbage
 * collector owns them.
 */
class TileCache<V : Any>(private val budgetBytes: Long, private val sizeOf: (V) -> Long) {
    private val map = LinkedHashMap<TileKey, V>(32, 0.75f, true)
    private var used = 0L

    val bytes: Long get() = used
    val count: Int get() = map.size

    operator fun get(key: TileKey): V? = map[key]

    fun contains(key: TileKey): Boolean = map.containsKey(key)

    fun put(key: TileKey, value: V) {
        val size = sizeOf(value).coerceAtLeast(0L)
        remove(key)
        if (size > budgetBytes) return
        map[key] = value
        used += size
        trim()
    }

    fun remove(key: TileKey) {
        map.remove(key)?.let { used -= sizeOf(it).coerceAtLeast(0L) }
    }

    fun clear() {
        map.clear()
        used = 0L
    }

    /** Keys currently held, least recently used first. */
    fun keys(): List<TileKey> = map.keys.toList()

    private fun trim() {
        val it = map.entries.iterator()
        while (used > budgetBytes && it.hasNext()) {
            val e = it.next()
            used -= sizeOf(e.value).coerceAtLeast(0L)
            it.remove()
        }
    }
}
