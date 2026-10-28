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
