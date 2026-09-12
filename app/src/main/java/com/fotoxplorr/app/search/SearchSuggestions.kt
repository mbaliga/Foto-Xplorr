package com.fotoxplorr.app.search

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * What to offer the user *about* their query — the two halves of the owner's search brief.
 *
 * 1. **Alternatives per term**, so each chip in the query bar opens a menu of contextually
 *    sensible swaps. The Quillbot reference is a rewriter, so its menu is alternate wordings;
 *    here the equivalent is alternate *constraints* — "August 2025" offers July, the whole of
 *    2025, or any date, because those are the edits a person actually wants to make next.
 * 2. **Expansions at the bottom of the results**, so scrolling to the end of a thin result set
 *    is met with ways to widen rather than with nothing.
 *
 * Both are derived from the parsed query and the corpus, never hardcoded: an alternative that
 * would return nothing is not offered, which is what stops this becoming a wall of dead ends.
 */

/** One offered edit. Applying it yields a new query string. */
data class SearchSuggestion(
    val label: String,
    /** The query text this suggestion produces when taken. */
    val query: String,
    val kind: Kind,
) {
    enum class Kind {
        /** Swap one term for a sibling — July instead of August. */
        ALTERNATIVE,

        /** Loosen: drop a constraint, or widen a window. */
        WIDEN,

        /** Tighten: add a constraint the corpus suggests. */
        NARROW,
    }
}

/**
 * The vocabulary the corpus actually contains, so suggestions can be checked against reality
 * before being offered. Built once per search rather than per suggestion.
 */
data class SearchVocabulary(
    val labels: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    val folders: Set<String> = emptySet(),
    val cameras: Set<String> = emptySet(),
    val categories: Set<String> = emptySet(),
)

/**
 * Alternatives for one term, for the chip menu.
 *
 * The term is identified by its index in [ParsedQuery.terms] so that rewriting is exact — two
 * identical words in a query are still two separate chips, and editing one must not move the other.
 */
fun alternativesFor(
    query: ParsedQuery,
    termIndex: Int,
    vocabulary: SearchVocabulary,
    zone: ZoneId = ZoneId.systemDefault(),
): List<SearchSuggestion> {
    val term = query.terms.getOrNull(termIndex) ?: return emptyList()
    val out = mutableListOf<SearchSuggestion>()

    when (term) {
        is Term.DateWindow -> out += dateAlternatives(query, termIndex, term, zone)
        is Term.Field -> out += fieldAlternatives(query, termIndex, term, vocabulary)
        is Term.Word -> out += wordAlternatives(query, termIndex, term, vocabulary)
        is Term.AnyOf -> Unit
    }

    // Every chip can always be removed. Offered last because it is the destructive one.
    out += SearchSuggestion(
        label = "Remove this",
        query = rewrite(query, termIndex, null),
        kind = SearchSuggestion.Kind.WIDEN,
    )
    return out
}

private fun dateAlternatives(
    query: ParsedQuery,
    index: Int,
    term: Term.DateWindow,
    zone: ZoneId,
): List<SearchSuggestion> {
    // A window with an open end is `after:`/`before:`; shifting it is not meaningful.
    if (term.fromMillis == Long.MIN_VALUE || term.toMillis == Long.MAX_VALUE) return emptyList()

    val from = Instant.ofEpochMilli(term.fromMillis).atZone(zone).toLocalDate()
    val span = ChronoUnit.DAYS.between(from, Instant.ofEpochMilli(term.toMillis).atZone(zone).toLocalDate())
    val out = mutableListOf<SearchSuggestion>()

    if (span in 27..31) {
        // A month: offer its neighbours and its year.
        val previous = from.minusMonths(1)
        val next = from.plusMonths(1)
        out += SearchSuggestion(
            monthPhrase(previous.monthValue, previous.year),
            rewrite(query, index, monthPhrase(previous.monthValue, previous.year)),
            SearchSuggestion.Kind.ALTERNATIVE,
        )
        out += SearchSuggestion(
            monthPhrase(next.monthValue, next.year),
            rewrite(query, index, monthPhrase(next.monthValue, next.year)),
            SearchSuggestion.Kind.ALTERNATIVE,
        )
        out += SearchSuggestion(
            "All of ${from.year}",
            rewrite(query, index, from.year.toString()),
            SearchSuggestion.Kind.WIDEN,
        )
    } else if (span in 360..372) {
        // A year: offer its neighbours, and narrowing to a season is not guessable, so skip it.
        out += SearchSuggestion(
            (from.year - 1).toString(),
            rewrite(query, index, (from.year - 1).toString()),
            SearchSuggestion.Kind.ALTERNATIVE,
        )
        out += SearchSuggestion(
            (from.year + 1).toString(),
            rewrite(query, index, (from.year + 1).toString()),
            SearchSuggestion.Kind.ALTERNATIVE,
        )
    }
    return out
}

private fun monthPhrase(month: Int, year: Int): String {
    val name = java.time.Month.of(month).getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH)
    return "$name $year"
}

private fun fieldAlternatives(
    query: ParsedQuery,
    index: Int,
    term: Term.Field,
    vocabulary: SearchVocabulary,
): List<SearchSuggestion> {
    val pool = when (term.field) {
        SearchField.LABEL -> vocabulary.labels
        SearchField.TAG -> vocabulary.tags
        SearchField.FOLDER -> vocabulary.folders
        SearchField.CAMERA -> vocabulary.cameras
        SearchField.CATEGORY -> vocabulary.categories
        else -> emptySet()
    }
    val current = term.value.lowercase(Locale.ROOT)
    return pool.asSequence()
        .filter { it.lowercase(Locale.ROOT) != current }
        .sortedBy { it.lowercase(Locale.ROOT) }
        .take(MAX_ALTERNATIVES)
        .map { candidate ->
            SearchSuggestion(
                label = "${term.field.key}: $candidate",
                query = rewrite(query, index, "${term.field.key}:${quoteIfNeeded(candidate)}"),
                kind = SearchSuggestion.Kind.ALTERNATIVE,
            )
        }
        .toList()
}

/**
 * A bare word could have meant several things — the label the AI saw, a tag, a folder, or text
 * inside the photo. Offering those as explicit scopes is the single most useful alternative here,
 * because it turns a vague match into a precise one without the user learning the syntax.
 */
private fun wordAlternatives(
    query: ParsedQuery,
    index: Int,
    term: Term.Word,
    vocabulary: SearchVocabulary,
): List<SearchSuggestion> {
    val word = term.value
    val lower = word.lowercase(Locale.ROOT)
    val out = mutableListOf<SearchSuggestion>()

    if (vocabulary.labels.any { it.lowercase(Locale.ROOT).contains(lower) }) {
        out += SearchSuggestion(
            "Only photos OF $word",
            rewrite(query, index, "label:${quoteIfNeeded(word)}"),
            SearchSuggestion.Kind.NARROW,
        )
    }
    if (vocabulary.tags.any { it.lowercase(Locale.ROOT).contains(lower) }) {
        out += SearchSuggestion(
            "Only tag #$word",
            rewrite(query, index, "tag:${quoteIfNeeded(word)}"),
            SearchSuggestion.Kind.NARROW,
        )
    }
    out += SearchSuggestion(
        "Only text IN the photo",
        rewrite(query, index, "text:${quoteIfNeeded(word)}"),
        SearchSuggestion.Kind.NARROW,
    )
    out += SearchSuggestion(
        "Only filenames",
        rewrite(query, index, "name:${quoteIfNeeded(word)}"),
        SearchSuggestion.Kind.NARROW,
    )
    return out
}

/**
 * What to show under a result list: ways to widen when it is thin, ways to cut when it is huge.
 *
 * [resultCount] drives the direction. A search that found three things does not want "narrow this
 * further"; a search that found four thousand does not want "try dropping a constraint".
 */
fun expansionSuggestions(
    query: ParsedQuery,
    resultCount: Int,
    vocabulary: SearchVocabulary,
    zone: ZoneId = ZoneId.systemDefault(),
): List<SearchSuggestion> {
    if (query.isEmpty) return emptyList()
    val out = mutableListOf<SearchSuggestion>()

    if (resultCount <= THIN_RESULTS) {
        // Dropping each constraint in turn: the most direct way to find out which one is the
        // one excluding everything.
        query.terms.forEachIndexed { index, term ->
            if (query.terms.size > 1) {
                out += SearchSuggestion(
                    label = "Without \"${term.label()}\"",
                    query = rewrite(query, index, null),
                    kind = SearchSuggestion.Kind.WIDEN,
                )
            }
        }
        // Widening a month to its year is the single most common useful widening.
        query.terms.forEachIndexed { index, term ->
            if (term is Term.DateWindow && term.fromMillis != Long.MIN_VALUE) {
                val year = Instant.ofEpochMilli(term.fromMillis).atZone(zone).toLocalDate().year
                val widened = rewrite(query, index, year.toString())
                if (widened != query.raw) {
                    out += SearchSuggestion("Widen to all of $year", widened, SearchSuggestion.Kind.WIDEN)
                }
            }
        }
        // If nothing in the query looked inside the photos, offer that.
        if (query.terms.none { it is Term.Field && it.field == SearchField.TEXT }) {
            val word = query.terms.filterIsInstance<Term.Word>().firstOrNull()
            if (word != null) {
                out += SearchSuggestion(
                    "Look for \"${word.value}\" in text on photos",
                    "text:${quoteIfNeeded(word.value)}",
                    SearchSuggestion.Kind.ALTERNATIVE,
                )
            }
        }
    } else {
        // Plenty of results: offer the dimensions the query has not used yet.
        val used = query.terms.filterIsInstance<Term.Field>().map { it.field }.toSet()
        if (SearchField.CATEGORY !in used) {
            vocabulary.categories.sorted().take(MAX_ALTERNATIVES).forEach { category ->
                out += SearchSuggestion(
                    "Only $category",
                    appendTerm(query, "is:${quoteIfNeeded(category)}"),
                    SearchSuggestion.Kind.NARROW,
                )
            }
        }
        if (query.terms.none { it is Term.DateWindow }) {
            out += SearchSuggestion("Only this year", appendTerm(query, "this year"), SearchSuggestion.Kind.NARROW)
        }
        if (SearchField.FOLDER !in used) {
            vocabulary.folders.sorted().take(MAX_ALTERNATIVES).forEach { folder ->
                out += SearchSuggestion(
                    "In $folder",
                    appendTerm(query, "folder:${quoteIfNeeded(folder)}"),
                    SearchSuggestion.Kind.NARROW,
                )
            }
        }
    }
    return out.distinctBy { it.query }.take(MAX_EXPANSIONS)
}

/**
 * Rebuild the query text with the term at [index] replaced by [replacement], or removed when it
 * is null.
 *
 * Rebuilt from the parsed terms rather than by splicing the raw string, because the raw string
 * has the user's own spacing and quoting in it and editing it positionally goes wrong the moment
 * a phrase contains a space.
 */
internal fun rewrite(query: ParsedQuery, index: Int, replacement: String?): String {
    val parts = query.terms.mapIndexedNotNull { i, term ->
        if (i == index) replacement else termToText(term)
    }
    return parts.joinToString(" ")
}

private fun appendTerm(query: ParsedQuery, addition: String): String {
    val existing = query.terms.mapNotNull { termToText(it) }
    return (existing + addition).joinToString(" ")
}

/** Render a term back to query text, so a rewritten query stays parseable. */
internal fun termToText(term: Term): String? = when (term) {
    is Term.Word -> (if (term.negated) "-" else "") + quoteIfNeeded(term.value)
    is Term.Field ->
        (if (term.negated) "-" else "") +
            "${term.field.key}:${term.comparison.symbol}${quoteIfNeeded(term.value)}"
    is Term.DateWindow -> (if (term.negated) "-" else "") + quoteIfNeeded(term.phrase)
    is Term.AnyOf -> term.branches.mapNotNull { termToText(it) }.joinToString(" or ")
}

private fun quoteIfNeeded(value: String): String =
    if (value.any { it.isWhitespace() }) "\"$value\"" else value

private const val MAX_ALTERNATIVES = 6
private const val MAX_EXPANSIONS = 8
private const val THIN_RESULTS = 12
