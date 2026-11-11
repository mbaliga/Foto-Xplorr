package com.fotoxplorr.core.organize.ingest

/**
 * Turns arbitrary text (a camera model, a card file name) into one safe path segment.
 *
 * Rules: path separators, `: * ? " < > |` and control characters become `_`; bidi and zero-width
 * format characters are dropped (they make a name look like another name); leading dots become `_`
 * (no hidden files, no `..`); trailing dots and spaces are trimmed (Windows/FAT); Windows device
 * names (`CON`, `NUL`, `COM1`...) get a `_` prefix; the segment is cut at [MAX_BYTES] UTF-8 bytes
 * on a code-point boundary (ext4 and exFAT allow 255 bytes per name, not 255 characters). Case is
 * kept; case-insensitive collisions are the planner's job (it compares lower-cased names).
 * Unicode is not normalised (no NFC in common Kotlin): two canonically equal names with different
 * code points stay different, which only costs a spurious "keep both", never an overwrite.
 */
object NameSanitizer {
    const val MAX_BYTES = 200
    private const val MAX_EXT_BYTES = 16
    private val reserved = setOf(
        "con", "prn", "aux", "nul",
        "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
        "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9",
    )

    fun segment(text: String, maxBytes: Int = MAX_BYTES): String {
        val sb = StringBuilder()
        for (c in text) {
            when {
                c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"' ||
                    c == '<' || c == '>' || c == '|' -> sb.append('_')
                c.isISOControl() -> sb.append('_')
                isInvisibleFormat(c) -> Unit
                else -> sb.append(c)
            }
        }
        var s = sb.toString().trim()
        var lead = 0
        while (lead < s.length && s[lead] == '.') lead++
        if (lead > 0) s = "_".repeat(lead) + s.substring(lead)
        s = s.trimEnd('.', ' ')
        if (s.isEmpty()) s = "_"
        s = cutBytes(s, maxBytes).trimEnd('.', ' ')
        if (s.isEmpty()) s = "_"
        val base = s.substringBefore('.').lowercase()
        if (base in reserved) s = "_$s"
        return s
    }

    /** Sanitises a file name keeping its extension; the stem is shortened first when too long. */
    fun fileName(stem: String, extension: String, maxBytes: Int = MAX_BYTES): String {
        val ext = extensionOf(extension)
        val room = maxBytes - (if (ext.isEmpty()) 0 else utf8Length(ext) + 1)
        val s = segment(stem, room.coerceAtLeast(8))
        return if (ext.isEmpty()) s else "$s.$ext"
    }

    private fun extensionOf(raw: String): String {
        val cleaned = segment(raw.trimStart('.'), MAX_EXT_BYTES).trimStart('_')
        return if (raw.isBlank()) "" else cleaned
    }

    private fun isInvisibleFormat(c: Char): Boolean =
        c in '​'..'‏' || c in '‪'..'‮' || c in '⁠'..'⁤' ||
            c in '⁦'..'⁩' || c == '﻿' || c == '­'

    fun utf8Length(s: String): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            n += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate() -> { i++; 4 }
                else -> 3
            }
            i++
        }
        return n
    }

    private fun cutBytes(s: String, maxBytes: Int): String {
        var n = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val pair = c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()
            val w = when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                pair -> 4
                else -> 3
            }
            if (n + w > maxBytes) return s.substring(0, i)
            n += w
            i += if (pair) 2 else 1
        }
        return s
    }
}
