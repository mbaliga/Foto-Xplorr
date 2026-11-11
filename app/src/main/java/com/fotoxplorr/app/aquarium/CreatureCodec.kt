package com.fotoxplorr.app.aquarium

import com.fotoxplorr.app.editor.JsonValue
import com.fotoxplorr.app.editor.asBool
import com.fotoxplorr.app.editor.asInt
import com.fotoxplorr.app.editor.asLong
import com.fotoxplorr.app.editor.asString
import com.fotoxplorr.app.editor.get
import com.fotoxplorr.app.editor.jsonObjectOf
import com.fotoxplorr.app.editor.parseJson
import com.fotoxplorr.app.editor.stringify
import com.fotoxplorr.app.editor.toJson
import java.util.UUID

/**
 * The small record stored beside a creature's PNG: enough to bring it back. The picture itself
 * lives in the PNG; this holds the sprite size (to check the PNG against), when it was made, and
 * which way the head points.
 */
data class StoredCreature(
    /** A UUID string, see [CreatureId]; it is also the file name, so it is never free text. */
    val id: String,
    val createdAtMillis: Long,
    val widthPx: Int,
    val heightPx: Int,
    /** True when the head is on the left of the picture. */
    val headLeft: Boolean,
    /** The version the record was WRITTEN with; [CreatureCodec.encode] always writes [CURRENT_VERSION]. */
    val version: Int = CURRENT_VERSION,
) {
    /** Width / height of the sprite, or 1 when a size is missing. */
    val aspect: Float get() = if (widthPx > 0 && heightPx > 0) widthPx.toFloat() / heightPx else 1f

    /** True for a record written by a newer build; its known fields were still read. */
    val isFromNewerVersion: Boolean get() = version > CURRENT_VERSION

    fun params(): CreatureParams = CreatureParams.forAspect(aspect, headLeft)

    companion object {
        const val CURRENT_VERSION = 1
        const val MAX_SIDE_PX = 4096
    }
}

/** Creature ids are random UUIDs and nothing else, so an id can be used in a file name safely. */
object CreatureId {
    private val shape = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    fun generate(): String = UUID.randomUUID().toString()

    fun isValid(id: String): Boolean = shape.matches(id)
}

/**
 * JSON for a [StoredCreature], on the app's own pure-Kotlin JSON reader (no org.json, so it runs in
 * a plain JVM test). The reader is TOLERANT, because a file may have been written by an older or a
 * newer build or half-damaged: unknown fields are ignored, a missing or odd version reads as 1,
 * missing optional fields take defaults, numbers are clamped; only a missing or non-UUID id, or text
 * that is not a JSON object, is refused (null). It never throws.
 */
object CreatureCodec {

    fun encode(creature: StoredCreature): String = jsonObjectOf(
        "version" to StoredCreature.CURRENT_VERSION.toJson(),
        "id" to creature.id.toJson(),
        "createdAt" to creature.createdAtMillis.toJson(),
        "widthPx" to creature.widthPx.toJson(),
        "heightPx" to creature.heightPx.toJson(),
        "headLeft" to creature.headLeft.toJson(),
    ).stringify()

    fun decode(text: String): StoredCreature? {
        val root = parseJson(text) as? JsonValue.JsonObject ?: return null
        val id = root["id"].asString()
        if (!CreatureId.isValid(id)) return null
        val version = root["version"].asInt(1).let { if (it < 1) 1 else it }
        return StoredCreature(
            id = id,
            createdAtMillis = root["createdAt"].asLong(0L).coerceAtLeast(0L),
            widthPx = root["widthPx"].asInt(0).coerceIn(0, StoredCreature.MAX_SIDE_PX),
            heightPx = root["heightPx"].asInt(0).coerceIn(0, StoredCreature.MAX_SIDE_PX),
            headLeft = root["headLeft"].asBool(false),
            version = version,
        )
    }
}
