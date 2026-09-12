package com.fotoxplorr.app.moments

import kotlin.math.abs

/**
 * One sampled instant in a video, reduced to just enough signal to tell it apart from its
 * neighbours: a coarse luma histogram (shape) and a mean luma (overall brightness).
 *
 * Deliberately not a pixel array or a `Bitmap` -- see [KeyMomentDetector]'s class doc for why
 * this is the exact boundary where the Android dependency ends and the pure, testable logic
 * begins. [FrameSampler] is the class that builds these from a real video.
 *
 * [histogram] is expected to be scaled onto a fixed total (see `FrameSampler`'s
 * `HISTOGRAM_SCALE`) so two signatures built from frames of different pixel counts are still
 * directly comparable -- but [KeyMomentDetector] does not actually trust that convention; it
 * re-normalises by each signature's own sum before comparing two of them (see
 * `KeyMomentDetector.histogramDistance`), so a hand-built test fixture that does not bother
 * scaling to round numbers still gets a correct answer, and so does any future producer of this
 * type that samples at a different resolution.
 *
 * Kotlin generates REFERENCE, not content, equality for a data class with an array property --
 * `FrameSignature(1, intArrayOf(1), 0f) == FrameSignature(1, intArrayOf(1), 0f)` is `false`. That
 * is never relied on anywhere in this file or its tests; it is called out here because it is
 * exactly the kind of thing that looks like a bug in a debugger and is not one.
 */
data class FrameSignature(
    val positionMs: Long,
    val histogram: IntArray,
    /** 0..1, mean normalised luma across the sampled frame. */
    val meanLuma: Float,
) {
    companion object {
        /**
         * The bin count every [histogram] must use. Shared with `FrameSampler` so the producer
         * and consumer of this type cannot silently drift apart the way
         * `com.fotoxplorr.app.share.SharePreparer`'s cache directory and `file_paths.xml` once
         * did -- see `com.fotoxplorr.app.share.ShareDirectoryTest`'s KDoc for that history, which
         * is the whole reason this is a shared constant instead of two separate `64`s.
         */
        const val HISTOGRAM_BINS = 64
    }
}

/**
 * Finds the points in a video worth jumping to, from nothing but a sequence of coarse frame
 * signatures -- no pixels, no `Bitmap`, no `MediaMetadataRetriever`. That boundary is the entire
 * design: `FrameSampler` is the only class in this feature that touches Android, and everything
 * that decides WHERE a moment is stays a pure function of [FrameSignature], so it can be checked
 * against exact, hand-built sequences on the JVM instead of eyeballed against a phone's camera
 * roll.
 *
 * ## The property that matters most
 * A video of identical frames must come back with NO moments. That is not a fallback for a
 * boring edge case -- it is the design's whole point. A "key moments" feature that always finds
 * something to point at, even in ten motionless seconds of a tripod shot, is a feature people
 * learn to ignore inside a week; see `com.fotoxplorr.app.editor.AutoFix`'s class doc for the
 * identical argument made about auto-fix, because it is the identical argument applied to a
 * different feature.
 *
 * ## Approach
 * Consecutive signatures (by position, not by list order -- see [detect]) are compared with an
 * L1 (Manhattan) distance over their luma histograms, each normalised to fractions so frames
 * sampled at different resolutions stay comparable. L1 over chi-square: chi-square's per-bin
 * term divides by `(p + q)`, which needs an explicit zero-guard, in exchange for a precision this
 * application does not spend -- the detector only needs the RELATIVE size of one video's own
 * spikes, to rank and threshold them, not a statistically calibrated goodness-of-fit to a
 * reference distribution.
 *
 * A distance at or past [MIN_SPIKE_DISTANCE] is a *candidate*. Candidates then go through greedy
 * non-maximum suppression -- strongest first, suppressing every weaker candidate within
 * [MIN_MOMENT_SPACING_MS] of it -- and the survivors are capped at `maxMoments`. Both the
 * suppression and the cap walk candidates in the SAME strongest-first order, because a cap that
 * instead kept "the first N candidates by position" would silently prefer whatever happens to
 * sit early in the video over whatever is actually most distinctive.
 */
object KeyMomentDetector {

    fun detect(
        signatures: List<FrameSignature>,
        durationMs: Long,
        maxMoments: Int = 8,
    ): List<DetectedMoment> {
        if (signatures.size < 2 || durationMs <= 0L) return emptyList()

        // Sorted defensively, not assumed: "frame-to-frame" distance only means something
        // between chronological neighbours, and this pure function's only real-world caller
        // today (FrameSampler) is not a guarantee it is the only caller it will ever have.
        val ordered = signatures.sortedBy { it.positionMs }

        val candidates = ArrayList<Candidate>(ordered.size)
        for (i in 1 until ordered.size) {
            val previous = ordered[i - 1]
            val current = ordered[i]
            val distance = histogramDistance(previous.histogram, current.histogram)
            if (distance < MIN_SPIKE_DISTANCE) continue
            val lumaShift = abs(current.meanLuma - previous.meanLuma)
            candidates += Candidate(
                // The LATER frame's position: that is where the new content actually starts, so
                // "jump to this moment" lands a viewer on the thing that changed, not on the
                // last instant of whatever came before it.
                positionMs = current.positionMs.coerceIn(0L, (durationMs - 1).coerceAtLeast(0L)),
                distance = distance,
                label = labelFor(distance, lumaShift),
            )
        }
        if (candidates.isEmpty()) return emptyList()

        return suppress(candidates, maxMoments)
            .sortedBy { it.positionMs }
            .map { candidate ->
                DetectedMoment(
                    positionMs = candidate.positionMs,
                    confidence = confidenceFor(candidate.distance),
                    label = candidate.label,
                )
            }
    }

    /**
     * Greedy non-maximum suppression: take the strongest remaining candidate, drop every other
     * candidate within [MIN_MOMENT_SPACING_MS] of it, repeat until either the list is exhausted
     * or [maxMoments] have been chosen.
     *
     * Processing strongest-first is what makes the cap and the spacing rule cooperate instead of
     * fighting each other. Sorting by POSITION first and taking the first `maxMoments` would let
     * an early, weak flicker crowd out a dramatic cut that happens to sit later in the video;
     * this instead always keeps the video's most distinctive moments, wherever in the timeline
     * they fall.
     */
    private fun suppress(candidates: List<Candidate>, maxMoments: Int): List<Candidate> {
        val order = candidates.indices.sortedByDescending { candidates[it].distance }
        val suppressed = BooleanArray(candidates.size)
        val chosen = ArrayList<Candidate>(minOf(maxMoments.coerceAtLeast(0), candidates.size))
        for (idx in order) {
            // Checked before touching idx, not after appending: with maxMoments <= 0 this must
            // choose nothing, and checking the cap first is what makes that fall out for free
            // instead of needing a separate guard.
            if (chosen.size >= maxMoments) break
            if (suppressed[idx]) continue
            val winner = candidates[idx]
            chosen += winner
            for (other in candidates.indices) {
                if (other == idx || suppressed[other]) continue
                if (abs(candidates[other].positionMs - winner.positionMs) < MIN_MOMENT_SPACING_MS) {
                    suppressed[other] = true
                }
            }
        }
        return chosen
    }

    /**
     * L1 distance between two histograms, each re-normalised to fractions by ITS OWN sum -- see
     * [FrameSignature]'s KDoc for why this does not trust that the caller already scaled them.
     * Range is 0 (identical shape) to 2 (completely disjoint support), which is what
     * [confidenceFor] maps back onto 0..1.
     */
    private fun histogramDistance(a: IntArray, b: IntArray): Float {
        val bins = minOf(a.size, b.size)
        if (bins == 0) return 0f
        val sumA = a.sumOf { it.toLong() }.toFloat()
        val sumB = b.sumOf { it.toLong() }.toFloat()
        var distance = 0f
        for (i in 0 until bins) {
            val pa = if (sumA > 0f) a[i] / sumA else 0f
            val pb = if (sumB > 0f) b[i] / sumB else 0f
            distance += abs(pa - pb)
        }
        return distance
    }

    /**
     * A guess at WHY a spike happened, from nothing more than how big it was and how much of it
     * was a straightforward brightness change. This is coarse -- a real classifier would want
     * motion vectors, which this feature does not have -- but a labelled guess that is honest
     * about being a rough one is more useful in a scrubber UI than an unlabelled dot, and no
     * worse than what a human skimming a timeline would guess from the same two numbers.
     *
     * Brightness is checked FIRST, ahead of the scene-change/big-movement split below, because a
     * real brightness event (a light turning on, a cut from indoors to full sun) usually ALSO
     * moves histogram mass into different bins -- left to the distance check alone it would get
     * mislabelled "Scene change", since a change of luma bin is indistinguishable from a change
     * of subject once it is reduced to "the histogram moved".
     */
    private fun labelFor(distance: Float, lumaShift: Float): String = when {
        lumaShift >= BRIGHTNESS_SHIFT_LUMA_THRESHOLD -> "Brightness shift"
        distance >= HARD_CUT_DISTANCE -> "Scene change"
        else -> "Big movement"
    }

    /**
     * 0..1, linear between "barely a candidate" and "theoretical maximum possible difference" --
     * not a calibrated probability, just how far past the noise floor this spike sits. See
     * [VideoMoment.confidence]'s doc: "how sure the detector is" is a self-report, not a promise.
     */
    private fun confidenceFor(distance: Float): Float =
        ((distance - MIN_SPIKE_DISTANCE) / (MAX_POSSIBLE_DISTANCE - MIN_SPIKE_DISTANCE)).coerceIn(0f, 1f)

    private data class Candidate(val positionMs: Long, val distance: Float, val label: String)

    /** Theoretical max of [histogramDistance]: two histograms with zero overlapping mass. */
    private const val MAX_POSSIBLE_DISTANCE = 2f

    /**
     * Below this L1 distance, a frame-to-frame change is treated as noise -- re-encoding
     * artefacts, sensor grain, a light flicker -- not a moment. Two IDENTICAL histograms sit at
     * distance 0, nowhere near this; two histograms with zero shared mass sit at 2, several
     * times past it. This is the number [KeyMomentDetectorTest]'s "identical frames" and "hard
     * cut" cases are built to fall cleanly on either side of.
     */
    private const val MIN_SPIKE_DISTANCE = 0.5f

    /**
     * How far apart two reported moments must be, in milliseconds. Below this a scrubber UI
     * cannot usefully show them as separate marks, and two cuts a few frames apart are almost
     * always one edit (a whip-pan, a flash cut) that reads as a single moment to a person
     * watching, not two.
     */
    private const val MIN_MOMENT_SPACING_MS = 3_000L

    /**
     * Mean-luma change, 0..1 scale, at or above which a spike is attributed to brightness rather
     * than content. See [labelFor] for why this is checked before the scene-change split.
     */
    private const val BRIGHTNESS_SHIFT_LUMA_THRESHOLD = 0.18f

    /**
     * Histogram distance at or above which a spike not already explained by brightness is called
     * a scene change rather than movement within one shot. A hard cut to unrelated footage tends
     * toward [MAX_POSSIBLE_DISTANCE]; a fast pan or a subject bursting into frame reshuffles the
     * SAME scene's tones and typically lands lower, between [MIN_SPIKE_DISTANCE] and this.
     */
    private const val HARD_CUT_DISTANCE = 1.1f
}
