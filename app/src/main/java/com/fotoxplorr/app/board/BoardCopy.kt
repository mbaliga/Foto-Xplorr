package com.fotoxplorr.app.board

import kotlin.math.roundToInt

/**
 * The words the mood board owns. One short sentence each (owner feedback #6). The names of things
 * (shapes, blends, masks, canvases, colours), the placeholder, and every sentence of the screen, the
 * saved-boards list and the export. A figure goes in through a function so the sentence stays whole.
 */
internal object BoardCopy {
    const val TITLE = "Mood board"
    const val BLURB = "Lay photos out on a canvas."

    /** Drawn on the preview where a photo is missing; read out by the layer list. */
    const val MISSING_PHOTO = "Photo missing"
    const val NOT_A_SUBJECT = "The whole photo shows until the subject is marked."

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

    // ---- the screen ----

    const val BACK = "Back"
    const val CANCEL = "Cancel"
    const val DONE = "Done"
    const val UNDO = "Undo"
    const val REDO = "Redo"
    const val EXPORT = "Export"
    const val RENAME = "Rename"
    const val NAME_LABEL = "Name"
    const val DEFAULT_NAME = "Untitled board"
    const val NOTHING_PICKED = "Pick a layer first."
    const val LOCKED_NOTE = "Unlock this layer to change it."
    const val SNAP = "Snap to guides"
    const val PREVIEW_PENDING = "Drawing the board."
    const val NEWER_VERSION = "This board is from a newer version, so saving may drop parts of it."
    const val LOCKED_PICK_NOTE = "Locked layers are picked in Layers."

    fun partsLost(skippedLayers: Int, droppedMasks: Int): String {
        val parts = buildList {
            if (skippedLayers > 0) add(if (skippedLayers == 1) "1 layer" else "$skippedLayers layers")
            if (droppedMasks > 0) add(if (droppedMasks == 1) "1 mask" else "$droppedMasks masks")
        }
        return "Could not read " + parts.joinToString(" and ") + "."
    }

    fun canvasDescription(layers: Int, picked: String?): String {
        val count = if (layers == 1) "1 layer" else "$layers layers"
        return if (picked == null) "Board, $count." else "Board, $count. $picked picked."
    }

    // The tool chips, left to right.
    const val TOOL_ADD = "Add"
    const val TOOL_LAYER = "Layer"
    const val TOOL_LOOK = "Look"
    const val TOOL_LAYERS = "Layers"
    const val TOOL_CANVAS = "Board"
    const val TOOL_COLOURS = "Colours"

    // ---- add ----

    const val ADD_PHOTO = "Photo"
    const val ADD_COLOUR = "Colour block"
    const val ADD_TEXT = "Text"
    const val ADD_SHAPE_HEADING = "Shape"
    const val COLOUR_FOR_NEW = "Colour for new things"
    const val TEXT_FIELD = "Your words"
    const val TEXT_STARTER = "Hello"
    const val ADD_THE_TEXT = "Add text"
    const val BOARD_FULL = "The board is full."
    const val NO_PHOTOS = "There are no photos to use yet."
    const val PICK_PHOTOS = "Pick photos to add."

    fun addPhotos(count: Int): String = if (count == 1) "Add 1 photo" else "Add $count photos"

    fun photosPicked(count: Int): String = if (count == 1) "1 photo picked" else "$count photos picked"

    // ---- the picked layer ----

    const val NUDGE_UP = "Move up"
    const val NUDGE_DOWN = "Move down"
    const val NUDGE_LEFT = "Move left"
    const val NUDGE_RIGHT = "Move right"
    const val CENTRE = "Centre it"
    const val BIGGER = "Bigger"
    const val SMALLER = "Smaller"
    const val TURN_LEFT_COARSE = "Turn left 15 degrees"
    const val TURN_RIGHT_COARSE = "Turn right 15 degrees"
    const val TURN_LEFT_FINE = "Turn left 1 degree"
    const val TURN_RIGHT_FINE = "Turn right 1 degree"
    const val FORWARD = "Forward"
    const val BACKWARD = "Backward"
    const val TO_FRONT = "To front"
    const val TO_BACK = "To back"
    const val LOCK = "Lock"
    const val DUPLICATE = "Duplicate"
    const val DELETE = "Delete"

    fun sizeNow(scale: Float): String = "Size ${(scale * 100f).roundToInt()}%"

    fun turnNow(degrees: Float): String = "Turn ${degrees.roundToInt()}°"

    fun opacityNow(opacity: Float): String = "Opacity ${(opacity * 100f).roundToInt()}%"

    // ---- text ----

    const val TEXT_BOLD = "Bold"
    const val ALIGN_START = "Left"
    const val ALIGN_CENTRE = "Middle"
    const val ALIGN_END = "Right"
    const val TEXT_BIGGER = "Bigger letters"
    const val TEXT_SMALLER = "Smaller letters"

    // ---- look: opacity, blend, mask ----

    const val OPACITY_LESS = "Less opaque"
    const val OPACITY_MORE = "More opaque"
    const val FEATHER_LESS = "Harder edge"
    const val FEATHER_MORE = "Softer edge"
    const val RADIUS_LESS = "Less rounded"
    const val RADIUS_MORE = "More rounded"
    const val BLEND_HEADING = "Blend"
    const val MASK_HEADING = "Mask"
    const val MASK_NONE = "No mask"
    const val MASK_ROUNDED = "Rounded corners"
    const val MASK_OVAL = "Oval"
    const val MASK_SHAPE = "Custom shape"
    const val MASK_SUBJECT = "Subject"
    const val DRAW_BY_TAPS = "Tap points"
    const val TAP_POINTS = "Tap the layer to place the corners."
    const val UNDO_POINT = "Undo point"
    const val PRESET_DIAMOND = "Diamond"
    const val PRESET_TRIANGLE = "Triangle"
    const val PRESET_HEXAGON = "Hexagon"
    const val PRESET_STAR = "Star"
    const val NEED_THREE_POINTS = "A shape needs three points."
    const val MARK_HINT = "Tap the subject, or switch to Not subject and tap what to leave out."
    const val MARK_SUBJECT = "Subject"
    const val MARK_NOT_SUBJECT = "Not subject"
    const val UNDO_MARK = "Undo mark"
    const val CLEAR_MARKS = "Clear marks"
    const val SUBJECT_SLOW = "Cutting out takes a moment."
    const val SUBJECT_PHOTOS_ONLY = "Subject works on photos."
    const val SUBJECT_EDGE_NOTE = "The cut-out is redrawn each time, so its edge may differ a little from the Lift tool."
    const val COLOUR_OF_LAYER = "Colour"

    fun featherNow(feather: Float): String = "Soft edge ${(feather * 100f).roundToInt()}%"

    fun roundingNow(radius: Float): String = "Corners ${(radius * 200f).roundToInt()}%"

    fun pointsNow(count: Int): String = if (count == 1) "1 point" else "$count points"

    fun marksNow(count: Int): String = if (count == 1) "1 mark" else "$count marks"

    fun maskOption(kind: MaskKind): String = when (kind) {
        MaskKind.NONE -> MASK_NONE
        MaskKind.ROUNDED -> MASK_ROUNDED
        MaskKind.OVAL -> MASK_OVAL
        MaskKind.SHAPE -> MASK_SHAPE
        MaskKind.SUBJECT -> MASK_SUBJECT
    }

    // ---- layers ----

    const val NO_LAYERS = "Nothing on the board yet."

    const val STATUS_PICKED = "Picked"
    const val STATUS_LOCKED = "Locked"

    fun lockLayer(name: String) = "Lock $name"

    fun unlockLayer(name: String) = "Unlock $name"

    fun forwardLayer(name: String) = "Move $name forward"

    fun backwardLayer(name: String) = "Move $name backward"

    // ---- canvas and colours ----

    const val CANVAS_SHAPE = "Shape of the board"
    const val BACKGROUND_HEADING = "Background"
    const val TRANSPARENT = "Transparent"
    const val TRANSPARENT_NOTE = "A see-through board saves as PNG."
    const val FIND_COLOURS = "Find colours in my photos"
    const val FINDING_COLOURS = "Looking at your photos."
    const val NO_PHOTO_COLOURS = "Add a photo first."
    const val COLOURS_OF_PHOTOS = "Colours of your photos"
    const val USE_AS_BACKGROUND = "Use as background"
    const val USE_AS_NEW_COLOUR = "Use for new things"
    const val ADD_AS_BLOCK = "Add as colour block"
    const val PICK_A_COLOUR = "Pick a colour."

    const val COLOUR_WHITE = "White"
    const val COLOUR_LIGHT_GREY = "Light grey"
    const val COLOUR_GREY = "Grey"
    const val COLOUR_BLACK = "Black"
    const val COLOUR_RED = "Red"
    const val COLOUR_ORANGE = "Orange"
    const val COLOUR_YELLOW = "Yellow"
    const val COLOUR_GREEN = "Green"
    const val COLOUR_TEAL = "Teal"
    const val COLOUR_BLUE = "Blue"
    const val COLOUR_PURPLE = "Purple"
    const val COLOUR_PINK = "Pink"
    const val COLOUR_BROWN = "Brown"

    fun swatch(hex: String, share: Float?): String =
        if (share == null) hex else "$hex, ${(share * 100f).roundToInt()}% of the photos"

    // ---- the list of saved boards ----

    const val NEW_BOARD = "New board"
    const val YOUR_BOARDS = "Your boards"
    const val NO_BOARDS = "No boards yet."
    const val SAVES_BY_ITSELF = "Boards save as you work."
    const val DAMAGED_BOARD = "This board can't be read."
    const val LIST_FULL = "Delete a board to make room for a new one."
    const val COULD_NOT_SAVE = "Could not save the board."
    const val DELETE_ASK = "Delete this board?"
    const val DELETE_KEEP = "Keep"
    const val OPENING = "Opening."
    const val COULD_NOT_OPEN = "Could not open that board."

    fun boardSummary(layers: Int, canvas: String): String {
        val count = if (layers == 1) "1 layer" else "$layers layers"
        return "$count, $canvas"
    }

    fun deleteBoard(name: String): String = "Delete $name"

    fun openBoard(name: String): String = "Open $name"

    // ---- export ----

    const val JOB_TITLE = "Saving a mood board"
    const val FORMAT_HEADING = "File type"
    const val SIZE_HEADING = "Size"
    const val FORMAT_PNG = "PNG"
    const val FORMAT_JPEG = "JPEG"
    const val PNG_NEEDED = "This board is see-through, so it saves as PNG."
    const val SAVE_PICTURE = "Save picture"
    const val SAVING = "Saving the picture."
    const val LEAVING_CANCELS = "Leaving this screen stops the save."
    const val SAVED = "Saved in Pictures, in Fotoz, in Boards."
    const val NOT_LISTED_YET = "It will show in the gallery in a moment."
    const val OPEN_IT = "Open"
    const val CANCELLED = "Cancelled."
    const val NEW_FILE_NOTE = "It saves as a new file and never changes your photos."
    const val MISSING_PHOTOS_NOTE = "Some photos can't be used because they may be gone or protected."
    const val SHOW_MISSING = "Pick the first one"

    fun problem(problem: BoardExportProblem): String = when (problem) {
        BoardExportProblem.EMPTY -> "Add something to the board first."
        BoardExportProblem.PHOTOS_UNAVAILABLE -> MISSING_PHOTOS_NOTE
        BoardExportProblem.PROTECTED -> "A photo is protected now, so nothing was saved."
        BoardExportProblem.TOO_BIG -> "Not enough memory for that size. Try a smaller one."
        BoardExportProblem.NO_SPACE -> "The phone is nearly full."
        BoardExportProblem.SAVE_FAILED -> "Could not save the picture."
    }

    fun sizeNote(width: Int, height: Int, steppedDown: Boolean): String =
        if (steppedDown) "Saved smaller than asked, $width by $height, to fit in memory." else "$width by $height pixels."

    fun aboutSize(width: Int, height: Int): String = "About $width by $height pixels."
}
