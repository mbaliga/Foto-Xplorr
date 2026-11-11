package com.fotoxplorr.app.editor

/**
 * Every tool the editor offers, as data: which group it belongs to, what it is called, how often a
 * stranger is expected to want it, and what sort of control it needs. The hub ([HubModel]) is built
 * on this list and nothing else, so it can be tested on the JVM with no Compose in sight.
 *
 * Owner, 2026-10: *"a better way to show the overly numerous photo editing options, or show only
 * what is necessary: centre the most used option, top/bottom the lesser used."* Chosen shape: a hub
 * and ring. The centre holds the person's few most-used tools, a ring of five groups surrounds it,
 * and the rarely used tools sit one tap deeper. No scrolling.
 *
 * WIRED: [EditorScreen] shows this catalogue through [HubRingPanel] (the hub replaced the old strip
 * of tabs). `EditTool`, the screen's private enum, is now only the host panel a tool's controls are
 * drawn in, named by [ToolSpec.hostTab]. The two stay in step by test: `ToolCatalogTest` scans the
 * screen's source for its host panels and for the `shows(ToolIds.X)` block that draws each tool's
 * slider, and reflects over [Adjustments] and [EditRecipe], so a tool added there and not here (or
 * the other way round) fails a test instead of silently being unreachable from the hub.
 *
 * Pure Kotlin, no Android imports.
 */

/** The five groups round the ring, in the order a photographer works (light before colour). */
enum class ToolGroup(val iconKey: String) {
    LIGHT("group_light"),
    COLOUR("group_colour"),
    DETAIL("group_detail"),
    CROP_STRAIGHTEN("group_crop"),
    EFFECTS("group_effects"),
}

/**
 * How often a person who has never used the editor is expected to want a tool. It seeds the
 * cold-start centre and decides which tools start one tap deeper; real use then takes over (see
 * [ToolUsage]). [priorMilli] is added to a tool's learned score, in the same thousandths-of-a-use
 * unit, so a default stays in the centre until something else has clearly been used more.
 */
enum class Rarity(val priorMilli: Int) {
    OFTEN(1_500),
    SOMETIMES(500),
    RARELY(0),
}

/**
 * The kind of control a tool needs, so a hub can pick the right surface without knowing the tool.
 * [ACTION] is a one-shot, possibly destructive command rather than an adjustment.
 */
enum class PanelKind { SLIDER, CURVES, HUE_BANDS, PRESET_STRIP, CROP_OVERLAY, BUTTON, PHOTO_TAPS, ACTION }

/**
 * One tool.
 *
 * @property id Stable, persisted by [ToolUsage]; never rename one (lowercase letters, digits, `_`).
 * @property iconKey A plain key the UI maps to a drawable; no drawable is named here.
 * @property hostTab The name of the entry in [EditorScreen]'s private tab enum that hosts this
 *   tool's control today. Lets the integrator open the right panel, and lets a test prove no tab
 *   is left without a tool.
 * @property fields The [Adjustments] ("adjustments.x") and [EditRecipe] ("recipe.x") properties this
 *   tool edits. Every such property belongs to exactly one tool (tested). Empty for a tool that
 *   replaces them wholesale (presets) or edits nothing (actions).
 */
data class ToolSpec(
    val id: String,
    val group: ToolGroup,
    val title: String,
    val iconKey: String,
    val rarity: Rarity,
    val panel: PanelKind,
    val hostTab: String,
    val fields: Set<String> = emptySet(),
) {
    /**
     * Whether the centre may ever offer this tool. An [PanelKind.ACTION] never is: "Revert to
     * original" one mis-tap from the middle of the screen is a way to lose work.
     */
    val centreEligible: Boolean get() = panel != PanelKind.ACTION
}

/** The ids as constants, for callers and tests that name a tool. */
object ToolIds {
    const val EXPOSURE = "exposure"
    const val CONTRAST = "contrast"
    const val HIGHLIGHTS = "highlights"
    const val SHADOWS = "shadows"
    const val WHITES = "whites"
    const val BLACKS = "blacks"
    const val CURVES = "curves"
    const val SATURATION = "saturation"
    const val TEMPERATURE = "temperature"
    const val TINT = "tint"
    const val VIBRANCE = "vibrance"
    const val HSL = "hsl"
    const val SHARPEN = "sharpen"
    const val CLARITY = "clarity"
    const val DENOISE = "denoise"
    const val CROP = "crop"
    const val STRAIGHTEN = "straighten"
    const val ROTATE = "rotate"
    const val FLIP = "flip"
    const val RESET = "reset_all"
    const val REVERT = "revert"
    const val PRESETS = "presets"
    const val VIGNETTE = "vignette"
    const val HEAL = "heal"
}

object ToolCatalog {

    private fun slider(
        id: String,
        group: ToolGroup,
        title: String,
        rarity: Rarity,
        hostTab: String,
        field: String,
    ) = ToolSpec(id, group, title, "tool_$id", rarity, PanelKind.SLIDER, hostTab, setOf("adjustments.$field"))

    /**
     * Catalogue order IS the order a group lists its tools in, so it is fixed on purpose: a tool
     * that moved with use would be a tool the person has to look for again.
     */
    val all: List<ToolSpec> = listOf(
        // ---- Light ----
        slider(ToolIds.EXPOSURE, ToolGroup.LIGHT, HubCopy.TOOL_EXPOSURE, Rarity.OFTEN, "LIGHT", "exposure"),
        slider(ToolIds.CONTRAST, ToolGroup.LIGHT, HubCopy.TOOL_CONTRAST, Rarity.OFTEN, "LIGHT", "contrast"),
        slider(ToolIds.HIGHLIGHTS, ToolGroup.LIGHT, HubCopy.TOOL_HIGHLIGHTS, Rarity.SOMETIMES, "LIGHT", "highlights"),
        slider(ToolIds.SHADOWS, ToolGroup.LIGHT, HubCopy.TOOL_SHADOWS, Rarity.SOMETIMES, "LIGHT", "shadows"),
        slider(ToolIds.WHITES, ToolGroup.LIGHT, HubCopy.TOOL_WHITES, Rarity.RARELY, "LIGHT", "whites"),
        slider(ToolIds.BLACKS, ToolGroup.LIGHT, HubCopy.TOOL_BLACKS, Rarity.RARELY, "LIGHT", "blacks"),
        ToolSpec(
            ToolIds.CURVES, ToolGroup.LIGHT, HubCopy.TOOL_CURVES, "tool_curves", Rarity.RARELY, PanelKind.CURVES, "CURVES",
            setOf(
                "adjustments.rgbCurve", "adjustments.redCurve", "adjustments.greenCurve",
                "adjustments.blueCurve", "adjustments.lumaCurve",
            ),
        ),

        // ---- Colour ----
        slider(ToolIds.SATURATION, ToolGroup.COLOUR, HubCopy.TOOL_SATURATION, Rarity.OFTEN, "COLOUR", "saturation"),
        slider(ToolIds.TEMPERATURE, ToolGroup.COLOUR, HubCopy.TOOL_TEMPERATURE, Rarity.SOMETIMES, "COLOUR", "temperature"),
        slider(ToolIds.VIBRANCE, ToolGroup.COLOUR, HubCopy.TOOL_VIBRANCE, Rarity.SOMETIMES, "COLOUR", "vibrance"),
        slider(ToolIds.TINT, ToolGroup.COLOUR, HubCopy.TOOL_TINT, Rarity.RARELY, "COLOUR", "tint"),
        ToolSpec(
            ToolIds.HSL, ToolGroup.COLOUR, HubCopy.TOOL_HSL, "tool_hsl", Rarity.RARELY, PanelKind.HUE_BANDS, "HSL",
            setOf("adjustments.hsl"),
        ),

        // ---- Detail ----
        slider(ToolIds.SHARPEN, ToolGroup.DETAIL, HubCopy.TOOL_SHARPEN, Rarity.SOMETIMES, "DETAIL", "sharpen"),
        slider(ToolIds.CLARITY, ToolGroup.DETAIL, HubCopy.TOOL_CLARITY, Rarity.SOMETIMES, "DETAIL", "clarity"),
        slider(ToolIds.DENOISE, ToolGroup.DETAIL, HubCopy.TOOL_DENOISE, Rarity.RARELY, "DETAIL", "denoise"),

        // ---- Crop and straighten ----
        ToolSpec(
            ToolIds.CROP, ToolGroup.CROP_STRAIGHTEN, HubCopy.TOOL_CROP, "tool_crop", Rarity.OFTEN, PanelKind.CROP_OVERLAY, "CROP",
            setOf("recipe.crop"),
        ),
        ToolSpec(
            ToolIds.STRAIGHTEN, ToolGroup.CROP_STRAIGHTEN, HubCopy.TOOL_STRAIGHTEN, "tool_straighten", Rarity.OFTEN,
            PanelKind.SLIDER, "ROTATE", setOf("recipe.straightenDegrees"),
        ),
        ToolSpec(
            ToolIds.ROTATE, ToolGroup.CROP_STRAIGHTEN, HubCopy.TOOL_ROTATE, "tool_rotate", Rarity.SOMETIMES,
            PanelKind.BUTTON, "ROTATE", setOf("recipe.quarterTurns"),
        ),
        ToolSpec(
            ToolIds.FLIP, ToolGroup.CROP_STRAIGHTEN, HubCopy.TOOL_FLIP, "tool_flip", Rarity.RARELY,
            PanelKind.BUTTON, "ROTATE", setOf("recipe.flipHorizontal"),
        ),
        // Both live in the Rotate tab today, so they stay with it. Actions: never offered in the centre.
        ToolSpec(ToolIds.RESET, ToolGroup.CROP_STRAIGHTEN, HubCopy.TOOL_RESET, "tool_reset", Rarity.RARELY, PanelKind.ACTION, "ROTATE"),
        ToolSpec(ToolIds.REVERT, ToolGroup.CROP_STRAIGHTEN, HubCopy.TOOL_REVERT, "tool_revert", Rarity.RARELY, PanelKind.ACTION, "ROTATE"),

        // ---- Effects and heal ----
        ToolSpec(
            ToolIds.PRESETS, ToolGroup.EFFECTS, HubCopy.TOOL_PRESETS, "tool_presets", Rarity.SOMETIMES,
            PanelKind.PRESET_STRIP, "PRESETS",
        ),
        ToolSpec(
            ToolIds.HEAL, ToolGroup.EFFECTS, HubCopy.TOOL_HEAL, "tool_heal", Rarity.SOMETIMES,
            PanelKind.PHOTO_TAPS, "HEAL", setOf("recipe.heals"),
        ),
        // Vignette is in the Detail tab today but is a look, not a repair, so the hub files it here.
        slider(ToolIds.VIGNETTE, ToolGroup.EFFECTS, HubCopy.TOOL_VIGNETTE, Rarity.RARELY, "DETAIL", "vignette"),
    )

    private val byId: Map<String, ToolSpec> = all.associateBy { it.id }

    fun get(id: String?): ToolSpec? = if (id == null) null else byId[id]

    fun has(id: String): Boolean = id in byId

    /** The tools of [group], in catalogue order. */
    fun inGroup(group: ToolGroup): List<ToolSpec> = all.filter { it.group == group }

    /**
     * Where [spec] stands for a person who has never used the editor: rarity first, then catalogue
     * order. Also the final tie-break everywhere, so no ranking in the hub is ever arbitrary.
     */
    fun defaultRank(spec: ToolSpec): Int = defaultOrder.indexOf(spec)

    /** Every tool, best default first. */
    val defaultOrder: List<ToolSpec> =
        all.withIndex().sortedWith(compareBy({ it.value.rarity.ordinal }, { it.index })).map { it.value }

    /** The centre a brand-new user sees: the first [size] centre-eligible tools of [defaultOrder]. */
    fun defaultCentre(size: Int = HubModel.CENTRE_SIZE): List<ToolSpec> =
        defaultOrder.filter { it.centreEligible }.take(size)
}
