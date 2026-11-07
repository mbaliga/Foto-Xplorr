package com.fotoxplorr.app.editor

/**
 * Named tone-curve shapes offered as shortcuts above the curve graph.
 *
 * A preset is not a mode: choosing one LOADS its control points into the channel being edited
 * (see [Adjustments.withCurve]), and from there they are ordinary draggable points. Each is a real
 * [ToneCurve], so [ToneCurveTest]'s monotone-interpolation guarantees apply to every shape here
 * exactly as they do to a hand-dragged one -- every set below is sorted, has points at x=0 and
 * x=1, stays within [ToneCurve.MAX_POINTS], and rises, which [CurvePresetsTest] pins.
 *
 * [SOFT_CONTRAST], [STRONG_CONTRAST] and [FADE] keep the exact points they had when they lived in
 * EditorScreen as private chips, so [Preset]'s built-in "Faded" look still compares equal.
 */
enum class CurvePreset(val label: String, val curve: ToneCurve) {
    /** [ToneCurve.IDENTITY] under a name that reads as an action ("remove the curve") rather
     *  than a shape, since an identity curve has no shape to describe. */
    LINEAR("Linear", ToneCurve.IDENTITY),
    SOFT_CONTRAST(
        "Soft contrast",
        ToneCurve(listOf(CurvePoint(0f, 0f), CurvePoint(0.25f, 0.20f), CurvePoint(0.75f, 0.80f), CurvePoint(1f, 1f))),
    ),
    STRONG_CONTRAST(
        "Strong contrast",
        ToneCurve(listOf(CurvePoint(0f, 0f), CurvePoint(0.25f, 0.12f), CurvePoint(0.75f, 0.88f), CurvePoint(1f, 1f))),
    ),
    // Two points only: a faded look is a straight lift-and-lower of black and white, not an S --
    // adding interior points here would just be a slower way to draw the same line.
    FADE("Fade", ToneCurve(listOf(CurvePoint(0f, 0.08f), CurvePoint(1f, 0.92f)))),
    /** Opens the darkest third without touching the whites: a bow above the diagonal low down. */
    LIGHTEN_SHADOWS(
        "Lighten shadows",
        ToneCurve(listOf(CurvePoint(0f, 0f), CurvePoint(0.2f, 0.30f), CurvePoint(0.5f, 0.56f), CurvePoint(1f, 1f))),
    ),
    /** The mirror: pulls the brightest third down and leaves the shadows where they were. */
    DARKEN_HIGHLIGHTS(
        "Darken highlights",
        ToneCurve(listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.45f), CurvePoint(0.8f, 0.68f), CurvePoint(1f, 1f))),
    ),
    /** Lifted blacks, lowered whites, and a little S between: the flat "matte print" look. */
    MATTE(
        "Matte",
        ToneCurve(listOf(CurvePoint(0f, 0.10f), CurvePoint(0.3f, 0.30f), CurvePoint(0.7f, 0.74f), CurvePoint(1f, 0.92f))),
    ),
}
