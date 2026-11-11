package com.fotoxplorr.app.editor

/**
 * The words the hub's screen adds to [HubCopy] (which owns the tool and group names and a few
 * shared buttons). One short sentence each (owner feedback #6). Names no library and no format.
 */
internal object HubRingCopy {
    /** Ring labels. The full group names (some are three words) stay on the group's page and in speech. */
    const val SHORT_LIGHT = "Light"
    const val SHORT_COLOUR = "Colour"
    const val SHORT_DETAIL = "Detail"
    const val SHORT_CROP = "Crop"
    const val SHORT_EFFECTS = "Effects"

    /** Says what the two destructive actions do, above their button. One sentence each. */
    const val RESET_NOTE = "Clears every edit on this photo."
    const val REVERT_NOTE = "Clears every edit and the saved draft."

    fun shortGroupTitle(group: ToolGroup): String = when (group) {
        ToolGroup.LIGHT -> SHORT_LIGHT
        ToolGroup.COLOUR -> SHORT_COLOUR
        ToolGroup.DETAIL -> SHORT_DETAIL
        ToolGroup.CROP_STRAIGHTEN -> SHORT_CROP
        ToolGroup.EFFECTS -> SHORT_EFFECTS
    }

    /** The "More" button's spoken label: how many tools it holds (or that it hides them again). */
    fun moreDescription(deeperCount: Int, shown: Boolean): String = when {
        shown -> HubCopy.FEWER
        deeperCount == 1 -> "$deeperCount more tool"
        else -> "$deeperCount more tools"
    }

    /** "More" with its count, as drawn. */
    fun moreLabel(deeperCount: Int, shown: Boolean): String =
        if (shown) HubCopy.FEWER else "${HubCopy.MORE} ($deeperCount)"
}
