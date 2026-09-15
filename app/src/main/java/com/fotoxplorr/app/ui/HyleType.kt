package com.fotoxplorr.app.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.fotoxplorr.app.R

/**
 * Hyle Grotesk Classic, the app's only typeface.
 *
 * The mockups specify Space Grotesk; the owner's direction is that the app uses Hyle faces only
 * (2026-08-18: *"No Space Grotesk. Only use Hyle Fonts. Hyle has a Grotesk as well, use that."*).
 * Hyle Grotesk Classic **is** that face reworked in house — its own licence note says it is built
 * on Space Grotesk with Archivo letterforms substituted — so following the direction costs the
 * mockups nothing. The metrics the designs were drawn against still hold.
 *
 * Classic rather than Plus: Plus is the same font with Hyle Deco sweep letterforms for N and R,
 * which is a display treatment. A swept N in the middle of `4,822 of 12,366` would be a flourish
 * inside a progress readout.
 *
 * Bundled under the SIL Open Font License 1.1 — the note ships in `res/raw/license_hyle_grotesk`.
 * Four weights, 88 KB each: the whole family is about the size of two photographs.
 */
val HyleGrotesk = FontFamily(
    Font(R.font.hyle_grotesk_light, FontWeight.Light),
    Font(R.font.hyle_grotesk_regular, FontWeight.Normal),
    Font(R.font.hyle_grotesk_medium, FontWeight.Medium),
    Font(R.font.hyle_grotesk_bold, FontWeight.Bold),
)

/**
 * Material's type scale, re-cut in Hyle Grotesk.
 *
 * Every style is respecified rather than only the ones the app uses today, because a partially
 * overridden Typography is the shape that leaks: one Material component reaching for a style
 * nobody remembered renders in Roboto, and it shows up as a single wrong-looking line in a screen
 * that is otherwise consistent.
 */
val HyleTypography: Typography = Typography().let { base ->
    Typography(
        displayLarge = base.displayLarge.copy(fontFamily = HyleGrotesk),
        displayMedium = base.displayMedium.copy(fontFamily = HyleGrotesk),
        displaySmall = base.displaySmall.copy(fontFamily = HyleGrotesk),
        headlineLarge = base.headlineLarge.copy(fontFamily = HyleGrotesk),
        headlineMedium = base.headlineMedium.copy(fontFamily = HyleGrotesk),
        headlineSmall = base.headlineSmall.copy(fontFamily = HyleGrotesk),
        titleLarge = base.titleLarge.copy(fontFamily = HyleGrotesk),
        titleMedium = base.titleMedium.copy(fontFamily = HyleGrotesk),
        titleSmall = base.titleSmall.copy(fontFamily = HyleGrotesk),
        bodyLarge = base.bodyLarge.copy(fontFamily = HyleGrotesk),
        bodyMedium = base.bodyMedium.copy(fontFamily = HyleGrotesk),
        bodySmall = base.bodySmall.copy(fontFamily = HyleGrotesk),
        labelLarge = base.labelLarge.copy(fontFamily = HyleGrotesk),
        labelMedium = base.labelMedium.copy(fontFamily = HyleGrotesk),
        labelSmall = base.labelSmall.copy(fontFamily = HyleGrotesk),
    )
}
