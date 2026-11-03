package com.fotoxplorr.app.sync

import java.text.Normalizer
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Where a file goes at the destination: `Fotoz/<yyyy>/<yyyy-mm>/<name>`, the date being the day the
 * photo was TAKEN in UTC (the phone's time zone is not allowed to move a photo to another folder
 * after a trip), else the file's modified date, else `Undated`.
 *
 * Names are made safe for every provider at once (WebDAV servers, S3 keys, SAF providers, a NAS on
 * a case-insensitive disk): separators and the characters Windows refuses become `_`, control
 * characters go, the text is NFC, leading and trailing dots and spaces go, a name that would be
 * `.` / `..` / empty becomes `file`, and a name is cut to [MAX_NAME_BYTES] UTF-8 bytes while keeping
 * its extension and never splitting a character. A path can never contain a `..` segment.
 */
object RemoteLayout {
    const val ROOT = "Fotoz"
    const val MAX_NAME_BYTES = 180
    private const val MAX_EXTENSION_CHARS = 12
    private const val UNDATED = "Undated"

    /** The folder (no trailing slash) for a file taken at [dateTakenMillis] / modified at [dateModifiedSeconds]. */
    fun folderFor(dateTakenMillis: Long, dateModifiedSeconds: Long, root: String = ROOT): String {
        val millis = when {
            dateTakenMillis > 0L -> dateTakenMillis
            dateModifiedSeconds > 0L -> dateModifiedSeconds * 1000L
            else -> return "${safeSegment(root)}/$UNDATED"
        }
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT).apply { timeInMillis = millis }
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH) + 1
        return "${safeSegment(root)}/%04d/%04d-%02d".format(Locale.ROOT, year, year, month)
    }

    /** The first-choice remote path of [candidate]; [suffix] is appended to the name (".fzs" when encrypted). */
    fun pathFor(candidate: SyncCandidate, suffix: String = "", root: String = ROOT): String =
        folderFor(candidate.dateTakenMillis, candidate.dateModifiedSeconds, root) + "/" +
            safeName(candidate.displayName, suffix)

    /** One path segment that is safe to send anywhere. */
    fun safeSegment(raw: String): String = safeName(raw, "")

    fun safeName(raw: String, suffix: String = ""): String {
        val cleaned = StringBuilder()
        for (ch in Normalizer.normalize(raw, Normalizer.Form.NFC)) {
            cleaned.append(
                when {
                    ch.isISOControl() -> ""
                    ch in FORBIDDEN -> "_"
                    // Unpaired surrogates and the BMP specials do not survive a trip through UTF-8.
                    ch.isSurrogate() -> ch.toString()
                    ch == '￾' || ch == '￿' -> "_"
                    else -> ch.toString()
                },
            )
        }
        var name = repairSurrogates(cleaned.toString()).trim().trim('.', ' ')
        if (name.isEmpty() || name.all { it == '.' || it == '_' }) name = "file"
        return fit(name, suffix)
    }

    /** [name] cut so that name + [suffix] is at most [MAX_NAME_BYTES] bytes, keeping the extension. */
    private fun fit(name: String, suffix: String): String {
        val budget = (MAX_NAME_BYTES - suffix.toByteArray(Charsets.UTF_8).size).coerceAtLeast(16)
        if (name.toByteArray(Charsets.UTF_8).size <= budget) return name + suffix
        val dot = name.lastIndexOf('.')
        val hasExtension = dot > 0 && name.length - dot - 1 in 1..MAX_EXTENSION_CHARS
        val extension = if (hasExtension) name.substring(dot) else ""
        val stem = if (hasExtension) name.substring(0, dot) else name
        val stemBudget = (budget - extension.toByteArray(Charsets.UTF_8).size).coerceAtLeast(1)
        return cutToBytes(stem, stemBudget).trimEnd('.', ' ').ifEmpty { "file" } + extension + suffix
    }

    private fun cutToBytes(text: String, maxBytes: Int): String {
        var bytes = 0
        var index = 0
        while (index < text.length) {
            val cp = text.codePointAt(index)
            val size = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (bytes + size > maxBytes) break
            bytes += size
            index += Character.charCount(cp)
        }
        return text.substring(0, index)
    }

    /** Replaces a lone surrogate with `_` so the name encodes as valid UTF-8. */
    private fun repairSurrogates(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                Character.isHighSurrogate(ch) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1]) -> {
                    out.append(ch).append(text[i + 1])
                    i += 2
                }
                ch.isSurrogate() -> {
                    out.append('_')
                    i++
                }
                else -> {
                    out.append(ch)
                    i++
                }
            }
        }
        return out.toString()
    }

    /**
     * [path] if [taken] says it is free, else `name (2).ext`, `name (3).ext`, ... Comparison is the
     * caller's ([taken] should compare case-insensitively: a NAS or a SAF provider may fold case).
     */
    fun unique(path: String, taken: (String) -> Boolean): String {
        if (!taken(path)) return path
        val slash = path.lastIndexOf('/')
        val folder = if (slash >= 0) path.substring(0, slash + 1) else ""
        val file = path.substring(slash + 1)
        val dot = file.lastIndexOf('.')
        val hasExt = dot > 0 && file.length - dot - 1 in 1..MAX_EXTENSION_CHARS
        val stem = if (hasExt) file.substring(0, dot) else file
        val ext = if (hasExt) file.substring(dot) else ""
        var n = 2
        while (true) {
            val candidate = folder + fit(stem, " ($n)$ext") 
            if (!taken(candidate)) return candidate
            n++
            check(n < 10_000) { "No free name" }
        }
    }

    /** Every folder on the way to [path]'s file, outermost first: `Fotoz`, `Fotoz/2026`, `Fotoz/2026/2026-11`. */
    fun foldersOf(path: String): List<String> {
        val parts = path.split('/').filter { it.isNotEmpty() }.dropLast(1)
        return parts.indices.map { parts.take(it + 1).joinToString("/") }
    }

    /** True for a path made only of safe segments: no `.`/`..`, no empty segment, no separator tricks. */
    fun isSafePath(path: String): Boolean {
        if (path.isEmpty() || path.startsWith("/") || path.contains('\\') || path.contains('\u0000')) return false
        return path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }
    }

    private val FORBIDDEN = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
}
