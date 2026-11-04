package com.fotoxplorr.core.formats.raw

/** Collects validated previews; every candidate is range-checked by [JpegProbe] before it counts. */
internal class Candidates(private val r: Reader) {
    private val seen = HashSet<Long>()
    private var tried = 0
    val found = ArrayList<Pair<RawPreview, Int?>>()

    /** [offsets] are absolute candidate positions (a vendor offset tried against several bases). */
    fun add(kind: RawPreviewKind, length: Long, vararg offsets: Long) {
        for (abs in offsets) {
            if (length <= 0 || abs < 0 || !seen.add(abs xor (length shl 40))) continue
            if (++tried > MAX_TRIED) return
            val info = JpegProbe.probe(r, abs, length) ?: continue
            found += RawPreview(abs, length, info.width, info.height, 1, kind) to info.orientation
        }
    }

    /** The largest by pixels (then length), with [containerOrientation] else its own EXIF else 1. */
    fun best(containerOrientation: Int?): RawPreview? {
        val (p, own) = found.maxWithOrNull(
            compareBy<Pair<RawPreview, Int?>> { it.first.pixels }.thenBy { it.first.length },
        ) ?: return null
        return p.copy(orientation = containerOrientation ?: own ?: 1)
    }

    private companion object {
        const val MAX_TRIED = 96
    }
}
