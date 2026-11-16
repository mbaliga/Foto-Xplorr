package com.fotoxplorr.app.sources.ui

import com.fotoxplorr.app.sources.runtime.AddResult
import com.fotoxplorr.app.sources.saf.SafCopy
import com.fotoxplorr.core.index.sources.SourceState

/** The words the Sources screen owns. One short sentence each; a state is a word, never only a colour. */
object SourcesCopy {
    const val ROW_CAPTION = "Show photos from folders, SD cards and drives."
    const val TITLE = "Folders and drives"
    const val INTRO = "Photos in these folders show in your gallery, and Fotoz only reads them."
    const val BACK = "Back"

    const val EMPTY_TITLE = "No folders added yet."
    const val EMPTY_BODY = "Add a folder on this phone, an SD card or a USB drive to see its photos in your gallery."
    const val ADD = "Add a folder"

    const val STATE_ONLINE = "Online"
    const val STATE_OFFLINE = "Offline"
    const val STATE_REVOKED = "Revoked"
    const val STATE_ERROR = "Error"
    const val STATE_PREFIX = "State"

    const val RESCAN = "Rescan"
    const val STOP = "Stop"
    const val REMOVE = "Remove"
    const val INCLUDE = "Show in gallery"
    const val INCLUDE_HELP = "Turn off to hide its photos without forgetting the folder."
    const val ON_CONNECT = "Read again when it is there"
    const val ON_CONNECT_HELP = "Follows your background rules, so it can wait for the charger."

    const val NEVER_READ = "Not read yet."
    const val READING = "Reading folders."
    const val SIZING = "Finding sizes."
    const val SIZES_PENDING = "Some sizes are still unknown, so a few tiles look square."

    const val REMOVE_TITLE = "Remove this folder?"
    const val REMOVE_BODY = "Fotoz forgets this folder and its saved previews, and never touches your files."
    const val REMOVE_CONFIRM = "Remove"
    const val CANCEL = "Cancel"
    const val DISMISS = "Dismiss"

    const val ADDED = "Folder added, and Fotoz is reading it now."
    const val ADD_FAILED = "That folder could not be added."

    fun files(n: Int): String = if (n == 1) "1 file" else "$n files"

    fun found(n: Long): String = if (n == 1L) "1 file found so far." else "$n files found so far."

    fun unreadable(n: Int): String = if (n == 1) "1 file could not be read." else "$n files could not be read."

    fun lastRead(label: String): String = "Last read $label."

    fun stateLabel(state: SourceState): String = when (state) {
        SourceState.ONLINE -> STATE_ONLINE
        SourceState.OFFLINE -> STATE_OFFLINE
        SourceState.REVOKED -> STATE_REVOKED
        SourceState.ERROR -> STATE_ERROR
    }

    /** What the state means, in one sentence; null for a source that is simply fine. */
    fun stateNote(state: SourceState): String? = when (state) {
        SourceState.ONLINE -> null
        SourceState.OFFLINE -> SafCopy.UNREACHABLE
        SourceState.REVOKED -> SafCopy.REVOKED
        SourceState.ERROR -> SafCopy.ERROR
    }

    fun forResult(result: AddResult): String? = when (result) {
        is AddResult.Added -> ADDED
        is AddResult.Duplicate -> "${result.existingName} is already added."
        is AddResult.InsideExisting -> "That folder is already part of ${result.existingName}."
        is AddResult.ContainsExisting -> "That folder holds ${result.existingName}, so remove that one first."
        is AddResult.GrantRefused -> result.reason
        AddResult.Failed -> ADD_FAILED
    }

    fun removeA11y(name: String) = "$REMOVE $name"
    fun rescanA11y(name: String) = "$RESCAN $name"
    fun stopA11y(name: String) = "$STOP reading $name"
}
