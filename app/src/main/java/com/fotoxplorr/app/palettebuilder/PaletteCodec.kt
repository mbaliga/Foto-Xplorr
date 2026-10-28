package com.fotoxplorr.app.palettebuilder

/**
 * The plain-text form of a saved palette, for a preference, a file or a saved-state bundle:
 *
 * ```
 * fotoz-palette 1
 * name Sunset
 * color #FF8800
 * color #00AAFF locked
 * ```
 *
 * Several palettes are joined by a line of three dashes. Strict where it matters and tolerant where a
 * newer build might have added something: the header must be `fotoz-palette <whole number >= 1>`; a line
 * that is neither `name` nor `color` is ignored (so a later version can add fields); a `color` line must
 * have a valid hex, an unknown word after it is ignored, `locked` sets the lock; the colour count must be
 * 2..12 and the whole text under [MAX_TEXT_LENGTH] characters. Anything else, including a missing header,
 * is corrupt and decodes to null. Never throws. Line ends may be `\n` or `\r\n`.
 *
 * What is SAVED is whatever the caller stores this text in: this file stores nothing itself.
 */
object PaletteCodec {
    const val VERSION = 1
    const val MAX_TEXT_LENGTH = 4096
    const val MAX_SAVED = 200
    private const val HEADER = "fotoz-palette"
    private const val SEPARATOR = "---"

    fun encode(palette: Palette): String = buildString {
        append(HEADER).append(' ').append(VERSION).append('\n')
        append("name ").append(palette.name).append('\n')
        for (c in palette.colors) {
            append("color ").append(c.hex)
            if (c.locked) append(" locked")
            append('\n')
        }
    }

    fun decode(text: String): Palette? {
        if (text.length > MAX_TEXT_LENGTH) return null
        val lines = text.lines().map { it.trimEnd('\r') }.dropWhile { it.isBlank() }
        val header = lines.firstOrNull()?.trim()?.split(' ')?.filter { it.isNotEmpty() } ?: return null
        if (header.size != 2 || header[0] != HEADER) return null
        val version = header[1].toIntOrNull() ?: return null
        if (version < 1) return null

        var name: String? = null
        val colors = ArrayList<PaletteColor>()
        for (line in lines.drop(1)) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("name ") || trimmed == "name" -> if (name == null) name = trimmed.removePrefix("name").trim()
                trimmed.startsWith("color ") -> {
                    val parts = trimmed.split(' ').filter { it.isNotEmpty() }
                    val argb = if (parts[1].startsWith("#") && parts[1].length == 7) ColorMath.parseHex(parts[1]) else null
                    if (argb == null) return null
                    colors += PaletteColor(argb, locked = parts.drop(2).contains("locked"))
                    if (colors.size > Palette.MAX_COLORS) return null
                }
                else -> Unit
            }
        }
        if (colors.size !in Palette.MIN_COLORS..Palette.MAX_COLORS) return null
        return Palette(Palette.cleanName(name.orEmpty()), colors)
    }

    /** Several palettes in one text, in order. Empty list gives the empty string. */
    fun encodeAll(palettes: List<Palette>): String = palettes.joinToString(separator = "$SEPARATOR\n") { encode(it) }

    /** Every palette in [text] that decodes, in order; a damaged one is skipped, not fatal. At most [MAX_SAVED]. */
    fun decodeAll(text: String): List<Palette> {
        if (text.isBlank()) return emptyList()
        val blocks = ArrayList<String>()
        var current = StringBuilder()
        for (line in text.lines()) {
            if (line.trimEnd('\r').trim() == SEPARATOR) {
                blocks += current.toString()
                current = StringBuilder()
            } else {
                current.append(line).append('\n')
            }
        }
        blocks += current.toString()
        return blocks.mapNotNull { decode(it) }.take(MAX_SAVED)
    }
}
