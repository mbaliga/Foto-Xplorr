package com.fotoxplorr.core.search

import com.fotoxplorr.core.model.MediaId
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The query language, pinned.
 *
 * These are the cases the owner named — "pictures of flowers downloaded in August 2025" first
 * among them — plus the ones that quietly break parsers: a quoted phrase containing a colon, a
 * negation, a month with no year, and a query made of nothing but stopwords.
 *
 * ADR-010 (WP1.2): moved from `com.fotoxplorr.app.search`, `java.time` ported to kotlinx-datetime,
 * JUnit4 ported to kotlin.test -- it never touched Android or Robolectric.
 */
class SearchQueryTest {

    private val zone: TimeZone = TimeZone.UTC
    private val today: LocalDate = LocalDate(2026, 3, 15)

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
    fun `field terms scope -- and numeric fields compare`() {
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
        assertTrue(matchesQuery(parse("size:>5mb"), doc))
        assertFalse(matchesQuery(parse("size:>50mb"), doc))
    }

    @Test
    fun `type raw classifies by extension -- not the old hard-coded mime list`() {
        // "orf" (Olympus) and "raf" (Fujifilm) were never in the old hand-picked
        // dng/raw/arw/cr2/nef list -- classifying via MediaFormat.classify catches them too.
        val olympus = document(name = "IMG_0001.orf", mimeType = "application/octet-stream")
        val fujifilm = document(name = "IMG_0002.raf", mimeType = "application/octet-stream")
        val jpeg = document(name = "IMG_0003.jpg", mimeType = "image/jpeg")

        assertTrue(matchesQuery(parse("type:raw"), olympus))
        assertTrue(matchesQuery(parse("type:raw"), fujifilm))
        assertFalse(matchesQuery(parse("type:raw"), jpeg))
    }

    @Test
    fun `camera and iso terms match everything -- positive or negated -- until an EXIF index exists`() {
        // No EXIF index exists yet (P0-16): SearchDocument.camera/.iso are never populated by the
        // live path (always "" / null), so letting these terms filter would silently hide every
        // photo the moment someone typed one. Both are parsed but inert -- neither excludes nor
        // requires anything -- regardless of what the document actually holds.
        val doc = document(name = "a.jpg", iso = 1600)
        assertTrue(matchesQuery(parse("camera:x"), doc))
        assertTrue(matchesQuery(parse("-camera:x"), doc))
        assertTrue(matchesQuery(parse("iso:>800"), doc))
        assertTrue(matchesQuery(parse("iso:<800"), doc))
        assertTrue(matchesQuery(parse("-iso:>800"), doc))
    }

    @Test
    fun `size accepts letter or two-letter unit suffixes and bare bytes`() {
        assertEquals(500_000L, parseByteSize("500k"))
        assertEquals(1_500_000L, parseByteSize("1.5m"))
        assertEquals(2_000_000_000L, parseByteSize("2gb"))
        assertEquals(5_000_000L, parseByteSize("5mb"))
        assertEquals(1_000L, parseByteSize("1kb"))
        assertEquals(700L, parseByteSize("700"))
        assertEquals(2_048L, parseByteSize("2048"))
        assertEquals(null, parseByteSize("abc"))
    }

    // ---- colour ----

    @Test
    fun `colour is a field, with the spellings people actually use`() {
        assertEquals(SearchField.COLOUR, SearchField.of("colour"))
        assertEquals(SearchField.COLOUR, SearchField.of("color"))
        assertEquals(SearchField.COLOUR, SearchField.of("hue"))
        assertEquals(SearchField.COLOUR, SearchField.of("palette"))

        val term = parse("color:#1A2B3C").terms.filterIsInstance<Term.Field>().single()
        assertEquals(SearchField.COLOUR, term.field)
        assertEquals("#1A2B3C", term.value)
        assertEquals(Comparison.EQ, term.comparison)
    }

    @Test
    fun `hex values parse in long, short and bare forms, case-insensitively`() {
        assertEquals(rgb(0x1A2B3C), ColourSearch.parseHex("#1a2b3c"))
        assertEquals(rgb(0x1A2B3C), ColourSearch.parseHex("#1A2B3C"))
        assertEquals(rgb(0x1A2B3C), ColourSearch.parseHex("1a2b3c"))
        assertEquals(rgb(0xAABBCC), ColourSearch.parseHex("#abc"))
        assertEquals(null, ColourSearch.parseHex("#12345"))
        assertEquals(null, ColourSearch.parseHex("#gggggg"))
        assertEquals(null, ColourSearch.parseHex(""))
        assertEquals(null, ColourSearch.parseHex("red"))
    }

    @Test
    fun `a hex term matches the colour itself and a near shade, not a different colour`() {
        val doc = document(name = "a.jpg", palette = palette(rgb(0x808080) to 0.6f, rgb(0x2244AA) to 0.4f))

        assertTrue(matchesQuery(parse("colour:#808080"), doc))
        // ΔE 13.4 from #808080 -- inside HEX_MAX_DELTA_E.
        assertTrue(matchesQuery(parse("colour:#a3a3a3"), doc))
        // ΔE 14.9 -- just outside it.
        assertFalse(matchesQuery(parse("colour:#a7a7a7"), doc))
        assertFalse(matchesQuery(parse("colour:#ff0000"), doc))
        // The second swatch counts too: a photo is all of its colours, not only its widest.
        assertTrue(matchesQuery(parse("colour:#2244aa"), doc))
    }

    @Test
    fun `the hex threshold's edge cases really do sit either side of the constant`() {
        // Pins the arithmetic behind the previous test, so a change to the Lab conversion that
        // moved these distances would be caught here rather than read as a threshold change.
        val grey = ColourSearch.labOf(rgb(0x808080))
        val inside = ColourSearch.deltaE(grey, ColourSearch.labOf(rgb(0xA3A3A3)))
        val outside = ColourSearch.deltaE(grey, ColourSearch.labOf(rgb(0xA7A7A7)))
        assertTrue(inside > 13f && inside <= ColourSearch.HEX_MAX_DELTA_E, "inside = $inside")
        assertTrue(outside > ColourSearch.HEX_MAX_DELTA_E && outside < 16f, "outside = $outside")
    }

    @Test
    fun `a colour word covers a region of shades, and stops short of its neighbours`() {
        assertTrue(matchesQuery(parse("colour:red"), document(name = "a", palette = palette(rgb(0xFF0000) to 1f))))
        // Firebrick and a Material red: neither is anywhere near pure red in RGB terms.
        assertTrue(matchesQuery(parse("colour:red"), document(name = "a", palette = palette(rgb(0xB22222) to 1f))))
        assertTrue(matchesQuery(parse("colour:red"), document(name = "a", palette = palette(rgb(0xD32F2F) to 1f))))
        assertFalse(matchesQuery(parse("colour:red"), document(name = "a", palette = palette(rgb(0x0000FF) to 1f))))
        assertFalse(matchesQuery(parse("colour:red"), document(name = "a", palette = palette(rgb(0x808080) to 1f))))

        // blue and navy are separate words for a reason: each stays out of the other's anchors.
        val navy = document(name = "a", palette = palette(rgb(0x000080) to 1f))
        assertTrue(matchesQuery(parse("colour:navy"), navy))
        assertFalse(matchesQuery(parse("colour:blue"), navy))
        assertTrue(matchesQuery(parse("colour:navy"), document(name = "a", palette = palette(rgb(0x191970) to 1f))))

        // Either spelling.
        val grey = document(name = "a", palette = palette(rgb(0x808080) to 1f))
        assertTrue(matchesQuery(parse("colour:grey"), grey))
        assertTrue(matchesQuery(parse("colour:gray"), grey))
    }

    @Test
    fun `achromatic words keep to their own band of lightness`() {
        val white = document(name = "a", palette = palette(rgb(0xFFFFFF) to 1f))
        assertTrue(matchesQuery(parse("colour:white"), white))
        assertTrue(matchesQuery(parse("colour:white"), document(name = "a", palette = palette(rgb(0xDDDDDD) to 1f))))
        // At the chromatic threshold silver would take pure white; at its own it does not.
        assertFalse(matchesQuery(parse("colour:silver"), white))
        assertFalse(matchesQuery(parse("colour:grey"), document(name = "a", palette = palette(rgb(0x222222) to 1f))))

        // black's edge: #525252 is ΔE 13.6 from its farthest anchor, #585858 is 16.2.
        assertTrue(matchesQuery(parse("colour:black"), document(name = "a", palette = palette(rgb(0x222222) to 1f))))
        assertTrue(matchesQuery(parse("colour:black"), document(name = "a", palette = palette(rgb(0x525252) to 1f))))
        assertFalse(matchesQuery(parse("colour:black"), document(name = "a", palette = palette(rgb(0x585858) to 1f))))
    }

    @Test
    fun `a colour has to cover a real share of the photo to count`() {
        val sliver = document(name = "a", palette = palette(rgb(0x00FF00) to 0.95f, rgb(0xFF0000) to 0.05f))
        assertFalse(matchesQuery(parse("colour:red"), sliver))
        assertTrue(matchesQuery(parse("colour:green"), sliver))

        val accent = document(name = "a", palette = palette(rgb(0x00FF00) to 0.92f, rgb(0xFF0000) to ColourSearch.MIN_PROPORTION))
        assertTrue(matchesQuery(parse("colour:red"), accent))
    }

    @Test
    fun `negated colour terms and alternatives behave like every other field`() {
        val red = document(name = "red.jpg", palette = palette(rgb(0xFF0000) to 1f))
        val blue = document(name = "blue.jpg", palette = palette(rgb(0x0000FF) to 1f))
        val both = document(name = "both.jpg", palette = palette(rgb(0xFF0000) to 0.5f, rgb(0x0000FF) to 0.5f))

        assertFalse(matchesQuery(parse("-colour:red"), red))
        assertTrue(matchesQuery(parse("-colour:red"), blue))
        assertTrue(matchesQuery(parse("not colour:red"), blue))

        val either = parse("colour:red or colour:blue")
        assertTrue(either.terms.single() is Term.AnyOf)
        assertTrue(matchesQuery(either, red))
        assertTrue(matchesQuery(either, blue))

        // Two terms AND together, as everywhere else in the language.
        val bothTerms = parse("colour:red colour:blue")
        assertTrue(matchesQuery(bothTerms, both))
        assertFalse(matchesQuery(bothTerms, red))
    }

    @Test
    fun `an unknown colour matches nothing, and a photo without a palette matches no colour`() {
        val red = document(name = "a", palette = palette(rgb(0xFF0000) to 1f))
        // Consistent with label:nosuchlabel -- a term the matcher cannot read never widens a search.
        assertFalse(matchesQuery(parse("colour:nosuchcolour"), red))
        assertTrue(matchesQuery(parse("-colour:nosuchcolour"), red))

        val unsampled = document(name = "a")
        assertFalse(matchesQuery(parse("colour:red"), unsampled))
        assertFalse(matchesQuery(parse("colour:#000000"), unsampled))
        assertTrue(matchesQuery(parse("-colour:red"), unsampled))
    }

    @Test
    fun `colourQuery is exactly what a swatch tap opens, and it round-trips through the parser`() {
        assertEquals("colour:#1a2b3c", ColourSearch.colourQuery(rgb(0x1A2B3C)))
        // Alpha is not part of the colour.
        assertEquals("colour:#1a2b3c", ColourSearch.colourQuery(0x001A2B3C))
        assertEquals("colour:#000000", ColourSearch.colourQuery(rgb(0x000000)))

        val doc = document(name = "a", palette = palette(rgb(0x1A2B3C) to 1f))
        assertTrue(matchesQuery(parse(ColourSearch.colourQuery(rgb(0x1A2B3C))), doc))
    }

    @Test
    fun `a colour chip offers the nearest colour words, and a bare colour word offers the scoped form`() {
        val navyish = parse("colour:#000080")
        val alternatives = alternativesFor(navyish, 0, SearchVocabulary(), zone)
        assertEquals("colour: navy", alternatives.first().label)
        assertEquals("Remove this", alternatives.last().label)
        // Every offered rewrite is itself a colour term the parser reads back.
        alternatives.dropLast(1).forEach { suggestion ->
            val term = parse(suggestion.query).terms.single()
            assertTrue(term is Term.Field && term.field == SearchField.COLOUR, suggestion.query)
        }

        val word = parse("red")
        val scoped = alternativesFor(word, 0, SearchVocabulary(), zone).single { it.label == "Only photos coloured red" }
        assertEquals("colour:red", scoped.query)
    }

    @Test
    fun `a rewritten colour term survives the round trip`() {
        val query = parse("colour:#1a2b3c flower")
        assertEquals("colour:navy flower", rewrite(query, 0, "colour:navy"))
        assertEquals("colour:#1a2b3c flower", rewrite(query, 1, "flower"))
    }

    private fun rgb(hex: Int): Int = (0xFF shl 24) or hex

    private fun palette(vararg colours: Pair<Int, Float>): PaletteSample = PaletteSample.of(
        argb = IntArray(colours.size) { colours[it].first },
        proportions = FloatArray(colours.size) { colours[it].second },
    )

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
        LocalDate(year, month, day).atStartOfDayIn(zone).toEpochMilliseconds()

    private fun document(
        name: String,
        folder: String = "Camera",
        mimeType: String = "image/jpeg",
        labels: Set<String> = emptySet(),
        text: String = "",
        takenAt: Long = instant(2025, 1, 1),
        iso: Int? = null,
        sizeBytes: Long = 1_000_000,
        palette: PaletteSample = PaletteSample.EMPTY,
    ) = SearchDocument(
        mediaId = MediaId(1L),
        name = name,
        folder = folder,
        mimeType = mimeType,
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
        palette = palette,
    )
}
