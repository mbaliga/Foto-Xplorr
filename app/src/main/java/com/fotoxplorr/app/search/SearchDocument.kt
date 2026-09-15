package com.fotoxplorr.app.search

import com.fotoxplorr.app.media.MediaId
import java.util.Locale

/**
 * Everything about one photo that a query can match against, flattened once.
 *
 * Built per asset and reused across every term, because the alternative — reaching into the
 * recognition index, the tag map and the EXIF cache once per term per asset — is the shape that
 * turns a 22k-photo library into a scroll stutter. The lowercasing happens here too, once, for
 * the same reason.
 *
 * Pure data with no Android types, so the matcher is a JVM unit test rather than a device test.
 */
data class SearchDocument(
    val mediaId: MediaId,
    val name: String,
    val folder: String,
    val mimeType: String,
    val takenAtMillis: Long,
    val tags: Set<String>,
    /** What the on-device labeller saw: "flower", "dog", "beach". */
    val labels: Set<String>,
    /** Text the on-device OCR read out of the image. */
    val text: String,
    /** Derived buckets: "video", "favourite", "screenshot", "pet", "document", "person"… */
    val categories: Set<String>,
    val camera: String,
    val iso: Int?,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
) {
    /** Every text surface joined, for bare words. Built once and cached on the instance. */
    val haystack: String by lazy(LazyThreadSafetyMode.NONE) {
        buildString {
            append(name).append(' ')
            append(folder).append(' ')
            append(mimeType).append(' ')
            append(camera).append(' ')
            tags.forEach { append(it).append(' ') }
            labels.forEach { append(it).append(' ') }
            categories.forEach { append(it).append(' ') }
            append(text)
        }.lowercase(Locale.ROOT)
    }
}

/** Does [document] satisfy every constraint in [query]? An empty query matches everything. */
fun matchesQuery(query: ParsedQuery, document: SearchDocument): Boolean =
    query.terms.all { matchesTerm(it, document) }

private fun matchesTerm(term: Term, document: SearchDocument): Boolean = when (term) {
    is Term.AnyOf -> term.branches.any { matchesTerm(it, document) }
    is Term.Word -> {
        val hit = document.haystack.contains(term.value.lowercase(Locale.ROOT))
        if (term.negated) !hit else hit
    }
    is Term.DateWindow -> {
        val hit = document.takenAtMillis >= term.fromMillis && document.takenAtMillis < term.toMillis
        if (term.negated) !hit else hit
    }
    is Term.Field -> {
        val hit = matchesField(term, document)
        if (term.negated) !hit else hit
    }
}

private fun matchesField(term: Term.Field, document: SearchDocument): Boolean {
    val value = term.value.lowercase(Locale.ROOT)
    if (value.isEmpty()) return true

    return when (term.field) {
        SearchField.NAME -> document.name.lowercase(Locale.ROOT).contains(value)
        SearchField.FOLDER -> document.folder.lowercase(Locale.ROOT).contains(value)
        SearchField.TAG -> document.tags.any { it.lowercase(Locale.ROOT).contains(value) }
        SearchField.TYPE -> matchesType(value, document)
        SearchField.TEXT -> document.text.lowercase(Locale.ROOT).contains(value)
        SearchField.LABEL -> document.labels.any { it.lowercase(Locale.ROOT).contains(value) }
        SearchField.CATEGORY -> document.categories.any { it.contains(value) }
        SearchField.CAMERA -> document.camera.lowercase(Locale.ROOT).contains(value)
        SearchField.ISO -> compareNumbers(term.comparison, document.iso?.toLong(), value.toLongOrNull())
        SearchField.WIDTH -> compareNumbers(term.comparison, document.width.toLong(), value.toLongOrNull())
        SearchField.HEIGHT -> compareNumbers(term.comparison, document.height.toLong(), value.toLongOrNull())
        SearchField.SIZE -> compareNumbers(term.comparison, document.sizeBytes, parseByteSize(value))
    }
}

/**
 * `type:` is friendlier than a MIME prefix match: people type `type:photo`, not `type:image/jpeg`.
 * Both work — the friendly words are checked first, then it falls back to a substring of the MIME.
 */
private fun matchesType(value: String, document: SearchDocument): Boolean {
    val mime = document.mimeType.lowercase(Locale.ROOT)
    return when (value) {
        "photo", "photos", "image", "images", "picture", "pictures" -> mime.startsWith("image/")
        "video", "videos", "movie", "movies" -> mime.startsWith("video/")
        "gif" -> mime.contains("gif")
        "png" -> mime.contains("png")
        "jpeg", "jpg" -> mime.contains("jpeg") || mime.contains("jpg")
        "raw", "dng" -> mime.contains("dng") || mime.contains("raw") || mime.contains("arw") ||
            mime.contains("cr2") || mime.contains("nef")
        "svg" -> mime.contains("svg")
        else -> mime.contains(value)
    }
}

private fun compareNumbers(comparison: Comparison, actual: Long?, expected: Long?): Boolean {
    if (actual == null || expected == null) return false
    return when (comparison) {
        Comparison.EQ -> actual == expected
        Comparison.GT -> actual > expected
        Comparison.LT -> actual < expected
        Comparison.GTE -> actual >= expected
        Comparison.LTE -> actual <= expected
    }
}

/** `size:>5mb` — accepts a bare byte count or a `kb`/`mb`/`gb` suffix. */
internal fun parseByteSize(value: String): Long? {
    val text = value.trim().lowercase(Locale.ROOT)
    val multiplier = when {
        text.endsWith("gb") -> 1_000_000_000L
        text.endsWith("mb") -> 1_000_000L
        text.endsWith("kb") -> 1_000L
        text.endsWith("b") -> 1L
        else -> 1L
    }
    val digits = text.trimEnd('b', 'k', 'm', 'g').trim()
    val number = digits.toDoubleOrNull() ?: return null
    return (number * multiplier).toLong()
}
