package com.fotoxplorr.core.formats.simple

/**
 * Reads one named entry out of a ZIP held in memory (stored or deflated; no ZIP64, no encryption).
 * Enough for OpenRaster and Krita, whose thumbnails are ordinary entries. It works from the central
 * directory, so a hostile local header cannot misdirect it, and every size is checked against the
 * caller's inflate cap BEFORE anything is allocated.
 */
internal object ZipReader {

    class Entry(val name: String, val method: Int, val compressedSize: Long, val size: Long, val localOffset: Long)

    fun entries(b: ByteArray): List<Entry> {
        // End of central directory: scan back through the maximum comment length.
        var eocd = -1
        var i = b.size - 22
        val stop = maxOf(0, b.size - 22 - 65_535)
        while (i >= stop) {
            if (b[i] == 0x50.toByte() && b[i + 1] == 0x4B.toByte() && b[i + 2] == 0x05.toByte() && b[i + 3] == 0x06.toByte()) { eocd = i; break }
            i--
        }
        if (eocd < 0) bad("not a ZIP")
        val count = b.le16(eocd + 10)
        val cdOffset = b.le32(eocd + 16)
        if (count == 0xFFFF || cdOffset == 0xFFFFFFFFL) bad("ZIP64 is not supported")
        if (cdOffset >= b.size) bad("bad ZIP directory")
        var p = cdOffset.toInt()
        val out = ArrayList<Entry>()
        repeat(minOf(count, 4096)) {
            if (p + 46 > b.size || b.le32(p) != 0x02014B50L) bad("bad ZIP directory")
            val flags = b.le16(p + 8)
            val method = b.le16(p + 10)
            val csize = b.le32(p + 20)
            val usize = b.le32(p + 24)
            val nameLen = b.le16(p + 28)
            val extraLen = b.le16(p + 30)
            val commentLen = b.le16(p + 32)
            val local = b.le32(p + 42)
            if (p + 46 + nameLen > b.size) bad("bad ZIP directory")
            val name = CharArray(nameLen) { (b[p + 46 + it].toInt() and 0xFF).toChar() }.concatToString()
            if (flags and 1 == 0) out.add(Entry(name, method, csize, usize, local))
            p += 46 + nameLen + extraLen + commentLen
        }
        return out
    }

    fun read(b: ByteArray, entry: Entry, maxSize: Long): ByteArray {
        if (entry.size > maxSize || entry.compressedSize > b.size) bad("ZIP entry too large")
        val lp = entry.localOffset
        if (lp < 0 || lp + 30 > b.size || b.le32(lp.toInt()) != 0x04034B50L) bad("bad ZIP entry")
        val start = lp + 30 + b.le16(lp.toInt() + 26) + b.le16(lp.toInt() + 28)
        if (start + entry.compressedSize > b.size) bad("ZIP entry truncated")
        return when (entry.method) {
            0 -> b.copyOfRange(start.toInt(), (start + entry.compressedSize).toInt())
            8 -> Inflate.raw(b, start.toInt(), entry.compressedSize.toInt(), minOf(maxSize, entry.size), entry.size)
            else -> bad("ZIP method not supported")
        }
    }

    /** The first entry of [names] that exists, decoded; null when none does. */
    fun readFirst(b: ByteArray, names: List<String>, maxSize: Long): ByteArray? {
        val all = entries(b)
        for (n in names) {
            val e = all.firstOrNull { it.name.equals(n, ignoreCase = true) } ?: continue
            return read(b, e, maxSize)
        }
        return null
    }
}
