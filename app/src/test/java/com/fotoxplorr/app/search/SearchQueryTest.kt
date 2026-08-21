package com.fotoxplorr.app.search

import com.fotoxplorr.app.media.MediaId
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The query language, pinned.
 *
 * These are the cases the owner named — "pictures of flowers downloaded in August 2025" first
 * among them — plus the ones that quietly break parsers: a quoted phrase containing a colon, a
 * negation, a month with no year, and a query made of nothing but stopwords.
 */
class SearchQueryTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private val today: LocalDate = LocalDate.of(2026, 3, 15)

    private fun parse(raw: String) = parseSearchQuery(raw, zone, today)

    @Test
    fun `the owner's sentence reduces to a word and a month`() {
        val query = parse("Pictures of flowers downloaded in August 2025")

        // "pictures", "downloaded" and "in" carry nothing and must not become AND-terms that
        // demand a filename containing them.
        val words = query.terms.filterIsInstance<Term.Word>().map { it.value }
        assertEquals(listOf("flowers"), words)

        val window = query.terms.filterIsInstance<Term.DateWindow>().single()
        assertEquals("August 2025", window.phrase)
        assertEquals(instant(2025, 8, 1), window.fromMillis)
        assertEquals(instant(2025, 9, 1), window.toMillis)
    }

    @Test
    fun `a query of only stopwords constrains nothing`() {
        // Must be empty rather than "a term that matches nothing" -- otherwise typing "photos"
        // shows an empty gallery, which reads as a broken app.
        assertTrue(parse("show me all the photos").isEmpty)
    }

    @Test
    fun `field terms scope, and numeric fields compare`() {
        val query = parse("label:flower iso:>800 size:<5mb")
        val fields = query.terms.filterIsInstance<Term.Field>()

        assertEquals(SearchField.LABEL, fields[0].field)
        assertEquals("flower", fields[0].value)
        assertEquals(Comparison.EQ, fields[0].comparison)

        assertEquals(SearchField.ISO, fields[1].field)
        assertEquals(Comparison.GT, fields[1].comparison)
        assertEquals("800", fields[1].value)

        assertEquals(SearchField.SIZE, fields[2].field)
        assertEquals(Comparison.LT, fields[2].comparison)
    }

    @Test
    fun `aliases mean a person need not learn the key`() {
        assertEquals(SearchField.LABEL, SearchField.of("of"))
        assertEquals(SearchField.FOLDER, SearchField.of("album"))
        assertEquals(SearchField.TEXT, SearchField.of("ocr"))
        assertEquals(SearchField.NAME, SearchField.of("filename"))
    }

    @Test
    fun `a quoted phrase stays one term even with a colon in it`() {
        val query = parse("\"gate 42: boarding\"")
        val word = query.terms.filterIsInstance<Term.Word>().single()
        assertEquals("gate 42: boarding", word.value)
    }

    @Test
    fun `negation drops matches rather than adding them`() {
        val query = parse("-screenshot")
        val word = query.terms.filterIsInstance<Term.Word>().single()
        assertTrue(word.negated)
    }

    @Test
    fun `a bare month means the most recent one that has happened`() {
        // Today is March 2026, so "december" is 2025 and "february" is 2026. A parser that
        // always picked the current year would send half the calendar into the future.
        val december = parse("december").terms.filterIsInstance<Term.DateWindow>().single()
        assertEquals("December 2025", december.phrase)

        val february = parse("february").terms.filterIsInstance<Term.DateWindow>().single()
        assertEquals("February 2026", february.phrase)
    }

    @Test
    fun `relative windows resolve against today`() {
        val lastMonth = parse("last month").terms.filterIsInstance<Term.DateWindow>().single()
        assertEquals(instant(2026, 2, 1), lastMonth.fromMillis)
        assertEquals(instant(2026, 3, 1), lastMonth.toMillis)
    }

    @Test
    fun `after and before produce open-ended windows`() {
        val after = parse("after:2025-06-01").terms.filterIsInstance<Term.DateWindow>().single()
        assertEquals(instant(2025, 6, 1), after.fromMillis)
        assertEquals(Long.MAX_VALUE, after.toMillis)

        val before = parse("before:2025").terms.filterIsInstance<Term.DateWindow>().single()
        assertEquals(Long.MIN_VALUE, before.fromMillis)
        assertEquals(instant(2025, 1, 1), before.toMillis)
    }

    @Test
    fun `or makes alternatives rather than a second requirement`() {
        val query = parse("cat or dog")
        val anyOf = query.terms.filterIsInstance<Term.AnyOf>().single()
        assertEquals(2, anyOf.branches.size)
    }

    // ---- matching ----

    @Test
    fun `a photo matches only when every constraint holds`() {
        val doc = document(
            name = "IMG_2031.jpg",
            labels = setOf("Flower", "Plant"),
            takenAt = instant(2025, 8, 14),
        )

        assertTrue(matchesQuery(parse("flowers August 2025"), doc.copy(labels = setOf("flowers"))))
        assertTrue(matchesQuery(parse("flower august 2025"), doc))
        // Right subject, wrong month.
        assertFalse(matchesQuery(parse("flower july 2025"), doc))
        // Right month, wrong subject.
        assertFalse(matchesQuery(parse("dog august 2025"), doc))
    }

    @Test
    fun `text inside the image is searchable`() {
        val doc = document(name = "IMG_9.jpg", text = "PLATFORM 9 3/4 KINGS CROSS")
        assertTrue(matchesQuery(parse("text:kings"), doc))
        assertTrue(matchesQuery(parse("kings"), doc))
        assertFalse(matchesQuery(parse("text:paddington"), doc))
    }

    @Test
    fun `numeric comparisons compare rather than string-match`() {
        val doc = document(name = "a.jpg", iso = 1600, sizeBytes = 8_000_000)
        assertTrue(matchesQuery(parse("iso:>800"), doc))
        assertFalse(matchesQuery(parse("iso:<800"), doc))
        assertTrue(matchesQuery(parse("size:>5mb"), doc))
        assertFalse(matchesQuery(parse("size:>50mb"), doc))
    }

    @Test
    fun `size accepts units and bare bytes`() {
        assertEquals(5_000_000L, parseByteSize("5mb"))
        assertEquals(1_000L, parseByteSize("1kb"))
        assertEquals(2_048L, parseByteSize("2048"))
    }

    // ---- suggestions ----

    @Test
    fun `dropping a term rewrites the query without it`() {
        val query = parse("flower august 2025")
        val rewritten = rewrite(query, 1, null)
        assertEquals("flower", rewritten)
    }

    @Test
    fun `a thin result set is offered ways to widen`() {
        val query = parse("flower august 2025")
        val suggestions = expansionSuggestions(query, resultCount = 2, vocabulary = SearchVocabulary(), zone = zone)

        assertTrue(suggestions.any { it.label.contains("Widen to all of 2025") })
        assertTrue(suggestions.any { it.kind == SearchSuggestion.Kind.WIDEN })
    }

    @Test
    fun `a rewritten query still parses`() {
        // The round trip is the property that matters: a suggestion the parser cannot read back
        // would silently drop constraints the moment it is taken.
        val query = parse("label:\"potted plant\" august 2025 -screenshot")
        val text = rewrite(query, 0, "label:flower")
        val reparsed = parse(text)
        assertEquals(query.terms.size, reparsed.terms.size)
    }

    private fun instant(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun document(
        name: String,
        folder: String = "Camera",
        labels: Set<String> = emptySet(),
        text: String = "",
        takenAt: Long = instant(2025, 1, 1),
        iso: Int? = null,
        sizeBytes: Long = 1_000_000,
    ) = SearchDocument(
        mediaId = MediaId(1L),
        name = name,
        folder = folder,
        mimeType = "image/jpeg",
        takenAtMillis = takenAt,
        tags = emptySet(),
        labels = labels,
        text = text,
        categories = emptySet(),
        camera = "",
        iso = iso,
        width = 4000,
        height = 3000,
        sizeBytes = sizeBytes,
    )
}
