package com.fotoxplorr.app.board

/**
 * The words the mood board owns. One short sentence each (owner feedback #6). This file has the
 * names of things (shapes, blends, masks, canvases) and the placeholder; the screen's own sentences
 * are added by the screen.
 */
internal object BoardCopy {
    const val TITLE = "Mood board"
    const val BLURB = "Lay photos out on a canvas."

    /** Drawn on the preview where a photo is missing; read out by the layer list. */
    const val MISSING_PHOTO = "Photo missing"
    const val NOT_A_SUBJECT = "Marks needed"

    fun canvas(preset: CanvasPreset): String = when (preset) {
        CanvasPreset.SQUARE -> "Square"
        CanvasPreset.PORTRAIT_4_5 -> "Portrait 4:5"
        CanvasPreset.PORTRAIT_2_3 -> "Portrait 2:3"
        CanvasPreset.STORY_9_16 -> "Story 9:16"
        CanvasPreset.LANDSCAPE_3_2 -> "Landscape 3:2"
        CanvasPreset.LANDSCAPE_16_9 -> "Wide 16:9"
    }

    fun blend(blend: LayerBlend): String = when (blend) {
        LayerBlend.NORMAL -> "Normal"
        LayerBlend.MULTIPLY -> "Multiply"
        LayerBlend.SCREEN -> "Screen"
        LayerBlend.OVERLAY -> "Overlay"
    }

    fun mask(mask: LayerMask): String = when (mask.shape) {
        MaskShape.Full -> if (mask.feather > 0f) "Soft edge" else "No mask"
        is MaskShape.RoundedRect -> "Rounded corners"
        MaskShape.Ellipse -> "Oval"
        is MaskShape.Polygon -> "Custom shape"
        is MaskShape.Subject -> "Subject"
    }

    fun shape(kind: ShapeKind): String = when (kind) {
        ShapeKind.RECTANGLE -> "Rectangle"
        ShapeKind.ELLIPSE -> "Oval"
        ShapeKind.TRIANGLE -> "Triangle"
    }

    /** The spoken name of a layer, [number] counting from 1 at the back. */
    fun layerName(layer: Layer, number: Int): String = when (val c = layer.content) {
        is LayerContent.Photo -> "Photo $number"
        is LayerContent.Colour -> "Colour $number"
        is LayerContent.Text -> "Text $number, ${c.text.take(20)}"
        is LayerContent.Shape -> "${shape(c.kind)} $number"
    }
}
