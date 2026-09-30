package com.fotoxplorr.app.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.fotoxplorr.app.gallery.AccentPalette
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import org.json.JSONException
import org.json.JSONObject

/**
 * JSON-based theming: a theme is a small file, not a hardcoded enum branch.
 *
 * The owner's ask (phrased once, identically, for every app in the constellation): *"All apps
 * need a JSON-based way to theme them. User should be able to preview the JSON's content as
 * plain text so they are never caught unawares."* The mandatory plain-text preview itself lives
 * in `SettingsTabs.kt`'s "Custom…" dialog, since that is inherently a UI concern -- this file is
 * the part of the feature that has nothing to do with a screen: the schema, strict validation
 * with a specific reason for every rejection, and turning a validated theme into the real
 * [ColorScheme] [FotoXplorrTheme] builds `MaterialTheme` from.
 *
 * Schema (version 1) -- deliberately identical across every app in the constellation building
 * this same feature right now, so a person using more than one of them sees one consistent idea
 * of "a theme file", not app-specific variations on it:
 * ```
 * {
 *   "version": 1,
 *   "name": "My Theme",
 *   "colors": {
 *     "light": { "primary": "#RRGGBB", "secondary": "#RRGGBB", "tertiary": "#RRGGBB",
 *                "background": "#RRGGBB", "surface": "#RRGGBB", "surfaceVariant": "#RRGGBB",
 *                "onBackground": "#RRGGBB", "onSurface": "#RRGGBB" },
 *     "dark":  { ...the same eight keys... }
 *   }
 * }
 * ```
 * The eight keys are exactly [FotoXplorrTheme]'s own `darkColorScheme`/`lightColorScheme` call --
 * `primary`/`secondary`/`tertiary`/`background`/`surface`/`surfaceVariant`/`onBackground`/
 * `onSurface` -- and nothing else: this app has never varied any other Material3 slot by palette,
 * so the schema does not carry one either, rather than shipping fields that would silently do
 * nothing.
 *
 * `#RRGGBB` and `#AARRGGBB` are both accepted (an omitted alpha means fully opaque); anything
 * else -- a 3-digit shorthand, a named colour, a value with no `#` -- is refused, naming the
 * field, per [parseThemeJson]'s own doc.
 */
const val THEME_JSON_VERSION = 1

/** One mode's eight colours, each already validated as `#RRGGBB` or `#AARRGGBB`. */
data class ThemeColorSet(
    val primary: String,
    val secondary: String,
    val tertiary: String,
    val background: String,
    val surface: String,
    val surfaceVariant: String,
    val onBackground: String,
    val onSurface: String,
)

/** A whole parsed theme file: its declared name, and one [ThemeColorSet] per mode. */
data class ThemeSpec(val name: String, val light: ThemeColorSet, val dark: ThemeColorSet)

/** [parseThemeJson]'s result: exactly one of a [Valid] spec or an [Invalid] reason -- never an
 *  exception, so every caller handles both without a `try`/`catch` of its own. */
sealed interface ThemeJsonResult {
    data class Valid(val spec: ThemeSpec) : ThemeJsonResult
    data class Invalid(val reason: String) : ThemeJsonResult
}

/**
 * Parses and strictly validates [raw] against the schema documented on this file, returning a
 * specific reason for every way it can be wrong rather than a generic "invalid JSON" -- the
 * owner's own bar, *"never a crash, never a partial application"*, plus the corollary this file
 * adds: never a vague one either. `SettingsTabs.kt`'s "Custom…" flow shows that reason inline,
 * next to the exact text the user pasted, so they can fix the one field named rather than
 * guessing at the whole file.
 *
 * This function never throws: a malformed JSON syntax error from [org.json.JSONObject] itself is
 * caught here and turned into the same [ThemeJsonResult.Invalid] shape as every other rejection.
 */
fun parseThemeJson(raw: String): ThemeJsonResult = try {
    ThemeJsonResult.Valid(parseThemeJsonOrThrow(raw))
} catch (e: JSONException) {
    ThemeJsonResult.Invalid("Malformed JSON: ${e.message ?: "the text could not be parsed"}")
} catch (e: ThemeJsonException) {
    ThemeJsonResult.Invalid(e.message ?: "Invalid theme JSON")
}

/** Thrown only within this file, and only ever caught by [parseThemeJson] itself -- the plain
 *  early-exit mechanism for [parseThemeJsonOrThrow]'s field-by-field validation, never seen by a
 *  caller of the public [parseThemeJson] function. */
private class ThemeJsonException(message: String) : Exception(message)

private fun parseThemeJsonOrThrow(raw: String): ThemeSpec {
    val root = JSONObject(raw) // JSONException on malformed syntax, caught by parseThemeJson.

    if (!root.has("version")) throw ThemeJsonException("Missing required field: version")
    val version = root.optInt("version", -1)
    if (version != THEME_JSON_VERSION) {
        throw ThemeJsonException(
            "Unsupported theme version: $version (this build understands version $THEME_JSON_VERSION)",
        )
    }

    if (!root.has("name")) throw ThemeJsonException("Missing required field: name")
    val name = root.optString("name", "")
    if (name.isBlank()) throw ThemeJsonException("Field \"name\" must not be blank")

    if (!root.has("colors")) throw ThemeJsonException("Missing required field: colors")
    val colors = root.optJSONObject("colors")
        ?: throw ThemeJsonException("Field \"colors\" must be an object")

    if (!colors.has("light")) throw ThemeJsonException("Missing required field: colors.light")
    val light = colors.optJSONObject("light")
        ?: throw ThemeJsonException("Field \"colors.light\" must be an object")

    if (!colors.has("dark")) throw ThemeJsonException("Missing required field: colors.dark")
    val dark = colors.optJSONObject("dark")
        ?: throw ThemeJsonException("Field \"colors.dark\" must be an object")

    return ThemeSpec(
        name = name,
        light = parseColorSet(light, "colors.light"),
        dark = parseColorSet(dark, "colors.dark"),
    )
}

/** The eight keys every mode's colour object must carry, in the schema's own order. */
private val COLOR_KEYS = listOf(
    "primary", "secondary", "tertiary", "background", "surface",
    "surfaceVariant", "onBackground", "onSurface",
)

private fun parseColorSet(obj: JSONObject, prefix: String): ThemeColorSet {
    val hex = COLOR_KEYS.associateWith { key -> requireHexColor(obj, prefix, key) }
    return ThemeColorSet(
        primary = hex.getValue("primary"),
        secondary = hex.getValue("secondary"),
        tertiary = hex.getValue("tertiary"),
        background = hex.getValue("background"),
        surface = hex.getValue("surface"),
        surfaceVariant = hex.getValue("surfaceVariant"),
        onBackground = hex.getValue("onBackground"),
        onSurface = hex.getValue("onSurface"),
    )
}

/** Strict `#RRGGBB` / `#AARRGGBB`, case-insensitive on the hex digits. Anything else -- a 3-digit
 *  shorthand, no `#`, a named colour, extra characters -- is refused by construction: this is the
 *  ENTIRE set of strings this app will ever try to interpret as a colour from a theme file. */
private val HEX_COLOR_REGEX = Regex("^#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$")

private fun requireHexColor(obj: JSONObject, prefix: String, key: String): String {
    if (!obj.has(key)) throw ThemeJsonException("Missing required field: $prefix.$key")
    val value = obj.optString(key, "")
    if (!HEX_COLOR_REGEX.matches(value)) {
        throw ThemeJsonException(
            "Invalid color for $prefix.$key: \"$value\" (expected #RRGGBB or #AARRGGBB)",
        )
    }
    return value
}

/** [requireHexColor] is the only thing that should ever hand this a string, so it trusts the
 *  input rather than re-validating it. An omitted alpha (`#RRGGBB`) means fully opaque. */
private fun hexToColor(hex: String): Color {
    val digits = hex.removePrefix("#")
    val argb = if (digits.length == 6) (0xFF000000L or digits.toLong(16)) else digits.toLong(16)
    return Color(argb)
}

/** Builds the real [ColorScheme] [FotoXplorrTheme] hands `MaterialTheme` -- the exact
 *  `darkColorScheme`/`lightColorScheme` call this app has always made, just fed from parsed JSON
 *  hex instead of hardcoded [Color] literals. */
fun ThemeColorSet.toColorScheme(dark: Boolean): ColorScheme {
    val primaryColor = hexToColor(primary)
    val secondaryColor = hexToColor(secondary)
    val tertiaryColor = hexToColor(tertiary)
    val backgroundColor = hexToColor(background)
    val surfaceColor = hexToColor(surface)
    val surfaceVariantColor = hexToColor(surfaceVariant)
    val onBackgroundColor = hexToColor(onBackground)
    val onSurfaceColor = hexToColor(onSurface)
    return if (dark) {
        darkColorScheme(
            primary = primaryColor,
            secondary = secondaryColor,
            tertiary = tertiaryColor,
            background = backgroundColor,
            surface = surfaceColor,
            surfaceVariant = surfaceVariantColor,
            onBackground = onBackgroundColor,
            onSurface = onSurfaceColor,
        )
    } else {
        lightColorScheme(
            primary = primaryColor,
            secondary = secondaryColor,
            tertiary = tertiaryColor,
            background = backgroundColor,
            surface = surfaceColor,
            surfaceVariant = surfaceVariantColor,
            onBackground = onBackgroundColor,
            onSurface = onSurfaceColor,
        )
    }
}

// =====================================================================================
// The five bundled presets, as literal schema-shaped JSON -- FotoXplorrTheme.kt used to compute
// `secondary`/`tertiary` as `accent.copy(alpha = 0.84f/0.68f)` (dark) or `0.88f/0.72f` (light),
// which the new schema cannot express (a hex colour has no alpha-blended-over-something-unknown
// meaning). Each is instead a CONCRETE hex value: the original accent alpha-blended over that
// mode's own `background`, computed once by hand and pasted in below, so the bundled presets
// still look recognisably the same as they always have rather than introducing a visible palette
// shift alongside a file format change nobody asked to see reflected in the UI. `primary` and the
// three shared neutrals (`background`/`surface`/`surfaceVariant`/`onBackground`/`onSurface`) are
// unchanged from the values FotoXplorrTheme.kt always used -- only secondary/tertiary needed
// converting at all, since they were the only ones ever alpha-derived.
// =====================================================================================

private val BUILT_IN_THEME_JSON: Map<AccentPalette, String> = mapOf(
    AccentPalette.VIOLET to """
        {"version":1,"name":"Violet","colors":{
          "light":{"primary":"#6E49B8","secondary":"#7F5EC1","tertiary":"#977BCC",
                   "background":"#FFFBFF","surface":"#FFFBFF","surfaceVariant":"#F0EBF2",
                   "onBackground":"#1D1A20","onSurface":"#1D1A20"},
          "dark":{"primary":"#CBB4FF","secondary":"#AD99D9","tertiary":"#8E7FB3",
                  "background":"#0D0D10","surface":"#121216","surfaceVariant":"#25242B",
                  "onBackground":"#F4F1F7","onSurface":"#F4F1F7"}
        }}
    """.trimIndent(),
    AccentPalette.OCEAN to """
        {"version":1,"name":"Ocean","colors":{
          "light":{"primary":"#006B9C","secondary":"#1F7CA8","tertiary":"#4793B8",
                   "background":"#FFFBFF","surface":"#FFFBFF","surfaceVariant":"#F0EBF2",
                   "onBackground":"#1D1A20","onSurface":"#1D1A20"},
          "dark":{"primary":"#80D1FF","secondary":"#6EB2D9","tertiary":"#5B92B3",
                  "background":"#0D0D10","surface":"#121216","surfaceVariant":"#25242B",
                  "onBackground":"#F4F1F7","onSurface":"#F4F1F7"}
        }}
    """.trimIndent(),
    AccentPalette.FOREST to """
        {"version":1,"name":"Forest","colors":{
          "light":{"primary":"#236C3A","secondary":"#3D7D52","tertiary":"#619471",
                   "background":"#FFFBFF","surface":"#FFFBFF","surfaceVariant":"#F0EBF2",
                   "onBackground":"#1D1A20","onSurface":"#1D1A20"},
          "dark":{"primary":"#8ED6A3","secondary":"#79B68B","tertiary":"#659674",
                  "background":"#0D0D10","surface":"#121216","surfaceVariant":"#25242B",
                  "onBackground":"#F4F1F7","onSurface":"#F4F1F7"}
        }}
    """.trimIndent(),
    AccentPalette.AMBER to """
        {"version":1,"name":"Amber","colors":{
          "light":{"primary":"#9A5A00","secondary":"#A66D1F","tertiary":"#B68747",
                   "background":"#FFFBFF","surface":"#FFFBFF","surfaceVariant":"#F0EBF2",
                   "onBackground":"#1D1A20","onSurface":"#1D1A20"},
          "dark":{"primary":"#FFCC80","secondary":"#D8AD6E","tertiary":"#B28F5C",
                  "background":"#0D0D10","surface":"#121216","surfaceVariant":"#25242B",
                  "onBackground":"#F4F1F7","onSurface":"#F4F1F7"}
        }}
    """.trimIndent(),
    AccentPalette.MONOCHROME to """
        {"version":1,"name":"Monochrome","colors":{
          "light":{"primary":"#4A474D","secondary":"#605D62","tertiary":"#7D797F",
                   "background":"#FFFBFF","surface":"#FFFBFF","surfaceVariant":"#F0EBF2",
                   "onBackground":"#1D1A20","onSurface":"#1D1A20"},
          "dark":{"primary":"#E4E1E6","secondary":"#C2BFC4","tertiary":"#9F9DA2",
                  "background":"#0D0D10","surface":"#121216","surfaceVariant":"#25242B",
                  "onBackground":"#F4F1F7","onSurface":"#F4F1F7"}
        }}
    """.trimIndent(),
)

/** Every bundled preset, parsed through the SAME [parseThemeJson] a pasted custom theme goes
 *  through -- one code path for "is this theme JSON good", not a trusted one for built-ins and a
 *  suspicious one for custom. A parse failure here is this file's OWN bug (the JSON above is a
 *  compile-time constant, never user input), so it fails loudly via [error] rather than falling
 *  back gracefully -- a broken bundled preset should break a test, not ship silently degraded. */
val BUILT_IN_THEMES: Map<AccentPalette, ThemeSpec> = BUILT_IN_THEME_JSON.mapValues { (palette, json) ->
    when (val result = parseThemeJson(json)) {
        is ThemeJsonResult.Valid -> result.spec
        is ThemeJsonResult.Invalid -> error("Bundled theme JSON for $palette is invalid: ${result.reason}")
    }
}

/** The literal JSON text a bundled preset is defined from, if the user ever wants to see or
 *  start a custom theme from one -- the SAME text [BUILT_IN_THEMES] is parsed from, not a second
 *  copy that could drift from it. Null for [AccentPalette.CUSTOM], which has no bundled text of
 *  its own. */
fun builtInThemeJson(palette: AccentPalette): String? = BUILT_IN_THEME_JSON[palette]

/**
 * Resolves the theme actually in force: a bundled preset, or, when
 * [GalleryPreferencesState.accentPalette] is [AccentPalette.CUSTOM],
 * [GalleryPreferencesState.customThemeJson] -- falling back to [AccentPalette.VIOLET] if that
 * JSON is ever empty or fails to parse, rather than crashing [FotoXplorrTheme].
 *
 * The settings flow that WRITES `customThemeJson` (`SettingsTabs.kt`'s "Custom…" confirm dialog)
 * never lets an invalid or unreviewed value reach here -- it validates before showing the
 * mandatory preview, and only Apply persists it. This fallback is the second, defensive line for
 * whatever that first line cannot see: a value edited directly in `SharedPreferences`, a future
 * version's stricter schema reading an older stored value, or a bug -- never the normal path.
 */
fun resolveThemeSpec(preferences: GalleryPreferencesState): ThemeSpec {
    if (preferences.accentPalette == AccentPalette.CUSTOM) {
        val result = parseThemeJson(preferences.customThemeJson)
        if (result is ThemeJsonResult.Valid) return result.spec
    }
    return BUILT_IN_THEMES[preferences.accentPalette] ?: BUILT_IN_THEMES.getValue(AccentPalette.VIOLET)
}
