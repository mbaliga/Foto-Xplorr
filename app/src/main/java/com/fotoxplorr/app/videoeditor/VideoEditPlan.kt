package com.fotoxplorr.app.videoeditor

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import com.fotoxplorr.app.video.CropAspect
import com.fotoxplorr.app.video.VideoExportOptions
import com.fotoxplorr.app.video.exportedDurationMs

/**
 * What has been done to a video, as data rather than as frames — the exact philosophy of the
 * photo editor's `EditRecipe`, applied to time-based media. Nothing here touches a decoder; the
 * plan is a small value, and pixels/samples are produced from it only at preview (ExoPlayer) and
 * export (Media3 Transformer, via [toExportOptions] and [com.fotoxplorr.app.video.VideoExporter])
 * time. The original file is never written to: like every edit in this app, "save" means "save a
 * copy". Ported from the Media3-based prototype built on `origin/claude/fotoz-continue`
 * (`videoedit/VideoEditPlan.kt`), reusing [CropAspect] from `com.fotoxplorr.app.video` rather than
 * a second, duplicate copy of the same geometry so the plain conversion path and this editor agree
 * on one crop implementation.
 *
 * Fields deliberately mirror the recipe's conventions where the concepts overlap
 * ([quarterTurns], [flipHorizontal]) so the two editors stay one mental model.
 */
data class VideoEditPlan(
    /** Total source duration, as reported by the loaded asset. The plan is meaningless without it. */
    val sourceDurationMs: Long,
    /** Trim in-point, inclusive, ms. */
    val trimStartMs: Long = 0L,
    /** Trim out-point, exclusive, ms. [sourceDurationMs] means "to the end". */
    val trimEndMs: Long = sourceDurationMs,
    /** Quarter turns clockwise, 0..3 — same convention as the photo recipe. */
    val quarterTurns: Int = 0,
    /** Mirror horizontally, applied after rotation — same convention as the photo recipe. */
    val flipHorizontal: Boolean = false,
    /** Playback-rate multiplier applied to the whole clip. One of [SPEED_CHOICES]. */
    val speed: Float = 1f,
    /** Drop the audio track entirely. */
    val muted: Boolean = false,
    /**
     * Target aspect for a centred crop, width:height, or null for the source's own frame. A
     * centred aspect crop covers the overwhelming share of real mobile crops (square posts,
     * vertical stories); a free pan-and-zoom crop is a later, separate control.
     */
    val cropAspect: CropAspect? = null,
    /** -1f..1f, 0f = unchanged. See [VideoExportOptions] for the exact effects these map to. */
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
) {
    init {
        require(sourceDurationMs > 0) { "A video plan needs the source duration." }
        require(trimStartMs in 0 until sourceDurationMs) { "Trim start is outside the video." }
        require(trimEndMs in (trimStartMs + 1)..sourceDurationMs) {
            "Trim end must sit after trim start, inside the video."
        }
        require(quarterTurns in 0..3) { "Quarter turns are 0..3." }
        require(speed in MIN_SPEED..MAX_SPEED) { "Speed $speed is outside $MIN_SPEED..$MAX_SPEED." }
        require(brightness in -1f..1f) { "brightness must be -1..1, was $brightness" }
        require(contrast in -1f..1f) { "contrast must be -1..1, was $contrast" }
        require(saturation in -1f..1f) { "saturation must be -1..1, was $saturation" }
    }

    /** True when exporting would reproduce the source — the "Save must be disabled" test. */
    val isIdentity: Boolean
        get() = trimStartMs == 0L && trimEndMs == sourceDurationMs &&
            quarterTurns == 0 && !flipHorizontal && speed == 1f && !muted && cropAspect == null &&
            brightness == 0f && contrast == 0f && saturation == 0f

    /** The clip's duration after trimming, before the speed change. */
    val trimmedDurationMs: Long get() = trimEndMs - trimStartMs

    /** What the exported file will run for, after trim and speed. */
    val exportedDurationMs: Long get() = (trimmedDurationMs / speed).toLong()

    /** Whether the rotation swaps the frame's width and height. */
    val swapsDimensions: Boolean get() = quarterTurns % 2 == 1

    /** This recipe, translated to what [com.fotoxplorr.app.video.VideoExporter] actually consumes
     *  — the plain conversion path and this editor share that one exporter (and every hardening
     *  fix in it) rather than each growing its own Transformer wiring. [textOverlay] is layered on
     *  separately by the caller (see [VideoEditorScreen]'s own doc) since it is not part of this
     *  otherwise-rotation-saveable recipe. */
    fun toExportOptions(): VideoExportOptions = VideoExportOptions(
        trimStartMs = trimStartMs,
        trimEndMs = trimEndMs.takeIf { it != sourceDurationMs },
        speedFactor = speed,
        rotationDegrees = quarterTurns * 90,
        mirror = flipHorizontal,
        cropAspect = cropAspect,
        muted = muted,
        brightness = brightness,
        contrast = contrast,
        saturation = saturation,
    )

    companion object {
        const val MIN_SPEED = VideoExportOptions.MIN_SPEED
        const val MAX_SPEED = VideoExportOptions.MAX_SPEED

        /** The offered speeds — the same set the viewer's own playback-speed control offers, so
         *  "2x" means the same thing everywhere in this app. */
        val SPEED_CHOICES = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f)

        /** The shortest clip the editor will export; trims collapsing below this are refused. */
        const val MIN_TRIMMED_MS = 200L

        /**
         * A flat list of non-null primitives (Compose's [listSaver] requires a non-null
         * `Saveable` type, so a missing crop aspect is encoded as an empty string rather than a
         * literal `null`), so the in-progress edit survives a rotation instead of resetting to
         * the source's identity plan. [CropAspect] is saved by its enum name rather than ordinal
         * — stable across a code change that reorders the enum, unlike an ordinal.
         */
        val Saver: Saver<VideoEditPlan, Any> = listSaver(
            save = { plan ->
                listOf<Any>(
                    plan.sourceDurationMs,
                    plan.trimStartMs,
                    plan.trimEndMs,
                    plan.quarterTurns,
                    plan.flipHorizontal,
                    plan.speed,
                    plan.muted,
                    plan.cropAspect?.name.orEmpty(),
                    plan.brightness,
                    plan.contrast,
                    plan.saturation,
                )
            },
            restore = { saved: List<Any> ->
                VideoEditPlan(
                    sourceDurationMs = saved[0] as Long,
                    trimStartMs = saved[1] as Long,
                    trimEndMs = saved[2] as Long,
                    quarterTurns = saved[3] as Int,
                    flipHorizontal = saved[4] as Boolean,
                    speed = saved[5] as Float,
                    muted = saved[6] as Boolean,
                    cropAspect = (saved[7] as String).takeIf { it.isNotEmpty() }?.let { CropAspect.valueOf(it) },
                    brightness = saved[8] as Float,
                    contrast = saved[9] as Float,
                    saturation = saved[10] as Float,
                )
            },
        )
    }
}
