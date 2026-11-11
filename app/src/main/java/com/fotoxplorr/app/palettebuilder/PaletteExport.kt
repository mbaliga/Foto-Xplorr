package com.fotoxplorr.app.palettebuilder

import com.fotoxplorr.app.editor.jsonArrayOf
import com.fotoxplorr.app.editor.jsonObjectOf
import com.fotoxplorr.app.editor.stringify
import com.fotoxplorr.app.editor.toJson
import java.util.Locale

/** What a palette can be written out as. */
enum class ExportFormat(val label: String, val extension: String, val mimeType: String) {
    GPL(PaletteBuilderCopy.FORMAT_GPL, "gpl", "text/plain"),
    CSS(PaletteBuilderCopy.FORMAT_CSS, "css", "text/css"),
    JSON(PaletteBuilderCopy.FORMAT_JSON, "json", "application/json"),
    CARD(PaletteBuilderCopy.FORMAT_CARD, "png", "image/png"),
}

/**
 * The text exports of a palette. Pure strings: writing them to a file is the seam's job, and a seam must
 * write a NEW file and never overwrite one (the person's photos are never touched by this tool).
 *
 * Colours are written as `#RRGGBB`, upper-case. Locks are an editing aid and are not exported; no photo,
 * location or file name is in any export, only the palette's own name and colours.
 */
object PaletteExport {

    /**
     * The GIMP palette file (`.gpl`): the header, the name, a column hint, a `#` line, then one line per
     * colour, `R G B` each right-aligned to width 3, a tab and the hex. GIMP, Inkscape and Krita read it.
     */
    fun gpl(palette: Palette): String = buildString {
        append("GIMP Palette\n")
        append("Name: ").append(palette.name).append('\n')
        append("Columns: ").append(minOf(palette.size, GPL_COLUMNS)).append('\n')
        append("#\n")
        for (c in palette.colors) {
            // Locale.ROOT: a phone set to Arabic, Persian or Bengali would write these numbers in its own digits.
            append(String.format(Locale.ROOT, "%3d %3d %3d\t%s\n", ColorMath.red(c.argb), ColorMath.green(c.argb), ColorMath.blue(c.argb), c.hex))
        }
    }

    /** CSS custom properties on `:root`, named `--<slug>-1`, `--<slug>-2` ... after the palette's name. */
    fun css(palette: Palette): String = buildString {
        val slug = slug(palette.name)
        append(":root {\n")
        palette.colors.forEachIndexed { i, c -> append("  --").append(slug).append('-').append(i + 1).append(": ").append(c.hex).append(";\n") }
        append("}\n")
    }

    /** `{"name":...,"colors":[{"hex":"#RRGGBB","rgb":[r,g,b]},...]}`, compact. */
    fun json(palette: Palette): String = jsonObjectOf(
        "name" to palette.name.toJson(),
        "colors" to jsonArrayOf(
            palette.colors.map {
                jsonObjectOf(
                    "hex" to it.hex.toJson(),
                    "rgb" to jsonArrayOf(listOf(ColorMath.red(it.argb).toJson(), ColorMath.green(it.argb).toJson(), ColorMath.blue(it.argb).toJson())),
                )
            },
        ),
    ).stringify()

    /** The text of [format], or null for [ExportFormat.CARD], which is a picture ([SwatchCard]). */
    fun text(palette: Palette, format: ExportFormat): String? = when (format) {
        ExportFormat.GPL -> gpl(palette)
        ExportFormat.CSS -> css(palette)
        ExportFormat.JSON -> json(palette)
        ExportFormat.CARD -> null
    }

    /** A file name for [palette] in [format]: the slug and the extension, no path. */
    fun fileName(palette: Palette, format: ExportFormat): String = "${slug(palette.name)}.${format.extension}"

    /** Lower-case ASCII letters and digits, other runs folded to one `-`, none at the ends; `palette` if nothing is left. */
    fun slug(name: String): String {
        val folded = buildString {
            var pendingDash = false
            for (ch in name.lowercase()) {
                if (ch in 'a'..'z' || ch in '0'..'9') {
                    if (pendingDash && isNotEmpty()) append('-')
                    pendingDash = false
                    append(ch)
                } else {
                    pendingDash = true
                }
            }
        }
        return folded.ifEmpty { "palette" }
    }

    private const val GPL_COLUMNS = 6
}
