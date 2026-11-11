package com.fotoxplorr.app.hyle

import kotlin.math.abs

/**
 * The model behind the photo stack the activity shade shows: which card is on top, what flipping
 * does, and where each card lies on the table.
 *
 * Pure, so the deck can be thumbed through in a JVM test. The composable owns the thumbnails and
 * the gestures; it asks this for the order and the transforms.
 *
 * A deck of [size] cards with the one at [top] face up. Flipping the top card to the back moves
 * [top] on by one and wraps; flipping back is the opposite. A card is its photo's position in
 * the list, so it keeps its own tilt as it travels through the deck.
 */
data class PhotoDeck(val size: Int, val top: Int = 0) {
    init {
        require(size >= 0) { "size must not be negative" }
    }

    /** The top card, wrapped into range; 0 for an empty deck (there is no card to point at). */
    val topIndex: Int get() = if (size == 0) 0 else Math.floorMod(top, size)

    val isEmpty: Boolean get() = size == 0

    /** Thumbing through only means something with two or more cards. */
    val canFlip: Boolean get() = size > 1

    /** The top card goes to the back; the next one is face up. A no-op unless [canFlip]. */
    fun flipForward(): PhotoDeck = if (canFlip) copy(top = (topIndex + 1) % size) else this

    /** The back card comes to the front. */
    fun flipBack(): PhotoDeck = if (canFlip) copy(top = (topIndex - 1 + size) % size) else this

    /** [steps] flips, positive forward and negative back, wrapping; zero returns this deck. */
    fun flip(steps: Int): PhotoDeck =
        if (!canFlip || steps == 0) this else copy(top = Math.floorMod(topIndex + steps, size))

    /**
     * The card indices to draw, top first, at most [maxVisible]. Only the first few of a deck
     * of eight can be seen under the top one; the rest would be decoded for nothing.
     */
    fun visible(maxVisible: Int = MAX_VISIBLE): List<Int> =
        List(minOf(size, maxVisible.coerceAtLeast(0))) { slot -> (topIndex + slot) % size }

    /** `3 of 8`, or an empty string for an empty deck. One-based, as a person counts cards. */
    fun counter(): String = if (isEmpty) "" else "${topIndex + 1} of $size"

    /**
     * The same deck after its list changed to [newSize] cards (the job advanced). The top card
     * stays where it is when it still exists; a shrunken deck clamps to its last card.
     */
    fun resizedTo(newSize: Int): PhotoDeck =
        if (newSize <= 0) PhotoDeck(0) else PhotoDeck(newSize, topIndex.coerceAtMost(newSize - 1))

    companion object {
        /** Cards drawn at once: the top one and a few visible beneath it. */
        const val MAX_VISIBLE = 4

        /** The most photos a stack holds. */
        const val MAX_CARDS = 8
    }
}

/**
 * Where one card lies: a tilt and a nudge from the stack's centre, like a deck on a table.
 *
 * @param rotationDeg clockwise, small.
 */
data class FanTransform(val rotationDeg: Float, val offsetXDp: Float, val offsetYDp: Float)

/**
 * The tilt and nudge for the card at [key] (its photo's id or position), random-looking but a pure
 * function of [key] and [seed]: the same photo always lies the same way, so a recomposition or a
 * flip never makes the pile shuffle itself. The top card ([isTop]) lies flatter than the ones
 * under it, so the picture being looked at is the tidiest.
 *
 * [instant] (reduce motion) lays every card square and centred: no fan at all.
 */
fun fanTransform(key: Long, seed: Long, isTop: Boolean, instant: Boolean = false): FanTransform {
    if (instant) return FanTransform(0f, 0f, 0f)
    val h = mix(key * 0x9E3779B97F4A7C15uL.toLong() + seed)
    val spin = unit(h, 0) * MAX_TILT_DEG
    val dx = unit(h, 1) * MAX_NUDGE_DP
    val dy = unit(h, 2) * MAX_NUDGE_DP
    val flat = if (isTop) TOP_FLATNESS else 1f
    return FanTransform(spin * flat, dx * flat, dy * flat)
}

/** -1..1 from three independent 16-bit slices of the hash. */
private fun unit(hash: Long, slice: Int): Float {
    val bits = ((hash ushr (slice * 16)) and 0xFFFF).toInt()
    return bits / 32767.5f - 1f
}

/** splitmix64's finaliser: neighbouring keys give unrelated results. */
private fun mix(value: Long): Long {
    var z = value
    z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
    z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
    return z xor (z ushr 31)
}

internal const val MAX_TILT_DEG = 9f
internal const val MAX_NUDGE_DP = 6f
internal const val TOP_FLATNESS = 0.4f

/**
 * Turns a drag into whole card flips, the way thumbing through a deck does: one flip for every
 * [stepPx] travelled, the remainder carried so a slow drag still adds up.
 *
 * Drag to the left (negative) flips forward, as a page turns; to the right flips back. Vertical
 * drags use the same sign convention (up is forward). The caller feeds one axis, whichever of
 * the two moved further, to [add].
 */
class RuffleAccumulator(private val stepPx: Float) {
    init {
        require(stepPx > 0f) { "stepPx must be positive" }
    }

    private var carry = 0f

    /** The flips this [deltaPx] completes: positive forward, negative back, usually 0 or 1. */
    fun add(deltaPx: Float): Int {
        carry += -deltaPx
        val whole = (carry / stepPx).toInt()
        carry -= whole * stepPx
        return whole
    }

    fun reset() {
        carry = 0f
    }
}

/** Which axis a drag delta feeds the ruffle: the larger movement wins, ties go horizontal. */
fun ruffleDelta(dx: Float, dy: Float): Float = if (abs(dy) > abs(dx)) dy else dx

/**
 * The photos for a stack from the ids a job reports as finished ([recentIds], newest first),
 * looked up in [byId] (the browsable, safe photos): an id that is not there (locked, archived,
 * hidden, trashed) is dropped, never shown. Empty until the job has reported any, which the
 * stack draws as its neutral silhouette.
 */
fun <K, T> photosForIds(recentIds: List<K>, byId: Map<K, T>, limit: Int = PhotoDeck.MAX_CARDS): List<T> =
    recentIds.asSequence().mapNotNull(byId::get).take(limit).toList()

/**
 * The deck once the person has thumbed it: the cards they were looking at stay exactly where they
 * are ([frozen], in order) and photos that arrive afterwards go BEHIND them, so the photo they
 * flipped to never changes under their finger. Bounded by [cap].
 */
fun <T> mergeBehind(frozen: List<T>, fresh: List<T>, cap: Int = PhotoDeck.MAX_CARDS * 2): List<T> {
    val known = frozen.toHashSet()
    return (frozen + fresh.filter { it !in known }).take(cap)
}
