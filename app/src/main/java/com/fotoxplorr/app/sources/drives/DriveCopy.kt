package com.fotoxplorr.app.sources.drives

/** The words of the drive card. One short sentence each; a choice is a word, never only a colour. */
object DriveCopy {
    const val OFFER_TITLE = "Drive connected."
    const val OFFER_BODY = "Add it to your library?"
    const val ADD = "Add"
    const val NOT_NOW = "Not now"
    const val NEVER = "Never for this drive"
    const val NOTE_READ_ONLY = "Fotoz only reads it and never changes your files."

    fun offerA11y(label: String): String = "Drive connected: $label. Add it to your library?"
    fun addA11y(label: String): String = "Add $label to your library"
    fun notNowA11y(label: String): String = "Not now for $label"
    fun neverA11y(label: String): String = "Never ask again for $label"
}
