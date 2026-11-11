package com.fotoxplorr.app.backup

import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sentences the restore dialog and the result notices are built from. */
class MetadataBackupCopyTest {
    private fun summary(
        hasIdentity: Boolean = true,
        exportedAtMillis: Long? = null,
        photosInFile: Int = 10,
        photosFound: Int = 10,
        photosMissing: Int = 0,
        unusable: Int = 0,
        collections: Int = 0,
        tags: Int = 0,
        captions: Int = 0,
        favorites: Int = 0,
        sensitive: Int = 0,
    ) = MetadataBackup.RestoreSummary(
        hasIdentity, exportedAtMillis, photosInFile, photosFound, 0, photosMissing, unusable,
        collections, tags, captions, favorites, sensitive,
    )

    @Test
    fun `contents lists only what is there, with the right plurals`() {
        assertNull(MetadataBackupCopy.contents(summary()))
        assertEquals("1 tag", MetadataBackupCopy.contents(summary(tags = 1)))
        assertEquals("1 collection and 2 tags", MetadataBackupCopy.contents(summary(collections = 1, tags = 2)))
        assertEquals(
            "3 collections, 12 tags, 40 captions, 1 favourite and 4 sensitive marks",
            MetadataBackupCopy.contents(summary(collections = 3, tags = 12, captions = 40, favorites = 1, sensitive = 4)),
        )
    }

    @Test
    fun `the match line only exists for a file that says which photos it is about`() {
        assertNull(MetadataBackupCopy.matching(summary(hasIdentity = false)))
        assertEquals("Found 10 of 10 photos on this phone.", MetadataBackupCopy.matching(summary()))
        val partial = MetadataBackupCopy.matching(summary(photosFound = 7, photosMissing = 3))!!
        assertTrue(partial, partial.startsWith("Found 7 of 10 photos on this phone."))
        assertTrue(partial, partial.contains("The other 3 are not here"))
        assertEquals("Found 1 of 1 photo on this phone.", MetadataBackupCopy.matching(summary(photosInFile = 1, photosFound = 1)))
    }

    @Test
    fun `unreadable entries are mentioned only when there are some`() {
        assertNull(MetadataBackupCopy.unusable(summary()))
        assertEquals("1 entry in the file could not be read and will be skipped.", MetadataBackupCopy.unusable(summary(unusable = 1)))
    }

    @Test
    fun `the saved date reads in the given zone and locale`() {
        assertNull(MetadataBackupCopy.savedOn(summary()))
        val text = MetadataBackupCopy.savedOn(summary(exportedAtMillis = 1_800_000_000_000), ZoneId.of("UTC"), Locale.UK)!!
        assertTrue(text, text.startsWith("Saved on ") && text.contains("2027"))
    }

    @Test
    fun `the old-file warning says to restore it only on the same phone`() {
        val warning = MetadataBackupCopy.LEGACY_WARNING
        assertTrue(warning.contains("older version of Fotoz"))
        assertTrue(warning.contains("only on the phone it was saved on"))
        assertTrue(warning.contains("wrong photos"))
    }

    @Test
    fun `replace is described as erasing and as final`() {
        assertTrue(MetadataBackupCopy.REPLACE_BODY.contains("erases"))
        assertTrue(MetadataBackupCopy.REPLACE_BODY.contains("cannot be undone"))
        assertTrue(MetadataBackupCopy.REPLACE_BODY.contains("Your photos are not touched"))
    }

    @Test
    fun `results say what happened and that the file is not encrypted`() {
        assertEquals("Added 2 tags.", MetadataBackupCopy.done(summary(tags = 2), ImportMode.MERGE))
        assertEquals("Replaced with 2 tags. 1 photo not on this phone, skipped.", MetadataBackupCopy.done(summary(tags = 2, photosMissing = 1), ImportMode.REPLACE))
        val saved = MetadataBackupCopy.saved(MetadataBackup.ExportCoverage(5, 5, 0))
        assertTrue(saved, saved.contains("not encrypted"))
        val skipped = MetadataBackupCopy.saved(MetadataBackup.ExportCoverage(5, 3, 2))
        assertTrue(skipped, skipped.contains("2 entries belong to photos that are no longer on this phone"))
    }

    @Test
    fun `no dialog sentence uses the word metadata, which the help copy rules out`() {
        val all = listOf(
            MetadataBackupCopy.TITLE, MetadataBackupCopy.LEGACY_WARNING, MetadataBackupCopy.MERGE_EXPLAINED,
            MetadataBackupCopy.NOTHING_TO_RESTORE, MetadataBackupCopy.REPLACE_TITLE, MetadataBackupCopy.REPLACE_BODY,
            MetadataBackupCopy.NO_CATALOGUE, MetadataBackupCopy.NOT_ENCRYPTED_AFTER_SAVE,
        )
        all.forEach { text -> assertTrue(text, !text.contains("metadata", ignoreCase = true)) }
    }
}
