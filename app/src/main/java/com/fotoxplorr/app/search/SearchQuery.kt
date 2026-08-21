package com.fotoxplorr.app.search

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * The query language behind the search field.
 *
 * The old search was a single `contains` over filename, MIME, folder and tags — which meant that
 * "flowers in August" searched for the literal string "flowers in August" in a filename and found
 * nothing, every time. This parses instead, so a query is a *set of constraints* that can be shown
 * back to the user as chips, relaxed one at a time, and widened when a result set comes back thin.
 *
 * Deliberately not a search engine. Everything here is pure Kotlin over an in-memory index, because
 * the corpus is one person's photo library and the index already lives in memory for the grid. The
 * point is expressiveness and the ability to *explain itself*, not throughput.
 *
 * Four things a query can carry:
 *  - **bare words** — matched across every text surface (name, folder, tags, OCR text, AI labels)
 *  - **`field:value`** — scoped, with `>` `<` `>=` `<=` comparisons where the field is numeric
 *  - **dates** — either `after:`/`before:` or a natural phrase like `august 2025`, `last month`
 *  - **negation** — a leading `-`, or the word `not`
 *
 * Terms are AND by default (every constraint must hold), because that is what narrowing means to
 * a person typing more words. `or` between two terms makes them alternatives.
 */

/** A field a term can be scoped to. `label` and `text` are the two the on-device AI feeds. */
enum class SearchField(val key: String, val aliases: List<String> = emptyList(), val numeric: Boolean = false) {
    NAME("name", listOf("filename", "file")),
    FOLDER("folder", listOf("album", "dir", "directory", "in")),
    TAG("tag", listOf("tags", "#")),
    TYPE("type", listOf("kind", "mime")),
    /** Text found *inside* the image by on-device OCR. */
    TEXT("text", listOf("ocr", "says", "reads")),
    /** What the on-device labeller saw: flower, dog, beach, food, … */
    LABEL("label", listOf("labels", "of", "shows", "contains")),
    /** A derived category: pet, document, person, screenshot, video, favourite, … */
    CATEGORY("is", listOf("category", "cat")),
    CAMERA("camera", listOf("make", "model", "device")),
    ISO("iso", numeric = true),
    WIDTH("width", numeric = true),
    HEIGHT("height", numeric = true),
    SIZE("size", listOf("bytes", "filesize"), numeric = true),
    ;

    companion object {
        /** Resolve a user-typed key, e.g. `album` -> [FOLDER]. Null when it is not a field at all. */
        fun of(key: String): SearchField? {
            val k = key.lowercase(Locale.ROOT)
            return entries.firstOrNull { it.key == k || it.aliases.contains(k) }
        }
    }
}

/** How a numeric field term compares. Text fields always use [EQ], which means "contains". */
enum class Comparison(val symbol: String) { EQ(""), GT(">"), LT("<"), GTE(">="), LTE("<=") }

/** One constraint. Every term knows how to describe itself, because the chips render that text. */
sealed interface Term {
    val negated: Boolean

    /** A short human label for the chip, e.g. `August 2025` or `of: flower`. */
    fun label(): String

    /** A bare word, matched against every text surface at once. */
    data class Word(val value: String, override val negated: Boolean = false) : Term {
        override fun label() = if (negated) "not $value" else value
    }

    /** A scoped `field:value`. */
    data class Field(
        val field: SearchField,
        val value: String,
        val comparison: Comparison = Comparison.EQ,
        override val negated: Boolean = false,
    ) : Term {
        override fun label(): String {
            val body = "${field.key}: ${comparison.symbol}$value"
            return if (negated) "not $body" else body
        }
    }

    /**
     * A half-open date window `[fromMillis, toMillis)` over the photo's taken-date, carrying the
     * phrase that produced it so the chip can say "August 2025" rather than two epoch numbers.
     */
    data class DateWindow(
        val fromMillis: Long,
        val toMillis: Long,
        val phrase: String,
        override val negated: Boolean = false,
    ) : Term {
        override fun label() = if (negated) "not $phrase" else phrase
    }

    /** Alternatives: matches when ANY branch matches. Produced by the `or` keyword. */
    data class AnyOf(val branches: List<Term>) : Term {
        override val negated: Boolean get() = false
        override fun label() = branches.joinToString(" or ") { it.label() }
    }
}

/**
 * A parsed query: the constraints, plus the raw text so the field can be restored verbatim.
 *
 * [isEmpty] is the "show everything" case, and it is deliberately distinct from "parsed to nothing"
 * — a query of only stopwords ("pictures of the") should show everything rather than no results.
 */
data class ParsedQuery(val terms: List<Term>, val raw: String) {
    val isEmpty: Boolean get() = terms.isEmpty()
}

/**
 * Words that carry no constraint and would otherwise become dead AND-terms that match nothing.
 *
 * This is what lets "pictures of flowers downloaded in August 2025" work: `pictures`, `of`,
 * `downloaded` and `in` are dropped, `flowers` survives as a word, and `August 2025` is folded
 * into a date window. Without this the query would demand a filename containing "downloaded".
 */
private val STOPWORDS = setOf(
    "a", "an", "the", "my", "me", "all", "any", "some",
    "picture", "pictures", "pic", "pics", "photo", "photos", "photograph", "photographs",
    "image", "images", "shot", "shots", "video", "videos",
    "show", "showing", "find", "search", "with", "that", "this", "those", "these",
    "taken", "shot", "made", "downloaded", "saved", "captured", "from", "at", "on", "to",
    "was", "were", "is", "are", "and",
)

/**
 * Words that are stopwords *as connectors* but meaningful as field keys — `of` and `in` head the
 * `label:` and `folder:` aliases. Dropped when bare, honoured when they carry a colon.
 */
private val MONTHS: Map<String, Int> = buildMap {
    java.time.Month.entries.forEach { month ->
        val full = month.getDisplayName(TextStyle.FULL, Locale.ENGLISH).lowercase(Locale.ROOT)
        val short = month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).lowercase(Locale.ROOT)
        put(full, month.value)
        put(short, month.value)
    }
}

/** Parse [raw] into constraints. Never throws: unparseable input degrades to bare words. */
fun parseSearchQuery(
    raw: String,
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone),
): ParsedQuery {
    val tokens = tokenize(raw)
    if (tokens.isEmpty()) return ParsedQuery(emptyList(), raw)

    val terms = mutableListOf<Term>()
    var index = 0
    var pendingOr = false
    var negateNext = false

    while (index < tokens.size) {
        val token = tokens[index]
        val lower = token.text.lowercase(Locale.ROOT)

        // `or` joins the previous term to the next one instead of adding a constraint.
        if (lower == "or" && terms.isNotEmpty()) {
            pendingOr = true
            index++
            continue
        }
        if (lower == "not") {
            negateNext = true
            index++
            continue
        }

        // Try the multi-token date phrases first ("august 2025", "last month"), because they
        // consume words that would otherwise each become a useless bare term.
        val date = readDatePhrase(tokens, index, zone, today)
        if (date != null) {
            addTerm(terms, date.term.withNegation(negateNext), pendingOr)
            index = date.nextIndex
            pendingOr = false
            negateNext = false
            continue
        }

        val term = readTerm(token, zone, today)
        if (term != null) {
            addTerm(terms, term.withNegation(negateNext), pendingOr)
        }
        index++
        pendingOr = false
        negateNext = false
    }

    return ParsedQuery(terms, raw)
}

private fun Term.withNegation(negate: Boolean): Term = when {
    !negate -> this
    this is Term.Word -> copy(negated = true)
    this is Term.Field -> copy(negated = true)
    this is Term.DateWindow -> copy(negated = true)
    else -> this
}

private fun addTerm(terms: MutableList<Term>, term: Term, asAlternative: Boolean) {
    if (asAlternative && terms.isNotEmpty()) {
        val previous = terms.removeAt(terms.lastIndex)
        val branches = if (previous is Term.AnyOf) previous.branches + term else listOf(previous, term)
        terms += Term.AnyOf(branches)
    } else {
        terms += term
    }
}

/** One lexical token, remembering whether it was quoted so a phrase stays a phrase. */
private data class Token(val text: String, val quoted: Boolean)

private fun tokenize(raw: String): List<Token> {
    val out = mutableListOf<Token>()
    val current = StringBuilder()
    var inQuotes = false
    var quotedRun = false

    fun flush() {
        if (current.isNotEmpty()) {
            out += Token(current.toString(), quotedRun)
            current.clear()
        }
        quotedRun = false
    }

    raw.forEach { ch ->
        when {
            ch == '"' -> {
                inQuotes = !inQuotes
                if (inQuotes) quotedRun = true else flush()
            }
            ch.isWhitespace() && !inQuotes -> flush()
            else -> current.append(ch)
        }
    }
    flush()
    return out
}

/** Turn one token into a term, or null when it is a stopword that carries nothing. */
private fun readTerm(token: Token, zone: ZoneId, today: LocalDate): Term? {
    val text = token.text
    val negated = !token.quoted && text.startsWith("-") && text.length > 1
    val body = if (negated) text.substring(1) else text

    // `#tag` shorthand.
    if (!token.quoted && body.startsWith("#") && body.length > 1) {
        return Term.Field(SearchField.TAG, body.substring(1), negated = negated)
    }

    val colon = if (token.quoted) -1 else body.indexOf(':')
    if (colon > 0 && colon < body.length - 1) {
        val key = body.substring(0, colon)
        val rawValue = body.substring(colon + 1)
        val field = SearchField.of(key)
        if (field != null) {
            return fieldTerm(field, rawValue, negated, zone, today)
        }
        // `after:`/`before:` are date operators rather than fields.
        val dateTerm = boundaryDate(key, rawValue, zone, today, negated)
        if (dateTerm != null) return dateTerm
    }

    val lower = body.lowercase(Locale.ROOT)
    if (!token.quoted && lower in STOPWORDS) return null
    if (body.isBlank()) return null
    return Term.Word(body, negated)
}

private fun fieldTerm(
    field: SearchField,
    rawValue: String,
    negated: Boolean,
    zone: ZoneId,
    today: LocalDate,
): Term {
    if (!field.numeric) {
        return Term.Field(field, rawValue, Comparison.EQ, negated)
    }
    val (comparison, valueText) = when {
        rawValue.startsWith(">=") -> Comparison.GTE to rawValue.drop(2)
        rawValue.startsWith("<=") -> Comparison.LTE to rawValue.drop(2)
        rawValue.startsWith(">") -> Comparison.GT to rawValue.drop(1)
        rawValue.startsWith("<") -> Comparison.LT to rawValue.drop(1)
        else -> Comparison.EQ to rawValue
    }
    return Term.Field(field, valueText, comparison, negated)
}

/** `after:2025-08-01`, `before:august`, `since:2024`. */
private fun boundaryDate(
    key: String,
    value: String,
    zone: ZoneId,
    today: LocalDate,
    negated: Boolean,
): Term? {
    val lower = key.lowercase(Locale.ROOT)
    val isAfter = lower == "after" || lower == "since"
    val isBefore = lower == "before" || lower == "until"
    if (!isAfter && !isBefore) return null

    val window = absoluteWindow(value, zone, today) ?: return null
    return if (isAfter) {
        Term.DateWindow(window.toMillis, Long.MAX_VALUE, "after ${window.phrase}", negated)
    } else {
        Term.DateWindow(Long.MIN_VALUE, window.fromMillis, "before ${window.phrase}", negated)
    }
}

private data class Window(val fromMillis: Long, val toMillis: Long, val phrase: String)
private data class DatePhrase(val term: Term.DateWindow, val nextIndex: Int)

private fun LocalDate.startMillis(zone: ZoneId) = atStartOfDay(zone).toInstant().toEpochMilli()

/** Parse a single date-ish token into a window: `2025`, `2025-08`, `2025-08-14`, `august`. */
private fun absoluteWindow(value: String, zone: ZoneId, today: LocalDate): Window? {
    val text = value.trim().lowercase(Locale.ROOT)
    if (text.isEmpty()) return null

    Regex("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$").find(text)?.let { m ->
        val (y, mo, d) = m.destructured
        val date = runCatching { LocalDate.of(y.toInt(), mo.toInt(), d.toInt()) }.getOrNull() ?: return null
        return Window(date.startMillis(zone), date.plusDays(1).startMillis(zone), text)
    }
    Regex("^(\\d{4})-(\\d{1,2})$").find(text)?.let { m ->
        val (y, mo) = m.destructured
        val ym = runCatching { YearMonth.of(y.toInt(), mo.toInt()) }.getOrNull() ?: return null
        return Window(
            ym.atDay(1).startMillis(zone),
            ym.plusMonths(1).atDay(1).startMillis(zone),
            ym.displayPhrase(),
        )
    }
    Regex("^(\\d{4})$").find(text)?.let { m ->
        val year = m.groupValues[1].toInt()
        if (year !in 1900..2999) return null
        return Window(
            LocalDate.of(year, 1, 1).startMillis(zone),
            LocalDate.of(year + 1, 1, 1).startMillis(zone),
            year.toString(),
        )
    }
    MONTHS[text]?.let { month ->
        // A bare month means the most recent one that has already happened.
        val year = if (month <= today.monthValue) today.year else today.year - 1
        val ym = YearMonth.of(year, month)
        return Window(ym.atDay(1).startMillis(zone), ym.plusMonths(1).atDay(1).startMillis(zone), ym.displayPhrase())
    }
    return null
}

private fun YearMonth.displayPhrase(): String =
    "${month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} $year"

/**
 * Read a date phrase starting at [start], possibly spanning two tokens (`august 2025`).
 *
 * Returns null when the tokens are not a date, so the caller falls through to normal term reading.
 */
private fun readDatePhrase(
    tokens: List<Token>,
    start: Int,
    zone: ZoneId,
    today: LocalDate,
): DatePhrase? {
    val first = tokens[start]
    if (first.quoted) return null
    val lower = first.text.lowercase(Locale.ROOT)

    // "last month" / "last week" / "last year" / "this month" …
    if (lower == "last" || lower == "past" || lower == "this") {
        val unit = tokens.getOrNull(start + 1)?.text?.lowercase(Locale.ROOT) ?: return null
        val window = relativeWindow(lower, unit, zone, today) ?: return null
        return DatePhrase(Term.DateWindow(window.fromMillis, window.toMillis, window.phrase), start + 2)
    }
    if (lower == "today") {
        return DatePhrase(
            Term.DateWindow(today.startMillis(zone), today.plusDays(1).startMillis(zone), "today"),
            start + 1,
        )
    }
    if (lower == "yesterday") {
        val y = today.minusDays(1)
        return DatePhrase(
            Term.DateWindow(y.startMillis(zone), today.startMillis(zone), "yesterday"),
            start + 1,
        )
    }

    // "august 2025" — a month name followed by a year.
    val month = MONTHS[lower]
    if (month != null) {
        val yearToken = tokens.getOrNull(start + 1)?.text
        val year = yearToken?.toIntOrNull()?.takeIf { it in 1900..2999 }
        if (year != null) {
            val ym = YearMonth.of(year, month)
            return DatePhrase(
                Term.DateWindow(
                    ym.atDay(1).startMillis(zone),
                    ym.plusMonths(1).atDay(1).startMillis(zone),
                    ym.displayPhrase(),
                ),
                start + 2,
            )
        }
        val window = absoluteWindow(lower, zone, today) ?: return null
        return DatePhrase(Term.DateWindow(window.fromMillis, window.toMillis, window.phrase), start + 1)
    }

    // A bare year or an ISO date standing alone.
    val window = absoluteWindow(first.text, zone, today) ?: return null
    return DatePhrase(Term.DateWindow(window.fromMillis, window.toMillis, window.phrase), start + 1)
}

private fun relativeWindow(qualifier: String, unit: String, zone: ZoneId, today: LocalDate): Window? =
    when (unit.removeSuffix("s")) {
        "day" -> if (qualifier == "this") {
            Window(today.startMillis(zone), today.plusDays(1).startMillis(zone), "today")
        } else {
            Window(today.minusDays(1).startMillis(zone), today.startMillis(zone), "yesterday")
        }
        "week" -> if (qualifier == "this") {
            val start = today.minusDays((today.dayOfWeek.value - 1).toLong())
            Window(start.startMillis(zone), start.plusWeeks(1).startMillis(zone), "this week")
        } else {
            val start = today.minusDays((today.dayOfWeek.value - 1).toLong()).minusWeeks(1)
            Window(start.startMillis(zone), start.plusWeeks(1).startMillis(zone), "last week")
        }
        "month" -> if (qualifier == "this") {
            val ym = YearMonth.from(today)
            Window(ym.atDay(1).startMillis(zone), ym.plusMonths(1).atDay(1).startMillis(zone), "this month")
        } else {
            val ym = YearMonth.from(today).minusMonths(1)
            Window(ym.atDay(1).startMillis(zone), ym.plusMonths(1).atDay(1).startMillis(zone), "last month")
        }
        "year" -> if (qualifier == "this") {
            Window(
                LocalDate.of(today.year, 1, 1).startMillis(zone),
                LocalDate.of(today.year + 1, 1, 1).startMillis(zone),
                "this year",
            )
        } else {
            Window(
                LocalDate.of(today.year - 1, 1, 1).startMillis(zone),
                LocalDate.of(today.year, 1, 1).startMillis(zone),
                "last year",
            )
        }
        else -> null
    }
