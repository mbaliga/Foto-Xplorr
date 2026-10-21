package com.fotoxplorr.app.editor

import android.content.Context
import java.io.File

/**
 * A named "look" — a partial recipe, applied by replacing the current [EditRecipe.adjustments]
 * outright (the same "select a shape" gesture [CurvePreset] in [EditorScreen] already uses for
 * tone curves). Partial in the sense that matters: a preset carries only [Adjustments] — colour,
 * tone, HSL, sharpen/denoise/vignette/clarity — and says nothing about crop, rotation or heal
 * spots, so applying one never moves or re-crops a photograph the user already framed.
 *
 * [isBuiltIn] presets ([BUILT_IN]) ship with the app and cannot be renamed or deleted; a user
 * preset is anything saved through [PresetStore] from the editor's current recipe.
 */
data class Preset(
    val id: String,
    val name: String,
    val adjustments: Adjustments,
    val isBuiltIn: Boolean = false,
) {
    // Empty on purpose -- see Preset.Companion.fromJson below.
    companion object
}

fun Preset.toJson(): JsonValue = jsonObjectOf(
    "id" to id.toJson(),
    "name" to name.toJson(),
    "adjustments" to adjustments.toJson(),
)

fun Preset.Companion.fromJson(json: JsonValue): Preset? {
    val id = json["id"].asString()
    val name = json["name"].asString()
    if (id.isBlank() || name.isBlank()) return null
    val adjustmentsJson = json["adjustments"]
    val adjustments = if (adjustmentsJson is JsonValue.JsonObject) Adjustments.fromJson(adjustmentsJson) else Adjustments.NONE
    return Preset(id = id, name = name, adjustments = adjustments, isBuiltIn = false)
}

/**
 * The looks this app ships with, tuned as gentle, generically-flattering starting points rather
 * than a single "trust me" strength — a user who dislikes the result of a built-in preset can
 * still see the individual sliders it moved and back off from there, because it is expressed in
 * the exact same [Adjustments] the manual controls edit.
 */
enum class BuiltInPreset(val presetName: String, val adjustments: Adjustments) {
    VIVID(
        "Vivid",
        Adjustments(contrast = 0.15f, saturation = 0.2f, vibrance = 0.2f, clarity = 0.15f),
    ),
    WARM(
        "Warm",
        Adjustments(temperature = 0.3f, tint = 0.04f),
    ),
    COOL(
        "Cool",
        Adjustments(temperature = -0.3f, tint = -0.03f),
    ),
    // Two curve points only, lifting black and lowering white -- the classic "faded film" look
    // is a flattened tonal range, not an S-curve; see CurvePreset.FADE's own reasoning, which
    // this mirrors rather than duplicates by referencing the identical shape.
    FADE(
        "Fade",
        Adjustments(
            contrast = -0.1f,
            saturation = -0.15f,
            rgbCurve = ToneCurve(listOf(CurvePoint(0f, 0.08f), CurvePoint(1f, 0.92f))),
        ),
    ),
    MONO(
        "Mono",
        Adjustments(saturation = -1f, contrast = 0.08f, clarity = 0.1f),
    ),
    // Vibrance rather than saturation (spares skin tones, see Adjustments.vibrance's own doc),
    // a gentle highlight rolloff, and slightly softened clarity rather than sharpened -- the
    // three moves a portrait retoucher reaches for before anything more surgical.
    PORTRAIT(
        "Portrait",
        Adjustments(highlights = -0.12f, vibrance = 0.18f, clarity = -0.08f, temperature = 0.06f),
    ),
    ;

    fun toPreset(): Preset = Preset(id = "built-in-${name.lowercase()}", name = presetName, adjustments = adjustments, isBuiltIn = true)
}

/**
 * User-saved presets, one JSON file per preset under `filesDir/presets/`.
 *
 * A whole directory of small files rather than one shared index file: renaming or deleting a
 * single preset then touches exactly one file, and a half-written save (the process dying
 * mid-write) can never corrupt any preset but the one being written — the same reasoning
 * [RecipeStore] applies to its own one-file-per-asset layout, for the identical reason.
 */
class PresetStore(context: Context) {
    private val directory = File(context.filesDir, "presets").apply { mkdirs() }

    fun list(): List<Preset> = directory.listFiles { file -> file.extension == "json" }
        ?.mapNotNull { file -> runCatching { parseJson(file.readText()) }.getOrNull()?.let { Preset.fromJson(it) } }
        ?.sortedBy { it.name.lowercase() }
        .orEmpty()

    /** Saves [recipe]'s current adjustments as a new preset named [name]. */
    fun save(name: String, adjustments: Adjustments): Preset {
        val id = slugify(name) + "-" + System.currentTimeMillis()
        val preset = Preset(id = id, name = name, adjustments = adjustments)
        write(preset)
        return preset
    }

    fun rename(preset: Preset, newName: String): Preset {
        val renamed = preset.copy(name = newName)
        write(renamed)
        return renamed
    }

    fun delete(preset: Preset) {
        File(directory, fileNameFor(preset.id)).delete()
    }

    private fun write(preset: Preset) {
        File(directory, fileNameFor(preset.id)).writeText(preset.toJson().stringify())
    }

    private fun fileNameFor(id: String) = "$id.json"
}

/** A filesystem-safe stem for a preset's id -- the display name is kept verbatim in the JSON
 *  body, this is only ever used to build a file name. */
internal fun slugify(name: String): String {
    val slug = name.trim().lowercase().map { if (it.isLetterOrDigit()) it else '-' }.joinToString("")
        .replace(Regex("-+"), "-")
        .trim('-')
    return slug.ifBlank { "preset" }
}
