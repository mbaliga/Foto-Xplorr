package com.fotoxplorr.core.index.walker

import com.fotoxplorr.core.formats.MediaFormat

/** A directory still to be listed. [path] is the display path from the root, no leading slash, `""` for the root. */
data class PendingDirectory(val documentId: String, val path: String, val depth: Int)

/** Running totals, kept in the cursor so progress and the final summary survive a restart. */
data class WalkCounters(
    val directoriesDone: Int = 0,
    val entriesSeen: Int = 0,
    val mediaFound: Int = 0,
    val notMedia: Int = 0,
    val ignoredDirectories: Int = 0,
    val ignoredFiles: Int = 0,
    val nomediaDirectories: Int = 0,
    val hostileSkipped: Int = 0,
    val loopsSkipped: Int = 0,
    /** Directories or entries left unvisited because a depth or count cap was hit. Non-zero means the walk is NOT exhaustive. */
    val limitSkipped: Int = 0,
    val sniffReads: Int = 0,
    val sniffMismatches: Int = 0,
    val sniffFailures: Int = 0,
)

/**
 * Everything needed to continue a walk: the breadth-first queue, the directories already queued
 * (the loop guard) and the counters. Immutable; [encode] and [decode] round-trip it as text so it
 * can sit in a database column. A walk resumed from a cursor visits exactly the directories the
 * uninterrupted walk would still have visited.
 */
class WalkCursor internal constructor(
    val rootDocumentId: String,
    val pending: List<PendingDirectory>,
    internal val queued: Set<String>,
    val counters: WalkCounters,
) {
    val isDrained: Boolean get() = pending.isEmpty()

    fun encode(): String {
        val sb = StringBuilder()
        sb.append(MAGIC).append('\n')
        sb.append("R\t").append(esc(rootDocumentId)).append('\n')
        val c = counters
        sb.append("C")
        for (v in listOf(
            c.directoriesDone, c.entriesSeen, c.mediaFound, c.notMedia, c.ignoredDirectories, c.ignoredFiles,
            c.nomediaDirectories, c.hostileSkipped, c.loopsSkipped, c.limitSkipped, c.sniffReads, c.sniffMismatches,
            c.sniffFailures,
        )) sb.append('\t').append(v)
        sb.append('\n')
        for (p in pending) {
            sb.append("P\t").append(p.depth).append('\t').append(esc(p.documentId)).append('\t').append(esc(p.path)).append('\n')
        }
        for (q in queued) sb.append("Q\t").append(esc(q)).append('\n')
        return sb.toString()
    }

    companion object {
        private const val MAGIC = "fotoz-walk-1"

        fun start(rootDocumentId: String): WalkCursor =
            WalkCursor(rootDocumentId, listOf(PendingDirectory(rootDocumentId, "", 0)), setOf(rootDocumentId), WalkCounters())

        /** Null for text that is not a cursor this version wrote; the caller then walks again from the start (safe: the walk is idempotent). */
        fun decode(text: String): WalkCursor? {
            val lines = text.split('\n')
            if (lines.firstOrNull() != MAGIC) return null
            var root: String? = null
            var counters: WalkCounters? = null
            val pending = ArrayList<PendingDirectory>()
            val queued = HashSet<String>()
            for (i in 1 until lines.size) {
                val line = lines[i]
                if (line.isEmpty()) continue
                val f = line.split('\t')
                when (f[0]) {
                    "R" -> root = unesc(f.getOrNull(1) ?: return null)
                    "C" -> {
                        val v = f.drop(1).map { it.toIntOrNull() ?: return null }
                        if (v.size != 13) return null
                        counters = WalkCounters(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9], v[10], v[11], v[12])
                    }
                    "P" -> {
                        if (f.size != 4) return null
                        pending += PendingDirectory(unesc(f[2]), unesc(f[3]), f[1].toIntOrNull() ?: return null)
                    }
                    "Q" -> queued += unesc(f.getOrNull(1) ?: return null)
                    else -> return null
                }
            }
            return WalkCursor(root ?: return null, pending, queued, counters ?: return null)
        }

        private fun esc(s: String): String {
            if (s.none { it == '\\' || it == '\t' || it == '\n' || it == '\r' }) return s
            val sb = StringBuilder(s.length + 8)
            for (ch in s) when (ch) {
                '\\' -> sb.append("\\\\")
                '\t' -> sb.append("\\t")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                else -> sb.append(ch)
            }
            return sb.toString()
        }

        private fun unesc(s: String): String {
            if ('\\' !in s) return s
            val sb = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val ch = s[i]
                if (ch == '\\' && i + 1 < s.length) {
                    when (s[i + 1]) {
                        't' -> sb.append('\t')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        else -> sb.append(s[i + 1])
                    }
                    i += 2
                } else {
                    sb.append(ch); i++
                }
            }
            return sb.toString()
        }
    }
}

/** One media file the walk found. */
data class WalkedFile(
    val documentId: String,
    val parentDocumentId: String,
    /** Display-safe name (control and bidi characters replaced). */
    val name: String,
    /** Path of the containing directory from the root, display-safe, no leading slash. */
    val directoryPath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
    val flags: Int,
    /** From name and mime only. */
    val labelledFormat: MediaFormat,
    /** From the leading bytes, null when not read, unreadable or unrecognised. */
    val sniffedFormat: MediaFormat?,
    /** What to trust: the bytes when they said something, else the label. */
    val format: MediaFormat,
    /** The bytes disagreed with the name (a `.jpg` that is a PNG). */
    val sniffMismatch: Boolean,
    /** Names of `.xmp` siblings seen in the same listing (`IMG_1.xmp` or `IMG_1.jpg.xmp`). */
    val sidecarNames: List<String>,
    val depth: Int,
)

sealed interface WalkStatus {
    /** More directories remain. */
    data object Running : WalkStatus

    /** Every directory was visited. Sweeping missing items is safe only when [exhaustive]. */
    data class Complete(val exhaustive: Boolean) : WalkStatus

    /** A read failed. The cursor still points at the failing directory; nothing here means "empty". */
    data class Failed(val failure: TreeFailure, val message: String) : WalkStatus

    data object Cancelled : WalkStatus
}

/** One budgeted step: the files found in it and the cursor to continue from. Persist them together. */
class WalkSlice(val files: List<WalkedFile>, val cursor: WalkCursor, val status: WalkStatus) {
    /** True only for a completed, exhaustive walk: the one case where "not seen" may mean "gone" (TRAPS #8). */
    val mayConcludeMissing: Boolean get() = (status as? WalkStatus.Complete)?.exhaustive == true
}
