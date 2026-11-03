package com.fotoxplorr.app.recognition

import java.text.NumberFormat
import java.util.Locale

/** Every user-facing string of the People destination: one short sentence (or label) each. */
object PeopleCopy {
    const val TITLE = "People"

    fun unnamed(id: Long): String = "Person $id"

    fun photos(count: Int): String = if (count == 1) "1 photo" else "$count photos"

    private fun thousands(value: Int): String = NumberFormat.getIntegerInstance(Locale.getDefault()).format(value)

    /** "Recognising faces: 749 of 20,743" -- the pass's own counters, nothing smoothed. */
    fun working(done: Int, total: Int): String =
        if (total > 0) "Recognising faces: ${thousands(done)} of ${thousands(total)}" else "Recognising faces"

    fun stopped(reason: String): String = "Face recognition stopped: $reason"

    const val UP_TO_DATE = "Every photo has been checked for faces."

    const val EMPTY_NOTHING_YET = "No people found yet."
    const val EMPTY_WHAT_TO_DO = "People appear here once a photo has a clear face in it, twice or more."
    const val EMPTY_PRIVATE = "Faces are matched on this device and never leave it."
    const val EMPTY_ALL_HIDDEN = "Everyone found so far is hidden."

    fun showHidden(count: Int) = "Show hidden ($count)"
    const val HIDE_HIDDEN = "Hide the hidden"

    const val ACTION_RENAME = "Rename"
    const val ACTION_HIDE = "Hide"
    const val ACTION_UNHIDE = "Show again"
    const val ACTION_MERGE = "Merge into another"
    const val ACTION_NOT_THIS_PERSON = "Not this person"

    const val NAME_LABEL = "Name"
    const val NAME_PLACEHOLDER = "Who is this?"
    const val SAVE = "Save"
    const val CANCEL = "Cancel"
    const val MERGE_TITLE = "Merge with which person?"
    const val BACK_TO_PEOPLE = "Back to people"
    const val MANAGE = "Manage this person"
    const val FACE_PENDING = "Face picture not ready yet"

    fun filterChip(name: String) = "$name ×"
    fun clearFilter(name: String) = "Remove filter $name, show all people"
}
