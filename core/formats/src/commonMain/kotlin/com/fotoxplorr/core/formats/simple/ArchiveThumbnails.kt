package com.fotoxplorr.core.formats.simple

/**
 * OpenRaster and Krita documents are ZIPs that carry a flattened PNG: `mergedimage.png` for both
 * (full size), with `Thumbnails/thumbnail.png` (ORA) or `preview.png` (KRA) as the small fallback.
 * Layers are never composed here.
 */
internal object ArchiveThumbnails {
    private val ORA = listOf("mergedimage.png", "Thumbnails/thumbnail.png")
    private val KRA = listOf("mergedimage.png", "preview.png")

    fun decode(b: ByteArray, krita: Boolean, limits: SimpleLimits): DecodedImage {
        val names = if (krita) KRA else ORA
        val all = ZipReader.entries(b)
        for (n in names) {
            val e = all.firstOrNull { it.name.equals(n, ignoreCase = true) } ?: continue
            val png = ZipReader.read(b, e, limits.maxInflated)
            return PngLite.decode(png, 0, png.size, limits)
        }
        bad("no merged image in the archive")
    }
}
