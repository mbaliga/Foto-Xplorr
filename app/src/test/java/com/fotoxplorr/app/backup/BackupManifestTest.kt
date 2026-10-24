package com.fotoxplorr.app.backup

import com.fotoxplorr.app.editor.jsonObjectOf
import com.fotoxplorr.app.editor.toJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.StringReader

class BackupManifestTest {
    private val sha = "ab".repeat(32)
    private val header = ManifestHeader(
        generation = 3,
        createdAtMillis = 1_700_000_000_000L,
        appVersion = "1.2.3",
        iterations = BackupFormat.DEFAULT_ITERATIONS,
        segmentSize = BackupFormat.DEFAULT_SEGMENT_SIZE,
    )

    private fun entry(id: Long, name: String = "IMG_$id.jpg", flags: Set<EntryFlag> = emptySet(), qh: String? = null) = ManifestEntry(
        entryId = id,
        mediaId = 500 + id,
        displayName = name,
        relativePath = "DCIM/Camera/",
        mimeType = "image/jpeg",
        sizeBytes = 4_000_000_000L + id,
        dateTakenMillis = 1_600_000_000_000L + id,
        dateModifiedSeconds = 1_600_000_000L + id,
        sha256 = sha,
        quickHash = qh,
        packName = "pack-0001.fzbp",
        flags = flags,
    )

    private fun write(block: ManifestWriter.() -> Unit): String {
        val out = StringBuilder()
        ManifestWriter(out).apply { writeHeader(header); block(); finish() }
        return out.toString()
    }

    @Test
    fun `a manifest round-trips header entries packs and sections`() {
        val entries = listOf(
            entry(0, flags = setOf(EntryFlag.WAS_ARCHIVED, EntryFlag.ORIGINAL_LOCATION, EntryFlag.MISSING)),
            entry(1, qh = "cd".repeat(32)),
            entry(2, name = "Zoë \"quoted\" \\ back\nline\ttab 😀.jpg"),
        )
        val text = write {
            entries.forEach(::writeEntry)
            writePack(ManifestPack("pack-0001.fzbp", 123_456_789L, 3))
            writeSection(ManifestSection("library", jsonObjectOf("schema" to 1.toJson())))
        }
        val read = ManifestReader.readAll(StringReader(text))
        assertEquals(header, read.header)
        assertEquals(entries, read.entries)
        assertEquals(listOf(ManifestPack("pack-0001.fzbp", 123_456_789L, 3)), read.packs)
        assertEquals(jsonObjectOf("schema" to 1.toJson()), read.section("library"))
        assertNull(read.section("missing"))
    }

    @Test
    fun `it is one record per line so a large library never needs one tree`() {
        val text = write { (0L until 200).forEach { writeEntry(entry(it)) } }
        assertEquals(1 + 200 + 1, text.trimEnd('\n').split('\n').size)
        // Streams: nothing is accumulated by read() itself.
        var seen = 0
        ManifestReader.read(StringReader(text)) { if (it is ManifestRecord.Entry) seen++ }
        assertEquals(200, seen)
    }

    @Test
    fun `fifty thousand entries stream through`() {
        val out = StringBuilder()
        val writer = ManifestWriter(out)
        writer.writeHeader(header)
        (0L until 50_000).forEach { writer.writeEntry(entry(it)) }
        writer.finish()
        var seen = 0
        ManifestReader.read(StringReader(out.toString())) { if (it is ManifestRecord.Entry) seen++ }
        assertEquals(50_000, seen)
    }

    @Test
    fun `unknown keys and record types are ignored`() {
        val text = write { writeEntry(entry(0)) }.lines().toMutableList()
        text[1] = text[1].replace("{\"t\":\"e\"", "{\"t\":\"e\",\"futureField\":[1,2,3]")
        text.add(2, "{\"t\":\"q\",\"anything\":true}")
        val read = ManifestReader.readAll(StringReader(text.joinToString("\n")))
        assertEquals(listOf(entry(0)), read.entries)
    }

    @Test
    fun `a manifest whose writer never finished is refused`() {
        val out = StringBuilder()
        ManifestWriter(out).apply { writeHeader(header); writeEntry(entry(0)) }
        refused(out.toString(), "ends early")
    }

    @Test
    fun `a missing header, a second header and content after the end are refused`() {
        val good = write { writeEntry(entry(0)) }.trimEnd('\n').split('\n')
        refused(good.drop(1).joinToString("\n"), "header")
        refused((listOf(good[0]) + good).joinToString("\n"), "two headers")
        refused((good + good[1]).joinToString("\n"), "after the end")
    }

    @Test
    fun `counts that disagree with the records are refused`() {
        val lines = write { writeEntry(entry(0)); writeEntry(entry(1)) }.trimEnd('\n').split('\n').toMutableList()
        lines.removeAt(2) // an entry vanished, the end line still counts two
        refused(lines.joinToString("\n"), "counts")
    }

    @Test
    fun `the same entry id twice is refused`() {
        val lines = write { writeEntry(entry(0)) }.trimEnd('\n').split('\n').toMutableList()
        lines.add(2, lines[1])
        refused(lines.joinToString("\n"), "twice")
    }

    @Test
    fun `a line that is not json, or not an object, is refused`() {
        val lines = write { writeEntry(entry(0)) }.trimEnd('\n').split('\n').toMutableList()
        refused((lines.take(1) + "not json" + lines.drop(1)).joinToString("\n"), "JSON object")
        refused((lines.take(1) + "[1,2]" + lines.drop(1)).joinToString("\n"), "JSON object")
    }

    @Test
    fun `hostile entries are refused`() {
        val line = write { writeEntry(entry(0)) }.trimEnd('\n').split('\n')[1]
        fun mutate(from: String, to: String) {
            val lines = write { writeEntry(entry(0)) }.trimEnd('\n').split('\n').toMutableList()
            assertTrue("fixture should contain $from", line.contains(from))
            lines[1] = line.replace(from, to)
            refused(lines.joinToString("\n"), null)
        }
        mutate("\"pack\":\"pack-0001.fzbp\"", "\"pack\":\"../pack-0001.fzbp\"")
        mutate("\"pack\":\"pack-0001.fzbp\"", "\"pack\":\"/etc/passwd\"")
        mutate("\"sha\":\"$sha\"", "\"sha\":\"nothex\"")
        mutate("\"sha\":\"$sha\"", "\"sha\":\"${sha.uppercase()}\"")
        mutate("\"size\":4000000000", "\"size\":-5")
        mutate("\"size\":4000000000", "\"size\":1.5")
        mutate("\"size\":4000000000", "\"size\":\"big\"")
        mutate("\"id\":0,", "\"id\":-1,")
        mutate("\"name\":\"IMG_0.jpg\"", "\"name\":\"\"")
    }

    @Test
    fun `a pack record with a bad name is refused`() {
        val lines = write { writePack(ManifestPack("pack-0001.fzbp", 1, 1)) }.trimEnd('\n').split('\n').toMutableList()
        lines[1] = lines[1].replace("pack-0001.fzbp", "..\\\\pack-0001.fzbp")
        refused(lines.joinToString("\n"), null)
    }

    @Test
    fun `key derivation parameters outside the accepted range are refused on read`() {
        val good = write { }.trimEnd('\n').split('\n').toMutableList()
        fun withHeader(from: String, to: String): String {
            val copy = good.toMutableList()
            assertTrue(copy[0].contains(from))
            copy[0] = copy[0].replace(from, to)
            return copy.joinToString("\n")
        }
        val iterations = "\"iter\":${BackupFormat.DEFAULT_ITERATIONS}"
        for (bad in listOf("0", "99999", "10000001", "4000000000", "-1")) {
            try {
                ManifestReader.readAll(StringReader(withHeader(iterations, "\"iter\":$bad")))
                fail("iterations $bad were accepted")
            } catch (e: BackupException.KdfOutOfRange) {
                // expected: the check runs before anything could use the number
            }
        }
        for (ok in listOf(BackupFormat.MIN_ITERATIONS, BackupFormat.MAX_ITERATIONS)) {
            assertEquals(ok, ManifestReader.readAll(StringReader(withHeader(iterations, "\"iter\":$ok"))).header.iterations)
        }
        try {
            ManifestReader.readAll(StringReader(withHeader("\"seg\":${BackupFormat.DEFAULT_SEGMENT_SIZE}", "\"seg\":64")))
            fail("a tiny segment size was accepted")
        } catch (e: BackupException.BadSegmentSize) {
            // expected
        }
    }

    @Test
    fun `an unsupported manifest version or generation is refused`() {
        val good = write { }
        refused(good.replace("\"v\":1", "\"v\":2"), "version")
        refused(good.replace("\"gen\":3", "\"gen\":0"), "generation")
    }

    @Test
    fun `a line past the ceiling is refused without being buffered`() {
        val text = write { writeEntry(entry(0)) }
        try {
            ManifestReader.readAll(StringReader(text), maxLineChars = 64)
            fail("a long line was accepted")
        } catch (e: ManifestFormatException) {
            assertTrue(e.message!!.contains("too long"))
        }
    }

    @Test
    fun `it survives the encrypted stream and a manifest that was cut off does not`() {
        val text = write { (0L until 40).forEach { writeEntry(entry(it)) } }
        val file = ByteArrayOutputStream().also { sink ->
            OutputStreamWriter(BackupStreams.encrypt(sink, BackupKind.MANIFEST, BackupTestSupport.keys, BackupTestSupport.SEGMENT), Charsets.UTF_8)
                .use { it.write(text) }
        }.toByteArray()
        val opened = BackupStreams.open(ByteArrayInputStream(file), BackupTestSupport.keys, BackupKind.MANIFEST)
        assertEquals(40, ManifestReader.readAll(InputStreamReader(opened.plaintext, Charsets.UTF_8)).entries.size)

        val cut = file.copyOf(file.size - 200)
        try {
            val stream = BackupStreams.open(ByteArrayInputStream(cut), BackupTestSupport.keys, BackupKind.MANIFEST).plaintext
            ManifestReader.readAll(InputStreamReader(stream, Charsets.UTF_8))
            fail("a truncated manifest was accepted")
        } catch (e: java.io.IOException) {
            // either the cipher layer or the end-line check refused it
        }
    }

    @Test
    fun `file names of a set`() {
        assertEquals("pack-0001.fzbp", BackupLayout.packName(1))
        assertEquals("pack-12345.fzbp", BackupLayout.packName(12345))
        assertEquals("manifest-0007.fzbm", BackupLayout.manifestName(7))
        assertEquals(12, BackupLayout.packNumber("pack-0012.fzbp"))
        assertNull(BackupLayout.packNumber("pack-12.fzbp"))
        assertNull(BackupLayout.packNumber("../pack-0001.fzbp"))
        assertNull(BackupLayout.packNumber("pack-0001.fzbp.tmp"))
        assertEquals(9, BackupLayout.manifestGeneration("manifest-0009.fzbm"))
        assertEquals(
            listOf("manifest-0010.fzbm", "manifest-0002.fzbm", "manifest-0001.fzbm"),
            BackupLayout.manifestsNewestFirst(listOf("manifest-0001.fzbm", "pack-0001.fzbp", "manifest-0010.fzbm", "notes.txt", "manifest-0002.fzbm")),
        )
    }

    private fun refused(text: String, messagePart: String?) {
        try {
            ManifestReader.readAll(StringReader(text))
            fail("a bad manifest was accepted: ${text.take(120)}")
        } catch (e: ManifestFormatException) {
            if (messagePart != null) assertTrue("'${e.message}' should mention '$messagePart'", e.message!!.contains(messagePart))
        }
    }
}
