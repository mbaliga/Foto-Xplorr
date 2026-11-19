package com.fotoxplorr.core.index.walker

import com.fotoxplorr.core.formats.FormatSniffer

/** When a file's leading bytes are read to check what it really is. */
enum class SniffPolicy {
    /** Never read file content; trust name and mime. */
    NEVER,

    /** Read the head of every media-looking or unknown-typed file (finds a `.jpg` that is a PNG). The default. */
    MEDIA_AND_UNKNOWN,
}

/**
 * The walker's rules. The built-in ignores are things that are never the person's photos
 * (Android's app data, thumbnail caches, recycle bins, Fotoz's own trash).
 */
data class WalkConfig(
    val honorNomedia: Boolean = true,
    /** True: a `.nomedia` hides the directory AND everything below it (Android's rule). */
    val nomediaCascades: Boolean = true,
    val skipHiddenDirectories: Boolean = false,
    val skipHiddenFiles: Boolean = false,
    /** Glob patterns (`*`, `?`, case-insensitive) on names, or on the path from the root when they contain `/`; a trailing `/` means directories only. */
    val ignorePatterns: List<String> = emptyList(),
    val sniff: SniffPolicy = SniffPolicy.MEDIA_AND_UNKNOWN,
    /** Reading a virtual (cloud-backed) document can download it, so it is not sniffed unless asked. */
    val sniffVirtualDocuments: Boolean = false,
    val maxDepth: Int = 48,
    val maxDirectories: Int = 200_000,
    val maxEntries: Int = 5_000_000,
    val headBytes: Int = FormatSniffer.REQUIRED_HEADER_BYTES,
) {
    companion object {
        /** Directory names skipped at any depth. Compared case-insensitively. */
        val BUILT_IN_IGNORED_DIRECTORIES: Set<String> = setOf(
            ".fotoz-trash", ".thumbnails", "\$recycle.bin", "system volume information", ".trashed", ".trash",
        )

        /** Skipped only directly under the root (a volume's app data folder). */
        const val ANDROID_DATA_DIRECTORY: String = "android"
    }
}

/** How much one [TreeWalker.advance] call may do. It always finishes the directory it is on, and does at least one. */
data class WalkBudget(
    val maxDirectories: Int = Int.MAX_VALUE,
    val maxFiles: Int = Int.MAX_VALUE,
    val maxMillis: Long = Long.MAX_VALUE,
)

/** Glob matching without regex: `*` any run (including none), `?` one character; case-insensitive. */
internal object Glob {
    fun matches(pattern: String, text: String): Boolean {
        val p = pattern.lowercase()
        val t = text.lowercase()
        var pi = 0
        var ti = 0
        var star = -1
        var mark = 0
        while (ti < t.length) {
            if (pi < p.length && (p[pi] == '?' || p[pi] == t[ti])) {
                pi++; ti++
            } else if (pi < p.length && p[pi] == '*') {
                star = pi++; mark = ti
            } else if (star >= 0) {
                pi = star + 1; ti = ++mark
            } else {
                return false
            }
        }
        while (pi < p.length && p[pi] == '*') pi++
        return pi == p.length
    }
}

/** Names a hostile or broken provider can hand back. Pure. */
internal object NameGuard {
    const val MAX_NAME_CHARS = 512
    const val MAX_ID_CHARS = 4096

    /** True when the entry must be skipped outright (it could not be a real file name, or could escape its folder). */
    fun isHostile(name: String, documentId: String): Boolean =
        name.isEmpty() || name.isBlank() || name == "." || name == ".." ||
            name.length > MAX_NAME_CHARS || documentId.isEmpty() || documentId.length > MAX_ID_CHARS ||
            name.any { it == '/' || it == '\u0000' }

    /** A name safe to show and to join into a path: control and bidirectional-override characters become a replacement mark. */
    fun display(name: String): String {
        if (name.none(::isUnsafe)) return name
        return name.map { if (isUnsafe(it)) '�' else it }.joinToString("")
    }

    private fun isUnsafe(c: Char): Boolean =
        c.code < 0x20 || c.code == 0x7F || c.code in 0x80..0x9F ||
            c.code in 0x202A..0x202E || c.code in 0x2066..0x2069 || c.code == 0x200E || c.code == 0x200F
}
