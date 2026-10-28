package com.fotoxplorr.app.palettebuilder

import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.model.MediaId

/** The pages of the palette builder. The name is what is saved. */
internal enum class BuilderPage {
    /** Choose how to begin; the saved palettes are listed here. */
    START,

    /** Tick one to six photos to take the colours from. */
    PHOTOS,

    /** Type the colour to build from. */
    COLOUR,

    /** The palette itself: swatches, harmonies, checks, save and share. */
    EDIT,

    /** Pick a photo to recolour toward the palette. */
    RECOLOUR,
}

/** The three numbers the steppers move. */
internal enum class HslChannel { HUE, SATURATION, LIGHTNESS }

/** The picked colour as the steppers show it: whole degrees and whole percents. */
internal data class HslReadout(val hue: Int, val saturation: Int, val lightness: Int)

/**
 * How a recolour is done, as the screen offers it: a strength in steps of 25 percent, and whether the photo's own
 * light and dark are kept. They map onto [PaletteRecolour]'s `amount` and `keepLightness`.
 */
internal data class RecolourSettings(val strength: Int = FULL, val keepShading: Boolean = true) {
    init {
        require(strength in MIN..FULL && strength % STEP == 0) { "strength is $MIN..$FULL in steps of $STEP, got $strength" }
    }

    /** `amount` for [PaletteRecolour]: the share of the result that comes from the palette, 0..1. */
    val amount: Float get() = strength / 100f

    /** `keepLightness` for [PaletteRecolour]. A first guess, not looked at on a real photo yet. */
    val keepLightness: Float get() = if (keepShading) KEEP_SHADING else 0f

    fun stronger(): RecolourSettings = copy(strength = (strength + STEP).coerceAtMost(FULL))

    fun weaker(): RecolourSettings = copy(strength = (strength - STEP).coerceAtLeast(MIN))

    companion object {
        const val STEP = 25
        const val MIN = 25
        const val FULL = 100
        const val KEEP_SHADING = 0.7f
    }
}

/**
 * Everything the palette builder screen holds, small enough to ride a process death as one string
 * ([encode] / [decode], kept by one `rememberSaveable`). Every function is pure and returns a new draft, so a
 * plain JUnit test holds the screen's rules.
 *
 * Saved: the page, the palette (with its locks), the picked swatch, which photos the colours came from
 * ([sources]), the photos ticked or picked on the photo pages, the recolour settings, and up to [MAX_UNDO] earlier
 * palettes for Undo. NOT saved: thumbnails, the recolour preview, a recolour in progress (a killed process ends
 * it and leaves no file), a delete the person was asked to confirm, and any text typed but not yet used.
 *
 * Undo covers the two edits that throw colours away: a harmony and Remove. A stepper tap, a lock, a move and Add
 * are each undone by the opposite button, so they do not fill the undo list.
 */
internal data class PaletteDraft(
    val page: BuilderPage = BuilderPage.START,
    val palette: Palette? = null,
    val selected: Int = 0,
    /** The photos the colours were read from (ids). Kept even when a photo has gone, see [ExportGate]. */
    val sources: List<Long> = emptyList(),
    /** The photos ticked on the photos page, in the order ticked. */
    val picks: List<Long> = emptyList(),
    /** The photo chosen on the recolour page. */
    val recolourPick: Long? = null,
    val recolour: RecolourSettings = RecolourSettings(),
    val undo: List<Palette> = emptyList(),
    /** Whether the palette has changed since it was last saved or opened from the saved list. */
    val dirty: Boolean = false,
    /** Goes up whenever the palette is replaced whole, so the name field knows to show the new name. */
    val generation: Int = 0,
) {
    init {
        require(selected >= 0) { "selected must not be negative" }
    }

    val colour: PaletteColor? get() = palette?.colors?.getOrNull(selected)

    /** Whether Back has a page of its own to go to before the host closes the experiment. */
    val hasInnerStep: Boolean get() = page != BuilderPage.START

    /** The page Back goes to from here. */
    fun backed(): PaletteDraft = when (page) {
        BuilderPage.START -> this
        BuilderPage.RECOLOUR -> copy(page = BuilderPage.EDIT)
        else -> copy(page = BuilderPage.START)
    }

    fun onPage(next: BuilderPage): PaletteDraft = if (next in NEEDS_PALETTE && palette == null) this else copy(page = next)

    // ---- starting and replacing ---------------------------------------------------------------------

    /** A new palette made from photos [from] (or from nothing, an empty list) opens on the edit page. */
    fun started(made: Palette, from: List<Long> = emptyList()): PaletteDraft =
        copy(page = BuilderPage.EDIT, palette = made, selected = 0, sources = from, undo = emptyList(), dirty = true, generation = generation + 1)

    /** A palette from the saved list: not dirty, and it carries no photos (the saved text holds only colours). */
    fun opened(saved: Palette): PaletteDraft =
        copy(page = BuilderPage.EDIT, palette = saved, selected = 0, sources = emptyList(), undo = emptyList(), dirty = false, generation = generation + 1)

    /** A palette grown from one colour: [START_COLOURS] colours, the neighbours of it round the wheel, the colour first. */
    fun fromColour(argb: Int): PaletteDraft {
        val colours = Harmony.generate(argb, HarmonyKind.ANALOGOUS, START_COLOURS)
        return started(Palette.of(PaletteBuilderCopy.DEFAULT_NAME, colours) ?: return this)
    }

    // ---- the picked swatch and its numbers ----------------------------------------------------------

    fun selecting(index: Int): PaletteDraft {
        val p = palette ?: return this
        return if (index in p.colors.indices && index != selected) copy(selected = index) else this
    }

    /** The picked colour's hue, saturation and lightness in whole units, or null with no palette. */
    fun readout(): HslReadout? {
        val c = colour ?: return null
        val hsl = ColorMath.hsl(c.argb)
        return HslReadout(
            hue = Math.round(hsl.h).toInt().mod(360),
            saturation = Math.round(hsl.s * 100).toInt().coerceIn(0, 100),
            lightness = Math.round(hsl.l * 100).toInt().coerceIn(0, 100),
        )
    }

    /**
     * The picked colour with [channel] moved by [delta] (degrees for hue, percentage points for the other two).
     * Hue wraps round; saturation and lightness stop at 0 and 100. The same draft when nothing would change
     * (a grey has no hue to turn, white cannot get lighter), so a button that does nothing adds no undo or dirt.
     */
    fun nudged(channel: HslChannel, delta: Int): PaletteDraft {
        val p = palette ?: return this
        val current = colour ?: return this
        val hsl = ColorMath.hsl(current.argb)
        val next = when (channel) {
            HslChannel.HUE -> ColorMath.fromHsl(hsl.h + delta, hsl.s, hsl.l)
            HslChannel.SATURATION -> ColorMath.fromHsl(hsl.h, (hsl.s + delta / 100.0).coerceIn(0.0, 1.0), hsl.l)
            HslChannel.LIGHTNESS -> ColorMath.fromHsl(hsl.h, hsl.s, (hsl.l + delta / 100.0).coerceIn(0.0, 1.0))
        }
        if (next == current.argb) return this
        return copy(palette = p.withColor(selected, next), dirty = true)
    }

    /** The picked colour set to [argb] outright (the hex it was typed as). */
    fun withPickedColour(argb: Int): PaletteDraft {
        val p = palette ?: return this
        val next = ColorMath.opaque(argb)
        return if (colour?.argb == next) this else copy(palette = p.withColor(selected, next), dirty = true)
    }

    // ---- structure ------------------------------------------------------------------------------------

    val canAdd: Boolean get() = palette?.canAdd == true
    val canRemove: Boolean get() = palette?.canRemove == true
    val canMoveLeft: Boolean get() = palette != null && selected > 0
    val canMoveRight: Boolean get() = palette != null && selected < (palette?.size ?: 0) - 1

    /** One more swatch, a neighbour of the picked one ([NEIGHBOUR_DEGREES] round the wheel), picked. */
    fun added(): PaletteDraft {
        val p = palette ?: return this
        val base = colour?.argb ?: return this
        val hsl = ColorMath.hsl(base)
        val neighbour = ColorMath.fromHsl(hsl.h + NEIGHBOUR_DEGREES, hsl.s, hsl.l)
        val grown = p.added(neighbour) ?: return this
        return copy(palette = grown, selected = grown.size - 1, dirty = true)
    }

    /** The picked swatch taken out; the earlier palette goes on the undo list. */
    fun removed(): PaletteDraft {
        val p = palette ?: return this
        val smaller = p.removed(selected) ?: return this
        return copy(palette = smaller, selected = selected.coerceAtMost(smaller.size - 1), undo = pushed(p), dirty = true)
    }

    /** The picked swatch swapped with its neighbour [by] places away (-1 left, +1 right), still picked. */
    fun movedBy(by: Int): PaletteDraft {
        val p = palette ?: return this
        val to = selected + by
        if (by == 0 || to !in p.colors.indices) return this
        return copy(palette = p.moved(selected, to), selected = to, dirty = true)
    }

    fun toggledLock(): PaletteDraft {
        val p = palette ?: return this
        return copy(palette = p.toggledLock(selected), dirty = true)
    }

    /** The palette rebuilt by [kind] around the picked swatch; locked swatches stay. The earlier palette goes on the undo list. */
    fun harmonised(kind: HarmonyKind): PaletteDraft {
        val p = palette ?: return this
        val next = Harmony.regenerate(p, kind, selected)
        return if (next == p) this else copy(palette = next, undo = pushed(p), dirty = true)
    }

    /** The last undoable edit taken back. */
    fun undone(): PaletteDraft {
        val earlier = undo.lastOrNull() ?: return this
        return copy(palette = earlier, selected = selected.coerceAtMost(earlier.size - 1), undo = undo.dropLast(1), dirty = true, generation = generation + 1)
    }

    /** The palette under a new name (cleaned by [Palette.cleanName]). Not an undo step. */
    fun renamed(name: String): PaletteDraft {
        val p = palette ?: return this
        val next = p.renamed(name)
        return if (next == p) this else copy(palette = next, dirty = true)
    }

    /** The palette as saved: it is no longer dirty. */
    fun savedNow(): PaletteDraft = copy(dirty = false)

    private fun pushed(before: Palette): List<Palette> = (undo + before).takeLast(MAX_UNDO)

    // ---- the photo pages ------------------------------------------------------------------------------

    val canMake: Boolean get() = picks.isNotEmpty()

    /** [id] ticked if it was not (and there is room), unticked if it was. */
    fun togglePick(id: Long): PaletteDraft = when {
        id in picks -> copy(picks = picks - id)
        picks.size >= PaletteBuilderCopy.MAX_PHOTOS -> this
        else -> copy(picks = picks + id)
    }

    val isFull: Boolean get() = picks.size >= PaletteBuilderCopy.MAX_PHOTOS

    /** The photo to recolour; the same one again takes it back. */
    fun pickRecolour(id: Long): PaletteDraft = copy(recolourPick = if (recolourPick == id) null else id)

    fun withRecolour(settings: RecolourSettings): PaletteDraft = copy(recolour = settings)

    /**
     * The draft against the photos the gallery offers now: a ticked or picked photo that was locked, trashed or
     * deleted since drops out. [sources] is NOT trimmed, see [ExportGate]. An empty [available] is a library still
     * loading as much as an empty one, so it is never taken as "everything is gone".
     */
    fun reconciled(available: Set<Long>): PaletteDraft {
        if (available.isEmpty()) return this
        val keptPicks = picks.filter { it in available }
        val keptRecolour = recolourPick?.takeIf { it in available }
        if (keptPicks.size == picks.size && keptRecolour == recolourPick) return this
        return copy(picks = keptPicks, recolourPick = keptRecolour)
    }

    // ---- saving ---------------------------------------------------------------------------------------

    /**
     * One string for `rememberSaveable`: a line of numbers, then the palette in [PaletteCodec]'s text, then (if
     * any) a `===` line and the undo list in [PaletteCodec.encodeAll]'s text, oldest first.
     */
    fun encode(): String = buildString {
        append(FORMAT).append(';')
        append(page.name).append(';')
        append(selected).append(';')
        append(if (dirty) 1 else 0).append(';')
        append(sources.joinToString(",")).append(';')
        append(picks.joinToString(",")).append(';')
        append(recolourPick?.toString().orEmpty()).append(';')
        append(recolour.strength).append(';')
        append(if (recolour.keepShading) 1 else 0).append(';')
        append(generation)
        append('\n')
        val p = palette
        if (p != null) {
            append(PaletteCodec.encode(p))
            if (undo.isNotEmpty()) {
                append(UNDO_MARK).append('\n')
                append(PaletteCodec.encodeAll(undo))
            }
        }
    }

    companion object {
        private const val FORMAT = "1"
        private const val UNDO_MARK = "===\n"

        /** Earlier palettes kept for Undo. */
        const val MAX_UNDO = 10

        /** Swatches a palette started from one colour has. */
        const val START_COLOURS = 5

        /** How far round the colour wheel the colour Add makes sits from the picked one. */
        const val NEIGHBOUR_DEGREES = 30.0

        private val NEEDS_PALETTE = setOf(BuilderPage.EDIT, BuilderPage.RECOLOUR)

        /** Saved text read back. Anything damaged or from another format gives an empty draft, never a crash. */
        fun decode(text: String): PaletteDraft {
            val cut = text.indexOf('\n')
            if (cut < 0) return PaletteDraft()
            val parts = text.substring(0, cut).split(';')
            if (parts.size != 10 || parts[0] != FORMAT) return PaletteDraft()
            val page = BuilderPage.entries.firstOrNull { it.name == parts[1] } ?: return PaletteDraft()
            val selected = parts[2].toIntOrNull()?.takeIf { it >= 0 } ?: return PaletteDraft()
            val dirty = flag(parts[3]) ?: return PaletteDraft()
            val sources = ids(parts[4]) ?: return PaletteDraft()
            val picks = ids(parts[5])?.distinct()?.take(PaletteBuilderCopy.MAX_PHOTOS) ?: return PaletteDraft()
            val recolourPick = if (parts[6].isEmpty()) null else parts[6].toLongOrNull() ?: return PaletteDraft()
            val strength = parts[7].toIntOrNull() ?: return PaletteDraft()
            if (strength !in RecolourSettings.MIN..RecolourSettings.FULL || strength % RecolourSettings.STEP != 0) return PaletteDraft()
            val keep = flag(parts[8]) ?: return PaletteDraft()
            val generation = parts[9].toIntOrNull()?.takeIf { it >= 0 } ?: return PaletteDraft()

            val body = text.substring(cut + 1)
            val markAt = body.indexOf(UNDO_MARK)
            val paletteText = if (markAt < 0) body else body.substring(0, markAt)
            val undoText = if (markAt < 0) "" else body.substring(markAt + UNDO_MARK.length)
            val palette = if (paletteText.isBlank()) null else PaletteCodec.decode(paletteText)
            // A palette that was there but cannot be read is a lost draft, not a draft on a page that needs one.
            if (palette == null && paletteText.isNotBlank()) return PaletteDraft()
            val undo = PaletteCodec.decodeAll(undoText).takeLast(MAX_UNDO)

            val settled = if (palette == null && page in NEEDS_PALETTE) BuilderPage.START else page
            return PaletteDraft(
                page = settled,
                palette = palette,
                selected = if (palette == null) 0 else selected.coerceAtMost(palette.size - 1),
                sources = sources.take(MAX_SOURCES),
                picks = picks,
                recolourPick = recolourPick,
                recolour = RecolourSettings(strength, keep),
                undo = if (palette == null) emptyList() else undo,
                dirty = dirty && palette != null,
                generation = generation,
            )
        }

        /** More source ids than this are not kept: a palette is read from six photos at most. */
        private const val MAX_SOURCES = 12

        private fun flag(text: String): Boolean? = when (text) {
            "1" -> true
            "0" -> false
            else -> null
        }

        private fun ids(text: String): List<Long>? =
            if (text.isEmpty()) emptyList() else text.split(',').map { it.toLongOrNull() ?: return null }

        /** The photos of [assets] that are stills: the ones the photo pages offer. */
        fun stills(assets: List<MediaAsset>): List<MediaAsset> = assets.filter { !it.isVideo && !it.isTrashed }

        /**
         * The library row that is the copy just saved at [uri], if the gallery's list has it yet. Matched by the
         * row id at the end of the uri (the volume part of a MediaStore uri is spelled several ways), then by the
         * whole string.
         */
        fun savedAsset(assets: List<MediaAsset>, uri: String): MediaAsset? {
            val id = uri.substringAfterLast('/').toLongOrNull()?.let(::MediaId)
            return assets.firstOrNull { (id != null && it.id == id) || (uri.isNotEmpty() && it.contentUriString == uri) }
        }
    }
}

/**
 * The one rule for "may this palette leave the app (a share, a card) or be applied to a photo?".
 *
 * A palette read from photos remembers their ids. Before it is published, each of those photos is asked again
 * whether it may be exported right now (`ExperimentEnv.mayExport`, via the host), because a folder can be
 * locked, or a photo marked sensitive, while the builder is open. A photo that is no longer in the gallery's list
 * at all counts as "no": the safe reading, since the screen cannot tell a lock from a deletion.
 *
 * What this does NOT do: it cannot follow a colour once it has been typed in or saved. Saving writes colours to the
 * app's private store, which is not publishing, and a palette opened from there has no photos behind it, so the
 * person can still reach the colours by saving and reopening. The check stops an accidental share of a palette
 * whose photos were just protected; it is not a seal on the colours.
 */
internal object ExportGate {
    /** The sentence to show when [sources] may not be published, or null when they all may. */
    fun refusal(sources: List<Long>, mayExport: (Long) -> Boolean): String? =
        if (sources.all(mayExport)) null else PaletteBuilderCopy.SOURCE_PROTECTED
}
