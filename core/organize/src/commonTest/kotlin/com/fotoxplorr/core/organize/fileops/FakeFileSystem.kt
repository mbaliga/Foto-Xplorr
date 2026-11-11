package com.fotoxplorr.core.organize.fileops

/** Thrown by the fake to model the process dying: the engine must NOT catch it (a real crash cleans nothing). */
class SimulatedCrash(message: String) : RuntimeException(message)

/**
 * In-memory [FileSystem] with fault injection, in commonTest so it runs on every target.
 * Volumes are roots such as "/phone", "/usb", "/sd"; a path belongs to the root it starts with.
 */
class FakeFileSystem(roots: Map<String, Long?> = mapOf("/phone" to null, "/usb" to null, "/sd" to null, "/app" to null)) : FileSystem {
    class Node(var bytes: ByteArray, var modifiedMs: Long)

    val files = LinkedHashMap<String, Node>()
    val dirs = HashSet<String>()
    private val capacity = HashMap<String, Long?>(roots)
    val unplugged = HashSet<String>()

    /** Every mutating or reading call: "op path". */
    val log = ArrayList<String>()

    /** Paths passed to [delete], in order. */
    val deleted = ArrayList<String>()

    /** Deletes that are not the engine's own bookkeeping (temp files, the superseded trash manifest). */
    fun deletedOriginals(): List<String> =
        deleted.filter { !it.endsWith(".fotoz-part") && !it.endsWith("manifest.json") && !it.endsWith("manifest.json.new") }

    var reportFree = true
    private var clock = 1L

    /** Crash (throw [SimulatedCrash]) on the call with this 1-based index counting [tick]ed operations. */
    var crashAtOp: Int? = null
    var opCount = 0

    /** Crash when this returns true for ("rename", path) style ops. */
    var crashWhen: ((String, String) -> Boolean)? = null

    /** After this many bytes WRITTEN in total, unplug [unplugRootOnWriteBudget]. */
    var writeBudget: Long? = null
    var unplugRootOnWriteBudget: String? = null
    private var written = 0L

    /** After this many bytes READ in total, unplug [unplugRootOnReadBudget]. */
    var readBudget: Long? = null
    var unplugRootOnReadBudget: String? = null
    private var readTotal = 0L

    /** Flip one byte of everything written (a bad write that is only caught by reading back). */
    var corruptWrites = false

    /** Called after each chunk is read. */
    var onRead: (() -> Unit)? = null

    init {
        for (r in roots.keys) dirs += r
    }

    fun put(path: String, content: ByteArray) {
        mkdirs(FilePaths.parent(path))
        files[path] = Node(content, clock++)
    }

    fun put(path: String, text: String) = put(path, text.encodeToByteArray())

    fun text(path: String): String? = files[path]?.bytes?.decodeToString()

    fun replug(root: String) {
        unplugged -= root
    }

    fun resetFaults() {
        crashAtOp = null
        crashWhen = null
        writeBudget = null
        readBudget = null
        corruptWrites = false
        onRead = null
        unplugged.clear()
    }

    private fun rootOf(path: String): String =
        capacity.keys.filter { path == it || path.startsWith("$it/") }.maxByOrNull { it.length }
            ?: throw FileOpsException(FileOpsError.NOT_FOUND, "no volume for $path")

    private fun alive(path: String) {
        if (rootOf(path) in unplugged) throw FileOpsException(FileOpsError.DEVICE_GONE, "unplugged")
    }

    private fun tick(op: String, path: String) {
        log += "$op $path"
        opCount++
        if (crashAtOp == opCount) throw SimulatedCrash("crash at op $opCount: $op $path")
        if (crashWhen?.invoke(op, path) == true) throw SimulatedCrash("crash when $op $path")
    }

    private fun used(root: String): Long = files.filter { it.key.startsWith("$root/") }.values.sumOf { it.bytes.size.toLong() }

    override fun stat(path: String): FileStat? {
        alive(path)
        files[path]?.let { return FileStat(it.bytes.size.toLong(), false, it.modifiedMs) }
        return if (path in dirs) FileStat(0, true, 0) else null
    }

    override fun list(dir: String): List<String> {
        alive(dir)
        val prefix = "$dir/"
        val names = LinkedHashSet<String>()
        for (p in files.keys) if (p.startsWith(prefix)) names += p.substring(prefix.length).substringBefore('/')
        for (d in dirs) if (d.startsWith(prefix)) names += d.substring(prefix.length).substringBefore('/')
        return names.toList()
    }

    override fun openRead(path: String): ByteSource {
        alive(path)
        tick("openRead", path)
        val node = files[path] ?: throw FileOpsException(FileOpsError.NOT_FOUND, path)
        val data = node.bytes
        var pos = 0
        return object : ByteSource {
            override fun read(buffer: ByteArray): Int {
                alive(path)
                tick("read", path)
                if (pos >= data.size) return -1
                val budget = readBudget
                val n = minOf(buffer.size, data.size - pos)
                data.copyInto(buffer, 0, pos, pos + n)
                pos += n
                if (unplugRootOnReadBudget?.let { path.startsWith("$it/") } != false) readTotal += n
                if (budget != null && readTotal >= budget) {
                    unplugRootOnReadBudget?.let { unplugged += it }
                    readBudget = null
                }
                onRead?.invoke()
                return n
            }

            override fun close() {}
        }
    }

    override fun openCreate(path: String): ByteSink {
        alive(path)
        tick("openCreate", path)
        if (path in files || path in dirs) throw FileOpsException(FileOpsError.EXISTS, path)
        mkdirs(FilePaths.parent(path))
        val node = Node(ByteArray(0), clock++)
        files[path] = node
        return sink(path, node)
    }

    override fun openAppend(path: String): ByteSink {
        alive(path)
        tick("openAppend", path)
        mkdirs(FilePaths.parent(path))
        val node = files.getOrPut(path) { Node(ByteArray(0), clock++) }
        return sink(path, node)
    }

    private fun sink(path: String, node: Node) = object : ByteSink {
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            alive(path)
            tick("write", path)
            val root = rootOf(path)
            val cap = capacity[root]
            if (cap != null && used(root) + length > cap) {
                throw FileOpsException(FileOpsError.NO_SPACE, "full")
            }
            val chunk = buffer.copyOfRange(offset, offset + length)
            if (corruptWrites && chunk.isNotEmpty()) chunk[0] = (chunk[0].toInt() xor 0x55).toByte()
            node.bytes += chunk
            val watched = unplugRootOnWriteBudget?.let { path.startsWith("$it/") } ?: true
            if (watched) written += length
            val b = writeBudget
            if (watched && b != null && written >= b) {
                unplugRootOnWriteBudget?.let { unplugged += it }
                writeBudget = null
                throw FileOpsException(FileOpsError.DEVICE_GONE, "unplugged mid-write")
            }
        }

        override fun close() {
            alive(path)
        }
    }

    override fun rename(from: String, to: String) {
        alive(from); alive(to)
        tick("rename", "$from -> $to")
        val node = files[from] ?: throw FileOpsException(FileOpsError.NOT_FOUND, from)
        if (to in files || to in dirs) throw FileOpsException(FileOpsError.EXISTS, to)
        if (rootOf(from) != rootOf(to)) throw FileOpsException(FileOpsError.IO, "cross-volume rename")
        mkdirs(FilePaths.parent(to))
        files.remove(from)
        files[to] = node
    }

    override fun delete(path: String) {
        alive(path)
        tick("delete", path)
        deleted += path
        files.remove(path)
    }

    override fun mkdirs(path: String) {
        if (path.isEmpty()) return
        alive(path)
        var p = path
        while (p.isNotEmpty() && p !in dirs) {
            dirs += p
            p = FilePaths.parent(p)
            if (p == "/") break
        }
    }

    override fun sourceId(path: String): String = rootOf(path)
    override fun sourceRoot(path: String): String = rootOf(path)
    override fun freeBytes(path: String): Long? {
        val root = rootOf(path)
        val cap = capacity[root] ?: return null
        return if (reportFree) cap - used(root) else null
    }

    /** Multiset of file contents (hash) anywhere on the fake, trash included. */
    fun contentHashes(excludeSuffix: String = ".journal"): List<String> =
        files.filter { !it.key.endsWith(excludeSuffix) && !it.key.endsWith(".nomedia") && !it.key.endsWith("manifest.json") && !it.key.endsWith("manifest.json.new") }
            .values.map { Sha256().also { h -> h.update(it.bytes) }.digestHex() }
}

class TestClock(var now: Long = 1_700_000_000_000L) : Clock {
    override fun nowMs(): Long = now
}

/** Deterministic pseudo-random numbers for property-style tests (no kotlin.random seeds across targets needed). */
class Lcg(seed: Long) {
    private var s = seed * 6364136223846793005L + 1442695040888963407L
    fun next(bound: Int): Int {
        s = s * 6364136223846793005L + 1442695040888963407L
        return (((s ushr 33) % bound) + bound).toInt() % bound
    }
}
