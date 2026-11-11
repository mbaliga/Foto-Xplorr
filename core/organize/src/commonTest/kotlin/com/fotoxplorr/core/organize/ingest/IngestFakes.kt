package com.fotoxplorr.core.organize.ingest

/** Simulated kill: an Error, so the runner (which catches Exception) cannot swallow it. */
class Kill : Error("killed")

class Chaos(var limit: Int = Int.MAX_VALUE) {
    var ticks = 0
    fun tick() { ticks++; if (ticks >= limit) throw Kill() }
}

class MemReader(private val bytes: ByteArray, private val maxPerRead: Int = Int.MAX_VALUE) : IngestReader {
    var closed = false
    override val size: Long get() = bytes.size.toLong()
    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= bytes.size) return -1
        val n = minOf(length, bytes.size - position.toInt(), maxPerRead)
        bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + n)
        return n
    }
    override fun close() { closed = true }
}

fun content(seed: Int, size: Int): ByteArray {
    var x = seed * 2654435761L + 12345
    return ByteArray(size) { x = x * 6364136223846793005L + 1442695040888963407L; (x ushr 33).toByte() }
}

class FakeSource(override val canDelete: Boolean = true) : IngestSource {
    val items = ArrayList<IngestItem>()
    val data = HashMap<String, ByteArray>()
    val deleted = ArrayList<String>()
    var writes = 0

    fun add(item: IngestItem, bytes: ByteArray) {
        items.add(item.copy(sizeBytes = bytes.size.toLong())); data[item.id] = bytes
    }
    override fun list() = items.toList()
    override fun open(item: IngestItem): IngestReader = MemReader(data.getValue(item.id))
    override fun delete(item: IngestItem): Boolean { deleted.add(item.id); return true }
}

/** In-memory destination, case-insensitive like FAT/exFAT. */
class MemTarget(private val chaos: Chaos? = null) : IngestTarget {
    val files = LinkedHashMap<String, ByteArray>()      // lower-cased path -> bytes
    val names = LinkedHashMap<String, String>()          // lower-cased path -> real path
    var failFor: (String) -> Boolean = { false }
    var corrupt: (String) -> Boolean = { false }
    val overwrites = ArrayList<String>()

    override fun exists(relPath: String) = relPath.lowercase() in files
    override fun write(relPath: String, reader: IngestReader): Long {
        if (failFor(relPath)) throw IllegalStateException("disk error")
        chaos?.tick()
        if (exists(relPath)) { overwrites.add(relPath); throw IllegalStateException("exists") }
        val out = ByteArray(reader.size.toInt())
        var p = 0
        while (p < out.size) { val n = reader.read(p.toLong(), out, p, out.size - p); if (n <= 0) break; p += n }
        if (corrupt(relPath)) out[out.size / 2] = (out[out.size / 2] + 1).toByte()
        files[relPath.lowercase()] = out; names[relPath.lowercase()] = relPath
        chaos?.tick()
        return out.size.toLong()
    }
    override fun open(relPath: String): IngestReader? = files[relPath.lowercase()]?.let { MemReader(it) }
    override fun discardOwnUnverified(relPath: String) { files.remove(relPath.lowercase()); names.remove(relPath.lowercase()) }

    fun put(relPath: String, bytes: ByteArray) { files[relPath.lowercase()] = bytes; names[relPath.lowercase()] = relPath }
}

class ChaosJournal(private val inner: IngestJournal, private val chaos: Chaos) : IngestJournal {
    override fun append(entry: JournalEntry) {
        chaos.tick()          // killed before the line is stored
        inner.append(entry)
        chaos.tick()          // killed right after
    }
    override fun entries() = inner.entries()
}
