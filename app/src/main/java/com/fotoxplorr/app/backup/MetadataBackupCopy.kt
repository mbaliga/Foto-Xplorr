package com.fotoxplorr.app.backup

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * The sentences the "Tags and collections" restore shows, built from a [MetadataBackup.RestoreSummary]
 * so the wording is testable without a screen. The short, always-visible lines live in
 * `ui.HelpCopy` (and are held to its length ceilings); these are the dialog bodies.
 */
object MetadataBackupCopy {
    const val TITLE = "Restore tags and collections"

    const val LEGACY_WARNING =
        "This file is from an older version of Fotoz and does not say which photos it belongs to. " +
            "Restore it only on the phone it was saved on. On any other phone its tags would land on the wrong photos."

    const val MERGE_EXPLAINED = "Merge adds these to what you already have. Nothing is removed or overwritten."

    const val NOTHING_TO_RESTORE =
        "None of the photos in this file are on this phone, so there is nothing to restore."

    const val REPLACE_TITLE = "Replace everything?"

    const val REPLACE_BODY =
        "This erases the tags, collections, captions, favourites and sensitive marks on this phone and puts the " +
            "ones from the file in their place. It cannot be undone. Your photos are not touched."

    const val NOT_ENCRYPTED_AFTER_SAVE = "The file is not encrypted."

    const val NO_CATALOGUE =
        "Fotoz can't see your photos right now, so the file could not say which photo each tag belongs to. " +
            "Try again once your photos have loaded."

    fun savedOn(summary: MetadataBackup.RestoreSummary, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String? {
        val at = summary.exportedAtMillis ?: return null
        return "Saved on " + DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
            .format(Instant.ofEpochMilli(at).atZone(zone))
    }

    /** "3 collections, 12 tags and 40 captions", or null when the file holds none of the five kinds. */
    fun contents(summary: MetadataBackup.RestoreSummary): String? {
        val parts = buildList {
            if (summary.collections > 0) add(count(summary.collections, "collection"))
            if (summary.tags > 0) add(count(summary.tags, "tag"))
            if (summary.captions > 0) add(count(summary.captions, "caption"))
            if (summary.favorites > 0) add(count(summary.favorites, "favourite"))
            if (summary.sensitive > 0) add(count(summary.sensitive, "sensitive mark"))
        }
        return when (parts.size) {
            0 -> null
            1 -> parts[0]
            else -> parts.dropLast(1).joinToString(", ") + " and " + parts.last()
        }
    }

    /** What was matched, for a file that says which photos it belongs to; null for one that does not. */
    fun matching(summary: MetadataBackup.RestoreSummary): String? {
        if (!summary.hasIdentity || summary.photosInFile == 0) return null
        val found = "Found ${summary.photosFound} of ${count(summary.photosInFile, "photo")} on this phone."
        return if (summary.photosMissing > 0) {
            found + " The other ${summary.photosMissing} are not here, or could not be told apart, so they are skipped."
        } else {
            found
        }
    }

    fun unusable(summary: MetadataBackup.RestoreSummary): String? =
        if (summary.unusableEntries > 0) "${count(summary.unusableEntries, "entry", "entries")} in the file could not be read and will be skipped." else null

    /** The text a finished restore reports. */
    fun done(summary: MetadataBackup.RestoreSummary, mode: ImportMode): String {
        val what = contents(summary) ?: "nothing"
        val verb = if (mode == ImportMode.REPLACE) "Replaced with" else "Added"
        val skipped = if (summary.photosMissing > 0) " ${count(summary.photosMissing, "photo")} not on this phone, skipped." else ""
        return "$verb $what.$skipped"
    }

    /** What a finished save reports. */
    fun saved(coverage: MetadataBackup.ExportCoverage): String {
        val note = if (coverage.withoutIdentity > 0) {
            " ${count(coverage.withoutIdentity, "entry", "entries")} belong to photos that are no longer on this phone and will be skipped when restoring."
        } else {
            ""
        }
        return "Saved your tags and collections. $NOT_ENCRYPTED_AFTER_SAVE$note"
    }

    private fun count(n: Int, noun: String, plural: String = noun + "s"): String = "$n ${if (n == 1) noun else plural}"
}
