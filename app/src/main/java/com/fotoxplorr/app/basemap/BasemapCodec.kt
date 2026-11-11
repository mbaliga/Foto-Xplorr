package com.fotoxplorr.app.basemap

/** One bundled place: [rank] 0 is the most populous. Coordinates in degrees. */
data class BasemapCity(val name: String, val lon: Double, val lat: Double, val rank: Int)

/** Closed or open paths, each a flat lon, lat array in 1/100 degree, with its bounding box. */
class BasemapLayer(val kind: Int, val paths: List<IntArray>) {
    /** minLon, minLat, maxLon, maxLat per path, 1/100 degree. */
    val bounds: List<IntArray> = paths.map { p ->
        var a = Int.MAX_VALUE; var b = Int.MAX_VALUE; var c = Int.MIN_VALUE; var d = Int.MIN_VALUE
        for (i in 0 until p.size / 2) {
            val x = p[2 * i]; val y = p[2 * i + 1]
            if (x < a) a = x; if (x > c) c = x; if (y < b) b = y; if (y > d) d = y
        }
        intArrayOf(a, b, c, d)
    }
}

class Basemap(val land: BasemapLayer, val lakes: BasemapLayer, val borders: BasemapLayer, val cities: List<BasemapCity>)

/**
 * Reader for the "FXB1" file `scripts/gen_basemap.py` writes (the format is documented there):
 * quantised 1/100 degree, delta-encoded zigzag varints. Pure Kotlin, no Android types.
 */
object BasemapCodec {
    const val KIND_LAND = 0
    const val KIND_LAKE = 1
    const val KIND_BORDER = 2

    fun decode(bytes: ByteArray): Basemap {
        require(
            bytes.size > 5 && bytes[0] == 'F'.code.toByte() && bytes[1] == 'X'.code.toByte() &&
                bytes[2] == 'B'.code.toByte() && bytes[3] == '1'.code.toByte(),
        ) { "Not a Fotoz basemap file" }
        val r = Reader(bytes, 4)
        val layers = HashMap<Int, BasemapLayer>()
        repeat(r.varint()) {
            val kind = r.byte()
            val count = r.varint()
            require(count >= 0 && count <= r.remaining()) { "Bad basemap path count" }
            val paths = ArrayList<IntArray>(count)
            repeat(count) {
                val points = r.varint()
                require(points >= 0 && points.toLong() * 2 <= r.remaining()) { "Bad basemap point count" }
                val xy = IntArray(points * 2)
                var x = 0
                var y = 0
                for (i in 0 until points) {
                    x += r.zigzag()
                    y += r.zigzag()
                    xy[2 * i] = x
                    xy[2 * i + 1] = y
                }
                paths += xy
            }
            layers[kind] = BasemapLayer(kind, paths)
        }
        val cityCount = r.varint()
        require(cityCount >= 0 && cityCount <= r.remaining()) { "Bad basemap city count" }
        val cities = ArrayList<BasemapCity>(cityCount)
        for (rank in 0 until cityCount) {
            val lon = r.zigzag() / 100.0
            val lat = r.zigzag() / 100.0
            val len = r.byte()
            require(len <= r.remaining()) { "Bad basemap city name" }
            cities += BasemapCity(String(bytes, r.pos, len, Charsets.UTF_8), lon, lat, rank)
            r.pos += len
        }
        val empty = BasemapLayer(-1, emptyList())
        return Basemap(layers[KIND_LAND] ?: empty, layers[KIND_LAKE] ?: empty, layers[KIND_BORDER] ?: empty, cities)
    }

    private class Reader(val b: ByteArray, var pos: Int) {
        fun remaining(): Int = b.size - pos
        fun byte(): Int {
            require(pos < b.size) { "Basemap file ends early" }
            return b[pos++].toInt() and 0xFF
        }
        fun varint(): Int {
            var result = 0L; var shift = 0
            while (true) {
                val v = byte()
                result = result or ((v and 0x7F).toLong() shl shift)
                if (v and 0x80 == 0) return result.toInt()
                shift += 7
                require(shift < 35) { "Bad number in basemap" }
            }
        }
        fun zigzag(): Int { val n = varint(); return (n ushr 1) xor -(n and 1) }
    }
}
