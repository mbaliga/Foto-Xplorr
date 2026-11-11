package com.fotoxplorr.app.palette

/**
 * What the details room knows about one photo's colours, with the four answers kept apart.
 *
 * The room used to carry a bare list: a decode that failed and a photo with nothing visible in
 * it both came back empty, and both were drawn as the dash every absent fact gets -- failure
 * rendered as absence (TRAPS 32). Here "not read yet", "read, here they are", "could not be
 * read" and "read, nothing visible" are four different values, and the room says each in its own
 * words.
 */
sealed interface PaletteState {

    /** The stored palette is not in hand and the room's own read has not finished. */
    data object Loading : PaletteState

    data class Ready(val swatches: List<PaletteSwatch>) : PaletteState

    /** The file could not be read at all (corrupt, unsupported, a video with no decodable frame). */
    data object Failed : PaletteState

    /** The file was read and has no visible colour: fully transparent, say. A fact about the photo. */
    data object None : PaletteState

    companion object {
        /**
         * The state for a [PaletteSampling.read] answer: null is a failure, an empty list is a fact
         * about the photo, anything else is a palette.
         */
        fun of(read: List<PaletteSwatch>?): PaletteState = when {
            read == null -> Failed
            read.isEmpty() -> None
            else -> Ready(read)
        }
    }
}
