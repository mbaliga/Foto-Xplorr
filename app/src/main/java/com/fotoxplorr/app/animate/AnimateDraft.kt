package com.fotoxplorr.app.animate

import com.fotoxplorr.app.editor.EditRecipe
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.formats.MediaFormat
import kotlin.math.max
import kotlin.math.min

/** The three steps of the screen. */
enum class AnimatePage { SOURCE, ARRANGE, OUTPUT }

/** Where the frames come from. */
enum class AnimateSource { PHOTOS, CLIP, FILE }

/** One photo of the strip: its library id and how long it stays up, before the speed is applied. */
data class DraftPhoto(val id: Long, val holdMs: Int)

/**
 * Which assets of the gallery's list each source offers. Photos and videos come only from the list
 * the Experimental host gives (the browsable one, TRAPS #33); nothing here reads a file.
 */
class AnimateLibrary(assets: List<MediaAsset>) {
    /** Still photos that can be drawn: not videos, not trashed, not a moving or vector file. */
    val photos: List<MediaAsset> = assets.filter { isStill(it) }

    /** Videos, newest first as the gallery has them. */
    val videos: List<MediaAsset> = assets.filter { it.isVideo && !it.isTrashed }

    /** Files that may be moving pictures: GIFs, WebP files, and this app's own saved animations (APNG is a .png). */
    val files: List<MediaAsset> = assets.filter { isMovingCandidate(it) }

    val empty: Boolean = assets.isEmpty()
    private val photoById = photos.associateBy { it.id.value }
    private val videoById = videos.associateBy { it.id.value }
    private val fileById = files.associateBy { it.id.value }

    fun photo(id: Long): MediaAsset? = photoById[id]
    fun video(id: Long): MediaAsset? = videoById[id]
    fun file(id: Long): MediaAsset? = fileById[id]

    companion object {
        fun isStill(a: MediaAsset): Boolean =
            !a.isVideo && !a.isTrashed && a.format.isLikelyDecodable && a.format != MediaFormat.Gif && a.format != MediaFormat.Svg

        /**
         * A file worth offering as "a GIF": every GIF and WebP, and a PNG saved into this app's own
         * animation album (an APNG looks like any PNG; the file is read to find out). Whether it
         * really moves is only known when the job opens it, and it then says so.
         */
        fun isMovingCandidate(a: MediaAsset): Boolean {
            if (a.isVideo || a.isTrashed) return false
            return when (a.format) {
                MediaFormat.Gif, MediaFormat.WebP -> true
                MediaFormat.Png -> a.relativePath.orEmpty().contains(ANIMATIONS_ALBUM, ignoreCase = true)
                else -> false
            }
        }

        /** The folder name [AnimateWriter] saves into. */
        const val ANIMATIONS_ALBUM = "Fotoz/Animations"

        /** The library row that is the file just saved at [uri], if the gallery's list has it yet. Matched by the row id at the end of the uri, then by the whole string. */
        fun savedAsset(assets: List<MediaAsset>, uri: String): MediaAsset? {
            val id = uri.substringAfterLast('/').toLongOrNull()
            return assets.firstOrNull { (id != null && it.id.value == id) || (uri.isNotEmpty() && it.contentUriString == uri) }
        }
    }
}

/**
 * Everything the person has chosen on the screen, as pure data with a function for each button. Every
 * function returns a new draft and never throws; a press that cannot do anything returns the draft
 * as it was, so the screen can tell nothing changed.
 *
 * The draft holds ids and numbers, not assets, so it can be written to one string for
 * `rememberSaveable` ([encode]) and checked against the library again ([reconciled]) whenever the
 * library changes. What it keeps across process death is that string; what it does not keep is in the
 * screen's KDoc.
 *
 * @property selected the photo of the strip the move, remove and time buttons act on.
 * @property clipStartMs where the part of the video starts, [clipEndMs] where it ends.
 * @property clipFps how many frames a second are taken from the part.
 * @property speedPercent 100 is the times as chosen; a photo's time on screen is divided by this.
 * @property loop the animation repeats for ever (and, with [fade], the last photo fades back to the first).
 * @property keepTransparency keep the see-through parts of a moving file when the output can.
 * @property useEdits show each photo with the edit saved for it, where there is one.
 */
data class AnimateDraft(
    val page: AnimatePage = AnimatePage.SOURCE,
    val source: AnimateSource = AnimateSource.PHOTOS,
    val photos: List<DraftPhoto> = emptyList(),
    val selected: Int = 0,
    val clipId: Long? = null,
    val clipStartMs: Long = 0L,
    val clipEndMs: Long = DEFAULT_CLIP_MS,
    val clipFps: Int = DEFAULT_FPS,
    val fileId: Long? = null,
    val output: AnimOutput = AnimOutput.GIF,
    val loop: Boolean = true,
    val speedPercent: Int = 100,
    val fade: Boolean = false,
    val keepTransparency: Boolean = true,
    val useEdits: Boolean = false,
) {
    // ---- where the person is ---------------------------------------------------------------------------

    /** Whether Back has a step to go to before the screen closes. */
    val hasInnerStep: Boolean get() = page != AnimatePage.SOURCE

    /** Whether the chosen source is complete enough to go on. */
    val canContinue: Boolean
        get() = when (source) {
            AnimateSource.PHOTOS -> photos.size >= MIN_PHOTOS
            AnimateSource.CLIP -> clipId != null
            AnimateSource.FILE -> fileId != null
        }

    /** Whether anything is picked yet: what leaving the screen would forget. */
    val hasChoices: Boolean get() = photos.isNotEmpty() || clipId != null || fileId != null

    fun withSource(kind: AnimateSource): AnimateDraft = if (kind == source) this else copy(source = kind)

    fun next(): AnimateDraft = when {
        !canContinue -> this
        page == AnimatePage.SOURCE -> copy(page = AnimatePage.ARRANGE)
        page == AnimatePage.ARRANGE -> copy(page = AnimatePage.OUTPUT)
        else -> this
    }

    fun backed(): AnimateDraft = when (page) {
        AnimatePage.OUTPUT -> copy(page = AnimatePage.ARRANGE)
        AnimatePage.ARRANGE -> copy(page = AnimatePage.SOURCE)
        AnimatePage.SOURCE -> this
    }

    // ---- photos ------------------------------------------------------------------------------------------

    /** Tick a photo (it joins the end, taking the time of the one before it) or untick it. */
    fun togglePhoto(id: Long): AnimateDraft {
        val at = photos.indexOfFirst { it.id == id }
        if (at >= 0) return withPhotos(photos.filterIndexed { i, _ -> i != at })
        if (photos.size >= MAX_PHOTOS) return this
        return withPhotos(photos + DraftPhoto(id, photos.lastOrNull()?.holdMs ?: DEFAULT_HOLD_MS))
    }

    fun select(index: Int): AnimateDraft = if (index in photos.indices && index != selected) copy(selected = index) else this

    val canMoveLeft: Boolean get() = selected in 1 until photos.size
    val canMoveRight: Boolean get() = selected in 0 until photos.size - 1

    /** Swap the selected photo with its neighbour; the selection goes with it. */
    fun movedBy(delta: Int): AnimateDraft {
        val to = selected + delta
        if (selected !in photos.indices || to !in photos.indices || delta == 0) return this
        val list = photos.toMutableList()
        val moving = list.removeAt(selected)
        list.add(to, moving)
        return copy(photos = list, selected = to)
    }

    /** Only while more than [MIN_PHOTOS] remain: the strip is never emptied from here. */
    val canRemove: Boolean get() = photos.size > MIN_PHOTOS && selected in photos.indices

    fun removed(): AnimateDraft {
        if (!canRemove) return this
        return withPhotos(photos.filterIndexed { i, _ -> i != selected }, keep = min(selected, photos.size - 2))
    }

    /** The selected photo's time on screen changed by [deltaMs], kept within [MIN_HOLD_MS] to [MAX_HOLD_MS]. */
    fun holdBy(deltaMs: Int): AnimateDraft {
        val p = photos.getOrNull(selected) ?: return this
        val ms = (p.holdMs + deltaMs).coerceIn(MIN_HOLD_MS, MAX_HOLD_MS)
        if (ms == p.holdMs) return this
        return copy(photos = photos.mapIndexed { i, q -> if (i == selected) q.copy(holdMs = ms) else q })
    }

    fun canHoldBy(deltaMs: Int): Boolean {
        val p = photos.getOrNull(selected) ?: return false
        return (p.holdMs + deltaMs).coerceIn(MIN_HOLD_MS, MAX_HOLD_MS) != p.holdMs
    }

    /** Every photo takes the selected one's time on screen. */
    fun sameForAll(): AnimateDraft {
        val p = photos.getOrNull(selected) ?: return this
        if (photos.all { it.holdMs == p.holdMs }) return this
        return copy(photos = photos.map { it.copy(holdMs = p.holdMs) })
    }

    val canSameForAll: Boolean get() = photos.getOrNull(selected)?.let { p -> photos.any { it.holdMs != p.holdMs } } == true

    private fun withPhotos(list: List<DraftPhoto>, keep: Int = selected): AnimateDraft =
        copy(photos = list, selected = if (list.isEmpty()) 0 else keep.coerceIn(0, list.size - 1))

    // ---- a part of a video ---------------------------------------------------------------------------------

    /** Pick [video]: the part starts at its beginning and runs [DEFAULT_CLIP_MS] (or all of a shorter video). */
    fun pickClip(video: MediaAsset): AnimateDraft {
        if (clipId == video.id.value) return this
        val end = if (video.durationMillis > 0) min(DEFAULT_CLIP_MS, video.durationMillis) else DEFAULT_CLIP_MS
        return copy(clipId = video.id.value, clipStartMs = 0L, clipEndMs = max(end, MIN_CLIP_MS))
    }

    fun pickFile(file: MediaAsset): AnimateDraft = if (fileId == file.id.value) this else copy(fileId = file.id.value)

    /** The part's start moved by [deltaMs]: never below 0, and at least [MIN_CLIP_MS] short of the end. */
    fun startBy(deltaMs: Long): AnimateDraft {
        val start = (clipStartMs + deltaMs).coerceIn(0L, max(0L, clipEndMs - MIN_CLIP_MS))
        return if (start == clipStartMs) this else copy(clipStartMs = start)
    }

    /**
     * The part's end moved by [deltaMs]: at least [MIN_CLIP_MS] past the start, no later than
     * [limitMs] (the video's length), and no longer than [MAX_CLIP_MS] in all.
     */
    fun endBy(deltaMs: Long, limitMs: Long): AnimateDraft {
        val low = clipStartMs + MIN_CLIP_MS
        val end = (clipEndMs + deltaMs).coerceIn(low, max(low, min(limitMs, clipStartMs + MAX_CLIP_MS)))
        return if (end == clipEndMs) this else copy(clipEndMs = end)
    }

    val clipLengthMs: Long get() = clipEndMs - clipStartMs

    fun fpsBy(delta: Int): AnimateDraft {
        val fps = (clipFps + delta).coerceIn(MIN_UI_FPS, MAX_UI_FPS)
        return if (fps == clipFps) this else copy(clipFps = fps)
    }

    // ---- settings ----------------------------------------------------------------------------------------

    fun withOutput(o: AnimOutput): AnimateDraft = if (o == output) this else copy(output = o)
    fun withLoop(on: Boolean): AnimateDraft = if (on == loop) this else copy(loop = on)
    fun withFade(on: Boolean): AnimateDraft = if (on == fade) this else copy(fade = on)
    fun withKeepTransparency(on: Boolean): AnimateDraft = if (on == keepTransparency) this else copy(keepTransparency = on)
    fun withUseEdits(on: Boolean): AnimateDraft = if (on == useEdits) this else copy(useEdits = on)

    fun speedBy(steps: Int): AnimateDraft {
        val p = (speedPercent + steps * SPEED_STEP).coerceIn(SPEED_MIN, SPEED_MAX)
        return if (p == speedPercent) this else copy(speedPercent = p)
    }

    // ---- against the library ---------------------------------------------------------------------------------

    /**
     * The draft against what the gallery offers now: a photo, video or file that was locked, trashed or
     * deleted since drops out, a clip is held within its video's length, and the screen goes back to
     * step 1 if what it needs is gone. An empty library is a gallery still loading as much as an empty
     * one, so it is never taken as "everything is gone" (the palette builder's rule).
     */
    fun reconciled(library: AnimateLibrary): AnimateDraft {
        if (library.empty) return this
        var d = this
        val kept = d.photos.filter { library.photo(it.id) != null }
        if (kept.size != d.photos.size) d = d.withPhotos(kept)
        val video = d.clipId?.let(library::video)
        if (d.clipId != null && video == null) d = d.copy(clipId = null)
        if (video != null && video.durationMillis > 0 && d.clipEndMs > video.durationMillis) {
            val end = max(video.durationMillis, MIN_CLIP_MS)
            d = d.copy(clipEndMs = end, clipStartMs = min(d.clipStartMs, max(0L, end - MIN_CLIP_MS)))
        }
        if (d.fileId != null && library.file(d.fileId) == null) d = d.copy(fileId = null)
        if (d.hasInnerStep && !d.canContinue) d = d.copy(page = AnimatePage.SOURCE)
        return d
    }

    // ---- the request ------------------------------------------------------------------------------------------

    /** How long [photo] stays up once [speedPercent] is applied. */
    fun effectiveHoldMs(photo: DraftPhoto): Int =
        (photo.holdMs.toLong() * 100L / speedPercent).toInt().coerceIn(1, AnimStill.MAX_HOLD_MS)

    private fun transition(): StillTransition? =
        if (fade) StillTransition(frames = FADE_FRAMES, frameMs = FADE_FRAME_MS, loop = loop) else null

    /**
     * What the job is asked to make, or null when the draft is not complete. [edit] is asked for a
     * photo's saved edit only when [useEdits] is on.
     */
    fun toRequest(library: AnimateLibrary, edit: (MediaAsset) -> EditRecipe? = { null }): AnimateRequest? {
        val input: AnimateInput = when (source) {
            AnimateSource.PHOTOS -> {
                if (photos.size < MIN_PHOTOS) return null
                val items = photos.map { p ->
                    val asset = library.photo(p.id) ?: return null
                    AnimStill(asset, effectiveHoldMs(p), if (useEdits) edit(asset) else null)
                }
                AnimateInput.Stills(items, transition())
            }
            AnimateSource.CLIP -> {
                val video = clipId?.let(library::video) ?: return null
                if (clipEndMs <= clipStartMs) return null
                AnimateInput.Clip(video, clipStartMs, clipEndMs, clipFps.toDouble())
            }
            AnimateSource.FILE -> AnimateInput.Existing(fileId?.let(library::file) ?: return null)
        }
        return AnimateRequest(
            input = input,
            output = output,
            plays = if (loop) 0 else 1,
            keepTransparency = source == AnimateSource.FILE && keepTransparency && !output.isVideo,
        )
    }

    /**
     * A key for a preview or an estimate: everything that changes what the frames look like or how long
     * they last, and nothing else (not the step, the selection or the file type).
     */
    fun previewKey(): String = buildString {
        append(source.name).append('|')
        when (source) {
            AnimateSource.PHOTOS -> {
                append(photos.joinToString(",") { "${it.id}:${effectiveHoldMs(it)}" })
                append('|').append(fade).append(loop && fade).append(useEdits)
            }
            AnimateSource.CLIP -> append(clipId).append(':').append(clipStartMs).append(':').append(clipEndMs).append(':').append(clipFps)
            AnimateSource.FILE -> append(fileId)
        }
    }

    // ---- one string for rememberSaveable --------------------------------------------------------------------------

    /** One line of numbers, then the photos as `id:hold` pairs. Nothing here is a file, a path or a pixel. */
    fun encode(): String = buildString {
        append(FORMAT).append(';')
        append(page.name).append(';')
        append(source.name).append(';')
        append(selected).append(';')
        append(clipId?.toString().orEmpty()).append(';')
        append(clipStartMs).append(';')
        append(clipEndMs).append(';')
        append(clipFps).append(';')
        append(fileId?.toString().orEmpty()).append(';')
        append(output.name).append(';')
        append(flag(loop)).append(';')
        append(speedPercent).append(';')
        append(flag(fade)).append(';')
        append(flag(keepTransparency)).append(';')
        append(flag(useEdits)).append(';')
        append(photos.joinToString(",") { "${it.id}:${it.holdMs}" })
    }

    companion object {
        const val MIN_PHOTOS = 2
        const val MAX_PHOTOS = 100
        const val DEFAULT_HOLD_MS = 1000
        const val MIN_HOLD_MS = 100
        const val MAX_HOLD_MS = 10_000

        /** The two sizes of a time-on-screen step. */
        const val HOLD_FINE_MS = 100
        const val HOLD_COARSE_MS = 500

        const val SPEED_MIN = 25
        const val SPEED_MAX = 400
        const val SPEED_STEP = 25

        const val MIN_CLIP_MS = 100L
        const val DEFAULT_CLIP_MS = 5_000L
        const val MAX_CLIP_MS = 60_000L

        /** A video that does not say how long it is can be taken from up to this far in. */
        const val UNKNOWN_VIDEO_LIMIT_MS = 600_000L
        const val DEFAULT_FPS = 12
        const val MIN_UI_FPS = 1
        const val MAX_UI_FPS = 30

        /** A part's start and end step in these. */
        const val CLIP_FINE_MS = 100L
        const val CLIP_COARSE_MS = 1_000L

        const val FADE_FRAMES = 4
        const val FADE_FRAME_MS = 60

        private const val FORMAT = "1"

        private fun flag(b: Boolean) = if (b) "1" else "0"

        private fun flag(text: String): Boolean? = when (text) {
            "1" -> true
            "0" -> false
            else -> null
        }

        /** The draft [encode] wrote, or null for anything else (a different format, a cut-off string, damage). Values are kept in range. */
        fun decode(text: String): AnimateDraft? {
            val parts = text.split(';')
            if (parts.size != 16 || parts[0] != FORMAT) return null
            val page = AnimatePage.entries.firstOrNull { it.name == parts[1] } ?: return null
            val source = AnimateSource.entries.firstOrNull { it.name == parts[2] } ?: return null
            val selected = parts[3].toIntOrNull() ?: return null
            val clipId = if (parts[4].isEmpty()) null else parts[4].toLongOrNull() ?: return null
            val start = parts[5].toLongOrNull() ?: return null
            val end = parts[6].toLongOrNull() ?: return null
            val fps = parts[7].toIntOrNull() ?: return null
            val fileId = if (parts[8].isEmpty()) null else parts[8].toLongOrNull() ?: return null
            val output = AnimOutput.entries.firstOrNull { it.name == parts[9] } ?: return null
            val loop = flag(parts[10]) ?: return null
            val speed = parts[11].toIntOrNull() ?: return null
            val fade = flag(parts[12]) ?: return null
            val keep = flag(parts[13]) ?: return null
            val edits = flag(parts[14]) ?: return null
            val photos = if (parts[15].isEmpty()) emptyList() else parts[15].split(',').map { pair ->
                val bits = pair.split(':')
                if (bits.size != 2) return null
                DraftPhoto(bits[0].toLongOrNull() ?: return null, (bits[1].toIntOrNull() ?: return null).coerceIn(MIN_HOLD_MS, MAX_HOLD_MS))
            }
            val safeStart = start.coerceIn(0L, 24L * 3_600_000L)
            val safeEnd = end.coerceIn(safeStart + MIN_CLIP_MS, safeStart + MAX_CLIP_MS)
            val draft = AnimateDraft(
                page = page,
                source = source,
                photos = photos.take(MAX_PHOTOS),
                selected = selected.coerceIn(0, max(0, min(photos.size, MAX_PHOTOS) - 1)),
                clipId = clipId,
                clipStartMs = safeStart,
                clipEndMs = safeEnd,
                clipFps = fps.coerceIn(MIN_UI_FPS, MAX_UI_FPS),
                fileId = fileId,
                output = output,
                loop = loop,
                speedPercent = (speed / SPEED_STEP * SPEED_STEP).coerceIn(SPEED_MIN, SPEED_MAX),
                fade = fade,
                keepTransparency = keep,
                useEdits = edits,
            )
            // A step that needs a complete source is not kept for an incomplete one.
            return if (draft.hasInnerStep && !draft.canContinue) draft.copy(page = AnimatePage.SOURCE) else draft
        }
    }
}
