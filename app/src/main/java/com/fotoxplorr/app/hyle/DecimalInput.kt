package com.fotoxplorr.app.hyle

/**
 * Text typed into a decimal field, made parseable. A decimal keyboard on a comma locale types ','
 * and some keyboards type Arabic-Indic or fullwidth digits; both become the ASCII a
 * `toDoubleOrNull()` reads. Anything else is left alone (the field's own check explains it).
 */
fun normalizeDecimalInput(text: String): String = buildString(text.length) {
    for (ch in text) {
        when {
            ch == ',' || ch == '٫' -> append('.')
            ch == '−' -> append('-')
            ch.code < 128 -> append(ch)
            Character.isDigit(ch) -> append(('0' + Character.digit(ch, 10)))
            else -> append(ch)
        }
    }
}
