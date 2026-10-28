package com.fotoxplorr.app.vision

/** What the viewer does with the colours: shows how they look to an affected eye, or moves them apart. */
internal enum class VisionKind { SIMULATE, HELP }

/**
 * What the colour-vision control in the viewer's view options holds. One immutable value, so the
 * rules about it ("a pattern needs a type", "strength is one of ten steps") are plain functions a
 * JVM test can hold. It is a per-viewing choice: nothing here is written to a file or to the
 * library, and it is kept while the person swipes the filmstrip and across a rotation
 * ([encode]/[decode] feed the saver), not across closing the viewer or the process being killed.
 *
 * @property deficiency which kind of hard-to-see colour to work for, or null for Off.
 * @property severityStep strength in tenths, 1..[STEPS]; [STEPS] is full dichromacy. A stepper and a
 *   slider move it by one step, so a person who cannot drag has the same reach.
 * @property naming whether a tap on the photo names the colour under the finger.
 * @property patterns whether regions that would look alike are marked with hatching. It needs a
 *   [deficiency] to know what looks alike, so [hatching] is false while the type is Off even when
 *   this is true (the choice is remembered, not applied).
 */
internal data class ColourVisionChoice(
    val deficiency: Deficiency? = null,
    val kind: VisionKind = VisionKind.SIMULATE,
    val severityStep: Int = STEPS,
    val naming: Boolean = false,
    val patterns: Boolean = false,
) {
    /** 0..1 for the maths: [severityStep] tenths. */
    val severity: Double get() = severityStep.coerceIn(MIN_STEP, STEPS) / STEPS.toDouble()

    /** Whether the photo's colours are changed at all. */
    val filtering: Boolean get() = deficiency != null

    /** Whether hatching is drawn: asked for, and a type is chosen to say what looks alike. */
    val hatching: Boolean get() = patterns && deficiency != null

    /** Whether the decoded pixels are needed for anything (the exact filter, the names, the patterns). */
    val needsPixels: Boolean get() = filtering || naming || hatching

    /** The filter the exact path would compute for this choice, or null when the colours are not changed. */
    val filterKey: FilterKey? get() = deficiency?.let { FilterKey(it, kind, severityStep.coerceIn(MIN_STEP, STEPS)) }

    fun withDeficiency(value: Deficiency?): ColourVisionChoice = copy(deficiency = value)

    fun withKind(value: VisionKind): ColourVisionChoice = copy(kind = value)

    /** Moves the strength by [delta] steps and stops at the ends. */
    fun stepSeverity(delta: Int): ColourVisionChoice = copy(severityStep = (severityStep + delta).coerceIn(MIN_STEP, STEPS))

    /** Sets the strength from a slider position (a step number as a float), to the nearest step. */
    fun withSeverityPosition(position: Float): ColourVisionChoice =
        copy(severityStep = if (position.isNaN()) severityStep else Math.round(position).coerceIn(MIN_STEP, STEPS))

    fun withNaming(on: Boolean): ColourVisionChoice = copy(naming = on)

    fun withPatterns(on: Boolean): ColourVisionChoice = copy(patterns = on)

    val canWeaken: Boolean get() = severityStep > MIN_STEP
    val canStrengthen: Boolean get() = severityStep < STEPS

    /** The strength as the person reads it: "70%". */
    val severityLabel: String get() = "${severityStep.coerceIn(MIN_STEP, STEPS) * 100 / STEPS}%"

    /** One line of text for a saver. [decode] is its inverse and never throws. */
    fun encode(): String = listOf(
        deficiency?.ordinal ?: -1,
        kind.ordinal,
        severityStep,
        if (naming) 1 else 0,
        if (patterns) 1 else 0,
    ).joinToString(",")

    companion object {
        /** Strength steps; the top one is full strength. */
        const val STEPS = 10
        const val MIN_STEP = 1

        /** The choice a saved line holds; anything unreadable gives the default (Off). */
        fun decode(text: String?): ColourVisionChoice {
            val parts = text?.split(',')?.map { it.trim().toIntOrNull() ?: return ColourVisionChoice() }
                ?: return ColourVisionChoice()
            if (parts.size != 5) return ColourVisionChoice()
            return ColourVisionChoice(
                deficiency = Deficiency.entries.getOrNull(parts[0]),
                kind = VisionKind.entries.getOrNull(parts[1]) ?: VisionKind.SIMULATE,
                severityStep = parts[2].coerceIn(MIN_STEP, STEPS),
                naming = parts[3] == 1,
                patterns = parts[4] == 1,
            )
        }
    }
}

/** What a computed (exact) filtered picture was made for. */
internal data class FilterKey(val deficiency: Deficiency, val kind: VisionKind, val severityStep: Int) {
    val severity: Double get() = severityStep / ColourVisionChoice.STEPS.toDouble()

    /** The row-major 3x3 linear-RGB matrix of this filter. */
    fun matrix(): DoubleArray = when (kind) {
        VisionKind.SIMULATE -> Simulation.matrix(deficiency, severity)
        VisionKind.HELP -> Daltonizer.matrix(deficiency, severity)
    }

    /** The 20-float colour matrix for a live preview only (see [GpuFilter] for how far off it can be). */
    fun previewMatrix(): FloatArray = when (kind) {
        VisionKind.SIMULATE -> GpuFilter.simulate(deficiency, severity)
        VisionKind.HELP -> GpuFilter.daltonize(deficiency, severity)
    }
}

/** What the viewer draws for the photo under a choice, given the exact picture computed so far. */
internal sealed interface DisplayPlan {
    /** The file as it is. */
    data object Original : DisplayPlan

    /** The colour-matrix filter on the live image, until the exact picture is ready. A rough guide. */
    class Preview(val matrix: FloatArray) : DisplayPlan

    /** The exact picture computed for [key]. */
    class Exact(val key: FilterKey) : DisplayPlan
}

/**
 * Chooses what to draw. Not filtering gives the original. A computed picture is used when it is for
 * the same type and kind, even if the strength has since moved a step or two (it is replaced a moment
 * later, and a flash back to the original on every step of the slider would be worse); anything else
 * falls back to the live preview, so the screen answers a tap at once.
 *
 * @param exactAllowed false for an image that cannot be shown as one still picture (an animated
 *   one) or whose pixels cannot be read.
 * @param ready the filter of the exact picture held now, or null for none.
 */
internal fun displayPlan(choice: ColourVisionChoice, ready: FilterKey?, exactAllowed: Boolean = true): DisplayPlan {
    val key = choice.filterKey ?: return DisplayPlan.Original
    if (exactAllowed && ready != null && ready.deficiency == key.deficiency && ready.kind == key.kind) {
        return DisplayPlan.Exact(ready)
    }
    return DisplayPlan.Preview(key.previewMatrix())
}
