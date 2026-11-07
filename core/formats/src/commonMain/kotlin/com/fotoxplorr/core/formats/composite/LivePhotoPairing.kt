package com.fotoxplorr.core.formats.composite

/**
 * One file as the pairing sees it. [folder] is any key that is equal exactly for files in the same
 * folder of the same volume; [baseName] is the display name without its extension; [contentId] is
 * the Apple content identifier when it has been read (null when unknown or absent).
 */
data class PairCandidate<K>(
    val key: K,
    val folder: String,
    val baseName: String,
    val isMovie: Boolean,
    val takenMs: Long,
    val contentId: String? = null,
)

/**
 * Pairs Live Photo stills with their MOVs. Pure: no file is read here (the identifiers come in on
 * [PairCandidate.contentId]); the result is a presentation, never stored.
 *
 * 1. Same content identifier: the pair, whatever the names and folders.
 * 2. Otherwise, a still and a movie with the same base name (case-insensitive) in the same folder,
 *    taken within [MAX_GAP_MS] of each other. A file that already has a partner from rule 1 is not
 *    reused, and a movie pairs with one still only (the nearest in time).
 *
 * A still or movie whose identifier is known and different from the other's never pairs by name:
 * two files can share `IMG_0001` across a renumbered import, and the identifier is the better witness.
 */
object LivePhotoPairing {
    const val MAX_GAP_MS = 2_000L

    /** Still key -> movie key. */
    fun <K> pair(candidates: List<PairCandidate<K>>): Map<K, K> {
        val out = LinkedHashMap<K, K>()
        val used = HashSet<K>()
        val movies = candidates.filter { it.isMovie }
        val stills = candidates.filter { !it.isMovie }

        val moviesById = HashMap<String, PairCandidate<K>>()
        for (m in movies) m.contentId?.let { id -> if (id !in moviesById) moviesById[id] = m }
        for (s in stills) {
            val id = s.contentId ?: continue
            val m = moviesById[id] ?: continue
            if (m.key in used) continue
            out[s.key] = m.key
            used += s.key
            used += m.key
        }

        // Every same-name option, nearest in time first, taken greedily: a movie goes to its nearest still.
        val moviesByName = movies.filter { it.key !in used }
            .groupBy { it.folder to it.baseName.lowercase() }
        val options = ArrayList<Triple<Long, PairCandidate<K>, PairCandidate<K>>>()
        for (s in stills) {
            if (s.key in used) continue
            for (m in moviesByName[s.folder to s.baseName.lowercase()].orEmpty()) {
                val gap = kotlin.math.abs(m.takenMs - s.takenMs)
                if (gap <= MAX_GAP_MS && compatibleIds(s.contentId, m.contentId)) options += Triple(gap, s, m)
            }
        }
        options.sortBy { it.first }
        for ((_, s, m) in options) {
            if (s.key in used || m.key in used) continue
            out[s.key] = m.key
            used += s.key
            used += m.key
        }
        return out
    }

    private fun compatibleIds(a: String?, b: String?): Boolean = a == null || b == null || a == b

    /** "IMG_0001.HEIC" -> "IMG_0001"; a name with no dot is its own base. */
    fun baseName(displayName: String): String {
        val dot = displayName.lastIndexOf('.')
        return if (dot > 0) displayName.substring(0, dot) else displayName
    }

    /** True for the movie half of a Live Photo by type: a QuickTime file. */
    fun isLiveMovie(mimeType: String, displayName: String): Boolean =
        mimeType.equals("video/quicktime", ignoreCase = true) || displayName.lowercase().endsWith(".mov")
}
