package com.fotoxplorr.app.reel

import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.formats.MediaFormat
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.min

/** The slow moves the editor offers for a photo. Each is one button; the maths of each is in [ReelMoves]. */
enum class MotionChoice { STILL, ZOOM_IN, ZOOM_OUT, PAN_LEFT, PAN_RIGHT, PAN_UP, PAN_DOWN }

/**
 * Turns the editor's move buttons into [ReelMotion]s and back. Pure.
 *
 * A pan runs between two windows of the frame's own shape, each [ReelMotion.ZOOM_SCALE] times closer than the
 * whole picture (or the whole picture's width or height when that side is already thin), one against each
 * edge. The picture's shape comes from the library's listing, which may be the file's before its turn, so a
 * photo that is stored sideways can pan along the wrong axis; [ReelGeometry.fit] keeps the window inside the
 * picture either way. Nothing has run on a device.
 */
object ReelMoves {
    private const val THIN = 0.8f

    /** Which button a stored motion is: a pan is told by the way its window travels. */
    fun choiceOf(motion: ReelMotion): MotionChoice = when (motion) {
        ReelMotion.None -> MotionChoice.STILL
        is ReelMotion.ZoomIn -> MotionChoice.ZOOM_IN
        is ReelMotion.ZoomOut -> MotionChoice.ZOOM_OUT
        is ReelMotion.Pan -> {
            val dx = motion.end.centreX - motion.start.centreX
            val dy = motion.end.centreY - motion.start.centreY
            if (abs(dx) >= abs(dy)) {
                if (dx >= 0f) MotionChoice.PAN_RIGHT else MotionChoice.PAN_LEFT
            } else {
                if (dy > 0f) MotionChoice.PAN_DOWN else MotionChoice.PAN_UP
            }
        }
    }

    /**
     * The motion for [choice] on a picture [imageAspect] (width over height) shown in a frame of [frameAspect].
     * A zoom aims at the middle.
     */
    fun motionFor(choice: MotionChoice, imageAspect: Float, frameAspect: Float): ReelMotion {
        val (coverW, coverH) = ReelGeometry.coverSize(imageAspect, frameAspect)
        return when (choice) {
            MotionChoice.STILL -> ReelMotion.None
            MotionChoice.ZOOM_IN -> ReelMotion.ZoomIn(NormPoint.CENTRE)
            MotionChoice.ZOOM_OUT -> ReelMotion.ZoomOut(NormPoint.CENTRE)
            MotionChoice.PAN_RIGHT, MotionChoice.PAN_LEFT -> {
                val scale = if (coverW < THIN) 1f else ReelMotion.ZOOM_SCALE
                val w = coverW / scale
                val h = coverH / scale
                val top = (1f - h) / 2f
                val left = NormRect(0f, top, w, top + h)
                val right = NormRect(1f - w, top, 1f, top + h)
                if (choice == MotionChoice.PAN_RIGHT) ReelMotion.Pan(left, right) else ReelMotion.Pan(right, left)
            }
            MotionChoice.PAN_DOWN, MotionChoice.PAN_UP -> {
                val scale = if (coverH < THIN) 1f else ReelMotion.ZOOM_SCALE
                val w = coverW / scale
                val h = coverH / scale
                val left = (1f - w) / 2f
                val top = NormRect(left, 0f, left + w, h)
                val bottom = NormRect(left, 1f - h, left + w, 1f)
                if (choice == MotionChoice.PAN_DOWN) ReelMotion.Pan(top, bottom) else ReelMotion.Pan(bottom, top)
            }
        }
    }
}

/** A colour for a title card, with its name for a screen reader. */
class CardColour(val argb: Int, val name: String)

/**
 * The edits the reel editor makes, on top of [ReelProject]'s own (move, remove, duplicate, ...). Every
 * function is pure, returns a new project, and leaves an out-of-range request alone, so the screen can call
 * one from a button without checking first. What the numbers may be is [ReelLimits]; a reel that is still
 * wrong (too long, an item too short) is told by [ReelPlan], not refused here, so the person can fix it in
 * any order.
 */
object ReelEditing {
    /** One press of a time button. */
    const val STEP_MS = 500L

    /** The longest the Longer button takes a photo or a card to; a clip is trimmed instead. */
    const val EDIT_MAX_MS = 30_000L

    /** One press of the music's start button. */
    const val MUSIC_STEP_MS = 5_000L

    /** One press of the fade button. */
    const val FADE_STEP_MS = 1_000L

    /** The colours a card can be, quiet ones first (the draft's own are among them). */
    val CARD_COLOURS: List<CardColour> = listOf(
        CardColour(0xFF1B2330.toInt(), "Navy"),
        CardColour(0xFF2A1F33.toInt(), "Plum"),
        CardColour(0xFF1F2E2A.toInt(), "Pine"),
        CardColour(0xFF33261F.toInt(), "Brown"),
        CardColour(0xFF2E2A1B.toInt(), "Olive"),
        CardColour(0xFF000000.toInt(), "Black"),
        CardColour(0xFFF2EFE8.toInt(), "Cream"),
        CardColour(0xFFFFFFFF.toInt(), "White"),
    )

    /**
     * Whether [asset] may be put in a reel: not trashed, and a video, or a photo this phone is likely to decode (not a RAW
     * file it has no decoder for, and not a vector picture, which the export's decode does not rasterise). One such photo
     * would otherwise make the whole video fail, naming only its place. A GIF goes in as its first frame.
     */
    fun canUse(asset: MediaAsset): Boolean =
        !asset.isTrashed && (asset.isVideo || (asset.format.isLikelyDecodable && asset.format != MediaFormat.Svg))

    /** How many more items the project takes. */
    fun room(project: ReelProject): Int = (ReelLimits.MAX_ITEMS - project.items.size).coerceAtLeast(0)

    /** What adding photos and clips did: the new [project], how many went in, how many were left out. */
    class Added(val project: ReelProject, val added: Int, val skipped: Int)

    /**
     * [assets] added in the order given, right after [after] (an index; -1 for the front), or at the end when
     * [after] is null. A photo becomes a still of the default length; a video a clip from its start for up to
     * [ReelLimits.CLIP_MAX_MS]. A video that is under [ReelLimits.MIN_ITEM_MS] long, or has no length, is left out
     * (counted in [Added.skipped]), as is anything past [room]. A trashed asset, and a photo that is not [canUse]ble, are left out too.
     */
    fun add(project: ReelProject, assets: List<MediaAsset>, after: Int? = null): Added {
        var next = project
        var at = if (after == null) project.items.size else (after + 1).coerceIn(0, project.items.size)
        var added = 0
        var skipped = 0
        for (asset in assets) {
            if (!canUse(asset) || room(next) == 0) {
                skipped++
                continue
            }
            val id = next.freshId()
            val item: ReelItem? = if (asset.isVideo) {
                val length = asset.durationMillis
                if (length < ReelLimits.MIN_ITEM_MS) null else ClipItem(id, asset.id.value, 0L, min(length, ReelLimits.CLIP_MAX_MS), length)
            } else {
                StillItem(id, asset.id.value)
            }
            if (item == null) {
                skipped++
            } else {
                next = next.inserted(at, item)
                at++
                added++
            }
        }
        return Added(next, added, skipped)
    }

    /** A title card with [text] after [after] (an index), or at the end when null. */
    fun addCard(project: ReelProject, text: String, after: Int? = null): ReelProject {
        if (room(project) == 0) return project
        val at = if (after == null) project.items.size else (after + 1).coerceIn(0, project.items.size)
        return project.inserted(at, TitleItem(project.freshId(), cleanText(text)))
    }

    /** A copy of the item at [index] right after it, unless the project is full. */
    fun duplicate(project: ReelProject, index: Int): ReelProject = if (room(project) == 0) project else project.duplicated(index)

    /** The index of the item with [id], or -1. */
    fun indexOf(project: ReelProject, id: String?): Int = if (id == null) -1 else project.items.indexOfFirst { it.id == id }

    /**
     * Which item to pick after the one at [removed] went: the one now in its place, else the one before, else
     * none. [project] is the project AFTER the removal.
     */
    fun pickAfterRemoval(project: ReelProject, removed: Int): String? =
        project.items.getOrNull(removed)?.id ?: project.items.getOrNull(removed - 1)?.id

    /** A photo's or a card's time on screen moved by [deltaMs], held between [ReelLimits.MIN_ITEM_MS] and [EDIT_MAX_MS]. A clip is left alone (see [trim]). */
    fun nudgeDuration(project: ReelProject, index: Int, deltaMs: Long): ReelProject {
        val item = project.items.getOrNull(index) ?: return project
        return when (item) {
            is StillItem -> project.replaced(index, item.copy(durationMs = clampDuration(item.durationMs + deltaMs)))
            is TitleItem -> project.replaced(index, item.copy(durationMs = clampDuration(item.durationMs + deltaMs)))
            is ClipItem -> project
        }
    }

    private fun clampDuration(ms: Long): Long = ms.coerceIn(ReelLimits.MIN_ITEM_MS, EDIT_MAX_MS)

    /**
     * A clip's start moved by [startDeltaMs] and its end by [endDeltaMs]. The start stays at 0 or later and at
     * least [ReelLimits.MIN_ITEM_MS] before the end; the end stays within the video (when its length is known)
     * and within [ReelLimits.ITEM_MAX_MS] of the start.
     */
    fun trim(project: ReelProject, index: Int, startDeltaMs: Long, endDeltaMs: Long): ReelProject {
        val clip = project.items.getOrNull(index) as? ClipItem ?: return project
        val minLength = ReelLimits.MIN_ITEM_MS
        val start = (clip.inMs + startDeltaMs).coerceIn(0L, (clip.outMs - minLength).coerceAtLeast(0L))
        // A video whose length is not known has no ceiling of its own; the export finds out when it reads it.
        val ceiling = if (clip.sourceDurationMs > 0L) clip.sourceDurationMs else Long.MAX_VALUE / 4
        val end = (clip.outMs + endDeltaMs).coerceIn(start + minLength, maxOf(ceiling, start + minLength))
            .coerceAtMost(start + ReelLimits.ITEM_MAX_MS)
        return project.replaced(index, clip.copy(inMs = start, outMs = end))
    }

    /** The move of a photo. Other items have none. */
    fun setMotion(project: ReelProject, index: Int, motion: ReelMotion): ReelProject {
        val item = project.items.getOrNull(index) as? StillItem ?: return project
        return project.replaced(index, item.copy(motion = motion))
    }

    /** How the item at [index] hands over to the next. */
    fun setTransition(project: ReelProject, index: Int, transition: ReelTransition): ReelProject {
        val item = project.items.getOrNull(index) ?: return project
        return project.replaced(index, item.withTransition(transition))
    }

    /** The words of a card, kept to [ReelLimits.TEXT_MAX_CHARS] with no control characters other than line breaks. */
    fun setCardText(project: ReelProject, index: Int, text: String): ReelProject {
        val card = project.items.getOrNull(index) as? TitleItem ?: return project
        return project.replaced(index, card.copy(text = cleanText(text)))
    }

    fun setCardColour(project: ReelProject, index: Int, argb: Int): ReelProject {
        val card = project.items.getOrNull(index) as? TitleItem ?: return project
        return project.replaced(index, card.copy(backgroundArgb = argb))
    }

    fun cleanText(text: String): String =
        cutAt(text.filter { it == '\n' || !it.isISOControl() }, ReelLimits.TEXT_MAX_CHARS)

    /** [text] cut to at most [max] chars, not between the two halves of a character such as an emoji. */
    fun cutAt(text: String, max: Int): String {
        val cut = text.take(max)
        return if (cut.length < text.length && cut.isNotEmpty() && cut.last().isHighSurrogate()) cut.dropLast(1) else cut
    }

    /** The reel's name, with no control characters, trimmed, cut to [NAME_MAX]: what is saved. */
    fun cleanName(name: String): String = cutAt(name.filter { !it.isISOControl() }.trim(), NAME_MAX).trim()

    const val NAME_MAX = 60

    /** The name as it is typed: not trimmed (the person is still writing), the store trims it when it saves. */
    fun setName(project: ReelProject, name: String): ReelProject =
        project.copy(name = cutAt(name.filter { !it.isISOControl() }, NAME_MAX))

    fun setAspect(project: ReelProject, aspect: ReelAspect): ReelProject = project.copy(aspect = aspect)

    // ---- the sound file ----

    /** [uri] as the soundtrack. A file that replaces another keeps its fade; its start is back at the beginning. */
    fun setSoundtrack(project: ReelProject, uri: String): ReelProject {
        if (uri.isBlank()) return project
        val fade = project.soundtrack?.fadeOutMs ?: ReelLimits.FADE_OUT_DEFAULT_MS
        return project.copy(soundtrack = ReelSoundtrack(uri, 0L, fade))
    }

    fun removeSoundtrack(project: ReelProject): ReelProject = project.copy(soundtrack = null)

    fun nudgeFade(project: ReelProject, deltaMs: Long): ReelProject {
        val sound = project.soundtrack ?: return project
        return project.copy(soundtrack = sound.copy(fadeOutMs = (sound.fadeOutMs + deltaMs).coerceIn(0L, ReelLimits.FADE_OUT_MAX_MS)))
    }

    fun nudgeMusicStart(project: ReelProject, deltaMs: Long): ReelProject {
        val sound = project.soundtrack ?: return project
        return project.copy(soundtrack = sound.copy(trimStartMs = (sound.trimStartMs + deltaMs).coerceIn(0L, MUSIC_START_MAX_MS)))
    }

    /** No song is started more than an hour in by this button. */
    const val MUSIC_START_MAX_MS = 60L * 60_000L

    // ---- what is no longer there ----

    /** The indexes of the photos and clips whose library id is not in [available]. */
    fun missing(project: ReelProject, available: Set<Long>): List<Int> =
        project.items.indices.filter { i ->
            when (val item = project.items[i]) {
                is StillItem -> item.mediaId !in available
                is ClipItem -> item.mediaId !in available
                is TitleItem -> false
            }
        }

    /** The kind of an item as a word, for a label. */
    fun kindWord(item: ReelItem): String = when (item) {
        is StillItem -> ReelCopy.ITEM_STILL
        is ClipItem -> ReelCopy.ITEM_CLIP
        is TitleItem -> ReelCopy.ITEM_TITLE
    }
}

/**
 * Starting a reel from the library: which pictures a draft may use, the months to choose from, and the draft.
 * Pure, so a test gives it a list. The draft is [ReelDraftBuilder] with no extra facts (no sharpness, no
 * similarity, no places: the library's listing has none of those), so it goes by time, favourites and the order
 * given. It cannot see a blurred shot or a near-twin, but with no picture to compare it takes photos within three
 * seconds of one it kept as one burst and keeps the first, so a walk shot every two seconds loses about every other
 * photo (counted in the report's `duplicates`, which the screen does not show). Scenes are cut by gaps in time only.
 */
object ReelDrafting {
    /** The most months offered as chips; older ones are reached by choosing by hand. */
    const val MAX_MONTHS = 36

    /** A month with media in it. */
    data class Month(val year: Int, val month: Int, val count: Int) {
        /** What is kept across the process being killed. */
        val code: String get() = codeOf(YearMonth.of(year, month))
    }

    fun codeOf(month: YearMonth): String = "%04d-%02d".format(month.year, month.monthValue)

    /** What a draft may use: not trashed, not undated, nothing the phone cannot decode ([ReelEditing.canUse]); the caller's list is already the browsable one (TRAPS #33). */
    fun candidatesOf(assets: List<MediaAsset>): List<ReelCandidate> =
        assets.asSequence().filter { ReelEditing.canUse(it) && it.dateTakenMillis > 0L }.map {
            ReelCandidate(
                mediaId = it.id.value,
                dateTakenMillis = it.dateTakenMillis,
                isVideo = it.isVideo,
                durationMs = it.durationMillis,
                width = it.width,
                height = it.height,
                isFavorite = it.isFavorite,
            )
        }.toList()

    fun monthOf(millis: Long, zone: ZoneId): YearMonth = YearMonth.from(Instant.ofEpochMilli(millis).atZone(zone))

    /** The months that have something in them, newest first, at most [MAX_MONTHS]. */
    fun months(assets: List<MediaAsset>, zone: ZoneId): List<Month> =
        assets.asSequence().filter { ReelEditing.canUse(it) && it.dateTakenMillis > 0L }
            .groupingBy { monthOf(it.dateTakenMillis, zone) }.eachCount()
            .entries.sortedByDescending { it.key }.take(MAX_MONTHS)
            .map { Month(it.key.year, it.key.monthValue, it.value) }

    /** [assets] taken in one of the months with a code in [codes]; all of them when [codes] is empty. */
    fun inMonths(assets: List<MediaAsset>, codes: Set<String>, zone: ZoneId): List<MediaAsset> =
        if (codes.isEmpty()) {
            assets
        } else {
            assets.filter { it.dateTakenMillis > 0L && codeOf(monthOf(it.dateTakenMillis, zone)) in codes }
        }

    /** The lengths the draft offers, in milliseconds. */
    val LENGTHS: List<Long> = listOf(15_000L, 30_000L, 60_000L, 120_000L)

    /** Makes the draft. Heavy for a big library: call it off the main thread. */
    fun draft(assets: List<MediaAsset>, codes: Set<String>, targetMs: Long, aspect: ReelAspect, zone: ZoneId = ZoneId.systemDefault()): ReelDraft =
        ReelDraftBuilder.build(
            candidatesOf(inMonths(assets, codes, zone)),
            ReelSignals.NONE,
            ReelDraftOptions(targetMs = targetMs, aspect = aspect, zone = zone),
        )
}

/** One thing to draw in the preview: an item of the plan, how opaque and how far pushed it is, and how far through its time it is. */
class PreviewPiece(val item: PlannedItem, val alpha: Float, val shiftX: Float, val progress: Float)

/**
 * What the preview draws at a moment, from the SAME plan the export is made from ([ReelRenderPlan] and
 * [ReelLayers]), so the two agree about when a fade starts and which picture is on top. Pure.
 */
object ReelPreviewMath {
    /** The preview is drawn small; its frame's short side, in the plan's own units. */
    const val PREVIEW_SHORT_SIDE = 360

    /**
     * The plan the preview follows: the project in its aspect, with hand-overs as chosen or, for [plainCuts], none
     * (so the preview shows what the "Plain cuts" switch will make). [firstRatio] is the first picture's shape,
     * used only by the original-shape aspect.
     */
    fun planFor(project: ReelProject, plainCuts: Boolean, firstRatio: Float?): ReelRenderPlan {
        val frame = ReelFrame.of(project.aspect, PREVIEW_SHORT_SIDE, firstRatio)
        return ReelRenderPlan.build(project, ReelAttempt(PREVIEW_SHORT_SIDE, transitions = !plainCuts), frame)
    }

    /** True when the system's animator duration scale says animations are off: the preview then starts paused. */
    fun reduceMotion(animatorDurationScale: Float): Boolean = animatorDurationScale.isFinite() && animatorDurationScale <= 0f

    /** The pieces on screen at [timeUs], the one underneath first. */
    fun piecesAt(plan: ReelRenderPlan, timeUs: Long): List<PreviewPiece> {
        val out = ArrayList<PreviewPiece>(2)
        for (layer in plan.layerCount - 1 downTo 0) {
            val state = ReelLayers.stateAt(plan, layer, timeUs)
            if (state.alpha <= 0f) continue
            val item = plan.items.firstOrNull { it.layer == layer && timeUs >= it.startUs && timeUs < it.endUs } ?: continue
            val progress = if (item.durationUs > 0L) ((timeUs - item.startUs).toFloat() / item.durationUs).coerceIn(0f, 1f) else 0f
            out += PreviewPiece(item, state.alpha, state.shiftX, progress)
        }
        return out
    }

    /** [elapsedUs] folded into the reel's length, so the preview loops. */
    fun loop(plan: ReelRenderPlan, elapsedUs: Long): Long = if (plan.totalUs > 0L) Math.floorMod(elapsedUs, plan.totalUs) else 0L

    /** Where item [index] starts on the preview's clock, or 0 for an index with no item. */
    fun startOf(plan: ReelRenderPlan, index: Int): Long = plan.items.firstOrNull { it.index == index }?.startUs ?: 0L

    /** The index of the item the clock is in, or -1: where the top piece is from. */
    fun indexAt(plan: ReelRenderPlan, timeUs: Long): Int = piecesAt(plan, timeUs).lastOrNull()?.item?.index ?: -1
}
