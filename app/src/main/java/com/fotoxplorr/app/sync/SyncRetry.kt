package com.fotoxplorr.app.sync

import kotlin.random.Random

/**
 * How long to wait before trying a file again after a failure worth retrying
 * ([SyncFailure.retryable]). Bounded in attempts and in delay, and jittered so that many phones
 * coming back online together do not hit one server on the same second. "Equal jitter": half the
 * exponential step is fixed and half is random, so the wait is never near zero.
 *
 * Deterministic when given a seeded [Random] (the tests do); the app passes a fresh one.
 */
class SyncRetryPolicy(
    val maxAttempts: Int = 4,
    val baseMillis: Long = 2_000L,
    val maxMillis: Long = 60_000L,
) {
    init {
        require(maxAttempts >= 1) { "At least one attempt" }
        require(baseMillis in 1..maxMillis) { "base must be positive and at most max" }
    }

    /** Wait before attempt number [nextAttempt] (2 is the first retry). */
    fun delayBefore(nextAttempt: Int, random: Random): Long {
        require(nextAttempt >= 2) { "The first attempt does not wait" }
        val step = (nextAttempt - 2).coerceAtMost(30)
        val exponential = (baseMillis shl step).coerceIn(baseMillis, maxMillis)
        val half = exponential / 2
        return half + if (half > 0) random.nextLong(half + 1) else 0L
    }

    fun shouldRetry(failure: SyncFailure, attemptJustFailed: Int): Boolean =
        failure.retryable && attemptJustFailed < maxAttempts

    companion object {
        val NONE = SyncRetryPolicy(maxAttempts = 1)
    }
}
