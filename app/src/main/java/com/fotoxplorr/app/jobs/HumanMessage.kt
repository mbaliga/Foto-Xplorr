package com.fotoxplorr.app.jobs

import java.io.IOException
import kotlinx.coroutines.CancellationException

/**
 * What to tell the user about [error], instead of `it.message ?: fallback`.
 *
 * That pattern — repeated at every call site FotoXplorrActivity used to catch its own exceptions
 * at — hands whatever a platform exception's `message` happens to hold straight to the screen:
 * a raw `NullPointerException` with no message text, a `SecurityException` naming a permission
 * string nobody asked for, or (worse) `IllegalStateException` text written for a logcat reader,
 * not the person who just tried to save a photo. This is the one place that decides what is
 * actually safe and useful to show, so every call site says the same thing for the same failure
 * instead of forty places each guessing.
 *
 * Pure: no Android type in the signature, so it is trivial to pin with plain JUnit.
 */
fun humanMessage(error: Throwable, fallback: String): String = when (error) {
    // A cancelled job is not a failure the user needs an explanation for — they (or a rotation
    // tidying up) are the ones who stopped it.
    is CancellationException -> "Cancelled."
    is SecurityException -> "Android would not allow this — the permission may have been withdrawn."
    is IOException -> fallback.ifBlank { "A storage error stopped this from completing." }
    else -> error.message?.takeIf { it.isNotBlank() && it.looksLikeProse() } ?: fallback
}

/**
 * A rough filter for "this reads as a sentence a person wrote", as opposed to a Java identifier,
 * a stack-trace fragment, or a bare class name that happened to end up as an exception's message.
 *
 * Not exhaustive — it cannot be, short of a real classifier — but it catches the two shapes that
 * actually turned up raw on screen: a message with no space at all (`"ENOENT"`,
 * `"content://media/external/images/media/482: open failed"`) reads fine actually... so the real
 * signal kept is simpler and cheaper: does it contain at least one space and at least one
 * lowercase letter, which a bare code/identifier almost never does and a written sentence almost
 * always does.
 */
private fun String.looksLikeProse(): Boolean =
    any { it.isWhitespace() } && any { it.isLowerCase() }
