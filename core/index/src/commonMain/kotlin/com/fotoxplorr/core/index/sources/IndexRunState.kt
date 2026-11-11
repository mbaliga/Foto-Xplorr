package com.fotoxplorr.core.index.sources

/**
 * The resumable position of a breadth-first walk of one source: the directories still to list
 * ([queue], front first), the directory being listed and how many of its entries were already
 * handled ([currentDirectory], [entriesDone]), and how many files were counted so far.
 * Serialised into `index_run.cursor` after every checkpoint; the walker (U2) resumes from it.
 */
data class WalkCursor(
    val queue: List<String> = emptyList(),
    val currentDirectory: String? = null,
    val entriesDone: Int = 0,
    val filesSeen: Long = 0,
) {
    /** Versioned, newline-delimited, with `\` and newline escaped inside each document id. */
    fun encode(): String = buildString {
        append(VERSION).append('\n')
        append(filesSeen).append('\n')
        append(entriesDone).append('\n')
        append(if (currentDirectory == null) "-" else "+" + escape(currentDirectory)).append('\n')
        append(queue.size)
        for (q in queue) append('\n').append(escape(q))
    }

    companion object {
        private const val VERSION = "wc1"

        /** `null` for anything that is not a well-formed cursor: a corrupt cursor means "start over", never a crash. */
        fun decode(text: String?): WalkCursor? {
            if (text == null) return null
            val lines = text.split('\n')
            if (lines.size < 5 || lines[0] != VERSION) return null
            val files = lines[1].toLongOrNull() ?: return null
            val done = lines[2].toIntOrNull() ?: return null
            if (files < 0 || done < 0) return null
            val cur = when {
                lines[3] == "-" -> null
                lines[3].startsWith("+") -> unescape(lines[3].substring(1)) ?: return null
                else -> return null
            }
            val count = lines[4].toIntOrNull() ?: return null
            if (count < 0 || lines.size != 5 + count) return null
            val queue = ArrayList<String>(count)
            for (i in 0 until count) queue.add(unescape(lines[5 + i]) ?: return null)
            return WalkCursor(queue, cur, done, files)
        }

        private fun escape(s: String): String = buildString {
            for (c in s) when (c) {
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                else -> append(c)
            }
        }

        private fun unescape(s: String): String? {
            val out = StringBuilder()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\') {
                    if (i + 1 >= s.length) return null
                    when (s[i + 1]) {
                        '\\' -> out.append('\\')
                        'n' -> out.append('\n')
                        else -> return null
                    }
                    i += 2
                } else {
                    out.append(c)
                    i++
                }
            }
            return out.toString()
        }
    }
}

enum class IndexRunStatus { RUNNING, COMPLETED, FAILED, CANCELLED }
