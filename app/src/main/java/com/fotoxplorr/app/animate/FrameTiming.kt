package com.fotoxplorr.app.animate

import kotlin.math.floor
import kotlin.math.roundToLong

/** Which source frames to keep, and how long each of the kept ones stays up. */
class Resampled(val picks: IntArray, val delaysMs: IntArray) {
    init {
        require(picks.size == delaysMs.size)
    }

    val size: Int get() = picks.size
}

/**
 * Frame-rate changes by picking frames. Nothing is blended or invented: the output shows, at each
 * tick of the new rate, the source frame that is on screen at that moment.
 *
 * The source is described by how long each of its frames lasts ([delaysMs]), so a clip with a
 * steady 30 fps, a GIF with uneven delays and a slideshow all take the same path.
 */
object FrameTiming {

    /**
     * Resample to [targetFps].
     *
     * - [collapse] true (for GIF and APNG): a source frame that covers several ticks appears once,
     *   with a delay that spans them, and a frame that falls between two ticks and is never on screen
     *   at one is dropped. The result is never longer than the source, and going to a higher rate than
     *   the source changes nothing.
     * - [collapse] false (for video, which wants a steady rate): one output frame per tick, so a
     *   frame can repeat, each lasting one tick.
     *
     * The tick times are `k / fps` taken from the exact value, so 10 fps gives 100 ms and 15 fps gives
     * 67, 67, 66 ms and not a drift. The total is kept to within a tick of the source's.
     */
    fun resample(delaysMs: IntArray, targetFps: Double, collapse: Boolean): Resampled {
        require(targetFps > 0.0 && targetFps.isFinite()) { "targetFps must be positive" }
        if (delaysMs.isEmpty()) return Resampled(IntArray(0), IntArray(0))
        val total = delaysMs.sumOf { it.toLong() }
        if (total <= 0L) return Resampled(intArrayOf(0), intArrayOf(0))
        val tickMs = 1000.0 / targetFps
        val ticks = maxOf(1L, floor(total / tickMs + 0.5).toLong())

        // Source frame start times.
        val start = LongArray(delaysMs.size + 1)
        for (i in delaysMs.indices) start[i + 1] = start[i] + delaysMs[i]

        val picks = ArrayList<Int>()
        val delays = ArrayList<Int>()
        var src = 0
        for (k in 0 until ticks) {
            val t = (k * tickMs).roundToLong()
            val tEnd = if (k == ticks - 1) maxOf(total, t) else ((k + 1) * tickMs).roundToLong()
            while (src < delaysMs.size - 1 && start[src + 1] <= t) src++
            val d = (tEnd - t).toInt()
            if (collapse && picks.isNotEmpty() && picks.last() == src) {
                delays[delays.size - 1] = delays.last() + d
            } else {
                picks.add(src)
                delays.add(d)
            }
        }
        return Resampled(picks.toIntArray(), delays.toIntArray())
    }

    /**
     * [n] source indices spread evenly over [count] frames, first and last included: the picks for
     * "keep n of these", ignoring timing. The delay of each kept frame is the caller's to set.
     */
    fun evenPicks(count: Int, n: Int): IntArray {
        require(count >= 1 && n >= 1)
        if (n >= count) return IntArray(count) { it }
        if (n == 1) return intArrayOf(0)
        return IntArray(n) { ((it.toLong() * (count - 1) + (n - 1) / 2) / (n - 1)).toInt() }
    }

    /**
     * [n] source indices out of [count] = `important.size`, keeping every important one if there is room:
     * the rest of the room is filled with the unimportant frames spread evenly, and when there are more
     * important frames than [n] only those are kept, spread evenly. Frame 0 counts as important, so a
     * timeline still starts where the source does. Rising indices; no important frames is [evenPicks].
     */
    fun evenPicksKeeping(important: BooleanArray, n: Int): IntArray {
        val count = important.size
        require(count >= 1 && n >= 1)
        if (n >= count) return IntArray(count) { it }
        if ((1 until count).none { important[it] }) return evenPicks(count, n)
        val must = (0 until count).filter { it == 0 || important[it] }
        if (must.size >= n) return evenPicks(must.size, n).let { spread -> IntArray(n) { must[spread[it]] } }
        val rest = (0 until count).filter { it != 0 && !important[it] }
        val extra = evenPicks(rest.size, n - must.size)
        return (must + extra.map { rest[it] }).sorted().toIntArray()
    }

    /**
     * Delays for a GIF, in hundredths of a second, one per entry of [delaysMs]: rounded with the
     * leftover carried to the next frame so the total stays right, and never below 2 because a viewer
     * shows less as 10. The same rule [GifStreamEncoder] applies; exposed so a plan can show the
     * real duration before anything is encoded.
     */
    fun gifDelaysCs(delaysMs: IntArray): IntArray {
        val out = IntArray(delaysMs.size)
        var carry = 0
        for (i in delaysMs.indices) {
            val total = delaysMs[i] + carry
            var cs = Math.round(total / 10.0).toInt()
            if (cs < GifStreamEncoder.MIN_DELAY_CS) {
                cs = GifStreamEncoder.MIN_DELAY_CS
                carry = 0
            } else {
                if (cs > GifStreamEncoder.MAX_DELAY_CS) cs = GifStreamEncoder.MAX_DELAY_CS
                carry = (total - cs * 10).coerceIn(-5, 5)
            }
            out[i] = cs
        }
        return out
    }
}
