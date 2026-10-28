package com.fotoxplorr.app.reel

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * The words the reel maker's pure part owns. One short sentence each (owner feedback #6). The screens
 * that come later add their own to this object, so a person reads one voice.
 */
internal object ReelCopy {
    const val TITLE = "Make a reel"
    const val BLURB = "Join photos and clips into a short video."

    // The draft.
    const val NOTHING_TO_USE = "There is nothing here to make a reel from."

    fun picked(picked: Int, considered: Int): String = "Picked $picked of $considered."

    // Making the video file. The job returns these, never an exception's text.
    const val JOB_TITLE = "Making a reel"
    const val SAVED = "Reel saved."
    const val SAVED_SMALLER = "Saved smaller, to fit this phone."
    const val SAVED_CUTS = "Saved with plain cuts, to fit this phone."
    const val SAVED_SILENT = "Reel saved without sound."
    const val PROTECTED = "A protected photo or clip cannot be used."
    const val NO_ENCODER = "This phone cannot make a video."
    const val SOURCE_UNREADABLE = "A photo, clip or sound file could not be read."
    const val OUT_OF_MEMORY = "There is not enough memory to make this reel."
    const val NO_SPACE = "There is not enough space on the phone."
    const val SAVE_FAILED = "The reel could not be saved."
    const val FAILED = "The reel could not be made."
    const val PLACE_LEFT_IN = "The reel still carried a place, so it was not saved."
    const val CANCELLED = "Cancelled."

    fun noLongerAvailable(item: Int): String = "Item $item is no longer available."

    fun cannotRead(item: Int): String = "Item $item could not be read."

    // The soundtrack. The file is only ever laid into the video; nothing is played in the app.
    const val SOUND_UNREADABLE = "This phone cannot read that sound file."
    const val SOUND_STARTS_LATE = "The sound file ends before it should start."
    const val SOUND_FAILED = "The sound could not be added."
    const val SOUND_ASK = "Make the reel without sound?"
    const val SOUND_PICK = "Choose a sound file"
    const val SOUND_REMOVE = "Remove the sound file"
    const val SOUND_NONE = "No sound file chosen."
    const val CLIP_SOUND = "Keep the sound of clips"

    fun soundFile(name: String): String = "Sound: $name"

    // Kinds of item, for a screen reader and a label.
    const val ITEM_STILL = "Photo"
    const val ITEM_CLIP = "Clip"
    const val ITEM_TITLE = "Title card"

    fun aspect(aspect: ReelAspect): String = when (aspect) {
        ReelAspect.WIDE -> "Wide"
        ReelAspect.TALL -> "Tall"
        ReelAspect.SQUARE -> "Square"
        ReelAspect.ORIGINAL -> "Original"
    }

    fun transition(transition: ReelTransition): String = when (transition) {
        ReelTransition.CUT -> "Cut"
        ReelTransition.CROSS_FADE -> "Fade"
        ReelTransition.SLIDE -> "Slide"
    }

    fun motion(motion: ReelMotion): String = when (motion) {
        ReelMotion.None -> "Still"
        is ReelMotion.ZoomIn -> "Zoom in"
        is ReelMotion.ZoomOut -> "Zoom out"
        is ReelMotion.Pan -> "Pan"
    }

    // ---- the screen: the list of saved reels ----
    const val BACK = "Back"
    const val DEFAULT_NAME = "My reel"
    const val SAVES_BY_ITSELF = "Your reel saves itself as you work."
    const val START_DRAFT = "Draft one for me"
    const val START_BLANK = "Start with nothing"
    const val YOUR_REELS = "Your reels"
    const val NO_REELS = "No saved reels yet."
    const val OPENING = "Opening."
    const val COULD_NOT_OPEN = "That reel could not be opened."
    const val LIST_FULL = "Delete a saved reel to make room."
    const val COULD_NOT_SAVE = "The reel could not be saved just now."
    const val DAMAGED_REEL = "Unreadable reel"
    const val DELETE_ASK = "Delete this reel?"
    const val DELETE = "Delete"
    const val DELETE_KEEP = "Keep it"
    const val NEWER_VERSION = "A newer version made this reel, so some of it may be missing."

    fun deleteReel(name: String): String = "Delete ${name.ifBlank { DEFAULT_NAME }}"

    fun reelSummary(items: Int, durationMs: Long): String =
        (if (items == 1) "1 item" else "$items items") + ", " + clock(durationMs)

    fun partsLost(items: Int): String =
        if (items == 1) "1 item could not be read and was left out." else "$items items could not be read and were left out."

    // ---- the screen: the draft and the picker ----
    const val DRAFT_TITLE = "Draft a reel"
    const val DRAFT_BLURB = "Choose when and how long, then change it all after."
    const val WHEN = "When"
    const val ALL_DATES = "All dates"
    const val LENGTH = "Length"
    const val SHAPE = "Shape"
    const val MAKE_DRAFT = "Make the draft"
    const val DRAFTING = "Drafting."
    const val PICK_BY_HAND = "Choose by hand"
    const val DRAFT_FAILED = "The draft could not be made."
    const val ADD_ITEMS = "Photos and clips"
    const val PICK_ITEMS = "Tap what to add, then press Add."
    const val NO_ITEMS_HERE = "There are no photos or clips to add."
    const val NONE_CHOSEN = "None chosen."

    fun lengthChoice(ms: Long): String = when {
        ms < 60_000L -> "${ms / 1000} seconds"
        ms == 60_000L -> "1 minute"
        else -> "${ms / 60_000L} minutes"
    }

    fun month(year: Int, month: Int, locale: Locale): String =
        DateTimeFormatter.ofPattern("LLL yyyy", locale).format(java.time.YearMonth.of(year, month))

    fun monthDescription(year: Int, month: Int, count: Int, locale: Locale): String =
        DateTimeFormatter.ofPattern("LLLL yyyy", locale).format(java.time.YearMonth.of(year, month)) + ", " + (if (count == 1) "1 item" else "$count items")

    fun itemsChosen(count: Int): String = if (count == 0) NONE_CHOSEN else "$count chosen."

    fun addItems(count: Int): String = if (count == 0) "Add" else "Add $count"

    fun itemsAdded(added: Int, skipped: Int): String =
        if (skipped == 0) "Added $added." else "Added $added; $skipped too short to use."

    // ---- the screen: the editor ----
    const val RENAME = "Rename"
    const val TOOL_ITEM = "Item"
    const val TOOL_ADD = "Add"
    const val TOOL_REEL = "Reel"
    const val TOOL_SOUND = "Sound"
    const val TOOL_MAKE = "Make"
    const val PLAY = "Play"
    const val PAUSE = "Pause"
    const val PREVIEW = "Preview of the reel"
    const val PREVIEW_NOTE = "The preview shows pictures only, and a clip shows one frame."
    const val PREVIOUS_ITEM = "Previous item"
    const val NEXT_ITEM = "Next item"
    const val MOVE_EARLIER = "Move earlier"
    const val MOVE_LATER = "Move later"
    const val DUPLICATE = "Copy this item"
    const val REMOVE = "Remove this item"
    const val EMPTY_REEL = "Nothing here yet."
    const val PICK_AN_ITEM = "Tap an item to change it."
    const val NOT_IN_LIBRARY = "Not in the library"
    const val NEEDS_ATTENTION = "needs attention"
    const val UNNAMED_SOUND = "a chosen file"

    fun itemPosition(number: Int, total: Int): String = "Item $number of $total"

    fun itemLabel(kind: String, number: Int, durationMs: Long): String = "$kind $number, ${seconds(durationMs)}"

    fun missingItems(count: Int): String =
        if (count == 1) "1 item is no longer in the library." else "$count items are no longer in the library."

    fun clock(ms: Long): String {
        val total = (ms.coerceAtLeast(0L) + 500L) / 1000L
        return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
    }

    fun clockOf(at: Long, total: Long): String = "${clock(at)} of ${clock(total)}"

    /** Whole seconds, or tenths when it is not whole: "3 s", "3.5 s". */
    fun seconds(ms: Long): String {
        val tenths = (ms.coerceAtLeast(0L) + 50L) / 100L
        return if (tenths % 10L == 0L) "${tenths / 10L} s" else "${tenths / 10L}.${tenths % 10L} s"
    }

    // The item panel.
    const val TIME_ON_SCREEN = "Time on screen"
    const val SHORTER = "Shorter"
    const val LONGER = "Longer"
    const val MOVE = "Move"
    const val MOVE_STAYS_STILL = "In the original shape, photos stay still."
    const val HAND_OVER = "Into the next item"
    const val LAST_ITEM = "The last item has no next item."
    const val CLIP_PART = "The part of the clip"
    const val START_EARLIER = "Start earlier"
    const val START_LATER = "Start later"
    const val END_EARLIER = "End earlier"
    const val END_LATER = "End later"
    const val TITLE_WORDS = "Words on the card"
    const val TITLE_COLOUR = "Card colour"
    const val NEW_CARD_WORDS = "Title"

    fun clipStarts(ms: Long): String = "Starts at ${seconds(ms)}"

    fun clipEnds(ms: Long): String = "Ends at ${seconds(ms)}"

    fun lastsFor(ms: Long): String = "Lasts ${seconds(ms)}"

    fun motionChoice(choice: MotionChoice): String = when (choice) {
        MotionChoice.STILL -> "Still"
        MotionChoice.ZOOM_IN -> "Zoom in"
        MotionChoice.ZOOM_OUT -> "Zoom out"
        MotionChoice.PAN_LEFT -> "Pan left"
        MotionChoice.PAN_RIGHT -> "Pan right"
        MotionChoice.PAN_UP -> "Pan up"
        MotionChoice.PAN_DOWN -> "Pan down"
    }

    // The add and reel panels.
    const val ADD_CARD = "Add a title card"
    const val REEL_NAME = "Name of the reel"
    const val EVERY_HAND_OVER = "Every hand-over"
    const val LENGTH_NOW = "Length"

    fun reelLength(ms: Long): String = "Length ${clock(ms)}"

    // The sound panel.
    const val MUSIC_NOTE = "Music stays in your file; Fotoz does not play it."
    const val FADE_OUT = "Fade out at the end"
    const val MUSIC_STARTS = "Music starts at"
    const val SOUND_MAY_STOP = "This sound file may need picking again after a restart."
    const val LATER_IN_SONG = "Start the music later"
    const val EARLIER_IN_SONG = "Start the music earlier"
    const val SHORTER_FADE = "Shorter fade"
    const val LONGER_FADE = "Longer fade"

    // The make panel.
    const val SIZE = "Size"
    const val PLAIN_CUTS = "Plain cuts"
    const val MAKE = "Make the video"
    const val MAKING = "Making the video."
    const val LEAVING_CANCELS = "Leaving stops the video being made."
    const val CANCEL = "Cancel"
    const val WATCH_IT = "Watch it"
    const val NO_PLAYER = "No app here can play it."
    const val SOUND_ASK_YES = "Make it without sound"
    const val SOUND_ASK_NO = "Not now"
    const val CANNOT_MAKE_HERE = "Making a video is not available here."

    fun sizeChoice(shortSide: Int): String = "${shortSide}p"

    fun madeNote(width: Int, height: Int, durationMs: Long): String = "$width by $height, ${clock(durationMs)}"

    /** What is wrong with the plan, in one sentence. Item numbers count from 1, as the strip shows them. */
    fun issue(issue: ReelIssue): String {
        val n = (issue.itemIndex ?: -1) + 1
        return when (issue.kind) {
            ReelIssueKind.NO_ITEMS -> "Add a photo or a clip first."
            ReelIssueKind.TOO_MANY_ITEMS -> "This reel has too many items."
            ReelIssueKind.TOO_LONG -> "This reel is too long."
            ReelIssueKind.DUPLICATE_ID -> "Item $n repeats another item."
            ReelIssueKind.ITEM_TOO_SHORT -> "Item $n is too short."
            ReelIssueKind.ITEM_TOO_LONG -> "Item $n is too long."
            ReelIssueKind.CLIP_RANGE -> "Clip $n ends before it starts."
            ReelIssueKind.CLIP_PAST_END -> "Clip $n runs past the end of its video."
            ReelIssueKind.MOTION_RECT -> "The move on item $n is outside its picture."
            ReelIssueKind.MOTION_FOCUS -> "The zoom on item $n is outside its picture."
            ReelIssueKind.SOUNDTRACK -> "The sound file's settings are not right."
            ReelIssueKind.TITLE_EMPTY -> "Title card $n has no words."
        }
    }

    /**
     * The words on the card the draft puts first: the date or dates the pictures were taken on, and
     * the place on a second line when one is given. Dates are written in [locale]'s own long form,
     * which is as far as this goes without the platform's skeleton formatter.
     */
    fun titleText(startMillis: Long, endMillis: Long, place: String?, zone: ZoneId, locale: Locale): String {
        val first = LocalDate.ofInstant(Instant.ofEpochMilli(startMillis), zone)
        val last = LocalDate.ofInstant(Instant.ofEpochMilli(maxOf(startMillis, endMillis)), zone)
        val long = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale)
        val dates = when {
            first == last -> long.format(first)
            first.year == last.year && first.month == last.month ->
                DateTimeFormatter.ofPattern("LLLL yyyy", locale).format(first)
            else -> long.format(first) + " – " + long.format(last)
        }
        val where = place?.trim().orEmpty()
        return if (where.isEmpty()) dates else dates + "\n" + where
    }
}
