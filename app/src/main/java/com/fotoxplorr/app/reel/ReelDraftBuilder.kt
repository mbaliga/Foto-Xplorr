package com.fotoxplorr.app.reel

import java.time.ZoneId
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One picture or video the draft may use, with only what the library already knows about it. The
 * dearer facts (how sharp, how alike another, where a video changes) are asked for through
 * [ReelSignals], and only for the candidates that need them.
 *
 * The caller passes ONLY what the gallery may show right now (`browsableAssets(...)`, TRAPS #33), and
 * leaves protected photos out; nothing here can tell. The renderer checks again at the moment it
 * draws, because that can change between the draft and the render.
 *
 * [dateTakenMillis] of 0 or less means "not known" and the candidate is left out (a reel is in time
 * order and has no honest place to put it). A video with no [durationMs], or one under half a second, is left out too.
 * [latitude] / [longitude] are used to split a day into places, and are never written anywhere.
 */
data class ReelCandidate(
    val mediaId: Long,
    val dateTakenMillis: Long,
    val isVideo: Boolean = false,
    val durationMs: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val isFavorite: Boolean = false,
)

/**
 * The dearer facts about a candidate, behind an interface so a test gives answers from a table and
 * the Android side gives them from the app's own stores. Each answer is `null` for "not known",
 * and the draft works without it. The draft asks lazily and at most once per candidate per call, and
 * not at all for a candidate that is alone in its scene, so a real implementation that decodes a
 * picture to answer is only paid for where the answer decides something. Calls are plain, not
 * suspending: run the whole draft off the main thread.
 */
interface ReelSignals {
    /**
     * `BlurDetector.sharpness` of a small copy of the picture: larger is sharper, on that
     * function's own scale (the default [ReelDraftOptions.blurFloor] is on it too).
     */
    fun sharpness(mediaId: Long): Float? = null

    /**
     * The 32-bit similarity signature the local index keeps for the picture
     * (`StoredEmbedding.signature`); two pictures whose signatures differ in
     * [ReelDraftOptions.duplicateMaxBits] bits or fewer are taken to be near-duplicates, the same
     * rule the similarity search uses for "near".
     */
    fun signature(mediaId: Long): Int? = null

    /**
     * The strongest key moment of a video, in milliseconds from its start (`KeyMomentDetector`'s
     * highest-confidence `DetectedMoment.positionMs`), or null when it has none.
     */
    fun keyMomentMs(mediaId: Long): Long? = null

    /** Where the eye should go in a picture (a subject the app already located), or null for no idea. */
    fun focus(mediaId: Long): NormPoint? = null

    companion object {
        /** Knows nothing; the draft then goes by time, place, favourites and the order given. */
        val NONE: ReelSignals = object : ReelSignals {}
    }
}

/** What the person (or a default) asks of the draft. Every number is clamped into a sane range on use. */
data class ReelDraftOptions(
    /** About how long the reel should be; never exceeded (see [ReelDraftBuilder]). */
    val targetMs: Long = DEFAULT_TARGET_MS,
    val aspect: ReelAspect = ReelAspect.WIDE,
    /** A gap this long between two pictures starts a new scene. */
    val sceneGapMs: Long = 30L * 60_000L,
    /** Pictures with places this far apart (both known) start a new scene. */
    val sceneDistanceMeters: Double = 1_000.0,
    /** A card with the dates (and [placeName]) first, when the reel is long enough to carry one. */
    val withTitleCard: Boolean = true,
    /** Shown under the dates on the card; the caller names the place (nothing is looked up here). */
    val placeName: String? = null,
    /** Below this sharpness a photo counts as blurred, unless it is a favourite. See the KDoc of [ReelDraftBuilder]. */
    val blurFloor: Float = BLUR_FLOOR,
    /** Signatures this many bits apart or fewer are near-duplicates. */
    val duplicateMaxBits: Int = 3,
    /** With no signature to go by, two photos this close in time are one burst. */
    val burstMs: Long = 3_000L,
    val zone: ZoneId = ZoneId.systemDefault(),
    val locale: Locale = Locale.getDefault(),
) {
    companion object {
        const val DEFAULT_TARGET_MS = 30_000L
        const val MIN_TARGET_MS = 5_000L
        const val MAX_TARGET_MS = 5L * 60_000L

        /**
         * The same number `ArchiveAdvisor` calls "very blurry" (40 on `BlurDetector`'s scale). That
         * constant is private there, so it is repeated here; change them together.
         */
        const val BLUR_FLOOR = 40f
    }
}

/** The counts behind a draft, so the screen can say what happened. Every dropped candidate is counted once. */
data class ReelDraftReport(
    val considered: Int,
    val scenes: Int,
    /** Left out for no date taken, a video of no known or of under half a second's length, or a repeated id. */
    val unusable: Int,
    val duplicates: Int,
    val blurred: Int,
    /** Good enough but over the length asked for. */
    val overLength: Int,
    /** The pictures and clips in the project (the title card is not counted). */
    val picked: Int,
)

/** A [project] for the person to edit, and the [report] of how it came about. */
data class ReelDraft(val project: ReelProject, val report: ReelDraftReport)

/**
 * Makes a first reel from a pile of pictures and videos, WITHOUT a model of any kind.
 *
 * ## Why this is deterministic heuristics, not AI
 * Everything here is arithmetic on dates, places, counts and numbers the app already computed
 * (a sharpness score, a similarity signature, a key-moment position). There is no generative model,
 * no network and no randomness: the same pile gives the same reel, byte for byte, and a person can
 * be told in a sentence why a photo was or was not chosen. The similarity signature and the key
 * moments were made elsewhere in the app (the optional local model, the frame sampler); if they are
 * missing the draft still works, from time and place alone. It will not understand a photo: it will
 * not know the cake from the candles, a funny face, or that two different-looking shots are the
 * same moment. That is what the editing is for.
 *
 * ## Steps
 * 1. Leave out what cannot be placed ([ReelDraftReport.unusable]) and sort by date taken.
 * 2. Cut the line into SCENES: a gap over [ReelDraftOptions.sceneGapMs], or two known places over
 *    [ReelDraftOptions.sceneDistanceMeters] apart, starts a new one.
 * 3. In each scene, rank photos (favourite first, then sharper first), drop near-duplicates of a
 *    better one, and drop blurred ones unless the scene would be left with nothing (then its best
 *    blurred photo stays). A favourite is never called blurred. Videos are not judged for sharpness
 *    and not deduplicated; they alternate with photos in the ranking.
 * 4. Fill the length asked for. Every scene gets its best before any scene gets a second (largest
 *    scenes first, if there is room for only some), then the rest are shared out in proportion to
 *    the square root of a scene's size, so a big event gets more of the reel without drowning a small one.
 *    A photo costs 3 s, a clip up to 4 s. The total NEVER exceeds the target: an item that does not
 *    fit is skipped (a clip is shortened if 1.5 s is left, a photo if 2.5 s), and photos are then stretched, 2.5 to 3.5
 *    s, to use what is left. The target is raised to 5 s if lower, which always has room for an item.
 * 5. Put the chosen items back in time order; cut within a scene, cross-fade between scenes.
 * 6. Give each photo a slow move: toward the subject if [ReelSignals.focus] gives one; else along the
 *    long side if the frame crops the picture a lot; else zoom in, zoom out and a diagonal pan in turn.
 *    Each clip starts a second before its key moment, or in the middle of the video if it has none.
 * 7. Put a title card with the dates (and place) first, when asked and the reel is 10 s or more.
 *
 * ## Not known
 * The 3 s, 4 s and 30 min numbers are a taste, not measured. The blur floor is the archive
 * suggestion's, which its KDoc says was never tuned on real photos. Nothing has run on a device.
 */
object ReelDraftBuilder {

    private const val TITLE_MIN_TARGET_MS = 10_000L
    private const val CLIP_LEAD_MS = 1_000L
    private const val LONG_SIDE_PAN_BELOW = 0.8f
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Dark, quiet cards; one is picked by the day of the first picture so a reel keeps its colour. */
    private val CARD_COLOURS = intArrayOf(
        0xFF1B2330.toInt(), 0xFF2A1F33.toInt(), 0xFF1F2E2A.toInt(),
        0xFF33261F.toInt(), 0xFF1F2A3A.toInt(), 0xFF2E2A1B.toInt(),
    )

    fun build(
        candidates: List<ReelCandidate>,
        signals: ReelSignals = ReelSignals.NONE,
        options: ReelDraftOptions = ReelDraftOptions(),
    ): ReelDraft {
        val target = options.targetMs.coerceIn(ReelDraftOptions.MIN_TARGET_MS, ReelDraftOptions.MAX_TARGET_MS)

        // 1. What can be placed, in time order.
        val seen = HashSet<Long>()
        val usable = ArrayList<ReelCandidate>(candidates.size)
        for (c in candidates) {
            val placed = c.dateTakenMillis > 0L && (!c.isVideo || c.durationMs >= ReelLimits.MIN_ITEM_MS)
            if (placed && seen.add(c.mediaId)) usable += c
        }
        usable.sortWith(compareBy<ReelCandidate> { it.dateTakenMillis }.thenBy { it.mediaId })
        val unusable = candidates.size - usable.size

        // 2. Scenes.
        val scenes = splitScenes(usable, options)

        // 3. Per scene: rank, dedupe, drop the blurred.
        val memo = Memo(signals)
        var duplicates = 0
        var blurred = 0
        val ranked = ArrayList<List<ReelCandidate>>(scenes.size)
        for (scene in scenes) {
            val result = rankScene(scene, memo, options)
            duplicates += result.duplicates
            blurred += result.blurred
            ranked += result.ranked
        }

        // 4. Fill the length.
        val titled = options.withTitleCard && target >= TITLE_MIN_TARGET_MS && ranked.any { it.isNotEmpty() }
        val budget = target - if (titled) ReelLimits.TITLE_DEFAULT_MS else 0L
        val chosen = choose(ranked, budget)
        val survivors = ranked.sumOf { it.size }

        // 5-6. Items in time order.
        val picks = chosen.sortedWith(compareBy<Pick> { it.candidate.dateTakenMillis }.thenBy { it.candidate.mediaId })
        val items = ArrayList<ReelItem>(picks.size + 1)
        val frameAspect = options.aspect.ratio
        var stillNumber = 0
        for ((i, pick) in picks.withIndex()) {
            val c = pick.candidate
            val boundary = i < picks.lastIndex && picks[i + 1].scene != pick.scene
            val transition = if (boundary) ReelTransition.CROSS_FADE else ReelTransition.CUT
            if (c.isVideo) {
                val (inMs, outMs) = clipWindow(c.durationMs, pick.durationMs, memo.keyMoment(c.mediaId))
                items += ClipItem("", c.mediaId, inMs, outMs, c.durationMs, transition)
            } else {
                val motion = autoMotion(c, stillNumber++, frameAspect, memo.focus(c.mediaId))
                items += StillItem("", c.mediaId, pick.durationMs, motion, transition)
            }
        }

        // 7. The card.
        val all = ArrayList<ReelItem>(items.size + 1)
        if (titled) {
            val first = picks.first().candidate.dateTakenMillis
            val last = picks.last().candidate.dateTakenMillis
            all += TitleItem(
                id = "",
                text = ReelCopy.titleText(first, last, options.placeName, options.zone, options.locale),
                backgroundArgb = CARD_COLOURS[Math.floorMod(first / 86_400_000L, CARD_COLOURS.size.toLong()).toInt()],
                durationMs = ReelLimits.TITLE_DEFAULT_MS,
                transition = ReelTransition.CROSS_FADE,
            )
        }
        all += items
        // The last item hands over to nothing.
        if (all.isNotEmpty()) all[all.lastIndex] = all.last().withTransition(ReelTransition.CUT)
        val numbered = stretchStills(all, target).mapIndexed { i, item -> item.withId("r${i + 1}") }

        val project = ReelProject(items = numbered, aspect = options.aspect)
        return ReelDraft(
            project = project,
            report = ReelDraftReport(
                considered = candidates.size,
                scenes = scenes.size,
                unusable = unusable,
                duplicates = duplicates,
                blurred = blurred,
                overLength = survivors - picks.size,
                picked = picks.size,
            ),
        )
    }

    // ---- scenes ---------------------------------------------------------------------------------

    private fun splitScenes(sorted: List<ReelCandidate>, options: ReelDraftOptions): List<List<ReelCandidate>> {
        if (sorted.isEmpty()) return emptyList()
        val gap = max(options.sceneGapMs, 60_000L)
        val scenes = ArrayList<MutableList<ReelCandidate>>()
        var current = ArrayList<ReelCandidate>()
        var previous: ReelCandidate? = null
        var anchor: ReelCandidate? = null // the last picture of the scene that has a place
        for (c in sorted) {
            val p = previous
            if (p != null) {
                val farInTime = c.dateTakenMillis - p.dateTakenMillis > gap
                val farInSpace = anchor != null && c.hasPlace() && distanceMeters(anchor, c) > options.sceneDistanceMeters
                if (farInTime || farInSpace) {
                    scenes += current
                    current = ArrayList()
                    anchor = null
                }
            }
            current += c
            if (c.hasPlace()) anchor = c
            previous = c
        }
        scenes += current
        return scenes
    }

    private fun ReelCandidate.hasPlace(): Boolean =
        latitude != null && longitude != null && latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0

    /** Great-circle distance, by the haversine formula; a sphere is exact enough for "a different place". */
    internal fun distanceMeters(a: ReelCandidate, b: ReelCandidate): Double {
        val lat1 = Math.toRadians(a.latitude ?: return 0.0)
        val lat2 = Math.toRadians(b.latitude ?: return 0.0)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians((b.longitude ?: return 0.0) - (a.longitude ?: return 0.0))
        val h = sin(dLat / 2).let { it * it } + cos(lat1) * cos(lat2) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))
    }

    // ---- ranking inside a scene -----------------------------------------------------------------

    private class SceneResult(val ranked: List<ReelCandidate>, val duplicates: Int, val blurred: Int)

    private fun rankScene(scene: List<ReelCandidate>, memo: Memo, options: ReelDraftOptions): SceneResult {
        val stills = scene.filter { !it.isVideo }
        val videos = scene.filter { it.isVideo }.sortedWith(
            compareByDescending<ReelCandidate> { it.isFavorite }.thenByDescending { it.durationMs }
                .thenBy { it.dateTakenMillis }.thenBy { it.mediaId },
        )

        var duplicates = 0
        var blurred = 0
        val keptStills: List<ReelCandidate>
        if (stills.size < 2) {
            // Alone in its scene: nothing to compare with, so nothing is asked and nothing is dropped.
            keptStills = stills
        } else {
            val byQuality = stills.sortedWith(
                compareByDescending<ReelCandidate> { it.isFavorite }
                    .thenByDescending { memo.sharpness(it.mediaId) ?: options.blurFloor }
                    .thenBy { it.dateTakenMillis }.thenBy { it.mediaId },
            )
            val sharp = ArrayList<ReelCandidate>()
            val soft = ArrayList<ReelCandidate>()
            for (c in byQuality) {
                val s = memo.sharpness(c.mediaId)
                if (!c.isFavorite && s != null && s < options.blurFloor) soft += c else sharp += c
            }
            val kept = ArrayList<ReelCandidate>()
            for (c in sharp) {
                if (kept.any { isNearDuplicate(it, c, memo, options) }) duplicates++ else kept += c
            }
            if (kept.isEmpty() && videos.isEmpty() && soft.isNotEmpty()) {
                // A scene of nothing but blurred photos keeps its best, so the day is not missing.
                kept += soft.first()
                blurred += soft.size - 1
            } else {
                blurred += soft.size
            }
            keptStills = kept
        }
        return SceneResult(alternate(keptStills, videos), duplicates, blurred)
    }

    private fun isNearDuplicate(a: ReelCandidate, b: ReelCandidate, memo: Memo, options: ReelDraftOptions): Boolean {
        val sa = memo.signature(a.mediaId)
        val sb = memo.signature(b.mediaId)
        if (sa != null && sb != null) return Integer.bitCount(sa xor sb) <= options.duplicateMaxBits
        return kotlin.math.abs(a.dateTakenMillis - b.dateTakenMillis) <= options.burstMs
    }

    /** Photos and videos taken in turn, a favourite's kind first, else a photo. */
    private fun alternate(stills: List<ReelCandidate>, videos: List<ReelCandidate>): List<ReelCandidate> {
        if (videos.isEmpty()) return stills
        if (stills.isEmpty()) return videos
        val videoFirst = videos.first().isFavorite && !stills.first().isFavorite
        val out = ArrayList<ReelCandidate>(stills.size + videos.size)
        var i = 0
        var j = 0
        var takeVideo = videoFirst
        while (i < stills.size || j < videos.size) {
            if ((takeVideo && j < videos.size) || i >= stills.size) out += videos[j++] else out += stills[i++]
            takeVideo = !takeVideo
        }
        return out
    }

    // ---- filling the length ---------------------------------------------------------------------

    private class Pick(val candidate: ReelCandidate, val scene: Int, val durationMs: Long)

    private fun nominalMs(c: ReelCandidate): Long =
        if (c.isVideo) min(c.durationMs, ReelLimits.CLIP_MAX_MS) else ReelLimits.STILL_DEFAULT_MS

    /**
     * The order in which the scenes' candidates are offered: every scene's best first (biggest scene
     * first, so if there is room for only some, the events with most pictures stay), then the rest
     * by the largest "square root of the size / picks so far" (a divisor method, so shares follow size
     * gently). Ties go to the earlier scene.
     */
    private fun priority(ranked: List<List<ReelCandidate>>): List<Pair<Int, ReelCandidate>> {
        val out = ArrayList<Pair<Int, ReelCandidate>>(ranked.sumOf { it.size })
        val taken = IntArray(ranked.size)
        ranked.indices.filter { ranked[it].isNotEmpty() }
            .sortedWith(compareByDescending<Int> { ranked[it].size }.thenBy { it })
            .forEach { s -> out += s to ranked[s][0]; taken[s] = 1 }
        while (true) {
            var best = -1
            var bestScore = -1.0
            for (s in ranked.indices) {
                if (taken[s] >= ranked[s].size) continue
                val score = sqrt(ranked[s].size.toDouble()) / (taken[s] + 1)
                if (score > bestScore) {
                    best = s
                    bestScore = score
                }
            }
            if (best < 0) break
            out += best to ranked[best][taken[best]]
            taken[best]++
        }
        return out
    }

    private fun choose(ranked: List<List<ReelCandidate>>, budgetMs: Long): List<Pick> {
        val offered = priority(ranked)
        if (offered.isEmpty()) return emptyList()
        val chosen = ArrayList<Pick>()
        var left = budgetMs
        for ((scene, c) in offered) {
            if (left < min(ReelLimits.CLIP_MIN_MS, ReelLimits.STILL_MIN_MS)) break
            val nominal = nominalMs(c)
            when {
                nominal <= left -> {
                    chosen += Pick(c, scene, nominal)
                    left -= nominal
                }
                // A clip that does not fit whole is shortened to what is left, if that is still worth seeing.
                // A photo likewise, down to the shortest a photo is shown.
                (c.isVideo && left >= ReelLimits.CLIP_MIN_MS) || (!c.isVideo && left >= ReelLimits.STILL_MIN_MS) -> {
                    chosen += Pick(c, scene, left)
                    left = 0L
                }
            }
        }
        return chosen
    }

    /**
     * Gives the time left under [budgetMs] (the whole reel's length, the card included) to the photos, 2.5 to 3.5 s each, evenly, without ever
     * passing the budget. The overlaps of cross-fades are counted, so the reel's real length
     * ([ReelProject.totalDurationMs]) is what is fitted.
     */
    private fun stretchStills(items: List<ReelItem>, budgetMs: Long): List<ReelItem> {
        val stillIndexes = items.indices.filter { items[it] is StillItem }
        if (stillIndexes.isEmpty()) return items
        val out = items.toMutableList()
        repeat(4) {
            val slack = budgetMs - ReelProject(out).totalDurationMs
            if (slack <= 0L) return out
            val room = stillIndexes.filter { out[it].durationMs < ReelLimits.STILL_MAX_MS }
            if (room.isEmpty()) return out
            val share = max(1L, slack / room.size)
            var left = slack
            for (i in room) {
                val still = out[i] as StillItem
                val add = min(min(share, left), ReelLimits.STILL_MAX_MS - still.durationMs)
                out[i] = still.copy(durationMs = still.durationMs + add)
                left -= add
            }
        }
        return out
    }

    // ---- clips and motion -----------------------------------------------------------------------

    /** The part of a video of [sourceMs] to show for [lengthMs]: from a second before the key moment, else the middle. */
    internal fun clipWindow(sourceMs: Long, lengthMs: Long, keyMomentMs: Long?): Pair<Long, Long> {
        val len = min(lengthMs, sourceMs).coerceAtLeast(0L)
        val latestStart = (sourceMs - len).coerceAtLeast(0L)
        val start = if (keyMomentMs != null) (keyMomentMs - CLIP_LEAD_MS).coerceIn(0L, latestStart) else latestStart / 2
        return start to start + len
    }

    /** A slow move for the [number]th photo (0-based, in reel order); see the class KDoc, step 6. */
    internal fun autoMotion(c: ReelCandidate, number: Int, frameAspect: Float?, focus: NormPoint?): ReelMotion {
        val imageAspect = if (c.width > 0 && c.height > 0) c.width.toFloat() / c.height else frameAspect ?: 1f
        val frame = frameAspect ?: imageAspect
        if (focus != null && focus.isInside) return ReelMotion.ZoomIn(focus)

        // A window can be a sliver (a panorama in a tall frame); a rectangle is never drawn thinner than this.
        val (rawW, rawH) = ReelGeometry.coverSize(imageAspect, frame)
        val coverW = max(rawW, ReelMotion.MIN_WINDOW_SIDE)
        val coverH = max(rawH, ReelMotion.MIN_WINDOW_SIDE)
        val forward = number % 2 == 0
        fun pan(a: NormRect, b: NormRect) = if (forward) ReelMotion.Pan(a, b) else ReelMotion.Pan(b, a)
        return when {
            coverW < LONG_SIDE_PAN_BELOW ->
                pan(NormRect(0f, 0f, coverW, 1f), NormRect(1f - coverW, 0f, 1f, 1f))
            coverH < LONG_SIDE_PAN_BELOW ->
                pan(NormRect(0f, 0f, 1f, coverH), NormRect(0f, 1f - coverH, 1f, 1f))
            else -> when (number % 3) {
                0 -> ReelMotion.ZoomIn(NormPoint.CENTRE)
                1 -> ReelMotion.ZoomOut(NormPoint.CENTRE)
                else -> {
                    val w = coverW / ReelMotion.ZOOM_SCALE
                    val h = coverH / ReelMotion.ZOOM_SCALE
                    pan(NormRect(0f, 0f, w, h), NormRect(1f - w, 1f - h, 1f, 1f))
                }
            }
        }
    }

    // ---- asking for signals once ----------------------------------------------------------------

    /** Asks [signals] at most once per id and kind of answer. */
    private class Memo(private val signals: ReelSignals) {
        private val sharp = HashMap<Long, Float?>()
        private val sig = HashMap<Long, Int?>()
        private val moment = HashMap<Long, Long?>()
        private val focus = HashMap<Long, NormPoint?>()

        fun sharpness(id: Long): Float? = once(sharp, id) { signals.sharpness(id) }
        fun signature(id: Long): Int? = once(sig, id) { signals.signature(id) }
        fun keyMoment(id: Long): Long? = once(moment, id) { signals.keyMomentMs(id) }
        fun focus(id: Long): NormPoint? = once(focus, id) { signals.focus(id) }

        /** `getOrPut` asks again when the answer was null; "not known" is an answer too. */
        private fun <T> once(cache: HashMap<Long, T?>, id: Long, ask: () -> T?): T? {
            if (cache.containsKey(id)) return cache[id]
            val answer = ask()
            cache[id] = answer
            return answer
        }
    }
}
