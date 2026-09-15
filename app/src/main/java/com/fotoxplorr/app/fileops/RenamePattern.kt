package com.fotoxplorr.app.fileops

import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * One photo as the bulk-rename engine needs to see it: the two pieces every generated name is
 * built from, and nothing else.
 *
 * A plain data class rather than [com.fotoxplorr.app.media.MediaAsset] itself, on purpose: the
 * whole point of a pattern engine is that its correctness is provable on the JVM without a device
 * or a MediaStore in sight, the same reason [com.fotoxplorr.app.editor.AutoFix] takes a raw pixel
 * `IntArray` rather than a `Bitmap`. `MediaAsset` also carries an `android.net.Uri`, which this
 * file has no business depending on just to read a name and two timestamps.
 */
data class RenameSubject(
    val originalDisplayName: String,
    val dateTakenMillis: Long,
    val dateModifiedSeconds: Long,
) {
    /** The extension without its dot, or empty when the file has none. */
    val extension: String get() = originalDisplayName.substringAfterLast('.', "")

    /** [originalDisplayName] with its extension removed — what `{orig}` expands to. */
    val stem: String get() =
        if (extension.isEmpty()) originalDisplayName else originalDisplayName.removeSuffix(".$extension")

    /**
     * The instant the date tokens are drawn from.
     *
     * Falls back to the file's modified time when it never recorded a capture date — screenshots,
     * downloads, anything that already had its EXIF stripped before this app ever saw it. This is
     * the exact fallback [com.fotoxplorr.app.gallery.summarise] already uses to give such files
     * SOME date rather than none in the info room; a rename pattern with a date token would
     * otherwise silently produce garbage for a large fraction of a real library. Falls all the way
     * to the epoch only when both are zero, which a real MediaStore scan never produces — this app
     * always sets at least the modified time — so that last resort exists to keep the function
     * total rather than because it is expected to fire.
     */
    val effectiveDateMillis: Long
        get() = dateTakenMillis.takeIf { it > 0L } ?: (dateModifiedSeconds * 1_000L)
}

/**
 * The bulk-rename token language.
 *
 * A pattern is ordinary text with `{token}` placeholders dropped in. Everything that is not
 * inside `{ }` is copied through literally, so `Holiday_{counter:3}` on three photos produces
 * `Holiday_001`, `Holiday_002`, `Holiday_003`.
 *
 * Recognised tokens:
 *
 * | token           | expands to                                                            |
 * |-----------------|------------------------------------------------------------------------|
 * | `{counter}`     | a sequential number, starting at the caller's `startAt` (default 1)   |
 * | `{counter:3}`   | the same number, zero-padded to 3 digits: `001`, `002`, … `999`, `1000` |
 * | `{orig}`        | the original file name, with its extension removed                    |
 * | `{yyyy}`        | the year the photo was taken, 4 digits                                 |
 * | `{yy}`          | the year, last 2 digits                                                |
 * | `{MM}`          | month, 2 digits                                                        |
 * | `{dd}`          | day of month, 2 digits                                                 |
 * | `{HH}`          | hour, 24-hour clock, 2 digits                                          |
 * | `{mm}`          | minute, 2 digits                                                       |
 * | `{ss}`          | second, 2 digits                                                       |
 *
 * The file extension is deliberately NOT a token. It is preserved and re-appended by
 * [BulkRenamePlanner] after a pattern is expanded, so a pattern can only ever rename the part of
 * the file the user actually typed — a pattern that forgets `.{ext}` can't turn a HEIC photo into
 * something the OS reads as plain text, and one that types it anyway can't accidentally double it
 * into `photo.heic.heic`.
 *
 * An unrecognised `{token}` (a typo, a token from a different tool) is left in the output exactly
 * as written rather than silently deleted. A pattern that vanishes a chunk of every filename with
 * no visible cause is far worse than one that visibly carries a `{typo}` the user can immediately
 * see is wrong and fix — this is the same "fail visibly" instinct [BulkRenamePlanner] applies to
 * an all-token pattern that collapses to nothing (see its `sanitizeStem`).
 */
object RenamePattern {

    private val TOKEN = Regex("\\{([a-zA-Z]+)(?::(\\d+))?}")

    /**
     * Expand [pattern] once per element of [subjects], in the SAME order [subjects] is given.
     *
     * The counter for the Nth subject (0-indexed) is `startAt + N` — so the caller controls both
     * where numbering starts and, by the order it hands [subjects] in, which photo gets which
     * number. This function does not reorder or sort; the gallery's own display order is what the
     * user was looking at when they picked "rename these", and reordering behind their back would
     * make the numbering not match what they saw on screen.
     */
    fun expand(
        pattern: String,
        subjects: List<RenameSubject>,
        startAt: Int = 1,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): List<String> = subjects.mapIndexed { index, subject ->
        expandOne(pattern, subject, startAt + index, zoneId)
    }

    private fun expandOne(pattern: String, subject: RenameSubject, counter: Int, zoneId: ZoneId): String {
        val takenAt = Instant.ofEpochMilli(subject.effectiveDateMillis).atZone(zoneId)
        return TOKEN.replace(pattern) { match ->
            val token = match.groupValues[1]
            val width = match.groupValues[2].toIntOrNull()
            when (token) {
                "counter" -> counter.toString().padStart(width ?: 1, '0')
                "orig" -> subject.stem
                "yyyy" -> fixed(takenAt.year, 4)
                "yy" -> fixed(takenAt.year % 100, 2)
                "MM" -> fixed(takenAt.monthValue, 2)
                "dd" -> fixed(takenAt.dayOfMonth, 2)
                "HH" -> fixed(takenAt.hour, 2)
                "mm" -> fixed(takenAt.minute, 2)
                "ss" -> fixed(takenAt.second, 2)
                else -> match.value
            }
        }
    }

    /**
     * Locale-US, zero-padded to [width]. Pinned to `US` deliberately: some locales render `%d`
     * with non-ASCII digit glyphs (Arabic-Indic, for one), which would produce a "number" that is
     * not a number as far as most filesystems and every human retyping the name are concerned.
     * A rename pattern's output has to be a filename first and a formatted number a distant
     * second.
     */
    private fun fixed(value: Int, width: Int): String =
        String.format(Locale.US, "%0${width}d", value)
}
