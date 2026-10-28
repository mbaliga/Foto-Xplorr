package com.fotoxplorr.app.animate

import com.fotoxplorr.app.editor.EditRecipe
import kotlin.math.min

/**
 * A slideshow as a [FrameSource]: pictures chosen from the library, each shown for its own time,
 * optionally with cross-fades between them ([StillsTimeline] says which entry is which).
 *
 * Each still is read through the host at the size the canvas needs ([AnimChoices.stillEdge]), with
 * its saved edit applied when it has one, placed on the canvas by [fit] (the canvas takes the first
 * picture's shape; a picture of another shape is fitted into it and the rest is [background]) and
 * flattened onto [background], so the animation is opaque. A cross-fade needs two stills at once,
 * so the last [CACHE] canvases are kept; a plain slideshow reads each photo once.
 *
 * Every read asks the host's `mayExport` first, so a photo locked while the job runs stops it with
 * [AnimSourceProtectedException] rather than being drawn.
 *
 * Nothing here has run on a device.
 */
internal class StillsFrameSource(
    private val host: AnimateHost,
    private val input: AnimateInput.Stills,
    private val fit: FitMode,
    private val background: Int,
    /** The edge the first picture is read at to learn the canvas's shape: the planner's long-edge cap. */
    private val probeEdge: Int,
) : FrameSource {

    private var timeline: StillsTimeline? = null

    private class Held(val still: Int, val width: Int, val height: Int, val argb: IntArray)

    private val cache = ArrayList<Held>()

    override suspend fun open(): SourceInfo {
        val items = input.items
        if (items.isEmpty()) throw AnimSourceException(AnimateCopy.NEED_PICTURES)
        val timeline = StillsTimeline.build(IntArray(items.size) { items[it].holdMs }, input.transition)
        this.timeline = timeline
        val first = items.first()
        if (!host.mayExport(first.asset)) throw AnimSourceProtectedException()
        val edge = AnimChoices.stillEdge(probeEdge, probeEdge, fit, cropFraction(first.recipe))
        val probe = host.still(first.asset, edge, first.recipe) ?: throw AnimSourceException(AnimateCopy.cannotRead(1))
        return SourceInfo(
            width = probe.width,
            height = probe.height,
            delaysMs = timeline.delaysMs,
            name = first.asset.displayName,
            dateTakenMillis = first.asset.dateTakenMillis,
            kind = ContentKind.PHOTO,
            // Unrelated pictures: nearly every pixel differs from the one before.
            changeFraction = 1.0,
        )
    }

    override suspend fun frame(index: Int, width: Int, height: Int): IntArray {
        val timeline = checkNotNull(timeline) { "open() first" }
        require(index in timeline.steps.indices) { "no frame $index" }
        val step = timeline.steps[index]
        val from = canvas(step.from, width, height)
        if (step.to < 0) return from.copyOf()
        val to = canvas(step.to, width, height)
        return CrossFade.blend(from, to, step.mix, input.transition?.linearLight == true)
    }

    override fun close() {
        cache.clear()
    }

    /** Still [index] placed on a [width] x [height] canvas, from the cache or the host. */
    private suspend fun canvas(index: Int, width: Int, height: Int): IntArray {
        cache.firstOrNull { it.still == index && it.width == width && it.height == height }?.let { return it.argb }
        val item = input.items[index]
        if (!host.mayExport(item.asset)) throw AnimSourceProtectedException()
        val edge = AnimChoices.stillEdge(width, height, fit, cropFraction(item.recipe))
        val raw = host.still(item.asset, edge, item.recipe) ?: throw AnimSourceException(
            if (host.mayExport(item.asset)) AnimateCopy.cannotRead(index + 1) else AnimateCopy.noLongerAvailable(index + 1),
        )
        val placed = FrameOps.flatten(FrameOps.place(raw, width, height, fit, background), background)
        cache.add(Held(index, width, height, placed))
        while (cache.size > CACHE) cache.removeAt(0)
        return placed
    }

    private fun cropFraction(recipe: EditRecipe?): Double =
        if (recipe == null) 1.0 else min(recipe.crop.width, recipe.crop.height).toDouble()

    private companion object {
        /** A cross-fade holds two canvases; a third spares re-reading the one that comes next. */
        const val CACHE = 3
    }
}
