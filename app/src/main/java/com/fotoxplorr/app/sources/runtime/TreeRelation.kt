package com.fotoxplorr.app.sources.runtime

import java.net.URLDecoder

/** How a tree the person just picked relates to one that is already a source. */
enum class TreeRelation { UNRELATED, SAME, INSIDE_EXISTING, CONTAINS_EXISTING }

/**
 * Pure comparison of two `content://<authority>/tree/<document id>` uris. Two sources that overlap
 * would list the same file twice, so one tree per folder: the same tree, a tree inside another and a
 * tree that contains another are all refused. Only documents whose ids are hierarchical
 * (`volume:path/to/folder`, what the platform's own storage provider and USB drives use) can be
 * compared; an app whose ids are opaque never matches and is allowed.
 */
object TreeRelations {
    /** `(authority, document id)` of [treeUri], or null when it is not a tree uri. */
    fun parse(treeUri: String): Pair<String, String>? {
        val rest = treeUri.removePrefix("content://").takeIf { it.length < treeUri.length } ?: return null
        val authority = rest.substringBefore('/').takeIf { it.isNotEmpty() } ?: return null
        val afterAuthority = rest.substringAfter('/', "")
        if (!afterAuthority.startsWith("tree/")) return null
        val encoded = afterAuthority.removePrefix("tree/").substringBefore('/')
        if (encoded.isEmpty()) return null
        // '+' is a literal plus in a document id; the decoder would turn it into a space.
        val id = runCatching { URLDecoder.decode(encoded.replace("+", "%2B"), "UTF-8") }.getOrNull() ?: return null
        return authority to id
    }

    fun relate(candidate: String, existing: String): TreeRelation {
        if (candidate == existing) return TreeRelation.SAME
        val a = parse(candidate) ?: return TreeRelation.UNRELATED
        val b = parse(existing) ?: return TreeRelation.UNRELATED
        if (a.first != b.first) return TreeRelation.UNRELATED
        if (a.second == b.second) return TreeRelation.SAME
        return when {
            isInside(a.second, b.second) -> TreeRelation.INSIDE_EXISTING
            isInside(b.second, a.second) -> TreeRelation.CONTAINS_EXISTING
            else -> TreeRelation.UNRELATED
        }
    }

    /** True when [inner] names a folder below [outer]: `primary:DCIM/Camera` below `primary:DCIM` and below the volume root `primary:`. */
    private fun isInside(inner: String, outer: String): Boolean {
        if (outer.endsWith(":")) return inner.startsWith(outer) && inner.length > outer.length
        return inner.startsWith("$outer/")
    }

    /** The volume part of the tree's document id (`primary`, `1234-ABCD`), or null when it has none. */
    fun volumeOf(treeUri: String): String? = parse(treeUri)?.second?.substringBefore(':', "")?.takeIf { it.isNotEmpty() }

    /** A readable name for a tree with no better one: its last folder, or a word for a volume root. */
    fun suggestedName(treeUri: String): String {
        val id = parse(treeUri)?.second ?: return "Folder"
        val path = id.substringAfter(':', "").trim('/')
        if (path.isNotEmpty()) return path.substringAfterLast('/')
        val volume = id.substringBefore(':', "")
        return if (volume == "primary") "Internal storage" else "Drive"
    }
}
