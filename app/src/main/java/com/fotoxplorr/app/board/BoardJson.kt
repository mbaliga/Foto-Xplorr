package com.fotoxplorr.app.board

import com.fotoxplorr.app.backup.MetadataBackup
import com.fotoxplorr.app.editor.CropRect
import com.fotoxplorr.app.editor.JsonValue
import com.fotoxplorr.app.editor.asArray
import com.fotoxplorr.app.editor.asBool
import com.fotoxplorr.app.editor.asFloat
import com.fotoxplorr.app.editor.asLong
import com.fotoxplorr.app.editor.asString
import com.fotoxplorr.app.editor.get
import com.fotoxplorr.app.editor.jsonArrayOf
import com.fotoxplorr.app.editor.jsonObjectOf
import com.fotoxplorr.app.editor.parseJson
import com.fotoxplorr.app.editor.stringify
import com.fotoxplorr.app.editor.toJson
import com.fotoxplorr.app.lift.BrushStroke
import com.fotoxplorr.app.lift.NormPoint
import com.fotoxplorr.app.lift.SelectionOp

/**
 * A board as text, so it can be kept across the process being killed, in a file, or in a backup.
 *
 * Reading is forgiving on purpose, because the text may be old, hand-edited, cut short or from a
 * newer build:
 *  - Keys it does not know are ignored; a newer schema is read as far as it can be and flagged
 *    ([Decoded.fromNewerVersion]) so a screen can say that saving again may drop something.
 *  - A layer it cannot read (no id or size, an unknown kind, no photo id) is skipped and counted
 *    ([Decoded.skippedLayers]); the rest of the board loads.
 *  - Numbers are clamped into [BoardLimits], so no file can ask for a huge layer or a thousand points.
 *  - A mask it does not understand becomes no mask and is counted ([Decoded.droppedMasks]).
 *  - A photo that is no longer in the library is NOT a read error: the layer keeps its reference and
 *    draws as a placeholder ([PhotoResolver] decides, not the codec).
 *  - Text that is not a board at all, or is longer than [MAX_CHARS], gives null.
 *
 * Colours are written as signed 32-bit ARGB numbers. A float is written as the double it equals, so
 * reading what was written gives back an equal board.
 */
object BoardJson {
    const val KIND = "fotoz-board"
    const val SCHEMA = 1
    const val MAX_CHARS = 4_000_000

    /**
     * The length up to which a board's text is safe to keep in the saved-state bundle (which has a
     * limit of about a megabyte for the whole screen). A board is a few hundred characters a layer;
     * many marks of a subject mask can pass this, and then the screen should keep the text in a
     * file of its own and save the file name.
     */
    const val SAVED_STATE_SAFE_CHARS = 200_000

    /** A board as read, with what was lost on the way. */
    class Decoded(
        val board: Board,
        val skippedLayers: Int,
        val droppedMasks: Int,
        val fromNewerVersion: Boolean,
    ) {
        /** True when nothing was skipped, dropped or from a newer build. */
        val isComplete: Boolean get() = skippedLayers == 0 && droppedMasks == 0 && !fromNewerVersion
    }

    fun encode(board: Board): String = encodeValue(board).stringify()

    fun encodeValue(board: Board): JsonValue = jsonObjectOf(
        "kind" to KIND.toJson(),
        "schema" to SCHEMA.toJson(),
        "canvas" to jsonObjectOf("w" to board.canvas.width.toJson(), "h" to board.canvas.height.toJson()),
        "background" to when (val b = board.background) {
            is BoardBackground.Colour -> jsonObjectOf("type" to "colour".toJson(), "argb" to b.argb.toJson())
            BoardBackground.Transparent -> jsonObjectOf("type" to "transparent".toJson())
        },
        "next" to board.nextLayerId.toJson(),
        "layers" to jsonArrayOf(board.layers.map(::encodeLayer)),
    )

    fun decode(text: String): Decoded? {
        if (text.length > MAX_CHARS) return null
        return decodeValue(parseJson(text) ?: return null)
    }

    fun decodeValue(root: JsonValue): Decoded? {
        if (root !is JsonValue.JsonObject || root["kind"].asString() != KIND) return null
        val schema = root["schema"].asLong(1L)
        val canvas = (root["canvas"] as? JsonValue.JsonObject)?.let {
            CanvasSize(sizeOf(it["w"], CanvasPreset.SQUARE.size.width), sizeOf(it["h"], CanvasPreset.SQUARE.size.height)).clamped()
        } ?: CanvasPreset.SQUARE.size
        val background = decodeBackground(root["background"])

        val tally = Tally()
        val rawLayers = root["layers"].asArray()
        val read = ArrayList<Layer>()
        for ((i, raw) in rawLayers.withIndex()) {
            if (i >= BoardLimits.MAX_LAYERS) {
                tally.skipped += rawLayers.size - i
                break
            }
            val layer = runCatching { decodeLayer(raw, canvas, tally) }.getOrNull()
            if (layer == null) tally.skipped++ else read += layer
        }

        // Ids are unique and below nextLayerId whatever the file said.
        val seen = HashSet<Long>()
        var top = read.maxOfOrNull { it.id } ?: 0L
        val layers = read.map { layer ->
            if (seen.add(layer.id)) layer else layer.copy(id = ++top).also { seen += it.id }
        }
        val next = maxOf(root["next"].asLong(1L), top + 1, 1L)
        return Decoded(
            board = Board(canvas, background, layers, next),
            skippedLayers = tally.skipped,
            droppedMasks = tally.droppedMasks,
            fromNewerVersion = schema > SCHEMA,
        )
    }

    private class Tally {
        var skipped = 0
        var droppedMasks = 0
    }

    // ---- write ----

    private fun encodeLayer(layer: Layer): JsonValue {
        val f = linkedMapOf<String, JsonValue>()
        f["id"] = layer.id.toJson()
        when (val c = layer.content) {
            is LayerContent.Photo -> {
                f["type"] = "photo".toJson()
                val photo = linkedMapOf<String, JsonValue>(
                    "id" to c.ref.mediaId.toJson(),
                    "w" to c.ref.widthPx.toJson(),
                    "h" to c.ref.heightPx.toJson(),
                )
                c.ref.identity?.let { photo["identity"] = MetadataBackup.identityJson(it) }
                f["photo"] = JsonValue.JsonObject(photo)
            }
            is LayerContent.Colour -> {
                f["type"] = "colour".toJson()
                f["argb"] = c.argb.toJson()
            }
            is LayerContent.Text -> {
                f["type"] = "text".toJson()
                f["text"] = c.text.toJson()
                f["argb"] = c.argb.toJson()
                f["size"] = c.sizeUnits.toJson()
                f["bold"] = c.bold.toJson()
                f["align"] = c.align.code.toJson()
            }
            is LayerContent.Shape -> {
                f["type"] = "shape".toJson()
                f["shape"] = c.kind.code.toJson()
                f["fill"] = c.fillArgb.toJson()
                f["stroke"] = c.strokeArgb.toJson()
                f["strokeW"] = c.strokeUnits.toJson()
            }
        }
        f["w"] = layer.width.toJson()
        f["h"] = layer.height.toJson()
        f["x"] = layer.transform.cx.toJson()
        f["y"] = layer.transform.cy.toJson()
        f["scale"] = layer.transform.scale.toJson()
        f["rot"] = layer.transform.rotationDeg.toJson()
        f["opacity"] = layer.opacity.toJson()
        f["blend"] = layer.blend.code.toJson()
        f["locked"] = layer.locked.toJson()
        if (!layer.mask.isNone) f["mask"] = encodeMask(layer.mask)
        if (!layer.crop.isFull) {
            f["crop"] = jsonArrayOf(listOf(layer.crop.left.toJson(), layer.crop.top.toJson(), layer.crop.right.toJson(), layer.crop.bottom.toJson()))
        }
        return JsonValue.JsonObject(f)
    }

    private fun encodeMask(mask: LayerMask): JsonValue {
        val f = linkedMapOf<String, JsonValue>()
        when (val s = mask.shape) {
            MaskShape.Full -> f["shape"] = "full".toJson()
            is MaskShape.RoundedRect -> {
                f["shape"] = "rounded".toJson()
                f["radius"] = s.radius.toJson()
            }
            MaskShape.Ellipse -> f["shape"] = "ellipse".toJson()
            is MaskShape.Polygon -> {
                f["shape"] = "polygon".toJson()
                f["points"] = flat(s.points)
            }
            is MaskShape.Subject -> {
                f["shape"] = "subject".toJson()
                f["ops"] = jsonArrayOf(s.ops.map(::encodeOp))
            }
        }
        if (mask.feather > 0f) f["feather"] = mask.feather.toJson()
        return JsonValue.JsonObject(f)
    }

    private fun encodeOp(op: SelectionOp): JsonValue = when (op) {
        is SelectionOp.Hint -> jsonObjectOf(
            "k" to "hint".toJson(), "on" to op.foreground.toJson(),
            "r" to op.stroke.radius.toJson(), "p" to flat(op.stroke.points),
        )
        is SelectionOp.Paint -> jsonObjectOf(
            "k" to "paint".toJson(), "on" to op.add.toJson(),
            "r" to op.stroke.radius.toJson(), "p" to flat(op.stroke.points),
        )
    }

    private fun flat(points: List<NormPoint>): JsonValue =
        jsonArrayOf(points.flatMap { listOf(it.x.toJson(), it.y.toJson()) })

    // ---- read ----

    private fun decodeBackground(value: JsonValue): BoardBackground = when (value["type"].asString()) {
        "transparent" -> BoardBackground.Transparent
        "colour" -> BoardBackground.Colour(argbOf(value["argb"], 0xFFFFFFFF.toInt()))
        else -> BoardBackground.Colour(0xFFFFFFFF.toInt())
    }

    private fun decodeLayer(raw: JsonValue, canvas: CanvasSize, tally: Tally): Layer? {
        if (raw !is JsonValue.JsonObject) return null
        val id = whole(raw["id"]) ?: return null
        if (id < 1L) return null
        val width = number(raw["w"]) ?: return null
        val height = number(raw["h"]) ?: return null

        val content: LayerContent = when (raw["type"].asString()) {
            "photo" -> {
                val photo = raw["photo"]
                val mediaId = whole(photo["id"]) ?: return null
                LayerContent.Photo(
                    PhotoRef(
                        mediaId = mediaId,
                        identity = MetadataBackup.identityFrom(photo["identity"]),
                        widthPx = whole(photo["w"])?.coerceIn(0L, 200_000L)?.toInt() ?: 0,
                        heightPx = whole(photo["h"])?.coerceIn(0L, 200_000L)?.toInt() ?: 0,
                    ),
                )
            }
            "colour" -> LayerContent.Colour(argbOf(raw["argb"], 0xFF000000.toInt()))
            "text" -> LayerContent.Text(
                text = raw["text"].asString().take(BoardLimits.MAX_TEXT_CHARS),
                argb = argbOf(raw["argb"], 0xFF000000.toInt()),
                sizeUnits = (number(raw["size"]) ?: 64f).coerceIn(4f, 2000f),
                bold = raw["bold"].asBool(),
                align = TextAlign.of(raw["align"].asString()),
            )
            "shape" -> LayerContent.Shape(
                kind = ShapeKind.of(raw["shape"].asString()),
                fillArgb = argbOf(raw["fill"], 0xFF000000.toInt()),
                strokeArgb = argbOf(raw["stroke"], 0xFF000000.toInt()),
                strokeUnits = (number(raw["strokeW"]) ?: 0f).coerceIn(0f, 500f),
            )
            else -> return null
        }

        val transform = Transform(
            cx = number(raw["x"]) ?: (canvas.width / 2f),
            cy = number(raw["y"]) ?: (canvas.height / 2f),
            scale = number(raw["scale"]) ?: 1f,
            rotationDeg = number(raw["rot"]) ?: 0f,
        ).clamped(canvas)

        return Layer(
            id = id,
            content = content,
            width = width.coerceIn(BoardLimits.MIN_BOX, BoardLimits.MAX_BOX),
            height = height.coerceIn(BoardLimits.MIN_BOX, BoardLimits.MAX_BOX),
            transform = transform,
            opacity = (number(raw["opacity"]) ?: 1f).clampUnit(),
            blend = LayerBlend.of(raw["blend"].asString()),
            locked = raw["locked"].asBool(),
            mask = decodeMask(raw["mask"], tally),
            crop = decodeCrop(raw["crop"]),
        )
    }

    private fun decodeCrop(value: JsonValue): CropRect {
        val v = value.asArray().map { number(it) ?: return CropRect.FULL }
        if (v.size != 4) return CropRect.FULL
        val l = v[0].clampUnit()
        val t = v[1].clampUnit()
        val r = v[2].clampUnit()
        val b = v[3].clampUnit()
        return if (r - l >= MIN_CROP && b - t >= MIN_CROP) CropRect(l, t, r, b) else CropRect.FULL
    }

    private const val MIN_CROP = 0.01f

    private fun decodeMask(value: JsonValue, tally: Tally): LayerMask {
        if (value !is JsonValue.JsonObject) return LayerMask.NONE
        val feather = (number(value["feather"]) ?: 0f).coerceIn(0f, BoardLimits.MAX_FEATHER)
        val shape: MaskShape = when (value["shape"].asString()) {
            "full" -> MaskShape.Full
            "rounded" -> MaskShape.RoundedRect((number(value["radius"]) ?: 0f).coerceIn(0f, 0.5f))
            "ellipse" -> MaskShape.Ellipse
            "polygon" -> {
                val pts = points(value["points"], BoardLimits.MAX_POLYGON_POINTS)
                if (pts.size < 3) {
                    tally.droppedMasks++
                    MaskShape.Full
                } else {
                    MaskShape.Polygon(pts)
                }
            }
            "subject" -> {
                val ops = value["ops"].asArray().take(BoardLimits.MAX_SELECTION_OPS).mapNotNull { decodeOp(it) }
                if (ops.size < value["ops"].asArray().size.coerceAtMost(BoardLimits.MAX_SELECTION_OPS)) tally.droppedMasks++
                MaskShape.Subject(ops)
            }
            else -> {
                tally.droppedMasks++
                MaskShape.Full
            }
        }
        return LayerMask(shape, feather)
    }

    private fun decodeOp(value: JsonValue): SelectionOp? {
        if (value !is JsonValue.JsonObject) return null
        val pts = points(value["p"], BoardLimits.MAX_STROKE_POINTS)
        if (pts.isEmpty()) return null
        val stroke = BrushStroke(pts, (number(value["r"]) ?: 0.02f).coerceIn(0.001f, 0.5f))
        return when (value["k"].asString()) {
            "hint" -> SelectionOp.Hint(value["on"].asBool(true), stroke)
            "paint" -> SelectionOp.Paint(value["on"].asBool(true), stroke)
            else -> null
        }
    }

    private fun points(value: JsonValue, limit: Int): List<NormPoint> {
        val nums = value.asArray()
        val out = ArrayList<NormPoint>()
        var i = 0
        while (i + 1 < nums.size && out.size < limit) {
            val x = number(nums[i]) ?: return emptyList()
            val y = number(nums[i + 1]) ?: return emptyList()
            out += NormPoint(x.clampUnit(), y.clampUnit())
            i += 2
        }
        return out
    }

    /** A finite number, or null for anything else (a string, null, a missing key). */
    private fun number(value: JsonValue): Float? {
        val n = (value as? JsonValue.JsonNumber)?.value ?: return null
        return if (n.isNaN() || n.isInfinite()) null else n.toFloat()
    }

    /** A whole number, or null for anything else. */
    private fun whole(value: JsonValue): Long? {
        val n = (value as? JsonValue.JsonNumber)?.value ?: return null
        if (n.isNaN() || n.isInfinite() || n != Math.floor(n) || n > 9.0e15 || n < -9.0e15) return null
        return n.toLong()
    }

    private fun sizeOf(value: JsonValue, fallback: Int): Int = whole(value)?.coerceIn(0L, 100_000L)?.toInt() ?: fallback

    private fun argbOf(value: JsonValue, fallback: Int): Int = whole(value)?.toInt() ?: fallback
}
