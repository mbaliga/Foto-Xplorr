package com.fotoxplorr.app.sources.previews

import com.fotoxplorr.app.sources.ui.SourcesUiModel

/** The words the offline previews own. One short sentence each; offline is a glyph and a word, never a grey alone. */
object PreviewsCopy {
    const val PLUG_IN = "Plug in the drive."
    const val OFFLINE_WORD = "Offline"
    const val NO_PREVIEW = "Plug in the drive to see this photo."
    const val CLEAR = "Clear previews"
    const val KEEP = "Keep previews for offline"
    const val KEEP_HELP = "Small pictures stay on this phone so the photos can be browsed when the drive is away."
    const val CLEARED = "Previews cleared."
    const val CAP_TITLE = "Space for previews"
    const val CAP_OFF = "Off"
    const val CAP_250 = "250 MB"
    const val CAP_1G = "1 GB"
    const val CAP_2G = "2 GB"
    const val CAP_CUSTOM = "Custom"
    const val CAP_OFF_NOTE = "Previews are off for this folder."

    fun capA11y(label: String, selected: Boolean): String = if (selected) "$CAP_TITLE: $label, selected" else "$CAP_TITLE: $label"

    /** What a screen reader says for a greyed tile: the file, that it is offline and what to do. */
    fun offlineA11y(name: String): String = "$name, $OFFLINE_WORD. $PLUG_IN"

    fun size(bytes: Long): String = "Previews: ${SourcesUiModel.formatBytes(bytes)}"

    fun clearA11y(source: String): String = "$CLEAR for $source"
}
