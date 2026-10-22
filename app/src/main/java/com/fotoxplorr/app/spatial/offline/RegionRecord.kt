package com.fotoxplorr.app.spatial.offline

/**
 * What the app remembers about one saved map area, stored as plain text in the map engine's own
 * per-region metadata so a relaunch can list regions without a second database.
 *
 * The codec is `key=value`, one per line, so it runs on a plain JVM (no org.json) and an older app
 * can read a newer record: unknown keys and lines with no `=` are skipped. A key the app needs
 * (the box and the zoom range) that is missing or unreadable makes the whole record undecodable,
 * because guessing a box would download or delete the wrong place.
 *
 * [bytes] is the estimate made when the area was planned and stays so: the engine has no call to
 * rewrite a stored record, so the figure it measures is never written back. Per-region progress is
 * not stored here either: it lives in the engine.
 */
data class RegionRecord(
    val name: String,
    val box: GeoBox,
    val minZoom: Int,
    val maxZoom: Int,
    /** A short id for the style that was downloaded, not its address. */
    val styleId: String,
    val createdAtMillis: Long,
    /**
     * Written equal to [createdAtMillis] when the area is saved and never updated since: nothing
     * records a "use" of a saved area. Kept in the text so records written so far still decode.
     */
    val lastUsedMillis: Long,
    val bytes: Long,
    val tileCount: Long,
) {
    fun encode(): String = buildString {
        line("v", VERSION.toString())
        line("name", escape(cutName(name)))
        line("west", box.west.toString())
        line("south", box.south.toString())
        line("east", box.east.toString())
        line("north", box.north.toString())
        line("minZoom", minZoom.toString())
        line("maxZoom", maxZoom.toString())
        line("style", escape(styleId))
        line("created", createdAtMillis.toString())
        line("lastUsed", lastUsedMillis.toString())
        line("bytes", bytes.toString())
        line("tiles", tileCount.toString())
    }.trimEnd('\n')

    companion object {
        /** Version of the text layout. Readers ignore it: new fields arrive as new keys. */
        const val VERSION: Int = 1

        const val MAX_NAME_LENGTH: Int = 80

        /** The record in [text], or null when it is empty or lacks a readable box or zoom range. */
        fun decode(text: String): RegionRecord? {
            val values = HashMap<String, String>()
            for (raw in text.lineSequence()) {
                val line = raw.trimEnd('\r')
                val at = line.indexOf('=')
                if (at <= 0) continue
                values[line.substring(0, at)] = line.substring(at + 1)
            }

            val west = values["west"]?.toDoubleOrNull() ?: return null
            val south = values["south"]?.toDoubleOrNull() ?: return null
            val east = values["east"]?.toDoubleOrNull() ?: return null
            val north = values["north"]?.toDoubleOrNull() ?: return null
            val box = GeoBox.orNull(west, south, east, north) ?: return null

            val minZoom = values["minZoom"]?.toIntOrNull() ?: return null
            val maxZoom = values["maxZoom"]?.toIntOrNull() ?: return null
            if (minZoom < 0 || maxZoom > TileMath.MAX_SUPPORTED_ZOOM || minZoom > maxZoom) return null

            val created = optionalLong(values, "created") ?: return null
            val lastUsed = optionalLong(values, "lastUsed") ?: return null
            val bytes = optionalLong(values, "bytes") ?: return null
            val tiles = optionalLong(values, "tiles") ?: return null

            return RegionRecord(
                name = cutName(unescape(values["name"].orEmpty())),
                box = box,
                minZoom = minZoom,
                maxZoom = maxZoom,
                styleId = unescape(values["style"].orEmpty()),
                createdAtMillis = created,
                lastUsedMillis = lastUsed,
                bytes = bytes,
                tileCount = tiles,
            )
        }

        /** Absent means 0; present but unreadable or negative means the record is corrupt (null). */
        private fun optionalLong(values: Map<String, String>, key: String): Long? {
            val raw = values[key] ?: return 0L
            return raw.toLongOrNull()?.takeIf { it >= 0L }
        }

        private fun StringBuilder.line(key: String, value: String) {
            append(key).append('=').append(value).append('\n')
        }

        private fun cutName(name: String): String {
            if (name.length <= MAX_NAME_LENGTH) return name
            val cut = name.substring(0, MAX_NAME_LENGTH)
            return if (Character.isHighSurrogate(cut.last())) cut.dropLast(1) else cut
        }

        private fun escape(value: String): String = buildString {
            for (c in value) {
                when (c) {
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    else -> append(c)
                }
            }
        }

        private fun unescape(value: String): String {
            if ('\\' !in value) return value
            return buildString {
                var i = 0
                while (i < value.length) {
                    val c = value[i]
                    if (c == '\\' && i + 1 < value.length) {
                        when (val next = value[i + 1]) {
                            'n' -> append('\n')
                            'r' -> append('\r')
                            else -> append(next)
                        }
                        i += 2
                    } else {
                        append(c)
                        i += 1
                    }
                }
            }
        }
    }
}

/** Whether a new region may be added without removing another. */
sealed interface RoomVerdict {
    data object Ok : RoomVerdict

    /** Already at [limit] regions: delete one first. */
    data class TooManyRegions(val limit: Int) : RoomVerdict

    /** The new region would take the total past [limit] by [overBy] bytes. */
    data class OverTotalBytes(val limit: Long, val overBy: Long) : RoomVerdict
}

/**
 * The count and total-size limits across all saved regions.
 *
 * This only answers yes or no. It never picks a region to remove: eviction would delete data the
 * person was not shown (TRAPS #64), so the answer is a refusal and the person chooses what to
 * delete themselves.
 */
object RegionLedger {

    /** Sum of the records' sizes; stops at [Long.MAX_VALUE] rather than wrapping. */
    fun totalBytes(records: List<RegionRecord>): Long {
        var total = 0L
        for (record in records) {
            total = if (total > Long.MAX_VALUE - record.bytes) Long.MAX_VALUE else total + record.bytes
        }
        return total
    }

    /** Count first, then bytes. A total exactly on the cap is allowed. */
    fun checkRoom(existing: List<RegionRecord>, incomingBytes: Long): RoomVerdict {
        if (existing.size >= OfflineMapLimits.MAX_REGIONS) {
            return RoomVerdict.TooManyRegions(OfflineMapLimits.MAX_REGIONS)
        }
        val used = totalBytes(existing)
        val incoming = incomingBytes.coerceAtLeast(0L)
        val total = if (used > Long.MAX_VALUE - incoming) Long.MAX_VALUE else used + incoming
        val over = total - OfflineMapLimits.MAX_TOTAL_BYTES
        return if (over > 0L) RoomVerdict.OverTotalBytes(OfflineMapLimits.MAX_TOTAL_BYTES, over) else RoomVerdict.Ok
    }
}
