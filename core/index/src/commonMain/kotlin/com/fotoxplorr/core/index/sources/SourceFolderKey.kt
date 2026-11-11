package com.fotoxplorr.core.index.sources

/**
 * ADR-020 decision 4 / PHASE-3-DESIGN 1.4: a source asset's `relativePath` is
 * `~src/<sourceRow>/<path inside the tree>/`, so the gallery's folder identity (which lowercases
 * the relative path and ignores the volume) never merges a drive's `DCIM/Camera` with the
 * phone's own. `~` cannot start a MediaStore relative path component the app produces.
 */
object SourceFolderKey {
    const val PREFIX = "~src/"

    /** `~src/3/DCIM/Camera/` for source row 3 and tree path `DCIM/Camera`; `~src/3/` for the root. */
    fun relativePath(sourceRow: Long, pathInsideTree: String): String {
        require(sourceRow >= 1) { "source row must be positive: $sourceRow" }
        val parts = pathInsideTree.replace('\\', '/').split('/').filter { it.isNotBlank() }
        return PREFIX + sourceRow + "/" + parts.joinToString("") { "$it/" }
    }

    fun isSourcePath(relativePath: String?): Boolean = relativePath != null && relativePath.startsWith(PREFIX)

    /** The source row encoded in [relativePath], or `null` for an ordinary path. */
    fun sourceRowOf(relativePath: String?): Long? {
        if (!isSourcePath(relativePath)) return null
        val rest = relativePath!!.substring(PREFIX.length)
        val digits = rest.substringBefore('/')
        return digits.toLongOrNull()?.takeIf { it >= 1 && digits.all { c -> c in '0'..'9' } }
    }

    /** The path inside the tree (no leading or trailing slash), or `null` for an ordinary path. */
    fun pathInsideTreeOf(relativePath: String?): String? {
        if (sourceRowOf(relativePath) == null) return null
        val rest = relativePath!!.substring(PREFIX.length)
        return rest.substringAfter('/', "").trim('/')
    }
}
