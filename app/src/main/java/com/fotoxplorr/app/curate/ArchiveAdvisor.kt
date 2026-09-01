package com.fotoxplorr.app.curate

import com.fotoxplorr.app.media.MediaId

/**
 * Turns per-photo signals the app already computes into a REVIEW QUEUE of "you might want to
 * archive this" suggestions -- never an action. Pure Kotlin, like the rest of this package: every
 * threshold below is a plain number compared against plain fields, so the whole decision is
 * unit-tested without a device, a bitmap, or a real photo library.
 *
 * ## The hard rule this whole file exists to serve
 *
 * [suggestions] returns [ArchiveSuggestion]s. It does not archive anything, cannot archive
 * anything -- there is no code path here that writes to storage -- and a suggestion this
 * function returns twice in a row for the same input is exactly as inert the second time as the
 * first. Turning a suggestion into an actual archive is [com.fotoxplorr.app.organize.LibraryStore.setArchived],
 * called from wherever the caller wires the review surface's accept action, and ONLY from there.
 * That separation is not incidental: "suggest, never move silently" was the explicit brief this
 * file was written against, and the only way to guarantee it in code -- rather than promise it in
 * a comment a later change could quietly invalidate -- is for this object to simply not have
 * access to anything that could archive a photo.
 *
 * ## Memory: a suggestion that was told no does not get asked again
 *
 * [ArchiveCandidate.previouslyDismissed] and [ArchiveCandidate.isFavorite] are both checked
 * before anything else, and either one alone removes a candidate from consideration entirely --
 * no reason computed, no suggestion produced, regardless of how strong the other signals are.
 * [previouslyDismissed] is deliberately a single caller-computed flag rather than two separate
 * ones for "rejected in the review queue" and "manually un-archived": both mean the identical
 * thing from this function's point of view -- a person already made a call about this specific
 * photo's archive status, and a heuristic re-litigating that call is not a second opinion, it is
 * nagging. See [com.fotoxplorr.app.organize.LibraryStore.rejectArchiveSuggestions] and the
 * bookkeeping [com.fotoxplorr.app.organize.LibraryStore.setArchived] does when something is
 * un-archived for where the caller is expected to source the two halves of that flag.
 *
 * ## Signals, in priority order, and why that order
 *
 * A photo can match more than one reason at once (an old, blurry screenshot exists), but
 * [suggestions] emits AT MOST ONE [ArchiveSuggestion] per candidate -- the review surface groups
 * by reason (see the task this shipped for), and the same photo turning up in two different
 * groups asking to be judged twice is confusing to review and easy to double-count when bulk
 * accepting. The order below is picked by how CERTAIN each signal is, most certain first, not by
 * how severe it sounds:
 *
 * 1. [ArchiveReasonCategory.DUPLICATE] -- an exact match on size, dimensions and MIME type
 *    against another photo in the same call is structural fact, not a judgement about this
 *    photo's own content.
 * 2. [ArchiveReasonCategory.OLD_SCREENSHOT] -- also structural (the caller's own screenshot
 *    detection, aged past a threshold), one layer removed from a raw byte comparison.
 * 3. [ArchiveReasonCategory.LOW_RESOLUTION] -- still structural (width times height), but a
 *    small image is not necessarily an unwanted one the way an exact duplicate or an old
 *    screenshot usually is.
 * 4. [ArchiveReasonCategory.BLURRY] -- the newest and least certain signal: [BlurDetector]'s
 *    score is this codebase's own heuristic, not a comparison against another known-duplicate
 *    photo or a filename convention, so it is asked last and only gets a say when nothing more
 *    certain already claimed the photo.
 */
object ArchiveAdvisor {

    enum class ArchiveReasonCategory {
        DUPLICATE,
        OLD_SCREENSHOT,
        LOW_RESOLUTION,
        BLURRY,
    }

    /**
     * Everything [suggestions] needs about one photo. Deliberately flat primitives rather than a
     * [com.fotoxplorr.app.media.MediaAsset] -- this keeps the decision logic decoupled from the
     * media package and, more importantly, keeps it PURE: [MediaAsset.dateTakenMillis] paired
     * with "now" would mean this function reads the clock, and a function that reads the clock
     * gives a different answer on every call even when nothing about the photo changed, which is
     * the opposite of what a stable, testable review queue needs. [ageMillis] is computed by the
     * caller once, at read time, instead.
     */
    data class ArchiveCandidate(
        val mediaId: MediaId,
        /** Mirrors [com.fotoxplorr.app.media.MediaAsset.isFavorite]. A favourite is never suggested, full stop. */
        val isFavorite: Boolean,
        /** Already archived. Excluded defensively -- suggesting to archive an archived photo is a no-op with a confusing label, not a real offer. */
        val isArchived: Boolean,
        /** The union of "rejected from a previous review queue" and "manually un-archived after being archived". See the class KDoc. */
        val previouslyDismissed: Boolean,
        val isScreenshot: Boolean,
        /** `now - dateTakenMillis`, computed by the caller. See the class KDoc for why this function never reads a clock itself. */
        val ageMillis: Long,
        val sizeBytes: Long,
        val widthPx: Int,
        val heightPx: Int,
        val mimeType: String,
        /**
         * [BlurDetector.sharpness] for this photo, or `null` when it was never computed --
         * running it costs a bitmap decode, and a caller is free to skip that for, say, a video
         * or a photo it has already ruled out on a cheaper signal. `null` never matches
         * [ArchiveReasonCategory.BLURRY]; it is treated as "unknown", not "blurry".
         */
        val sharpness: Float? = null,
    )

    /** One offer: which photo, which family of reason (for the review surface's grouping), and the sentence a person reads. */
    data class ArchiveSuggestion(
        val mediaId: MediaId,
        val category: ArchiveReasonCategory,
        val reason: String,
    )

    fun suggestions(candidates: List<ArchiveCandidate>): List<ArchiveSuggestion> {
        // Duplicate grouping runs over EVERY candidate, including ones that will never be
        // suggested (favourited, already archived, previously dismissed) -- because who counts
        // as "the best copy to keep" is a fact about the group, and computing it from only the
        // eligible subset would let an ineligible member's absence silently promote some other,
        // perfectly ordinary copy to "the one worth keeping". Concretely: four identical photos,
        // the oldest one favourited by the user -- the other three must all still read as
        // redundant copies of THAT one, not have the second-oldest quietly excused because the
        // true original was filtered out of view first.
        val eligible = candidates.filter { it.sizeBytes > 0L && it.widthPx > 0 && it.heightPx > 0 }
        val duplicateGroups: Map<DuplicateKey, List<ArchiveCandidate>> = eligible
            .groupBy { it.duplicateKey() }
            .filterValues { it.size > 1 }

        val out = mutableListOf<ArchiveSuggestion>()
        for (candidate in candidates) {
            if (candidate.isArchived || candidate.isFavorite || candidate.previouslyDismissed) continue
            val group = duplicateGroups[candidate.duplicateKey()]
            val (category, reason) = reasonFor(candidate, group) ?: continue
            out += ArchiveSuggestion(candidate.mediaId, category, reason)
        }
        return out
    }

    private fun reasonFor(
        candidate: ArchiveCandidate,
        duplicateGroup: List<ArchiveCandidate>?,
    ): Pair<ArchiveReasonCategory, String>? {
        if (duplicateGroup != null) {
            val keeper = bestOfGroup(duplicateGroup)
            if (keeper.mediaId != candidate.mediaId) {
                val others = duplicateGroup.size - 1
                return ArchiveReasonCategory.DUPLICATE to
                    "Near-duplicate of $others other${if (others == 1) "" else "s"}"
            }
            // This candidate IS the keeper of its group -- fall through, it may still qualify on
            // a completely different signal (an old screenshot that also happens to be the
            // oldest of several identical re-saves is still an old screenshot).
        }

        if (candidate.isScreenshot && candidate.ageMillis >= SCREENSHOT_AGE_THRESHOLD_MILLIS) {
            return ArchiveReasonCategory.OLD_SCREENSHOT to "Screenshot from ${formatAge(candidate.ageMillis)} ago"
        }

        // Both dimensions positive, not just their product: a video or a photo whose metadata
        // never got read can carry width=0/height=0, and 0 pixels is not evidence of a small
        // image, it is evidence of a read that never happened. Treating it as "low resolution"
        // would be a confident-looking suggestion built on missing data, not on a real photo a
        // human could look at and agree is tiny.
        if (candidate.widthPx > 0 && candidate.heightPx > 0 &&
            candidate.widthPx.toLong() * candidate.heightPx <= LOW_RESOLUTION_MAX_PIXELS
        ) {
            return ArchiveReasonCategory.LOW_RESOLUTION to
                "Low resolution (${candidate.widthPx}×${candidate.heightPx})"
        }

        val sharpness = candidate.sharpness
        if (sharpness != null && sharpness < BLUR_SHARPNESS_THRESHOLD) {
            return ArchiveReasonCategory.BLURRY to "Very blurry"
        }

        return null
    }

    /**
     * Which member of an exact-duplicate group is the one worth keeping -- the rest become
     * [ArchiveReasonCategory.DUPLICATE] suggestions.
     *
     * Oldest capture time wins: an unmodified original predates any copy a share, a backup
     * round-trip or a "save image" produces, so the earliest [ArchiveCandidate.ageMillis] --
     * the LARGEST value, since age counts backwards from now -- is read as the original. Ties
     * (genuinely identical capture times, or a caller that could not populate one) fall back to
     * the lower [MediaId], which has no real-world meaning but is stable across calls -- what
     * matters for a review queue is that running this twice on the same input always names the
     * same photo as the keeper, not that the tie-break itself is meaningful.
     */
    private fun bestOfGroup(group: List<ArchiveCandidate>): ArchiveCandidate =
        group.sortedWith(compareByDescending<ArchiveCandidate> { it.ageMillis }.thenBy { it.mediaId.value }).first()

    private fun ArchiveCandidate.duplicateKey() = DuplicateKey(sizeBytes, widthPx, heightPx, mimeType.lowercase())

    /**
     * Mirrors the definition [com.fotoxplorr.app.gallery.GalleryProjection.duplicateCandidateIds]
     * already established for the DUPLICATES smart album -- exact size, dimensions and MIME type
     * -- re-expressed here rather than reused because that function's own key type is private to
     * a file this package does not own. Worth flagging plainly: if that definition ever changes,
     * this one has to change with it by hand, or the DUPLICATES album and this signal will
     * quietly disagree about which photos are duplicates.
     */
    private data class DuplicateKey(val sizeBytes: Long, val widthPx: Int, val heightPx: Int, val mimeType: String)

    /**
     * A rough, deliberately calendar-free "N days/months/years" -- millisecond arithmetic only,
     * no [java.time], no timezone. `ageMillis` already IS the answer to "how long ago"; running
     * it through a calendar would only add a timezone this function has no business knowing
     * about for a plain-English approximation nobody reads to the day. A month is approximated
     * at 30 days and a year at 365 -- close enough that "8 months" reads the same to a person
     * whether the real span was 235 or 245 days, which is the only bar a review-queue caption
     * needs to clear.
     */
    private fun formatAge(ageMillis: Long): String {
        val days = (ageMillis / DAY_MILLIS).coerceAtLeast(1L)
        return when {
            days < DAYS_PER_MONTH -> "$days day${if (days == 1L) "" else "s"}"
            days < DAYS_PER_YEAR -> {
                val months = (days / DAYS_PER_MONTH).coerceAtLeast(1L)
                "$months month${if (months == 1L) "" else "s"}"
            }
            else -> {
                val years = (days / DAYS_PER_YEAR).coerceAtLeast(1L)
                "$years year${if (years == 1L) "" else "s"}"
            }
        }
    }

    private const val DAY_MILLIS = 24L * 60L * 60L * 1_000L
    private const val DAYS_PER_MONTH = 30L
    private const val DAYS_PER_YEAR = 365L

    /** A screenshot must be at least this old before it is worth mentioning. Roughly six months. */
    private const val SCREENSHOT_AGE_THRESHOLD_MILLIS = 180L * DAY_MILLIS

    /**
     * At or below this many pixels (width times height), a photo reads as "low resolution" --
     * roughly 480x360, well under a single megapixel and smaller than anything a phone camera
     * has produced in well over a decade. Chosen to catch web-saved images, stickers and heavily
     * downscaled re-saves without catching a deliberately small but real photo; a false negative
     * here (a genuinely tiny photo that slips through) costs nothing since this is one signal
     * among four, while a false positive would put an ordinary photo in a review queue captioned
     * "low resolution" for no reason a person could see on screen.
     */
    private const val LOW_RESOLUTION_MAX_PIXELS = 480L * 360L

    /**
     * Below this [BlurDetector.sharpness] score, a photo is offered as "Very blurry". Chosen as a
     * low starting point relative to that function's own output scale (see its KDoc for what the
     * number means) rather than tuned against a corpus of real photos, which this pure-Kotlin
     * module has no access to -- [BlurDetectorTest] establishes the scale with synthetic sharp
     * and blurred patterns, and this is the constant a future pass with real photos to compare
     * against should revisit first.
     */
    private const val BLUR_SHARPNESS_THRESHOLD = 40f
}
