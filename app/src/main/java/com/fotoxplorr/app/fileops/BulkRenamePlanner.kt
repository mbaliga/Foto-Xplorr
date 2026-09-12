package com.fotoxplorr.app.fileops

import java.time.ZoneId

/**
 * Turns a [RenamePattern] and a batch of photos into concrete, collision-free file names.
 *
 * This is the part of bulk rename that actually matters. Expanding a pattern is arithmetic that
 * is easy to get right; making sure the arithmetic never hands two different photos the same
 * name — or a name some untouched third file already owns — is the part where a naive
 * implementation quietly destroys data, because a MediaStore rename that lands on an existing
 * name does not merge or warn, it silently overwrites whatever was there. Pure Kotlin, no Android
 * import anywhere in this file, so this property is something a JVM test proves rather than
 * something demonstrated by hand on a device and hoped to still hold.
 */
object BulkRenamePlanner {

    /** One resolved outcome: the photo, and the exact display name (with extension) it will get. */
    data class PlannedName(val subject: RenameSubject, val finalName: String)

    /** Characters MediaStore's DISPLAY_NAME column will not accept, or that no filesystem should see. */
    private val ILLEGAL_CHARACTERS = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")

    /**
     * Bound on the `(2)`, `(3)`, … collision-suffix search. Only reachable by a pattern with no
     * differentiating token at all, applied to an implausibly large batch — see [uniqueName].
     */
    private const val MAX_COLLISION_ATTEMPTS = 10_000

    /**
     * Plan collision-free final names for [subjects], in order.
     *
     * @param existingNames every display name already present anywhere the renamed photos could
     *   land — queried from MediaStore by the caller, so a rename can never steal the name of a
     *   file that was not even part of the selection. Matched case-insensitively: two names that
     *   differ only in case are a real collision on the case-insensitive volumes most external
     *   storage actually is, and being stricter here than the OS strictly requires costs nothing
     *   worse than an occasional unnecessary `(2)`, where being looser could silently merge two
     *   photos into one file.
     * @throws IllegalArgumentException if [pattern] is blank or [startAt] is negative — caught
     *   before any name is generated, rather than discovered halfway through a 40-photo batch.
     */
    fun plan(
        pattern: String,
        subjects: List<RenameSubject>,
        startAt: Int = 1,
        existingNames: Set<String> = emptySet(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): List<PlannedName> {
        require(pattern.isNotBlank()) { "A rename pattern is required" }
        require(startAt >= 0) { "The starting number can't be negative, was $startAt" }
        if (subjects.isEmpty()) return emptyList()

        val stems = RenamePattern.expand(pattern, subjects, startAt, zoneId)
        val claimed = existingNames.mapTo(HashSet()) { it.lowercase() }

        return subjects.zip(stems).map { (subject, rawStem) ->
            val stem = sanitizeStem(rawStem, fallback = subject.stem)
            val finalName = uniqueName(stem, subject.extension, claimed)
            // Claimed the instant it is assigned, not after the whole batch is planned — the very
            // next photo in this same batch must see it as taken too. This is what stops two
            // photos both matching a pattern with no differentiating token (e.g. plain "Holiday")
            // from being planned onto the identical name.
            claimed += finalName.lowercase()
            PlannedName(subject, finalName)
        }
    }

    private fun uniqueName(stem: String, extension: String, claimed: MutableSet<String>): String {
        val bare = withExtension(stem, extension)
        if (bare.lowercase() !in claimed) return bare

        // The established convention in this codebase for "this name is taken" — see
        // ZipExporter.uniqueName — is a " (2)", " (3)", … suffix before the extension. Reimplemented
        // here rather than called: that function resolves collisions against zip-entry names typed
        // by hand as they are written, this one resolves them against MediaStore display names
        // case-insensitively and needs its own extension bookkeeping, and the two are owned by
        // different agents working this codebase concurrently.
        var index = 2
        while (index < MAX_COLLISION_ATTEMPTS) {
            val candidate = withExtension("$stem ($index)", extension)
            if (candidate.lowercase() !in claimed) return candidate
            index++
        }
        // Not reachable by any real pattern: it requires ten thousand photos to all resolve to the
        // literal same stem, which only happens when the pattern carries no {counter} or date
        // token at all. Failing loudly here is correct — silently truncating the batch would be
        // exactly the kind of silent data loss this whole planner exists to prevent.
        error("Too many photos would be named \"$stem\" — add a {counter} or date token to the pattern")
    }

    private fun withExtension(stem: String, extension: String): String =
        if (extension.isEmpty()) stem else "$stem.$extension"

    /**
     * Strips characters MediaStore rejects, the same rule
     * [MediaFileOperations.sanitizeDisplayName] applies to a single rename (duplicated rather than
     * shared because that is an instance method on a class that also touches `ContentResolver`,
     * and this object has to stay callable from a plain JVM test with no Android on the classpath).
     *
     * [fallback] covers the pattern that is ALL tokens and resolves to nothing for this subject —
     * e.g. `{orig}` on a file with no name, which cannot happen from a real scan but must not be
     * allowed to produce a blank display name if it somehow did. The original stem stands in
     * rather than the rename silently failing.
     */
    private fun sanitizeStem(stem: String, fallback: String): String {
        val cleaned = stem.replace(ILLEGAL_CHARACTERS, "_").trim('.', ' ')
        return cleaned.ifEmpty { fallback }
    }
}
