package com.fotoxplorr.core.organize.fileops

/**
 * Append-only record of what a run has done, so an interrupted run can resume (ADR-022 point 2).
 * Entries are written BEFORE the action they announce ([Started]) and AFTER the one they confirm
 * ([Copied], [Finished]); resume trusts only confirmed entries and re-checks the disk for the rest.
 */
sealed interface JournalEntry {
    val stepId: String

    /** About to write [temp] (null for renames/trash) for [target]. */
    data class Started(override val stepId: String, val target: String, val temp: String?) : JournalEntry

    /** An existing target was sent to the trash as [trashId] (replace), before anything was written. */
    data class Trashed(override val stepId: String, val trashId: String) : JournalEntry

    /** The temp file was verified and renamed to its final name. Cross-source move still owes the original's trashing. */
    data class Copied(override val stepId: String, val target: String, val size: Long, val sha256: String) : JournalEntry

    data class Finished(
        override val stepId: String,
        val status: StepStatus,
        val target: String?,
        val error: FileOpsError?,
        val trashId: String?,
    ) : JournalEntry
}

class JournalState(entries: List<JournalEntry>) {
    private val started = HashMap<String, JournalEntry.Started>()
    private val trashed = HashMap<String, String>()
    private val copied = HashMap<String, JournalEntry.Copied>()
    private val finished = HashMap<String, JournalEntry.Finished>()

    init {
        for (e in entries) {
            when (e) {
                is JournalEntry.Started -> started[e.stepId] = e
                is JournalEntry.Trashed -> trashed[e.stepId] = e.trashId
                is JournalEntry.Copied -> copied[e.stepId] = e
                is JournalEntry.Finished -> finished[e.stepId] = e
            }
        }
    }

    /** DONE or SKIPPED are final; FAILED and CANCELLED are retried on resume. */
    fun terminal(stepId: String): JournalEntry.Finished? =
        finished[stepId]?.takeIf { it.status == StepStatus.DONE || it.status == StepStatus.SKIPPED }

    fun started(stepId: String): JournalEntry.Started? = started[stepId]
    fun trashedId(stepId: String): String? = trashed[stepId]
    fun copied(stepId: String): JournalEntry.Copied? = copied[stepId]

    /** Temp files announced by steps that never reached a Copied/Finished entry: resume deletes them. */
    fun danglingTemps(): List<String> = started.values
        .filter { it.temp != null && copied[it.stepId] == null && terminal(it.stepId) == null }
        .mapNotNull { it.temp }
}

interface Journal {
    fun load(planId: String): JournalState
    fun append(planId: String, entry: JournalEntry)
    fun clear(planId: String)
}

/** One tab-separated line per entry in `<dir>/<planId>.journal`. A torn last line (a crash mid-append) is ignored. */
class FileJournal(private val fs: FileSystem, private val dir: String) : Journal {
    private fun path(planId: String) = FilePaths.join(dir, "$planId.journal")

    override fun load(planId: String): JournalState {
        val p = path(planId)
        if (fs.stat(p) == null) return JournalState(emptyList())
        val text = fs.readAllBytes(p).decodeToString()
        val lines = text.split('\n')
        // The final element is either empty (clean end) or a torn line without its newline: drop it either way.
        val entries = lines.dropLast(1).mapNotNull { parse(it) }
        return JournalState(entries)
    }

    override fun append(planId: String, entry: JournalEntry) {
        fs.mkdirs(dir)
        val sink = fs.openAppend(path(planId))
        try {
            val bytes = (format(entry) + "\n").encodeToByteArray()
            sink.write(bytes, 0, bytes.size)
        } finally {
            sink.close()
        }
    }

    override fun clear(planId: String) {
        if (fs.stat(path(planId)) != null) fs.delete(path(planId))
    }

    private fun esc(s: String?): String = if (s == null) "-" else "=" + s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

    private fun unesc(s: String): String? {
        if (s == "-") return null
        val body = s.removePrefix("=")
        val sb = StringBuilder()
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c == '\\' && i + 1 < body.length) {
                sb.append(when (body[i + 1]) { 't' -> '\t'; 'n' -> '\n'; else -> body[i + 1] })
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private fun format(e: JournalEntry): String = when (e) {
        is JournalEntry.Started -> listOf("S", e.stepId, esc(e.target), esc(e.temp))
        is JournalEntry.Trashed -> listOf("T", e.stepId, esc(e.trashId))
        is JournalEntry.Copied -> listOf("C", e.stepId, esc(e.target), e.size.toString(), e.sha256)
        is JournalEntry.Finished -> listOf("F", e.stepId, e.status.name, esc(e.target), esc(e.error?.name), esc(e.trashId))
    }.joinToString("\t")

    private fun parse(line: String): JournalEntry? = try {
        val f = line.split('\t')
        when (f[0]) {
            "S" -> if (f.size == 4) JournalEntry.Started(f[1], unesc(f[2])!!, unesc(f[3])) else null
            "T" -> if (f.size == 3) JournalEntry.Trashed(f[1], unesc(f[2])!!) else null
            "C" -> if (f.size == 5) JournalEntry.Copied(f[1], unesc(f[2])!!, f[3].toLong(), f[4]) else null
            "F" -> if (f.size == 6) {
                JournalEntry.Finished(f[1], StepStatus.valueOf(f[2]), unesc(f[3]), unesc(f[4])?.let { FileOpsError.valueOf(it) }, unesc(f[5]))
            } else null
            else -> null
        }
    } catch (e: Exception) {
        null
    }
}
