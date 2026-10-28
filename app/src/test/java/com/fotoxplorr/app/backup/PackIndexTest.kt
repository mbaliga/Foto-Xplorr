package com.fotoxplorr.app.backup

import java.io.StringReader
import java.io.StringWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PackIndexTest {
    private fun entry(id: Long, pack: String = "pack-0003.fzbp") = ManifestEntry(
        entryId = id, mediaId = 100 + id, displayName = "IMG_$id.jpg", relativePath = "DCIM/Camera/", mimeType = "image/jpeg",
        sizeBytes = 1_000 + id, dateTakenMillis = 5, dateModifiedSeconds = 6, sha256 = "a".repeat(64), quickHash = "b".repeat(64),
        packName = pack, flags = setOf(EntryFlag.ORIGINAL_LOCATION, EntryFlag.WAS_PROTECTED),
    )

    private fun text(pack: ManifestPack, entries: List<ManifestEntry>): String =
        StringWriter().also { PackIndexWriter(it).write(pack, entries) }.toString()

    private fun read(text: String) = PackIndexReader.read(StringReader(text))

    @Test
    fun `an index round trips with every field`() {
        val entries = listOf(entry(7), entry(8))
        val index = read(text(ManifestPack("pack-0003.fzbp", 4_096, 2), entries))
        assertEquals("pack-0003.fzbp", index.pack.name)
        assertEquals(4_096, index.pack.fileBytes)
        assertEquals(2, index.pack.entryCount)
        assertEquals(entries, index.entries)
    }

    @Test
    fun `an index goes through the real encrypted stream and back`() {
        val entries = (1L..40L).map { entry(it) }
        val keys = BackupTestSupport.newKeys()
        val sealed = java.io.ByteArrayOutputStream()
        val out = BackupStreams.encrypt(sealed, BackupKind.PACK_INDEX, keys, BackupTestSupport.SEGMENT)
        java.io.OutputStreamWriter(out, Charsets.UTF_8).use { PackIndexWriter(it).write(ManifestPack("pack-0003.fzbp", 9, 40), entries) }
        val opened = BackupStreams.open(java.io.ByteArrayInputStream(sealed.toByteArray()), keys, BackupKind.PACK_INDEX)
        val index = PackIndexReader.read(java.io.InputStreamReader(opened.plaintext, Charsets.UTF_8))
        assertEquals(entries, index.entries)
        // ...and it is not a manifest or a pack to anything that checks the kind.
        try {
            BackupStreams.open(java.io.ByteArrayInputStream(sealed.toByteArray()), keys, BackupKind.MANIFEST)
            fail("an index must not open as a manifest")
        } catch (e: BackupException.UnexpectedKind) {
            assertEquals(BackupKind.PACK_INDEX, e.actual)
        }
    }

    @Test
    fun `a cut index is refused`() {
        val full = text(ManifestPack("pack-0003.fzbp", 10, 2), listOf(entry(1), entry(2)))
        val withoutEnd = full.lines().dropLast(2).joinToString("\n") // drops the z line
        listOf(withoutEnd, full.substring(0, full.length / 2), "").forEach {
            try {
                read(it)
                fail("a cut index must be refused: ${it.take(30)}")
            } catch (e: ManifestFormatException) {
                // expected
            }
        }
    }

    @Test
    fun `an entry of another pack, a repeated id and a wrong count are refused`() {
        val good = text(ManifestPack("pack-0003.fzbp", 10, 2), listOf(entry(1), entry(2)))
        val lines = good.lines().filter { it.isNotBlank() }
        val foreignEntry = lines.joinToString("\n") { if (it.contains("\"t\":\"e\"")) it.replace("pack-0003.fzbp", "pack-0004.fzbp") else it }
        val repeated = (lines.take(2) + lines[1] + lines.last()).joinToString("\n")
        val wrongCount = good.replace("\"entries\":2", "\"entries\":3")
        assertTrue(foreignEntry != good && wrongCount != good)
        listOf(foreignEntry, repeated, wrongCount).forEach {
            try {
                read(it)
                fail("expected a refusal")
            } catch (e: ManifestFormatException) {
                assertTrue(e.message!!.isNotBlank())
            }
        }
    }

    @Test
    fun `an unknown record type and an unknown key are ignored`() {
        val good = text(ManifestPack("pack-0003.fzbp", 10, 1), listOf(entry(1)))
        val withExtra = good.replace("\n{\"t\":\"z\"", "\n{\"t\":\"q\",\"future\":true}\n{\"t\":\"z\"")
        assertEquals(1, read(withExtra).entries.size)
    }

    @Test
    fun `the writer refuses an entry that is not in its pack`() {
        try {
            text(ManifestPack("pack-0003.fzbp", 10, 1), listOf(entry(1, pack = "pack-0009.fzbp")))
            fail("an index is for one pack")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `index file names pair with their pack`() {
        assertEquals("pack-0012.fzbx", BackupLayout.indexName(12))
        assertEquals(12, BackupLayout.indexNumber("pack-0012.fzbx"))
        assertTrue(BackupLayout.isIndexName("pack-0012.fzbx"))
        assertTrue(!BackupLayout.isPackName("pack-0012.fzbx"))
        assertTrue(!BackupLayout.isIndexName("pack-0012.fzbp"))
        assertTrue(BackupLayout.isBackupFileName("pack-0012.fzbx"))
        assertTrue(BackupLayout.isBackupFileName("manifest-0002.fzbm"))
        assertTrue(!BackupLayout.isBackupFileName("holiday.jpg"))
    }
}
