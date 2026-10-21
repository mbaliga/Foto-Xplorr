package com.fotoxplorr.app.editor

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files

/**
 * What makes the safety copy trustworthy: it is compared with the original's size right now, it is
 * where the system will not clear it, it is only deleted once the write reached storage, and one
 * that outlives a crash is swept by age and never sooner.
 */
class ReplaceWithRestoreDurabilityTest {

    private val root: File = Files.createTempDirectory("fx-durable").toFile()
    private val persistentDir = File(root, "files/overwrite-recovery")
    private val originalBytes = ByteArray(5000) { (it % 200).toByte() }
    private val newBytes = ByteArray(1200) { (255 - it % 200).toByte() }
    private val originalFile = File(root, "original.jpg").also { it.writeBytes(originalBytes) }
    private val staged = File(root, "staged.jpg").also { it.writeBytes(newBytes) }

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    private open inner class FileOriginal(
        val liveSize: Long = originalBytes.size.toLong(),
        /** What the read returns; fewer bytes than [liveSize] models a copy that came out short. */
        val readBytes: () -> InputStream = { originalFile.inputStream() },
        val plainStream: Boolean = false,
    ) : OriginalFile {
        var writeOpens = 0

        override fun openForRead(): InputStream? = readBytes()

        override fun openForTruncatingWrite(): OutputStream? {
            writeOpens++
            val real = originalFile.outputStream()
            if (!plainStream) return real
            // Not backed by a file descriptor, so nothing can be confirmed on storage.
            return object : OutputStream() {
                override fun write(b: Int) = real.write(b)
                override fun write(b: ByteArray, off: Int, len: Int) = real.write(b, off, len)
                override fun close() = real.close()
            }
        }

        override fun currentSizeBytes(): Long = liveSize
    }

    private fun run(
        original: OriginalFile,
        recorded: Long = originalBytes.size.toLong(),
        usableSpace: () -> Long = { Long.MAX_VALUE },
        verify: () -> Unit = {},
    ) = replaceWithRestore(original, staged, persistentDir, persistentDir, "original.jpg", recorded, usableSpace, verify)

    private fun backups() = persistentDir.listFiles().orEmpty().filter { it.name.startsWith("backup-") }

    @Test
    fun `the safety copy exists in the given directory and holds the original while the write is verified`() {
        var seenDuringVerify: List<File> = emptyList()
        var seenBytes = ByteArray(0)
        run(FileOriginal()) {
            seenDuringVerify = backups()
            seenBytes = seenDuringVerify.firstOrNull()?.readBytes() ?: ByteArray(0)
        }

        assertEquals(1, seenDuringVerify.size)
        assertArrayEquals(originalBytes, seenBytes)
        assertArrayEquals(newBytes, originalFile.readBytes())
        assertTrue("deleted once verified and flushed", backups().isEmpty())
    }

    @Test
    fun `a copy shorter than the original is now is refused before anything is truncated`() {
        val short = FileOriginal(readBytes = { ByteArrayInputStream(originalBytes.copyOf(3000)) })

        val refused = runCatching { run(short) }.exceptionOrNull() as OverwriteException.Refused

        assertEquals(OverwriteException.Refused.Reason.BACKUP_FAILED, refused.reason)
        assertEquals("no truncating open without a complete copy", 0, short.writeOpens)
        assertArrayEquals(originalBytes, originalFile.readBytes())
        assertTrue(backups().isEmpty())
    }

    @Test
    fun `a stale recorded size does not decide anything when the file can be asked`() {
        // MediaStore says 1 byte; the file says its real size, and the copy matches that.
        run(FileOriginal(), recorded = 1L)

        assertArrayEquals(newBytes, originalFile.readBytes())
    }

    @Test
    fun `the room check uses the live size when the recorded one is missing`() {
        val original = FileOriginal()

        val refused = runCatching { run(original, recorded = 0L, usableSpace = { 100L }) }.exceptionOrNull()

        assertEquals(OverwriteException.Refused.Reason.BACKUP_FAILED, (refused as OverwriteException.Refused).reason)
        assertEquals(0, original.writeOpens)
    }

    @Test
    fun `a write that cannot be confirmed on storage but reads back as written leaves no safety copy`() {
        var verified = false
        run(FileOriginal(plainStream = true)) { verified = true }

        assertTrue(verified)
        assertArrayEquals("the replace itself still happened", newBytes, originalFile.readBytes())
        assertTrue("a full-size copy is not kept once the write verified", backups().isEmpty())
    }

    @Test
    fun `a write that cannot be confirmed and fails verification is put back and the failure says so`() {
        val failure = runCatching { run(FileOriginal(plainStream = true)) { error("does not read back") } }
            .exceptionOrNull() as OverwriteException.FailedAfterWrite

        assertTrue(failure.restored)
        assertArrayEquals(originalBytes, originalFile.readBytes())
    }

    @Test
    fun `the wording for an original that could not be put back promises no way to get it back`() {
        val original = object : OriginalFile {
            var writes = 0
            override fun openForRead(): InputStream? = originalFile.inputStream()
            override fun openForTruncatingWrite(): OutputStream? =
                if (writes++ == 0) originalFile.outputStream() else null
            override fun currentSizeBytes(): Long = originalBytes.size.toLong()
        }

        val failure = runCatching { run(original) { error("does not read back") } }.exceptionOrNull() as OverwriteException.FailedAfterWrite

        assertFalse(failure.restored)
        val message = failure.message!!
        assertTrue(message, "Save a copy" in message)
        assertFalse(message, "kept inside Fotoz" in message)
        assertTrue(message, "cannot open" in message)
        assertTrue("the copy is kept", failure.recoveryFile!!.exists())
    }

    @Test
    fun `when the size cannot be asked the old rule applies and an empty copy is refused`() {
        val unknown = object : OriginalFile {
            override fun openForRead(): InputStream? = ByteArrayInputStream(ByteArray(0))
            override fun openForTruncatingWrite(): OutputStream? = error("must not be opened")
        }

        val refused = runCatching { run(unknown) }.exceptionOrNull() as OverwriteException.Refused

        assertEquals(OverwriteException.Refused.Reason.BACKUP_FAILED, refused.reason)
    }

    // ---- sweeping ----

    private fun orphan(name: String, ageMillis: Long, now: Long): File {
        persistentDir.mkdirs()
        return File(persistentDir, name).also {
            it.writeBytes(byteArrayOf(1, 2, 3))
            it.setLastModified(now - ageMillis)
        }
    }

    @Test
    fun `sweeping deletes in-flight copies older than the limit and keeps younger ones`() {
        val now = 1_000_000_000_000L
        val old = orphan("backup-111.bin", ageMillis = 8 * DAY, now = now)
        val young = orphan("backup-222.bin", ageMillis = 1 * DAY, now = now)

        val remaining = sweepOrphanBackups(persistentDir, now, maxAgeMillis = 7 * DAY)

        assertFalse(old.exists())
        assertTrue(young.exists())
        assertEquals(listOf(young), remaining)
    }

    @Test
    fun `sweeping never deletes a kept recovery file or an unrelated one however old`() {
        val now = 1_000_000_000_000L
        val kept = orphan("IMG_0001.jpg.1700000000000.bak", ageMillis = 400 * DAY, now = now)
        val other = orphan("notes.txt", ageMillis = 400 * DAY, now = now)

        val remaining = sweepOrphanBackups(persistentDir, now, maxAgeMillis = 7 * DAY)

        assertTrue(kept.exists() && other.exists())
        assertEquals(setOf(kept, other), remaining.toSet())
    }

    @Test
    fun `sweeping a directory that does not exist is empty and does not throw`() {
        assertTrue(sweepOrphanBackups(File(root, "nowhere"), 0L, 1L).isEmpty())
    }

    @Test
    fun `sweeping looks at a bounded number of files`() {
        val now = 1_000_000_000_000L
        persistentDir.mkdirs()
        repeat(MAX_SWEPT_FILES + 20) { i ->
            File(persistentDir, "backup-$i.bin").also { it.writeBytes(byteArrayOf(1)); it.setLastModified(now - 30 * DAY) }
        }

        val remaining = sweepOrphanBackups(persistentDir, now, maxAgeMillis = 7 * DAY)

        assertEquals("the overflow is left for a later start", 20, remaining.size)
        assertEquals(20, persistentDir.listFiles().orEmpty().size)
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
    }
}
