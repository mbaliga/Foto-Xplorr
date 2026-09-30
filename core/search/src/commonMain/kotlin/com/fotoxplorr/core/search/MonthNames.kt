package com.fotoxplorr.core.search

/**
 * English month names, hardcoded rather than platform-formatted.
 *
 * ADR-010 (WP1.2) calls for "an injected MonthNames interface (the JVM implementation uses
 * java.time.format.TextStyle)" for the month-name formatting `SearchQuery`/`SearchSuggestions`
 * needed `java.time.Month.getDisplayName` for. This app has never offered any locale but English,
 * on any platform -- there is no i18n seam anywhere else in the search feature for a per-platform
 * formatter to plug into. A fixed table produces output identical to
 * `java.time.Month.getDisplayName(TextStyle.FULL/SHORT, Locale.ENGLISH)` for every value this
 * code ever passes it, without an expect/actual split current behaviour has no use for. Revisit
 * with a real per-platform (or per-locale) implementation only if this app ever adds one.
 */
internal object MonthNames {
    private val FULL = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )
    private val SHORT = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    )

    fun full(month: Int): String = FULL[month - 1]
    fun short(month: Int): String = SHORT[month - 1]
}
