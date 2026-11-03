package com.fotoxplorr.app.services

/**
 * What the screen may say about a saved API key. The key itself is never read for display: only
 * its last four characters, and only when the key is long enough that four characters give
 * nothing away (a short key shows no tail at all).
 */
object KeyMasking {
    private const val TAIL = 4
    private const val MIN_LENGTH_FOR_TAIL = 12

    /** The last four characters of [key], or "" when the key is too short to show any. */
    fun tail(key: CharArray?): String {
        if (key == null || key.size < MIN_LENGTH_FOR_TAIL) return ""
        return String(key, key.size - TAIL, TAIL)
    }

    /** "Key saved, ending 1234", or "Key saved" when there is no safe tail. */
    fun savedLabel(tail: String): String =
        if (tail.isEmpty()) ServicesCopy.KEY_SAVED else "${ServicesCopy.KEY_SAVED}, ending $tail"
}
