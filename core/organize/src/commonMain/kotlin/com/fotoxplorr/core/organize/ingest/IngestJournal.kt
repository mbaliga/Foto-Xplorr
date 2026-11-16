package com.fotoxplorr.core.organize.ingest

enum class JournalState {
    /** The planned path of a file (destIndex -1), written before any copying so a resume reuses names. */
    PLANNED,
    COPIED,
    VERIFIED,
    FAILED,
    CARD_DELETED,
}

/**
 * One journal line. [fingerprint] and [size] pin a VERIFIED / CARD_DELETED entry to the exact bytes it verified
 * (the plan item's [CheapFingerprint.key] and size): item ids are only stable within one card, so an entry
 * is trusted for a plan item only when both match. [scope] names the import session (card) the entry belongs
 * to, so two cards that reuse the same item ids never read each other's lines. Old lines have all three empty.
 */
data class JournalEntry(
    val itemId: String,
    val destIndex: Int,
    val state: JournalState,
    val relPath: String,
    val detail: String = "",
    val fingerprint: String = "",
    val size: Long = -1L,
    val scope: String = "",
)

/** Append-only; the LAST entry for an (item, destination) wins. */
interface IngestJournal {
    fun append(entry: JournalEntry)

    fun entries(): List<JournalEntry>
}

class InMemoryIngestJournal : IngestJournal {
    private val list = ArrayList<JournalEntry>()
    override fun append(entry: JournalEntry) { list.add(entry) }
    override fun entries(): List<JournalEntry> = list.toList()
}

/**
 * Journal over any line-oriented store (a file the app appends to and flushes per line). A kill in
 * the middle of a line leaves a torn last line, which [JournalCodec.decode] rejects, so the entry
 * counts as never written and that step is simply redone.
 */
class LineIngestJournal(
    private val appendLine: (String) -> Unit,
    private val readLines: () -> List<String>,
) : IngestJournal {
    override fun append(entry: JournalEntry) = appendLine(JournalCodec.encode(entry))
    override fun entries(): List<JournalEntry> = readLines().mapNotNull(JournalCodec::decode)
}

object JournalCodec {
    private const val END = "#"

    fun encode(e: JournalEntry): String =
        listOf(e.itemId, e.destIndex.toString(), e.state.name, e.relPath, e.detail, e.fingerprint, e.size.toString(), e.scope)
            .joinToString("\t") { esc(it) } + "\t" + END

    /** Reads the current 9-field line and the older 6-field one (no fingerprint, size or scope). */
    fun decode(line: String): JournalEntry? {
        val parts = line.split('\t')
        if (parts.size != 6 && parts.size != 9) return null
        if (parts.last() != END) return null
        val state = JournalState.entries.firstOrNull { it.name == parts[2] } ?: return null
        val dest = parts[1].toIntOrNull() ?: return null
        if (parts.size == 6) return JournalEntry(unesc(parts[0]), dest, state, unesc(parts[3]), unesc(parts[4]))
        val size = parts[6].toLongOrNull() ?: return null
        return JournalEntry(
            unesc(parts[0]), dest, state, unesc(parts[3]), unesc(parts[4]), unesc(parts[5]), size, unesc(parts[7]),
        )
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")

    private fun unesc(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                sb.append(when (s[i + 1]) { 't' -> '\t'; 'n' -> '\n'; 'r' -> '\r'; else -> s[i + 1] })
                i += 2
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }
}

/** The last state per (item, destination) of one import [scope], keyed `itemId|dest`. */
internal fun IngestJournal.latest(scope: String = ""): MutableMap<String, JournalEntry> {
    val m = HashMap<String, JournalEntry>()
    for (e in entries()) if (e.scope == scope) m["${e.itemId}|${e.destIndex}"] = e
    return m
}

/** Paths a previous run of the same import [scope] planned, to be reused verbatim when re-planning after a kill. */
fun IngestJournal.plannedPaths(scope: String = ""): Map<String, String> =
    entries().filter { it.state == JournalState.PLANNED && it.scope == scope }.associate { it.itemId to it.relPath }
