package com.fotoxplorr.app.viewer.colour

/** The window colour modes the viewer can ask for; the Android seam maps them to `ActivityInfo.COLOR_MODE_*`. */
enum class WindowColourMode { DEFAULT, WIDE_COLOR_GAMUT, HDR }

/** What the file says about its own colour. */
data class PhotoColourFacts(
    /** The XMP declares the Ultra HDR gain-map namespace (a real positive, see `DetailFormatting.dynamicRangeBadge`). */
    val hasGainMap: Boolean,
    /** The embedded profile's name ("Display P3", "sRGB IEC61966-2.1", "Rec. 2100 PQ"), when the platform could name it. */
    val profileName: String?,
    /** EXIF's own ColorSpace word, when present. */
    val exifColourSpace: String? = null,
)

/** What the screen and OS can do, as the `Configuration` reports it. */
data class ScreenColourFacts(val sdk: Int, val screenHdr: Boolean, val screenWideGamut: Boolean)

/** The decision, with the one sentence that says what the person is seeing (or why not). */
data class ColourDecision(val mode: WindowColourMode, val sentence: String?)

/**
 * Which window colour mode a photo wants on this screen: HDR for a gain-map or PQ/HLG photo when the
 * OS (Android 14+, where Ultra HDR gain maps are rendered) and the panel can show it; wide colour
 * for a P3-or-wider photo on a wide-gamut panel (Android 8+); otherwise the default. Pure, so the
 * "never ask a screen for what it cannot do" rule is a test. The Android seam
 * ([ApplyWindowColourMode]) applies the answer; whether a highlight actually looks brighter is a
 * device check.
 *
 * Not done here: `Window.setDesiredHdrHeadroom` (Android 15). The headroom a gain map wants is
 * its `hdrgm:GainMapMax`, which the app's XMP reader does not expose; the system's default
 * (as much as the panel can give) is what is in force.
 */
object ColourModePolicy {
    const val SDK_WIDE_COLOUR = 26
    const val SDK_ULTRA_HDR = 34

    private val WIDE_WORDS = listOf("display p3", "p3", "adobe rgb", "adobergb", "prophoto", "rec. 2020", "rec.2020", "bt.2020", "bt2020", "dci", "rec. 2100", "rec.2100")
    private val HDR_WORDS = listOf("pq", "hlg", "2100", "st 2084", "st2084")

    fun isWideProfile(name: String?): Boolean {
        val n = name?.lowercase()?.trim().orEmpty()
        return n.isNotEmpty() && WIDE_WORDS.any { it in n }
    }

    fun isHdrTransferProfile(name: String?): Boolean {
        val n = name?.lowercase()?.trim().orEmpty()
        return n.isNotEmpty() && HDR_WORDS.any { it in n }
    }

    fun decide(photo: PhotoColourFacts, screen: ScreenColourFacts): ColourDecision {
        val wantsHdr = photo.hasGainMap || isHdrTransferProfile(photo.profileName)
        val wide = isWideProfile(photo.profileName) || isWideProfile(photo.exifColourSpace)
        if (wantsHdr) {
            return when {
                screen.sdk < SDK_ULTRA_HDR -> ColourDecision(WindowColourMode.DEFAULT, Copy.OLD_ANDROID)
                !screen.screenHdr -> ColourDecision(WindowColourMode.DEFAULT, Copy.SCREEN_NOT_HDR)
                else -> ColourDecision(WindowColourMode.HDR, Copy.SHOWING_HDR)
            }
        }
        if (wide) {
            return if (screen.sdk >= SDK_WIDE_COLOUR && screen.screenWideGamut) {
                ColourDecision(WindowColourMode.WIDE_COLOR_GAMUT, Copy.SHOWING_WIDE)
            } else {
                ColourDecision(WindowColourMode.DEFAULT, Copy.SCREEN_NOT_WIDE)
            }
        }
        return ColourDecision(WindowColourMode.DEFAULT, null)
    }

    /** One short sentence each, in the feature's own words. */
    object Copy {
        const val SHOWING_HDR = "Showing this photo in HDR."
        const val SHOWING_WIDE = "Showing this photo in wide colour."
        const val SCREEN_NOT_HDR = "This screen cannot show the HDR highlights."
        const val SCREEN_NOT_WIDE = "This screen shows it in standard colour."
        const val OLD_ANDROID = "This Android version shows it in standard range."
    }
}
