package com.fotoxplorr.app.board

import com.fotoxplorr.app.backup.MediaIdentity
import com.fotoxplorr.app.editor.CropRect
import com.fotoxplorr.app.lift.BrushStroke
import com.fotoxplorr.app.lift.NormPoint
import com.fotoxplorr.app.lift.SelectionOp

/** Small builders so a test says what matters and nothing else. */
internal object BoardTestKit {
    const val WHITE = 0xFFFFFFFF.toInt()
    const val RED = 0xFFFF0000.toInt()
    const val GREEN = 0xFF00FF00.toInt()
    const val BLUE = 0xFF0000FF.toInt()

    fun layer(
        id: Long,
        cx: Float,
        cy: Float,
        w: Float = 100f,
        h: Float = 100f,
        rot: Float = 0f,
        scale: Float = 1f,
        content: LayerContent = LayerContent.Colour(RED),
        locked: Boolean = false,
        mask: LayerMask = LayerMask.NONE,
        opacity: Float = 1f,
        blend: LayerBlend = LayerBlend.NORMAL,
        crop: CropRect = CropRect.FULL,
    ) = Layer(id, content, w, h, Transform(cx, cy, scale, rot), opacity, blend, locked, mask, crop)

    fun board(vararg layers: Layer, canvas: CanvasSize = CanvasSize(1080, 1080)) =
        Board(canvas = canvas, layers = layers.toList(), nextLayerId = (layers.maxOfOrNull { it.id } ?: 0L) + 1)

    fun photo(mediaId: Long, w: Int = 4000, h: Int = 3000, identity: MediaIdentity? = identity("IMG_$mediaId.jpg", mediaId * 1000)) =
        LayerContent.Photo(PhotoRef(mediaId, identity, w, h))

    fun identity(name: String, size: Long, date: Long = 1_700_000_000_000L, path: String? = "Pictures/Camera/") =
        MediaIdentity(name, size, date, path)

    fun stroke(x: Float, y: Float, r: Float = 0.05f) = BrushStroke(listOf(NormPoint(x, y)), r)

    fun hint(fg: Boolean, x: Float, y: Float, r: Float = 0.05f) = SelectionOp.Hint(fg, stroke(x, y, r))
}
