package com.fotoxplorr.app.editor

/**
 * What the editor's Save does.
 *
 * The owner asked to be asked each time, with the choice remembered as a changeable default
 * (2026-08-18). [ASK] is therefore the shipped default, and it is also the only default that
 * cannot destroy a photograph before the user has understood the control — an editor whose Save
 * silently overwrites is an editor that will eventually eat someone's only copy of an image.
 *
 * A separate type rather than a boolean because there are genuinely three states: "copy",
 * "overwrite", and "I have not decided". A boolean would have to encode the third as one of the
 * other two, and whichever it picked would be wrong for somebody.
 */
enum class EditorSaveMode(val label: String, val description: String) {
    /** Show the choice at save time. The default. */
    ASK(
        "Ask me",
        "Choose each time you save.",
    ),

    /** Write a new file beside the original, which is never touched. */
    COPY(
        "Save a copy",
        "A new photo beside the original. Your original is never touched.",
    ),

    /**
     * Replace the original.
     *
     * Needs a per-file write grant from Android for photos this app did not create, so it can be
     * refused by the system even when it is the chosen mode — the editor falls back to a copy and
     * says so rather than failing the save.
     */
    OVERWRITE(
        "Replace the original",
        "The edit takes the place of the photo. The original cannot be recovered.",
    ),
}
