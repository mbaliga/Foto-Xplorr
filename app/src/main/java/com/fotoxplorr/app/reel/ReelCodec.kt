package com.fotoxplorr.app.reel

import com.fotoxplorr.app.editor.JsonValue
import com.fotoxplorr.app.editor.asArray
import com.fotoxplorr.app.editor.asInt
import com.fotoxplorr.app.editor.asLong
import com.fotoxplorr.app.editor.asObject
import com.fotoxplorr.app.editor.asString
import com.fotoxplorr.app.editor.get
import com.fotoxplorr.app.editor.jsonArrayOf
import com.fotoxplorr.app.editor.jsonObjectOf
import com.fotoxplorr.app.editor.parseJson
import com.fotoxplorr.app.editor.stringify
import com.fotoxplorr.app.editor.toJson

/**
 * What [ReelCodec.decode] read: the [project], how many entries it had to leave out ([skippedItems]:
 * an entry of a kind this build does not know, one with no picture named, or any beyond
 * [ReelLimits.MAX_ITEMS]), and whether the text came from a newer build ([fromNewerVersion]).
 *
 * When either is set, saving the project again WRITES BACK ONLY WHAT THIS BUILD KNOWS: the left-out
 * entries and fields are gone from the new text. The screen should say so before it overwrites.
 */
data class ReelDecoded(
    val project: ReelProject,
    val skippedItems: Int,
    val fromNewerVersion: Boolean,
)

/**
 * A reel project as JSON text (on the app's own pure-Kotlin JSON reader, so it runs in a plain JVM
 * test). Plain text on purpose: a reel can be kept in a file, a backup or a bug report and read by a
 * person.
 *
 * ## Tolerant, never throwing
 * Unknown keys are ignored. A missing or odd value takes a default (an unknown transition is a cut,
 * an unknown motion is none, an unknown aspect is 16:9), a number is clamped into its range, a
 * missing or repeated item id is replaced by a fresh one, and an item of an unknown kind is left out
 * and counted ([ReelDecoded.skippedItems]). Only text that is not a JSON object gives null. The
 * decoded project is safe to show; [ReelPlan] still tells what is wrong with it as a plan.
 *
 * Numbers that are fractions are written the way `Float.toString` prints them and read back to the
 * same `Float`, so a project round-trips exactly.
 */
object ReelCodec {
    const val CURRENT_VERSION = 1

    /** A position in a file is never more than a week in; a larger number is a damaged file, and this keeps the sums from overflowing. */
    private const val MAX_POSITION_MS = 7L * 24 * 3_600_000L

    fun encode(project: ReelProject): String = jsonObjectOf(
        "version" to CURRENT_VERSION.toJson(),
        "name" to project.name.toJson(),
        "aspect" to project.aspect.code.toJson(),
        "items" to jsonArrayOf(project.items.map(::encodeItem)),
        "soundtrack" to (project.soundtrack?.let(::encodeSoundtrack) ?: JsonValue.JsonNull),
    ).stringify()

    fun decode(text: String): ReelDecoded? {
        val root = parseJson(text) as? JsonValue.JsonObject ?: return null
        val version = root["version"].asInt(1).coerceAtLeast(1)

        val taken = HashSet<String>()
        val raw = root["items"].asArray()
        var skipped = 0
        val items = ArrayList<ReelItem>()
        for (entry in raw) {
            if (items.size >= ReelLimits.MAX_ITEMS) {
                skipped++
                continue
            }
            val item = decodeItem(entry, taken, items.size)
            if (item == null) skipped++ else items += item
        }

        val project = ReelProject(
            items = items,
            aspect = ReelAspect.fromCode(root["aspect"].asString().ifEmpty { null }),
            soundtrack = decodeSoundtrack(root["soundtrack"]),
            name = root["name"].asString().take(ReelLimits.TEXT_MAX_CHARS),
        )
        return ReelDecoded(project, skipped, fromNewerVersion = version > CURRENT_VERSION)
    }

    // ---- items --------------------------------------------------------------------------------

    private fun encodeItem(item: ReelItem): JsonValue = when (item) {
        is StillItem -> jsonObjectOf(
            "type" to "still".toJson(),
            "id" to item.id.toJson(),
            "media" to item.mediaId.toJson(),
            "ms" to item.durationMs.toJson(),
            "motion" to encodeMotion(item.motion),
            "next" to item.transition.code.toJson(),
        )
        is ClipItem -> jsonObjectOf(
            "type" to "clip".toJson(),
            "id" to item.id.toJson(),
            "media" to item.mediaId.toJson(),
            "in" to item.inMs.toJson(),
            "out" to item.outMs.toJson(),
            "source" to item.sourceDurationMs.toJson(),
            "next" to item.transition.code.toJson(),
        )
        is TitleItem -> jsonObjectOf(
            "type" to "title".toJson(),
            "id" to item.id.toJson(),
            "text" to item.text.toJson(),
            "color" to argbText(item.backgroundArgb).toJson(),
            "ms" to item.durationMs.toJson(),
            "next" to item.transition.code.toJson(),
        )
    }

    private fun decodeItem(entry: JsonValue, taken: MutableSet<String>, position: Int): ReelItem? {
        if (entry !is JsonValue.JsonObject) return null
        val transition = ReelTransition.fromCode(entry["next"].asString().ifEmpty { null })
        val id = uniqueId(entry["id"].asString(), taken, position)
        return when (entry["type"].asString()) {
            "still" -> {
                val media = mediaIdOf(entry) ?: return null
                StillItem(
                    id = id,
                    mediaId = media,
                    durationMs = entry["ms"].asLong(ReelLimits.STILL_DEFAULT_MS).coerceIn(ReelLimits.MIN_ITEM_MS, ReelLimits.ITEM_MAX_MS),
                    motion = decodeMotion(entry["motion"]),
                    transition = transition,
                )
            }
            "clip" -> {
                val media = mediaIdOf(entry) ?: return null
                val inMs = entry["in"].asLong(0L).coerceIn(0L, MAX_POSITION_MS)
                val outMs = entry["out"].asLong(0L).coerceAtLeast(inMs + ReelLimits.MIN_ITEM_MS)
                    .coerceAtMost(inMs + ReelLimits.ITEM_MAX_MS)
                ClipItem(
                    id = id,
                    mediaId = media,
                    inMs = inMs,
                    outMs = outMs,
                    sourceDurationMs = entry["source"].asLong(0L).coerceIn(0L, MAX_POSITION_MS),
                    transition = transition,
                )
            }
            "title" -> TitleItem(
                id = id,
                text = entry["text"].asString().take(ReelLimits.TEXT_MAX_CHARS),
                backgroundArgb = parseArgb(entry["color"].asString()) ?: ReelLimits.TITLE_DEFAULT_ARGB,
                durationMs = entry["ms"].asLong(ReelLimits.TITLE_DEFAULT_MS).coerceIn(ReelLimits.MIN_ITEM_MS, ReelLimits.ITEM_MAX_MS),
                transition = transition,
            )
            else -> null
        }
    }

    /** The picture an entry names, or null when it names none (a library id is positive). */
    private fun mediaIdOf(entry: JsonValue): Long? {
        val media = entry["media"]
        if (media !is JsonValue.JsonNumber) return null
        return media.value.toLong().takeIf { it > 0L }
    }

    private fun uniqueId(wanted: String, taken: MutableSet<String>, position: Int): String {
        val clean = wanted.take(40)
        if (clean.isNotEmpty() && taken.add(clean)) return clean
        var n = position + 1
        while (!taken.add("r$n")) n++
        return "r$n"
    }

    // ---- motion -------------------------------------------------------------------------------

    private fun encodeMotion(motion: ReelMotion): JsonValue = when (motion) {
        ReelMotion.None -> jsonObjectOf("kind" to "none".toJson())
        is ReelMotion.ZoomIn -> jsonObjectOf("kind" to "zoomIn".toJson(), "x" to number(motion.focus.x), "y" to number(motion.focus.y))
        is ReelMotion.ZoomOut -> jsonObjectOf("kind" to "zoomOut".toJson(), "x" to number(motion.focus.x), "y" to number(motion.focus.y))
        is ReelMotion.Pan -> jsonObjectOf(
            "kind" to "pan".toJson(),
            "from" to rectArray(motion.start),
            "to" to rectArray(motion.end),
        )
    }

    private fun decodeMotion(value: JsonValue): ReelMotion = when (value["kind"].asString()) {
        "zoomIn" -> ReelMotion.ZoomIn(focusOf(value))
        "zoomOut" -> ReelMotion.ZoomOut(focusOf(value))
        "pan" -> {
            val start = rectOf(value["from"])
            val end = rectOf(value["to"])
            if (start != null && end != null) ReelMotion.Pan(start, end) else ReelMotion.None
        }
        else -> ReelMotion.None
    }

    private fun focusOf(value: JsonValue): NormPoint = NormPoint(
        x = floatOf(value["x"], 0.5f).coerceIn(0f, 1f),
        y = floatOf(value["y"], 0.5f).coerceIn(0f, 1f),
    )

    private fun rectArray(r: NormRect): JsonValue =
        jsonArrayOf(listOf(number(r.left), number(r.top), number(r.right), number(r.bottom)))

    /** A rectangle clamped into the picture, or null when it is not four numbers or has no size left. */
    private fun rectOf(value: JsonValue): NormRect? {
        val parts = value.asArray()
        if (parts.size < 4 || parts.take(4).any { it !is JsonValue.JsonNumber }) return null
        val n = parts.take(4).map { (it as JsonValue.JsonNumber).value.toFloat().coerceIn(0f, 1f) }
        val rect = NormRect(n[0], n[1], n[2], n[3])
        return rect.takeIf { it.width >= ReelMotion.MIN_WINDOW_SIDE && it.height >= ReelMotion.MIN_WINDOW_SIDE }
    }

    // ---- soundtrack ---------------------------------------------------------------------------

    private fun encodeSoundtrack(s: ReelSoundtrack): JsonValue = jsonObjectOf(
        "uri" to s.uri.toJson(),
        "trimStart" to s.trimStartMs.toJson(),
        "fadeOut" to s.fadeOutMs.toJson(),
    )

    private fun decodeSoundtrack(value: JsonValue): ReelSoundtrack? {
        if (value.asObject().isEmpty()) return null
        val uri = value["uri"].asString()
        if (uri.isBlank()) return null
        return ReelSoundtrack(
            uri = uri,
            trimStartMs = value["trimStart"].asLong(0L).coerceIn(0L, MAX_POSITION_MS),
            fadeOutMs = value["fadeOut"].asLong(ReelLimits.FADE_OUT_DEFAULT_MS).coerceIn(0L, ReelLimits.FADE_OUT_MAX_MS),
        )
    }

    // ---- small helpers ------------------------------------------------------------------------

    /** A float as the shortest text that reads back as the same float, not as the long double it widens to. */
    private fun number(value: Float): JsonValue =
        JsonValue.JsonNumber(if (value.isFinite()) value.toString().toDouble() else 0.0)

    private fun floatOf(value: JsonValue, default: Float): Float =
        (value as? JsonValue.JsonNumber)?.value?.toFloat() ?: default

    private fun argbText(argb: Int): String = "#" + "%08X".format(argb)

    /** `#RRGGBB` (opaque) or `#AARRGGBB`; anything else is null. */
    private fun parseArgb(text: String): Int? {
        if (!text.startsWith("#")) return null
        val hex = text.substring(1)
        if (hex.length != 6 && hex.length != 8) return null
        val value = hex.toLongOrNull(16) ?: return null
        return if (hex.length == 6) (0xFF000000L or value).toInt() else value.toInt()
    }
}
