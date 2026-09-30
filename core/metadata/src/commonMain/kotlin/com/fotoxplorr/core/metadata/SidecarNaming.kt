package com.fotoxplorr.core.metadata

/**
 * The two sidecar-filename conventions WP1.10 (MASTER-PLAN.md §3) names by the tool that made
 * them common, both verified against primary/community documentation before being encoded here
 * (not assumed, per this project's own standing discipline for platform/format details):
 * darktable's own 4.6 user manual ("Sidecar files") for [DARKTABLE], corroborated by that same
 * manual's "Importing sidecar files" page (which documents Lightroom's format for its own
 * read-compatibility) for [LIGHTROOM] -- Adobe's own help pages returned HTTP 403 to automated
 * fetch, so this is community/secondary-sourced for Lightroom specifically, not primary Adobe.
 *
 * Deliberately does NOT model darktable's numbered "duplicate" sidecars
 * (`IMG_1234_01.CR3.xmp` for a second edit history on the same raw) -- that is darktable's own
 * multiple-develop-versions-per-raw feature, a concept this app has no equivalent of, so there is
 * nothing here for a second name to mean. [candidateSidecarFileNames] only ever looks for the
 * unnumbered, "version 0" sidecar either tool writes by default.
 */
enum class SidecarStyle {
    /** `<original name, extension replaced>.xmp` -- e.g. `IMG_1234.CR3` -> `IMG_1234.xmp`. */
    LIGHTROOM,

    /** `<original name, extension kept>.xmp` -- e.g. `IMG_1234.CR3` -> `IMG_1234.CR3.xmp`. */
    DARKTABLE,
}

/**
 * Pure filename arithmetic, no filesystem access -- see [XmpSidecarStore] (jvmMain) for the I/O
 * half that actually reads and writes these paths.
 */
object SidecarNaming {

    /** The sidecar filename [originalFileName] would have under [style]. */
    fun sidecarFileName(originalFileName: String, style: SidecarStyle): String = when (style) {
        // Darktable appends ".xmp" to the file's full existing name unconditionally -- there is
        // no "extension" to replace from darktable's point of view, only a name to extend.
        SidecarStyle.DARKTABLE -> "$originalFileName.xmp"
        // Lightroom replaces the LAST extension only -- "My.Vacation.Photo.CR3" becomes
        // "My.Vacation.Photo.xmp", not "My.Vacation.Photo.CR3.xmp". A name with no extension at
        // all has nothing to replace, so substringBeforeLast's own no-delimiter fallback (the
        // whole string, unchanged) already gives the one correct answer here without a special case.
        SidecarStyle.LIGHTROOM -> "${originalFileName.substringBeforeLast('.')}.xmp"
    }

    /**
     * Every sidecar filename worth checking for [originalFileName] when READING -- both styles,
     * since a file's existing sidecar may have been written by whichever tool the user's workflow
     * used last, regardless of which style this app itself is configured to WRITE. Deduplicated:
     * a name with no extension produces the identical filename under both styles.
     */
    fun candidateSidecarFileNames(originalFileName: String): List<String> =
        SidecarStyle.entries.map { sidecarFileName(originalFileName, it) }.distinct()
}
