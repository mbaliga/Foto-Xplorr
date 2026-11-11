package com.fotoxplorr.app.editor

/**
 * Every word the editor's tool hub shows. One short sentence each (owner feedback #6). Tool titles
 * are the labels the editor's sliders already carry today, so a person who knows "Highlights" finds
 * "Highlights". Names no library and no file format.
 */
internal object HubCopy {
    const val HUB_LABEL = "Editing tools"
    const val CENTRE_LABEL = "Used most"
    const val MORE = "More"
    const val FEWER = "Fewer"
    const val BACK = "Back"

    const val GROUP_LIGHT = "Light"
    const val GROUP_COLOUR = "Colour"
    const val GROUP_DETAIL = "Detail"
    const val GROUP_CROP_STRAIGHTEN = "Crop and straighten"
    const val GROUP_EFFECTS = "Effects and heal"

    const val TOOL_EXPOSURE = "Exposure"
    const val TOOL_CONTRAST = "Contrast"
    const val TOOL_HIGHLIGHTS = "Highlights"
    const val TOOL_SHADOWS = "Shadows"
    const val TOOL_WHITES = "Whites"
    const val TOOL_BLACKS = "Blacks"
    const val TOOL_CURVES = "Curves"
    const val TOOL_SATURATION = "Saturation"
    const val TOOL_TEMPERATURE = "Temperature"
    const val TOOL_TINT = "Tint"
    const val TOOL_VIBRANCE = "Vibrance"
    const val TOOL_HSL = "HSL"
    const val TOOL_SHARPEN = "Sharpen"
    const val TOOL_CLARITY = "Clarity"
    const val TOOL_DENOISE = "Denoise"
    const val TOOL_CROP = "Crop"
    const val TOOL_STRAIGHTEN = "Straighten"
    const val TOOL_ROTATE = "Rotate 90°"
    const val TOOL_FLIP = "Flip"
    const val TOOL_RESET = "Reset all"
    const val TOOL_REVERT = "Revert to original"
    const val TOOL_PRESETS = "Presets"
    const val TOOL_VIGNETTE = "Vignette"
    const val TOOL_HEAL = "Heal"

    fun groupTitle(group: ToolGroup): String = when (group) {
        ToolGroup.LIGHT -> GROUP_LIGHT
        ToolGroup.COLOUR -> GROUP_COLOUR
        ToolGroup.DETAIL -> GROUP_DETAIL
        ToolGroup.CROP_STRAIGHTEN -> GROUP_CROP_STRAIGHTEN
        ToolGroup.EFFECTS -> GROUP_EFFECTS
    }

    /** A group button's spoken label: the title and how many tools sit one tap deeper. */
    fun groupDescription(group: ToolGroup, deeperCount: Int): String =
        if (deeperCount == 0) groupTitle(group) else "${groupTitle(group)}, $deeperCount more inside"
}
