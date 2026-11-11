package com.fotoxplorr.core.metadata

/**
 * WP3.8's "per-source policy" as one pure decision, so no caller re-derives it. Defaults are the
 * conservative ones: sidecar writing is OFF, nothing is written unless the user took an explicit
 * action (a rating tap is the user's action; an index pass is not), and the file itself is never
 * touched unless [embedWhenWritable] says so.
 *
 * [style] defaults to [SidecarStyle.DARKTABLE] (`IMG.CR3.xmp`), not Lightroom's `IMG.xmp`: the
 * Lightroom name drops the extension, so a RAW+JPEG pair (`IMG_1.CR3` + `IMG_1.JPG`) would share
 * ONE sidecar and one file's rating would silently become the other's. Reading checks both
 * styles regardless ([SidecarNaming.candidateSidecarFileNames]).
 */
data class SidecarPolicy(
    val writeEnabled: Boolean = false,
    val style: SidecarStyle = SidecarStyle.DARKTABLE,
    val embedWhenWritable: Boolean = false,
) {

    /** What the source file is, as far as embedding is concerned. */
    data class Source(
        /** A camera RAW (CR3, NEF, ARW, DNG ...): the original is never written, only a sidecar. */
        val isRaw: Boolean,
        /** The format has a writer here (`WritableFormat`) AND the file can be rewritten in place. */
        val embedWritable: Boolean,
    )

    sealed interface Decision {
        data class Skip(val reason: String) : Decision
        data class WriteSidecar(val style: SidecarStyle) : Decision
        data object EmbedInFile : Decision
    }

    fun decide(source: Source, explicitUserAction: Boolean): Decision = when {
        !writeEnabled -> Decision.Skip("Sidecar writing is off.")
        !explicitUserAction -> Decision.Skip("Sidecars are written only on an explicit action.")
        source.isRaw -> Decision.WriteSidecar(style) // never into a RAW, whatever embedWhenWritable says
        embedWhenWritable && source.embedWritable -> Decision.EmbedInFile
        else -> Decision.WriteSidecar(style)
    }
}
