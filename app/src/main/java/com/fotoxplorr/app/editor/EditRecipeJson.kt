package com.fotoxplorr.app.editor

import com.fotoxplorr.app.editor.JsonValue.JsonObject

/**
 * [EditRecipe] <-> JSON, for the two places a recipe needs to survive outside the running process:
 * a persisted per-asset edit ([RecipeStore]) and a saved preset ([PresetStore]). One shared
 * encoding for both, so a bug fixed in the format is fixed everywhere it is used, and so a future
 * field on [Adjustments] or [EditRecipe] is written into both places at once rather than one of
 * them silently falling behind.
 *
 * Every field round-trips through its own explicit key rather than positional order, and every
 * reader defaults a missing key to that field's own neutral value (via [JsonValue]'s `asFloat`
 * and friends, which already default on a wrong/missing type) — a document saved before a field
 * existed decodes with that field at its identity value, which is what makes it safe to add one
 * without a migration step. See [Adjustments]'s own KDoc for why "0 is identity" is a promise
 * this whole persistence story depends on, not a style choice.
 */
fun EditRecipe.toJson(): JsonValue = jsonObjectOf(
    "quarterTurns" to quarterTurns.toJson(),
    "flipHorizontal" to flipHorizontal.toJson(),
    "straightenDegrees" to straightenDegrees.toJson(),
    "adjustments" to adjustments.toJson(),
    "crop" to crop.toJson(),
    "heals" to jsonArrayOf(heals.map { it.toJson() }),
)

fun EditRecipe.Companion.fromJson(json: JsonValue): EditRecipe = EditRecipe(
    quarterTurns = json["quarterTurns"].asInt(0),
    flipHorizontal = json["flipHorizontal"].asBool(false),
    straightenDegrees = json["straightenDegrees"].asFloat(0f),
    adjustments = json["adjustments"].let { if (it is JsonObject) Adjustments.fromJson(it) else Adjustments.NONE },
    crop = json["crop"].let { if (it is JsonObject) CropRect.fromJson(it) else CropRect.FULL },
    heals = json["heals"].asArray().mapNotNull { runCatching { HealSpot.fromJson(it) }.getOrNull() },
)

fun CropRect.toJson(): JsonValue = jsonObjectOf(
    "left" to left.toJson(), "top" to top.toJson(), "right" to right.toJson(), "bottom" to bottom.toJson(),
)

fun CropRect.Companion.fromJson(json: JsonValue): CropRect = CropRect(
    left = json["left"].asFloat(0f),
    top = json["top"].asFloat(0f),
    right = json["right"].asFloat(1f),
    bottom = json["bottom"].asFloat(1f),
)

fun HealSpot.toJson(): JsonValue = jsonObjectOf("cx" to cx.toJson(), "cy" to cy.toJson(), "radius" to radius.toJson())

fun HealSpot.Companion.fromJson(json: JsonValue): HealSpot = HealSpot(
    cx = json["cx"].asFloat(0.5f),
    cy = json["cy"].asFloat(0.5f),
    radius = json["radius"].asFloat(HealSpot.DEFAULT_RADIUS),
)

fun ToneCurve.toJson(): JsonValue = jsonArrayOf(
    points.map { jsonObjectOf("x" to it.x.toJson(), "y" to it.y.toJson()) },
)

fun ToneCurve.Companion.fromJson(json: JsonValue): ToneCurve {
    val points = json.asArray().map { CurvePoint(it["x"].asFloat(0f), it["y"].asFloat(0f)) }
    // A curve needs at least two points (CurvePoint's own `init` requires it) -- a corrupt or
    // truncated document falls back to identity rather than crashing the whole recipe load.
    return if (points.size >= 2) runCatching { ToneCurve(points) }.getOrDefault(ToneCurve.IDENTITY) else ToneCurve.IDENTITY
}

fun HslBandAdjustment.toJson(): JsonValue = jsonObjectOf(
    "hue" to hue.toJson(), "saturation" to saturation.toJson(), "luminance" to luminance.toJson(),
)

fun HslBandAdjustment.Companion.fromJson(json: JsonValue): HslBandAdjustment = HslBandAdjustment(
    hue = json["hue"].asFloat(0f),
    saturation = json["saturation"].asFloat(0f),
    luminance = json["luminance"].asFloat(0f),
)

fun HslAdjustments.toJson(): JsonValue = jsonObjectOf(
    *HueBand.entries.map { band -> band.name to this[band].toJson() }.toTypedArray(),
)

fun HslAdjustments.Companion.fromJson(json: JsonValue): HslAdjustments {
    var result = HslAdjustments.NONE
    HueBand.entries.forEach { band ->
        val bandJson = json[band.name]
        if (bandJson is JsonObject) result = result.with(band, HslBandAdjustment.fromJson(bandJson))
    }
    return result
}

fun Adjustments.toJson(): JsonValue = jsonObjectOf(
    "exposure" to exposure.toJson(),
    "contrast" to contrast.toJson(),
    "highlights" to highlights.toJson(),
    "shadows" to shadows.toJson(),
    "whites" to whites.toJson(),
    "blacks" to blacks.toJson(),
    "temperature" to temperature.toJson(),
    "tint" to tint.toJson(),
    "saturation" to saturation.toJson(),
    "vibrance" to vibrance.toJson(),
    "rgbCurve" to rgbCurve.toJson(),
    "redCurve" to redCurve.toJson(),
    "greenCurve" to greenCurve.toJson(),
    "blueCurve" to blueCurve.toJson(),
    "hsl" to hsl.toJson(),
    "sharpen" to sharpen.toJson(),
    "denoise" to denoise.toJson(),
    "vignette" to vignette.toJson(),
    "clarity" to clarity.toJson(),
)

fun Adjustments.Companion.fromJson(json: JsonValue): Adjustments = Adjustments(
    exposure = json["exposure"].asFloat(0f),
    contrast = json["contrast"].asFloat(0f),
    highlights = json["highlights"].asFloat(0f),
    shadows = json["shadows"].asFloat(0f),
    whites = json["whites"].asFloat(0f),
    blacks = json["blacks"].asFloat(0f),
    temperature = json["temperature"].asFloat(0f),
    tint = json["tint"].asFloat(0f),
    saturation = json["saturation"].asFloat(0f),
    vibrance = json["vibrance"].asFloat(0f),
    rgbCurve = json["rgbCurve"].let { if (it is JsonValue.JsonArray) ToneCurve.fromJson(it) else ToneCurve.IDENTITY },
    redCurve = json["redCurve"].let { if (it is JsonValue.JsonArray) ToneCurve.fromJson(it) else ToneCurve.IDENTITY },
    greenCurve = json["greenCurve"].let { if (it is JsonValue.JsonArray) ToneCurve.fromJson(it) else ToneCurve.IDENTITY },
    blueCurve = json["blueCurve"].let { if (it is JsonValue.JsonArray) ToneCurve.fromJson(it) else ToneCurve.IDENTITY },
    hsl = json["hsl"].let { if (it is JsonObject) HslAdjustments.fromJson(it) else HslAdjustments.NONE },
    sharpen = json["sharpen"].asFloat(0f),
    denoise = json["denoise"].asFloat(0f),
    vignette = json["vignette"].asFloat(0f),
    clarity = json["clarity"].asFloat(0f),
)
