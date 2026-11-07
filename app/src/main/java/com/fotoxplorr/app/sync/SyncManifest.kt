package com.fotoxplorr.app.sync

import java.util.Locale

/** A file the destination is known to hold whole, as the last run recorded it. */
data class SyncDone(
    val key: String,
    val sizeBytes: Long,
    val modifiedSeconds: Long,
    val remotePath: String,
    val remoteSize: Long,
    /** SHA-256 of the local bytes in lower-case hex, or null when it was never worked out. */
    val sha256: String?,
    val atMillis: Long,
)

/** A file whose upload was started and not confirmed: the next run may overwrite [remotePath] (it is ours). */
data class SyncBegun(val key: String, val remotePath: String, val sizeBytes: Long, val modifiedSeconds: Long)

/**
 * What one destination already holds, according to our own records. Per destination (a manifest is
 * never shared between two), plain text, append-only while a run goes, with no secret in it.
 *
 * Format (UTF-8, `\n` line ends, tab-separated, `%`/tab/newline percent-escaped inside fields):
 * ```
 * fotoz-sync-manifest<TAB>1
 * B<TAB>key<TAB>remotePath<TAB>size<TAB>modifiedSeconds
 * D<TAB>key<TAB>size<TAB>modifiedSeconds<TAB>remotePath<TAB>remoteSize<TAB>sha256-or-dash<TAB>atMillis
 * ```
 * Reading is tolerant: a line that does not end in a newline (a write cut short by a crash), a line
 * with the wrong number of fields or an unparsable number, and a line of a kind this version does
 * not know are skipped and counted, never fatal. Later lines win. A `D` for a key closes its `B`.
 */
class SyncManifest private constructor(
    private val doneByKey: LinkedHashMap<String, SyncDone>,
    private val begunByKey: LinkedHashMap<String, SyncBegun>,
    private val unknownLines: List<String>,
    val ignoredLines: Int,
) {
    constructor() : this(LinkedHashMap(), LinkedHashMap(), emptyList(), 0)

    val done: Collection<SyncDone> get() = doneByKey.values
    val begun: Collection<SyncBegun> get() = begunByKey.values

    fun doneFor(key: String): SyncDone? = doneByKey[key]
    fun begunFor(key: String): SyncBegun? = begunByKey[key]

    /** True when [key] is recorded with exactly this size and modified time: nothing to send. */
    fun isUpToDate(key: String, sizeBytes: Long, modifiedSeconds: Long): Boolean =
        doneByKey[key]?.let { it.sizeBytes == sizeBytes && it.modifiedSeconds == modifiedSeconds } == true

    /** Another key recorded with this size and modified time: a probable rename or move. */
    fun lookalikesOf(key: String, sizeBytes: Long, modifiedSeconds: Long): List<SyncDone> =
        signatureIndex()[sizeBytes to modifiedSeconds].orEmpty().filter { it.key != key }

    // Built on first use (the planner asks once per candidate; scanning every entry each time was quadratic).
    private var bySignature: HashMap<Pair<Long, Long>, MutableList<SyncDone>>? = null

    private fun signatureIndex(): Map<Pair<Long, Long>, List<SyncDone>> = bySignature ?: HashMap<Pair<Long, Long>, MutableList<SyncDone>>().also { index ->
        doneByKey.values.forEach { index.getOrPut(it.sizeBytes to it.modifiedSeconds) { ArrayList() } += it }
        bySignature = index
    }

    /** Every remote path we know of, lower-cased, for collision checks. */
    fun remotePathsLower(): Set<String> {
        val all = HashSet<String>()
        doneByKey.values.forEach { all += it.remotePath.lowercase(Locale.ROOT) }
        begunByKey.values.forEach { all += it.remotePath.lowercase(Locale.ROOT) }
        return all
    }

    /** Remembers that an upload to [SyncBegun.remotePath] started. Mutates; one run writes at a time. */
    fun recordBegun(entry: SyncBegun) {
        begunByKey[entry.key] = entry
    }

    /** Remembers a confirmed upload and closes the key's open `B`. */
    fun recordDone(entry: SyncDone) {
        bySignature = null
        doneByKey[entry.key] = entry
        begunByKey.remove(entry.key)
    }

    /** The whole manifest as text (a compaction: one line per fact, in key order of first sight). */
    fun encode(): String = buildString {
        append(SyncManifestCodec.HEADER).append('\n')
        doneByKey.values.forEach { append(SyncManifestCodec.doneLine(it)) }
        begunByKey.values.forEach { append(SyncManifestCodec.begunLine(it)) }
        unknownLines.forEach { append(it).append('\n') }
    }

    companion object {
        fun decode(text: String): SyncManifest = SyncManifestCodec.decode(text)

        internal fun of(
            done: LinkedHashMap<String, SyncDone>,
            begun: LinkedHashMap<String, SyncBegun>,
            unknown: List<String>,
            ignored: Int,
        ) = SyncManifest(done, begun, unknown, ignored)
    }
}

object SyncManifestCodec {
    const val HEADER = "fotoz-sync-manifest\t1"

    fun doneLine(e: SyncDone): String =
        listOf("D", e.key, e.sizeBytes, e.modifiedSeconds, e.remotePath, e.remoteSize, e.sha256 ?: "-", e.atMillis)
            .joinToString("\t") { escape(it.toString()) }
            .plus('\n')

    fun begunLine(e: SyncBegun): String =
        listOf("B", e.key, e.remotePath, e.sizeBytes, e.modifiedSeconds).joinToString("\t") { escape(it.toString()) } + '\n'

    fun decode(text: String): SyncManifest {
        val done = LinkedHashMap<String, SyncDone>()
        val begun = LinkedHashMap<String, SyncBegun>()
        val unknown = ArrayList<String>()
        var ignored = 0
        // Only complete lines count; the tail after the last newline is a torn write.
        val lastNewline = text.lastIndexOf('\n')
        if (text.isNotEmpty() && lastNewline < 0) return SyncManifest.of(done, begun, unknown, 1)
        val complete = if (lastNewline >= 0) text.substring(0, lastNewline) else ""
        if (lastNewline >= 0 && lastNewline < text.length - 1) ignored++
        for (raw in complete.split('\n')) {
            val line = raw.removeSuffix("\r")
            if (line.isEmpty()) continue
            val fields = line.split('\t')
            when (fields[0]) {
                "fotoz-sync-manifest" -> Unit
                "D" -> {
                    val entry = parseDone(fields)
                    if (entry == null) ignored++ else {
                        done[entry.key] = entry
                        begun.remove(entry.key)
                    }
                }
                "B" -> {
                    val entry = parseBegun(fields)
                    if (entry == null) ignored++ else begun[entry.key] = entry
                }
                else -> {
                    unknown += line
                    ignored++
                }
            }
        }
        return SyncManifest.of(done, begun, unknown, ignored)
    }

    private fun parseDone(f: List<String>): SyncDone? {
        if (f.size != 8) return null
        val key = unescape(f[1])
        val path = unescape(f[4])
        if (key.isEmpty() || !RemoteLayout.isSafePath(path)) return null
        return SyncDone(
            key = key,
            sizeBytes = f[2].toLongOrNull() ?: return null,
            modifiedSeconds = f[3].toLongOrNull() ?: return null,
            remotePath = path,
            remoteSize = f[5].toLongOrNull() ?: return null,
            sha256 = f[6].takeIf { it != "-" && it.matches(HEX64) },
            atMillis = f[7].toLongOrNull() ?: return null,
        )
    }

    private fun parseBegun(f: List<String>): SyncBegun? {
        if (f.size != 5) return null
        val key = unescape(f[1])
        val path = unescape(f[2])
        if (key.isEmpty() || !RemoteLayout.isSafePath(path)) return null
        return SyncBegun(key, path, f[3].toLongOrNull() ?: return null, f[4].toLongOrNull() ?: return null)
    }

    internal fun escape(text: String): String {
        if (text.none { it == '%' || it == '\t' || it == '\n' || it == '\r' }) return text
        return buildString {
            for (ch in text) {
                when (ch) {
                    '%' -> append("%25")
                    '\t' -> append("%09")
                    '\n' -> append("%0A")
                    '\r' -> append("%0D")
                    else -> append(ch)
                }
            }
        }
    }

    internal fun unescape(text: String): String {
        if (!text.contains('%')) return text
        return text.replace("%09", "\t").replace("%0A", "\n").replace("%0D", "\r").replace("%25", "%")
    }

    private val HEX64 = Regex("[0-9a-f]{64}")
}
