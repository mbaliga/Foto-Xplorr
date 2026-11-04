package com.fotoxplorr.app.viewer.colour

import com.fotoxplorr.app.viewer.DetailFormatting
import com.fotoxplorr.app.viewer.ImageExifDetails

/** One row of the pro info card. */
data class ProInfoLine(val label: String, val value: String)

/**
 * The rows of the viewer's pro info card, built from facts the viewer already read (EXIF, MediaStore
 * size) and the colour decision. Pure; only rows with something true to say are returned, in a fixed
 * order (what the pixels are, then what the screen does with them, then how it was shot).
 */
object ProInfoLines {
    fun of(
        details: ImageExifDetails,
        mimeType: String,
        width: Int,
        height: Int,
        sizeBytes: Long,
        decision: ColourDecision,
    ): List<ProInfoLine> = buildList {
        val w = if (details.displayWidth > 0) details.displayWidth else width
        val h = if (details.displayHeight > 0) details.displayHeight else height
        DetailFormatting.dynamicRangeBadge(details.hasHdrGainMap, mimeType)?.let { add(ProInfoLine("Range", it)) }
        (details.colorProfile ?: details.colorSpace)?.takeIf { it.isNotBlank() }?.let { add(ProInfoLine("Colour", it)) }
        decision.sentence?.let { add(ProInfoLine("Screen", it)) }
        if (w > 0 && h > 0) {
            add(ProInfoLine("Size", listOfNotNull(DetailFormatting.megapixels(w, h), "$w × $h").joinToString(" • ")))
        }
        DetailFormatting.formatBytes(sizeBytes)?.let { add(ProInfoLine("File", it)) }
        val exposure = listOfNotNull(
            details.aperture?.let { DetailFormatting.apertureText(it) },
            details.shutterSpeed?.takeIf { it.isNotBlank() },
            details.iso?.takeIf { it.isNotBlank() }?.let { "ISO $it" },
        )
        if (exposure.isNotEmpty()) add(ProInfoLine("Exposure", exposure.joinToString("  ")))
        details.exposureBiasEv?.let { add(ProInfoLine("Bias", DetailFormatting.exposureBiasText(it))) }
        DetailFormatting.lensLine(details.lensModel, details.focalLengthMm, details.aperture)
            ?.takeIf { it.isNotBlank() }?.let { add(ProInfoLine("Lens", it)) }
    }
}
