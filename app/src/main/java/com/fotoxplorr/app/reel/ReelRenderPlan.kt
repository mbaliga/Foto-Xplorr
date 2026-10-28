package com.fotoxplorr.app.reel

import kotlin.math.max
import kotlin.math.min

/** The size of the video frame, both sides even (the encoder wants that). */
data class ReelFrame(val width: Int, val height: Int) {
    init {
        require(width >= 2 && height >= 2 && width % 2 == 0 && height % 2 == 0) { "A frame needs two even sides, was ${width}x$height." }
    }

    val ratio: Float get() = width.toFloat() / height

    companion object {
        /** The widest and the tallest shape an "original" reel takes from its first picture. */
        const val MIN_RATIO = 9f / 16f
        const val MAX_RATIO = 16f / 9f

        /**
         * The frame of a reel in [aspect] whose SHORT side is [shortSide] (1080 gives 1920x1080 wide, 1080x1920 tall).
         * [ReelAspect.ORIGINAL] takes the shape of [firstPictureRatio] (width / height of the first photo or clip,
         * wide when unknown), held between 9:16 and 16:9 so a panorama does not make a sliver of a video.
         */
        fun of(aspect: ReelAspect, shortSide: Int, firstPictureRatio: Float? = null): ReelFrame {
            val ratio = aspect.ratio ?: (firstPictureRatio?.takeIf { it > 0f && it.isFinite() } ?: MAX_RATIO).coerceIn(MIN_RATIO, MAX_RATIO)
            val short = max(2, shortSide)
            return if (ratio >= 1f) ReelFrame(even(short * ratio), even(short.toFloat())) else ReelFrame(even(short.toFloat()), even(short / ratio))
        }

        private fun even(value: Float): Int {
            val n = Math.round(value)
            return n + (n and 1)
        }
    }
}

/** How a picture of another shape than the frame is placed: [COVER] fills the frame and crops, [CONTAIN] fits whole with bars. */
enum class ReelFit {
    COVER,
    CONTAIN,
    ;

    companion object {
        /** A fixed shape fills the frame; "original" keeps every picture whole. */
        fun of(aspect: ReelAspect): ReelFit = if (aspect == ReelAspect.ORIGINAL) CONTAIN else COVER
    }
}

/**
 * One way of rendering: the frame's short side, and whether the hand-overs are real ([transitions]) or
 * all cuts. The job takes the rungs of [ReelAttempts] in order when the phone says no.
 */
data class ReelAttempt(val shortSide: Int, val transitions: Boolean) {
    /** The video bitrate asked of the encoder; a request the encoder may adjust (the factory is told it may). */
    val bitrate: Int
        get() = when {
            shortSide >= 1080 -> 8_000_000
            shortSide >= 720 -> 5_000_000
            else -> 2_500_000
        }
}

/**
 * The size ladder. 1080 with real transitions first; then 720; then 720 with cuts only (one video track
 * at a time, so one decoder and no compositor, the least a phone is asked); then 480 with cuts. The
 * numbers are guesses about what phones manage; none was measured.
 */
object ReelAttempts {
    const val DEFAULT_SHORT_SIDE = 1080
    private val SIZES = listOf(1080, 720, 480)
    private val LADDER = listOf(ReelAttempt(1080, true), ReelAttempt(720, true), ReelAttempt(720, false), ReelAttempt(480, false))

    /** The rungs to try, best first, when the person asked for [shortSide] and for [transitions] or plain cuts. */
    fun from(shortSide: Int = DEFAULT_SHORT_SIDE, transitions: Boolean = true): List<ReelAttempt> {
        val start = SIZES.firstOrNull { it <= shortSide } ?: SIZES.last()
        if (!transitions) return SIZES.filter { it <= start }.map { ReelAttempt(it, false) }
        val out = LADDER.filter { it.shortSide <= start }.toMutableList()
        // A request for the smallest size still tries real hand-overs once before the cuts.
        if (start == SIZES.last()) out.add(0, ReelAttempt(start, true))
        return out
    }
}

/** What an item draws. */
enum class PlannedKind { STILL, CLIP, CARD }

/**
 * One item of a render, in microseconds on the reel's clock. [layer] is 0 or 1 (always 0 in a flat plan);
 * [gapBeforeUs] is the empty time in its layer before it starts. Stills carry [motion], clips their
 * [inUs]..[outUs] of the video, cards their [text] on [colorArgb]; [mediaId] is the library id (0 for a card).
 */
data class PlannedItem(
    val index: Int,
    val id: String,
    val kind: PlannedKind,
    val mediaId: Long,
    val motion: ReelMotion,
    val inUs: Long,
    val outUs: Long,
    val text: String,
    val colorArgb: Int,
    val startUs: Long,
    val durationUs: Long,
    val layer: Int,
    val gapBeforeUs: Long,
) {
    val endUs: Long get() = startUs + durationUs
}

/** A hand-over that lays item [fromIndex] and the next over each other from [startUs] for [durationUs]. */
data class PlannedJoin(val fromIndex: Int, val transition: ReelTransition, val startUs: Long, val durationUs: Long)

/**
 * Everything the encoder is told, as plain numbers: no photo, no Media3 type. The builder
 * ([build]) is pure so it is tested without a phone; the Android side only turns this into a Composition.
 *
 * ## Layers
 * With real hand-overs the reel is two video tracks laid over each other, the way a cross-fade needs two
 * pictures at once. Item `i` is on layer `(n - 1 - i) % 2`, so the LAST item is always on layer 0 (layer 0
 * is what drives the output's frames, and it must run to the end). Layer 0 is drawn on top. A cross-fade
 * changes the opacity of whichever of the two is on top; a slide moves both. Between its items a layer
 * holds empty time ([PlannedItem.gapBeforeUs]); [ReelLayers.stateAt] says what each layer shows when.
 * With cuts only there is one layer, no overlap, and the reel is as long as its items added up.
 *
 * ## Sound
 * [audio] is the soundtrack, trimmed to the reel; [keepClipSound] keeps the clips' own sound (off by
 * default). With neither the file has no sound track at all.
 */
data class ReelRenderPlan(
    val frame: ReelFrame,
    val fit: ReelFit,
    val fps: Int,
    val attempt: ReelAttempt,
    val items: List<PlannedItem>,
    val joins: List<PlannedJoin>,
    val layerCount: Int,
    val totalUs: Long,
    val audio: ReelAudioPlan?,
    val keepClipSound: Boolean,
) {
    val isLayered: Boolean get() = layerCount > 1
    val hasAudio: Boolean get() = audio != null || keepClipSound
    val totalMs: Long get() = totalUs / 1000L

    fun itemsOn(layer: Int): List<PlannedItem> = items.filter { it.layer == layer }

    companion object {
        const val FPS = 30

        /**
         * Plan [project] for [attempt] in [frame]. [sound] says how long the soundtrack file is (null: no
         * soundtrack, or it is not to be used). [keepClipSound] is honoured only when there is a clip.
         * The project is not changed; in a flat plan its hand-overs are all cuts and so the reel is longer
         * than [ReelProject.totalDurationMs] by the overlaps it had.
         */
        fun build(
            project: ReelProject,
            attempt: ReelAttempt,
            frame: ReelFrame,
            fit: ReelFit = ReelFit.of(project.aspect),
            sound: SoundtrackFacts? = null,
            keepClipSound: Boolean = false,
        ): ReelRenderPlan {
            val n = project.items.size
            val layered = attempt.transitions && (0 until n).any { project.overlapAfterMs(it) > 0L }
            val slots = if (layered) project.timeline() else flatTimeline(project)
            val lastEnd = longArrayOf(0L, 0L)
            val items = ArrayList<PlannedItem>(n)
            for (i in 0 until n) {
                val item = project.items[i]
                val slot = slots[i]
                val layer = if (layered && (n - 1 - i) % 2 == 1) 1 else 0
                val start = slot.startMs * 1000L
                val duration = item.durationMs * 1000L
                items += when (item) {
                    is StillItem -> planned(i, item, PlannedKind.STILL, item.mediaId, if (fit == ReelFit.CONTAIN) ReelMotion.None else item.motion, 0L, 0L, "", 0, start, duration, layer, start - lastEnd[layer])
                    is ClipItem -> planned(i, item, PlannedKind.CLIP, item.mediaId, ReelMotion.None, item.inMs * 1000L, item.outMs * 1000L, "", 0, start, duration, layer, start - lastEnd[layer])
                    is TitleItem -> planned(i, item, PlannedKind.CARD, 0L, ReelMotion.None, 0L, 0L, item.text, item.backgroundArgb, start, duration, layer, start - lastEnd[layer])
                }
                lastEnd[layer] = start + duration
            }
            val joins = ArrayList<PlannedJoin>()
            if (layered) {
                for (i in 0 until n - 1) {
                    val overlap = project.overlapAfterMs(i) * 1000L
                    if (overlap > 0L) joins += PlannedJoin(i, project.items[i].transition, items[i].endUs - overlap, overlap)
                }
            }
            val total = items.lastOrNull()?.endUs ?: 0L
            val keep = keepClipSound && project.items.any { it is ClipItem }
            val audio = if (sound != null) project.soundtrack?.let { ReelAudioPlan.plan(it, sound.durationMs, total, keep) } else null
            return ReelRenderPlan(frame, fit, FPS, attempt, items, joins, if (layered) 2 else 1, total, audio, keep)
        }

        private fun planned(
            index: Int, item: ReelItem, kind: PlannedKind, mediaId: Long, motion: ReelMotion, inUs: Long, outUs: Long,
            text: String, argb: Int, start: Long, duration: Long, layer: Int, gap: Long,
        ) = PlannedItem(index, item.id, kind, mediaId, motion, inUs, outUs, text, argb, start, duration, layer, gap.coerceAtLeast(0L))

        private fun flatTimeline(project: ReelProject): List<ReelSlot> {
            var start = 0L
            return project.items.mapIndexed { i, item ->
                ReelSlot(i, start, start + item.durationMs, 0L).also { start += item.durationMs }
            }
        }
    }
}

/** What is known of the soundtrack file before planning: how long it is, in ms (0 when the file does not say). */
data class SoundtrackFacts(val durationMs: Long)

/**
 * The soundtrack as it is laid: from [startUs] into the file, for [lengthUs] (the reel's length, or less
 * when the file runs out first), falling to silence over the last [fadeOutUs], all multiplied by [gain].
 * The file is NOT looped: a track shorter than the reel ends, with its fade, where the file ends.
 */
data class ReelAudioPlan(val uri: String, val startUs: Long, val lengthUs: Long, val fadeOutUs: Long, val gain: Float) {
    val endUs: Long get() = startUs + lengthUs

    /** The volume factor [timeUs] after the music's start (0 before it starts and after it ends). */
    fun gainAtUs(timeUs: Long): Float {
        if (timeUs < 0L || timeUs >= lengthUs) return 0f
        val fadeStart = lengthUs - fadeOutUs
        val fade = if (fadeOutUs > 0L && timeUs >= fadeStart) (lengthUs - timeUs).toFloat() / fadeOutUs else 1f
        return (gain * fade).coerceIn(0f, 1f)
    }

    companion object {
        /** The music is quieter when it is mixed with the clips' own sound; a guess, not heard on a device. */
        const val MIXED_GAIN = 0.6f

        /**
         * Lay [sound] under a reel [reelUs] long. Null when nothing would play: the file is known and
         * ends before [ReelSoundtrack.trimStartMs], or the reel is empty. [fileDurationMs] of 0 means the file did not say:
         * it is then taken as long enough, so a file that is in fact short stops where it ends, its fade not reached, and
         * nothing can tell the person (the finished-video note about a short sound needs a known length).
         */
        fun plan(sound: ReelSoundtrack, fileDurationMs: Long, reelUs: Long, mixedWithClipSound: Boolean): ReelAudioPlan? {
            if (reelUs <= 0L || sound.uri.isBlank()) return null
            val start = max(0L, sound.trimStartMs) * 1000L
            val available = if (fileDurationMs > 0L) fileDurationMs * 1000L - start else Long.MAX_VALUE
            if (available <= 0L) return null
            val length = min(reelUs, available)
            val fade = min(max(0L, sound.fadeOutMs) * 1000L, length)
            return ReelAudioPlan(sound.uri, start, length, fade, if (mixedWithClipSound) MIXED_GAIN else 1f)
        }
    }
}

/** How a layer looks at one moment: how opaque it is, and how far it is pushed sideways (frame widths are 2 in NDC; 0 is in place). */
data class LayerState(val alpha: Float, val shiftX: Float) {
    companion object {
        /** Nothing of this layer is on screen (its blank time). */
        val HIDDEN = LayerState(0f, 0f)
        val SHOWN = LayerState(1f, 0f)
    }
}

/** What each layer shows at a moment of the reel, from the plan alone. */
object ReelLayers {
    /** The frame width in normalised device coordinates; a slide moves this far. */
    const val FRAME_WIDTH_NDC = 2f

    /**
     * The state of [layer] at [timeUs] on the reel's clock: hidden outside its items; inside one, fully shown
     * except during a hand-over. A cross-fade changes only the layer on TOP (layer 0): it fades in over the
     * layer below, or fades out to show it. A slide pushes both: the outgoing item leaves to the left while
     * the incoming one comes from the right. In a flat plan every moment of layer 0 is [LayerState.SHOWN].
     */
    fun stateAt(plan: ReelRenderPlan, layer: Int, timeUs: Long): LayerState {
        val item = plan.items.firstOrNull { it.layer == layer && timeUs >= it.startUs && timeUs < it.endUs } ?: return LayerState.HIDDEN
        var alpha = 1f
        var shift = 0f
        for (join in plan.joins) {
            if (timeUs < join.startUs || timeUs >= join.startUs + join.durationUs) continue
            val progress = (timeUs - join.startUs).toFloat() / join.durationUs
            val incoming = item.index == join.fromIndex + 1
            val outgoing = item.index == join.fromIndex
            if (!incoming && !outgoing) continue
            when (join.transition) {
                ReelTransition.CROSS_FADE -> if (layer == 0) alpha = if (incoming) progress else 1f - progress
                ReelTransition.SLIDE -> {
                    val eased = ReelGeometry.ease(progress)
                    shift = if (incoming) FRAME_WIDTH_NDC * (1f - eased) else -FRAME_WIDTH_NDC * eased
                }
                ReelTransition.CUT -> Unit
            }
        }
        return LayerState(alpha.coerceIn(0f, 1f), shift)
    }
}

/** A window of a picture as the centre and half-sides of a crop in normalised device coordinates (x right, y up, -1..1). */
data class NdcWindow(val centerX: Float, val centerY: Float, val halfWidth: Float, val halfHeight: Float) {
    companion object {
        val FULL = NdcWindow(0f, 0f, 1f, 1f)

        /** [window], a rectangle on the picture (0..1 from the top left), as the crop that shows exactly that part. */
        fun of(window: NormRect): NdcWindow = NdcWindow(
            centerX = window.left + window.right - 1f,
            centerY = 1f - window.top - window.bottom,
            halfWidth = window.width,
            halfHeight = window.height,
        )
    }

    /** The scale and shift that make this window fill the frame: out = in * scale + shift, per axis. */
    val scaleX: Float get() = 1f / halfWidth
    val scaleY: Float get() = 1f / halfHeight
    val shiftX: Float get() = -centerX / halfWidth
    val shiftY: Float get() = -centerY / halfHeight
}

/** The Ken Burns of one still, as numbers. */
object ReelStillMath {
    /**
     * The crop to apply to a still whose upright picture is [imageWidth] x [imageHeight] at [progress] (0..1
     * through its time on screen) so that the frame of shape [frameRatio] shows the part [ReelGeometry.windowAt] picks. The move
     * is eased. The crop is meant to be followed by a stretch of the picture to the frame's size (the window
     * already has the frame's shape in pixels, so nothing is squashed).
     */
    fun cropAt(motion: ReelMotion, progress: Float, imageWidth: Int, imageHeight: Int, frameRatio: Float): NdcWindow {
        if (motion == ReelMotion.None) return NdcWindow.of(ReelGeometry.windowAt(ReelMotion.None, 0f, imageWidth, imageHeight, frameRatio))
        return NdcWindow.of(ReelGeometry.windowAt(motion, ReelGeometry.ease(progress), imageWidth, imageHeight, frameRatio))
    }

    /** The longest side a decoded still must have for a window of its motion to show a frame of [frame] at full detail. */
    const val MAX_STAGED_EDGE = 3072

    /**
     * How many pixels the long side of a staged still should have when the library lists the picture as
     * [hintWidth] x [hintHeight] (0 for unknown): enough that the smallest window the motion shows is as sharp
     * as the frame, between the frame's own long side and [MAX_STAGED_EDGE]. The listing may be the file's size
     * before its EXIF turn, so the larger of the two readings is taken; [stagedLongEdgeFor] with the real upright
     * shape is exact and asks for less.
     */
    fun stagedLongEdge(motion: ReelMotion, hintWidth: Int, hintHeight: Int, frame: ReelFrame): Int {
        if (hintWidth <= 0 || hintHeight <= 0) return stagedLongEdgeFor(motion, frame.ratio, frame)
        val shape = hintWidth.toFloat() / hintHeight
        return max(stagedLongEdgeFor(motion, shape, frame), stagedLongEdgeFor(motion, 1f / shape, frame))
    }

    /** [stagedLongEdge] for a picture whose upright width over height is [shape]. */
    fun stagedLongEdgeFor(motion: ReelMotion, shape: Float, frame: ReelFrame): Int {
        val floor = max(frame.width, frame.height)
        if (motion == ReelMotion.None || !(shape > 0f) || !shape.isFinite()) return floor
        val (a, b) = ReelGeometry.endpoints(motion, shape, frame.ratio)
        var need = floor
        for (w in listOf(a, b)) {
            // The window w.width of the picture's width must hold frame.width pixels, and likewise for height.
            val byWidth = frame.width / max(w.width, ReelMotion.MIN_WINDOW_SIDE)
            val byHeight = frame.height / max(w.height, ReelMotion.MIN_WINDOW_SIDE)
            // Pixels across the long side of that picture shape.
            val longByWidth = if (shape >= 1f) byWidth else byWidth / shape
            val longByHeight = if (shape >= 1f) byHeight * shape else byHeight
            // The small allowance keeps a float that lands a hair over a whole number from asking for one pixel more.
            need = max(need, Math.ceil(max(longByWidth, longByHeight).toDouble() - 0.01).toInt())
        }
        return need.coerceIn(floor, MAX_STAGED_EDGE)
    }
}
