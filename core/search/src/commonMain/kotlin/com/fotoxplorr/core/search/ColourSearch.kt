package com.fotoxplorr.core.search

import kotlin.math.cbrt
import kotlin.math.sqrt

/**
 * `colour:` -- searching a library by the colours in its photos.
 *
 * The recognition pass stores each photo's dominant colours (up to five, each with the share of the
 * image it covers). A `colour:` term names a target -- a CSS-style hex value such as `#1a2b3c`, or a
 * word from [ColourNames] such as `teal` -- and a photo matches when one of its stored colours that
 * covers at least [ColourSearch.MIN_PROPORTION] of the image is *perceptually close* to that target.
 *
 * "Close" is measured in CIE L*a*b* (D65), not RGB. RGB distance says `#0000ff` and `#0000cc` are as
 * far apart as `#0000ff` and `#3333ff`, and a human says one pair is the same blue while the other is
 * two blues; Lab was built so that equal distances mean roughly equal perceived differences. The
 * distance itself is CIE76 -- a plain Euclidean distance over (L, a, b). CIE94 and CIEDE2000 weight
 * the axes to fix CIE76's known over-sensitivity in saturated regions, but they need several more
 * multiplications per comparison and this code runs once per stored colour per photo per keystroke;
 * the thresholds below are loose enough that CIE76's bias does not change what a person sees match.
 *
 * Pure Kotlin, no platform type anywhere: the whole thing is arithmetic over packed ARGB ints, so it
 * builds for every target `:core:search` has and is pinned by plain unit tests.
 */
object ColourSearch {

    /**
     * How far (ΔE, CIE76) a stored colour may sit from a `#rrggbb` target and still count.
     *
     * A hex value is a precise ask -- it usually arrives from a tap on one of a photo's own swatches,
     * meaning "more like THIS" -- so the net is tighter than for a word. ΔE 14 is roughly the point
     * at which two colours side by side stop reading as shades of one colour; below it, a quantiser
     * that averaged a slightly different sample of the same scene lands inside, above it, a
     * genuinely different colour stays out.
     */
    const val HEX_MAX_DELTA_E = 14f

    /**
     * ΔE for a chromatic colour *word* -- `red`, `teal`, `purple`.
     *
     * A word covers a region, not a point: a person saying "red" means brick red and scarlet and
     * cherry alike. [ColourNames] gives each word several anchor colours spread across that region,
     * and 25 around each anchor is what fills the gaps between them without reaching into the
     * neighbouring word's territory -- `blue` at 25 does not reach `navy`'s anchors, `pink` does not
     * reach `magenta`'s, `tan` does not reach `brown`'s. Measured, not guessed: see the ΔE table in
     * `docs/handoff/COLOUR-SEARCH.md`.
     */
    const val NAME_MAX_DELTA_E = 25f

    /**
     * ΔE for an achromatic word -- `black`, `white`, `grey`, `silver`.
     *
     * These differ from one another along the L axis alone, where 25 is far too wide: at 25, `silver`
     * would swallow pure white and `grey` would take in near-black. 14 keeps each to its own band
     * while still letting `black` catch the dark greys a photograph's shadows actually are.
     */
    const val ACHROMATIC_NAME_MAX_DELTA_E = 14f

    /**
     * A stored colour must cover at least this share of the image to count for a match.
     *
     * The quantiser faithfully reports a 3% sliver of red in a photo of a green field; a person
     * searching `colour:red` does not want that field. 8% is below "an accent colour" and well above
     * "noise the quantiser happened to bucket".
     */
    const val MIN_PROPORTION = 0.08f

    /** The exact query a tap on a swatch should open: `colour:#1a2b3c`. */
    fun colourQuery(argb: Int): String {
        val out = StringBuilder(14)
        out.append(SearchField.COLOUR.key).append(":#")
        appendHex(out, (argb shr 16) and 0xFF)
        appendHex(out, (argb shr 8) and 0xFF)
        appendHex(out, argb and 0xFF)
        return out.toString()
    }

    /**
     * `#rrggbb`, `#rgb`, or either without the `#`, as an opaque ARGB int. Null for anything else.
     * Case-insensitive.
     */
    fun parseHex(value: String): Int? {
        val text = value.trim().removePrefix("#")
        if (text.length != 3 && text.length != 6) return null
        var packed = 0
        for (ch in text) {
            val digit = hexDigit(ch)
            if (digit < 0) return null
            packed = (packed shl 4) or digit
        }
        if (text.length == 3) {
            val r = (packed shr 8) and 0xF
            val g = (packed shr 4) and 0xF
            val b = packed and 0xF
            packed = (r shl 20) or (r shl 16) or (g shl 12) or (g shl 8) or (b shl 4) or b
        }
        return OPAQUE or packed
    }

    /** CIE Lab (D65) of an sRGB colour. Alpha is ignored. */
    fun labOf(argb: Int): LabColour {
        val out = FloatArray(3)
        labInto(argb, out, 0)
        return LabColour(out[0], out[1], out[2])
    }

    /** CIE76 colour difference. */
    fun deltaE(x: LabColour, y: LabColour): Float {
        val dl = x.l - y.l
        val da = x.a - y.a
        val db = x.b - y.b
        return sqrt(dl * dl + da * da + db * db)
    }

    /**
     * One colour standing for the whole of [value] -- the hex itself, or a name's first anchor --
     * for drawing a swatch beside a chip. Null when [value] is neither a hex nor a known name.
     */
    fun representativeArgb(value: String): Int? {
        val key = value.trim().lowercase()
        ColourNames.anchors(key)?.let { return it.first() }
        return parseHex(key)
    }

    /**
     * Does [palette] satisfy `colour:[value]`? An unknown value matches nothing, in line with
     * `label:nosuchlabel` -- a term that cannot be understood should not quietly widen a search.
     */
    fun matches(value: String, palette: PaletteSample): Boolean {
        if (palette.isEmpty) return false
        val target = targetFor(value) ?: return false
        return palette.matches(target)
    }

    // ---- the hot path's own cache --------------------------------------------------------------

    /**
     * Resolved targets by (lowercased) value. The matcher is called once per photo per keystroke,
     * and every one of those calls carries the same handful of term values, so resolving a value --
     * a name lookup, or a hex parse plus a Lab conversion -- is done once and read back thereafter.
     *
     * Copy-on-write over an immutable map rather than a mutable one behind a lock: there is no
     * common-Kotlin lock to reach for, a torn read here can only ever cost one redundant resolve
     * (the value is a pure function of the key), and the map is bounded so a stream of one-off hex
     * values from someone typing cannot grow it without end.
     */
    private var targets: Map<String, ColourTarget> = emptyMap()

    /** Stands in for "resolved, and it is not a colour" so a miss is cached too. */
    private val NO_TARGET = ColourTarget(FloatArray(0), 0f, 0f)

    internal fun targetFor(value: String): ColourTarget? {
        val key = value.trim().lowercase()
        val cached = targets[key]
        if (cached != null) return cached.takeUnless { it === NO_TARGET }
        val resolved = resolve(key) ?: NO_TARGET
        val snapshot = targets
        targets = if (snapshot.size >= MAX_CACHED_TARGETS) mapOf(key to resolved) else snapshot + (key to resolved)
        return resolved.takeUnless { it === NO_TARGET }
    }

    private fun resolve(key: String): ColourTarget? {
        ColourNames.anchors(key)?.let { anchors ->
            val lab = FloatArray(anchors.size * 3)
            anchors.forEachIndexed { index, argb -> labInto(argb, lab, index * 3) }
            val threshold = if (ColourNames.isAchromatic(key)) ACHROMATIC_NAME_MAX_DELTA_E else NAME_MAX_DELTA_E
            return ColourTarget(lab, threshold, MIN_PROPORTION)
        }
        val argb = parseHex(key) ?: return null
        val lab = FloatArray(3)
        labInto(argb, lab, 0)
        return ColourTarget(lab, HEX_MAX_DELTA_E, MIN_PROPORTION)
    }

    // ---- sRGB -> CIE Lab --------------------------------------------------------------------------

    /**
     * Writes the Lab triple of [argb] into [out] at [offset]. The flat-array form is what
     * [PaletteSample] and [ColourTarget] store, so the comparison loop never allocates.
     *
     * sRGB companding is undone through a 256-entry table rather than three `pow` calls: the input
     * is always an 8-bit channel, so the table is exact, and `pow` is the single most expensive step
     * of the conversion.
     */
    internal fun labInto(argb: Int, out: FloatArray, offset: Int) {
        val r = LINEAR[(argb shr 16) and 0xFF]
        val g = LINEAR[(argb shr 8) and 0xFF]
        val b = LINEAR[argb and 0xFF]
        // sRGB (D65) to XYZ, each divided by the D65 reference white.
        val x = (0.4124564f * r + 0.3575761f * g + 0.1804375f * b) / 0.95047f
        val y = 0.2126729f * r + 0.7151522f * g + 0.0721750f * b
        val z = (0.0193339f * r + 0.1191920f * g + 0.9503041f * b) / 1.08883f
        val fx = labF(x)
        val fy = labF(y)
        val fz = labF(z)
        out[offset] = 116f * fy - 16f
        out[offset + 1] = 500f * (fx - fy)
        out[offset + 2] = 200f * (fy - fz)
    }

    private fun labF(t: Float): Float =
        if (t > LAB_EPSILON) cbrt(t) else LAB_KAPPA * t + LAB_OFFSET

    /** sRGB 8-bit channel value to linear light. */
    private val LINEAR = FloatArray(256) { channel ->
        val c = channel / 255.0
        val linear = if (c <= 0.04045) c / 12.92 else pow24((c + 0.055) / 1.055)
        linear.toFloat()
    }

    /** `x^2.4` for x in (0,1]. Runs 256 times when the table is built and never again. */
    private fun pow24(x: Double): Double = kotlin.math.exp(2.4 * kotlin.math.ln(x))

    private const val LAB_EPSILON = 0.008856452f // (6/29)^3
    private const val LAB_KAPPA = 7.787037f // (29/6)^2 / 3
    private const val LAB_OFFSET = 0.13793103f // 16/116
    private const val OPAQUE = 0xFF shl 24
    private const val MAX_CACHED_TARGETS = 64

    private fun hexDigit(ch: Char): Int = when (ch) {
        in '0'..'9' -> ch - '0'
        in 'a'..'f' -> ch - 'a' + 10
        in 'A'..'F' -> ch - 'A' + 10
        else -> -1
    }

    private fun appendHex(out: StringBuilder, channel: Int) {
        out.append(HEX_DIGITS[(channel shr 4) and 0xF]).append(HEX_DIGITS[channel and 0xF])
    }

    private const val HEX_DIGITS = "0123456789abcdef"
}

/** CIE L*a*b* coordinates (D65) of one colour -- the space `colour:` measures distance in. */
class LabColour(val l: Float, val a: Float, val b: Float) {
    override fun toString(): String = "Lab($l, $a, $b)"
}

/**
 * What one `colour:` term resolves to: the Lab coordinates of its anchor colours (three floats per
 * anchor, flat), the ΔE radius around each, and the share of the image a stored colour needs.
 */
internal class ColourTarget(
    private val anchorLab: FloatArray,
    maxDeltaE: Float,
    val minProportion: Float,
) {
    private val maxDeltaESquared = maxDeltaE * maxDeltaE

    /** Is the colour at (l, a, b) within range of any anchor? Compares squared distances: no sqrt. */
    fun within(l: Float, a: Float, b: Float): Boolean {
        var i = 0
        while (i < anchorLab.size) {
            val dl = l - anchorLab[i]
            val da = a - anchorLab[i + 1]
            val db = b - anchorLab[i + 2]
            if (dl * dl + da * da + db * db <= maxDeltaESquared) return true
            i += 3
        }
        return false
    }
}

/**
 * A photo's dominant colours as search sees them: each colour packed as ARGB, its share of the
 * image, and its Lab coordinates computed once at construction.
 *
 * Computed at construction, not lazily, on purpose. A [SearchDocument] is rebuilt per photo per
 * keystroke, so anything lazy on the document is redone per keystroke; this object is meant to be
 * built once when the recognition index loads (on a background thread) and handed to every document
 * for that photo thereafter, so the main thread only ever does the comparison itself.
 *
 * Equality is by content -- the same colours in the same order with the same shares -- so a
 * [SearchDocument] holding one still behaves as the plain data class it is.
 */
class PaletteSample private constructor(
    private val argb: IntArray,
    private val proportions: FloatArray,
    /** Three floats per colour: L, a, b -- parallel to [argb]. */
    private val lab: FloatArray,
) {
    val size: Int get() = argb.size
    val isEmpty: Boolean get() = argb.isEmpty()

    fun argbAt(index: Int): Int = argb[index]
    fun proportionAt(index: Int): Float = proportions[index]

    internal fun matches(target: ColourTarget): Boolean {
        for (index in argb.indices) {
            if (proportions[index] < target.minProportion) continue
            val base = index * 3
            if (target.within(lab[base], lab[base + 1], lab[base + 2])) return true
        }
        return false
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PaletteSample) return false
        return argb.contentEquals(other.argb) && proportions.contentEquals(other.proportions)
    }

    override fun hashCode(): Int = 31 * argb.contentHashCode() + proportions.contentHashCode()

    override fun toString(): String = buildString {
        append("PaletteSample[")
        for (index in argb.indices) {
            if (index > 0) append(", ")
            append(ColourSearch.colourQuery(argb[index]).removePrefix("${SearchField.COLOUR.key}:"))
            append('@').append(proportions[index])
        }
        append(']')
    }

    companion object {
        val EMPTY = PaletteSample(IntArray(0), FloatArray(0), FloatArray(0))

        /**
         * [argb] and [proportions] are parallel: colour i covers `proportions[i]` of the image. Both
         * are copied, so a caller's arrays stay its own.
         */
        fun of(argb: IntArray, proportions: FloatArray): PaletteSample {
            require(argb.size == proportions.size) {
                "palette has ${argb.size} colours but ${proportions.size} proportions"
            }
            if (argb.isEmpty()) return EMPTY
            val lab = FloatArray(argb.size * 3)
            for (index in argb.indices) ColourSearch.labInto(argb[index], lab, index * 3)
            return PaletteSample(argb.copyOf(), proportions.copyOf(), lab)
        }
    }
}

/**
 * The colour words `colour:` understands, each with several anchor colours spread across what the
 * word covers.
 *
 * A fixed table, not a derived one, for the same reason [MonthNames] is: the set of words a person
 * reaches for is small, stable, and has to be the same on every target. Each word's anchors were
 * chosen so that, at [ColourSearch.NAME_MAX_DELTA_E], neighbouring words do not reach into each
 * other (navy/blue, pink/magenta, tan/brown, yellow/gold, teal/cyan are the pairs that matter) --
 * see `docs/handoff/COLOUR-SEARCH.md` for the measured distances.
 */
object ColourNames {

    private class Entry(val anchors: IntArray, val achromatic: Boolean = false)

    private fun rgb(vararg hex: Int): IntArray = IntArray(hex.size) { (0xFF shl 24) or hex[it] }

    /** In spectrum order, then the neutrals -- the order suggestions list them in. */
    private val entries: Map<String, Entry> = linkedMapOf(
        "red" to Entry(rgb(0xFF0000, 0xCC0000, 0xB22222, 0xE53935)),
        "orange" to Entry(rgb(0xFFA500, 0xFF8C00, 0xF57C00, 0xE67E22)),
        "yellow" to Entry(rgb(0xFFFF00, 0xFFEB3B, 0xF5E663, 0xE6D800)),
        "gold" to Entry(rgb(0xFFD700, 0xD4AF37, 0xC9A227, 0xE6BE4A)),
        // Pure lime and lime-green are anchors in their own right: a saturated screen green is
        // ΔE ~60 from the leafy anchors, so without them `colour:green` would miss every
        // chroma-key backdrop and highlighter stroke in the library.
        "green" to Entry(rgb(0x008000, 0x2E8B57, 0x4CAF50, 0x228B22, 0x7CB342, 0x32CD32, 0x00FF00)),
        "teal" to Entry(rgb(0x008080, 0x009688, 0x20B2AA, 0x00897B)),
        "cyan" to Entry(rgb(0x00FFFF, 0x00BCD4, 0x40E0D0, 0x00CED1)),
        "blue" to Entry(rgb(0x0000FF, 0x1E90FF, 0x2196F3, 0x4169E1, 0x0D47A1)),
        "navy" to Entry(rgb(0x000080, 0x1A237E, 0x0B1F4D, 0x002147)),
        "purple" to Entry(rgb(0x800080, 0x9C27B0, 0x6A1B9A, 0x7B1FA2)),
        "violet" to Entry(rgb(0x8F00FF, 0x7F00FF, 0x673AB7, 0x9400D3)),
        "magenta" to Entry(rgb(0xFF00FF, 0xD500F9, 0xC71585, 0xE040FB)),
        "pink" to Entry(rgb(0xFFC0CB, 0xFF69B4, 0xF48FB1, 0xE91E63, 0xFF80AB)),
        "brown" to Entry(rgb(0x8B4513, 0xA0522D, 0x795548, 0x5D4037, 0x6D4C41)),
        "tan" to Entry(rgb(0xD2B48C, 0xC9A66B, 0xBC9A6A, 0xD1A054)),
        "beige" to Entry(rgb(0xF5F5DC, 0xE8DCC8, 0xD9CBB3, 0xEAE0C8)),
        "black" to Entry(rgb(0x000000, 0x111111, 0x222222, 0x333333), achromatic = true),
        "grey" to Entry(rgb(0x4D4D4D, 0x666666, 0x808080, 0x999999), achromatic = true),
        "silver" to Entry(rgb(0xB4B4B4, 0xC0C0C0, 0xCCCCCC), achromatic = true),
        "white" to Entry(rgb(0xFFFFFF, 0xF7F7F7), achromatic = true),
    )

    /** Spellings that mean one of [entries]' words. */
    private val aliases: Map<String, String> = mapOf("gray" to "grey")

    /** Every word, in the order suggestions should offer them. Canonical spellings only. */
    val canonical: List<String> = entries.keys.toList()

    fun isName(value: String): Boolean = canonicalOf(value) != null

    /** The anchor colours (opaque ARGB) for [value], or null when it is not a colour word. */
    fun anchors(value: String): IntArray? = canonicalOf(value)?.let { entries.getValue(it).anchors }

    fun isAchromatic(value: String): Boolean = canonicalOf(value)?.let { entries.getValue(it).achromatic } ?: false

    /**
     * The words closest to [argb], nearest first, by the ΔE to each word's nearest anchor. Feeds the
     * chip menu: tapping `colour:#1a2b3c` offers `navy` and `black` before `pink`.
     */
    fun nearest(argb: Int, limit: Int, excluding: String? = null): List<String> {
        val target = ColourSearch.labOf(argb)
        val skip = excluding?.let(::canonicalOf)
        return entries.entries.asSequence()
            .filter { it.key != skip }
            .map { (name, entry) -> name to entry.anchors.minOf { ColourSearch.deltaE(target, ColourSearch.labOf(it)) } }
            .sortedBy { it.second }
            .take(limit)
            .map { it.first }
            .toList()
    }

    private fun canonicalOf(value: String): String? {
        val key = value.trim().lowercase()
        if (entries.containsKey(key)) return key
        return aliases[key]
    }
}
