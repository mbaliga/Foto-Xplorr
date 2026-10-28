package com.fotoxplorr.app.chroma

import com.fotoxplorr.app.palettebuilder.Palette
import com.fotoxplorr.app.palettebuilder.PaletteLibrary
import com.fotoxplorr.app.palettebuilder.StoreSave

/** One thing the person can do with a colour in the palette list. */
internal sealed interface PaletteTarget {
    /** Add the colour to the saved palette [name]. [reason] is why that cannot be done, or null when it can. */
    class AddTo(val name: String, val size: Int, val reason: Unavailable?) : PaletteTarget {
        val available: Boolean get() = reason == null
    }

    /** Start a new palette [name] from the colour and the colour of [partner]. */
    class NewPalette(val name: String, val partner: SavedReading) : PaletteTarget

    /** A new palette needs two colours and there is only one reading to give. */
    data object NewNeedsTwo : PaletteTarget
}

internal enum class Unavailable { FULL, ALREADY_THERE }

/** What a hand-off did. */
internal sealed interface HandoffResult {
    class Added(val name: String) : HandoffResult
    class Created(val name: String) : HandoffResult
    class Full(val name: String) : HandoffResult
    class AlreadyThere(val name: String) : HandoffResult

    /** The palette list holds as many palettes as it can. */
    data object ListFull : HandoffResult

    /** The palette was removed meanwhile, or the file could not be read or written. */
    data object Failed : HandoffResult
}

/**
 * Hands a colour to the palette builder's saved list (`palettebuilder.PaletteStore`, through the
 * [PaletteLibrary] it exposes). Chroma writes no palette file of its own.
 *
 * - **A palette holds 2 to 12 colours**, so a single reading cannot make a palette by itself. A new palette
 *   is offered with the most recent OTHER reading as its second colour, and when there is none the choice
 *   says a new palette needs two colours.
 * - **Nothing is overwritten by name.** The builder's store replaces a palette of the same name (ignoring
 *   case), so a new palette gets a name no saved one has ([newName]); adding to a palette saves it under
 *   its own name on purpose, which moves it to the front.
 * - **Read just before the write.** [save] reads the list again at the moment of the choice, so a palette
 *   edited in the builder meanwhile is not saved over from an old copy. The read and the write are two calls
 *   to the store, not one transaction: a save from another screen between them is the one race left, and
 *   the builder is never open at the same time as this screen.
 * - A colour already in a palette is not added twice.
 */
internal object ChromaPalettes {
    const val BASE_NAME = "Chroma ID"

    /** [BASE_NAME], or that with the first free number after it, so no saved palette is replaced. */
    fun newName(saved: List<Palette>): String {
        val taken = saved.map { it.name.lowercase() }.toSet()
        if (BASE_NAME.lowercase() !in taken) return BASE_NAME
        var n = 2
        while ("$BASE_NAME $n".lowercase() in taken) n++
        return "$BASE_NAME $n"
    }

    /**
     * The choices for [colour], saved palettes first, then the new-palette choice. [partner] is the other
     * colour a new palette would hold (null: none to give).
     */
    fun targets(colour: Int, saved: List<Palette>, partner: SavedReading?): List<PaletteTarget> {
        val adds = saved.map { p ->
            val reason = when {
                p.argbs.any { it == colour } -> Unavailable.ALREADY_THERE
                !p.canAdd -> Unavailable.FULL
                else -> null
            }
            PaletteTarget.AddTo(p.name, p.size, reason)
        }
        val new = if (partner != null) PaletteTarget.NewPalette(newName(saved), partner) else PaletteTarget.NewNeedsTwo
        return adds + new
    }

    /** The most recent reading in [history] that is not [subject] and not the same colour, or null. */
    fun partnerFor(subject: SavedReading, history: List<SavedReading>): SavedReading? =
        history.filter { it != subject && it.argb != subject.argb }.maxByOrNull { it.atMillis }

    suspend fun save(library: PaletteLibrary, colour: Int, target: PaletteTarget): HandoffResult {
        val current = library.load()
        if (current.unreadable) return HandoffResult.Failed
        return when (target) {
            PaletteTarget.NewNeedsTwo -> HandoffResult.Failed
            is PaletteTarget.AddTo -> {
                val palette = current.palettes.firstOrNull { it.name.equals(target.name, ignoreCase = true) }
                    ?: return HandoffResult.Failed
                if (palette.argbs.any { it == colour }) return HandoffResult.AlreadyThere(palette.name)
                val grown = palette.added(colour) ?: return HandoffResult.Full(palette.name)
                when (library.save(grown)) {
                    is StoreSave.Saved -> HandoffResult.Added(palette.name)
                    StoreSave.Full -> HandoffResult.ListFull
                    StoreSave.Failed -> HandoffResult.Failed
                }
            }
            is PaletteTarget.NewPalette -> {
                // The name is chosen again from the list just read, in case one was saved since the choices were shown.
                val name = newName(current.palettes)
                val palette = Palette.of(name, listOf(colour, target.partner.argb)) ?: return HandoffResult.Failed
                when (library.save(palette)) {
                    is StoreSave.Saved -> HandoffResult.Created(palette.name)
                    StoreSave.Full -> HandoffResult.ListFull
                    StoreSave.Failed -> HandoffResult.Failed
                }
            }
        }
    }
}
