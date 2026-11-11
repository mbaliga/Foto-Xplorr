package com.fotoxplorr.app.editor

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files

/**
 * [replaceWithRestore] against plain files: the happy path leaves no backup behind, and every way
 * the truncating write can go wrong either leaves the original untouched (it was never opened) or
 * puts its bytes back and says so.
 */
class ReplaceWithRestoreTest {

    private val root: File = Files.createTempDirectory("fx-restore").toFile()
    private val backupDir = File(root, "cache/edit-backup")
    private val recoveryDir = File(root, "files/overwrite-recovery")
    private val originalBytes = ByteArray(5000) { (it % 200).toByte() }
    private val newBytes = ByteArray(1200) { (255 - it % 200).toByte() }
    private val originalFile = File(root, "original.jpg").also { it.writeBytes(originalBytes) }
    private val staged = File(root, "staged.jpg").also { it.writeBytes(newBytes) }

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    /** Backs [OriginalFile] with [originalFile]; each flag injects one failure. */
    private inner class FileOriginal(
        var unreadable: Boolean = false,
        var failOpenWrite: Boolean = false,
        /** The first truncating write dies after this many bytes (disk full / interrupted). */
        var failFirstWriteAfter: Int = -1,
        /** Every truncating write after the first one fails to open (the provider went away). */
        var failRestoreOpen: Boolean = false,
    ) : OriginalFile {
        var writeOpens = 0

        override fun openForRead(): InputStream? =
            if (unreadable) throw IOException("cannot read") else originalFile.inputStream()

        override fun openForTruncatingWrite(): OutputStream? {
            writeOpens++
            if (failOpenWrite) throw SecurityException("no write access")
            if (writeOpens > 1 && failRestoreOpen) throw IOException("provider gone")
            val real = originalFile.outputStream() // truncates, exactly like "wt"
            if (writeOpens == 1 && failFirstWriteAfter >= 0) {
                return object : OutputStream() {
                    var written = 0
                    override fun write(b: Int) {
                        if (written >= failFirstWriteAfter) throw IOException("disk full")
                        real.write(b)
                        written++
                    }

                    override fun write(b: ByteArray, off: Int, len: Int) {
                        for (i in off until off + len) write(b[i].toInt())
                    }

                    override fun close() = real.close()
                }
            }
            return real
        }
    }

    private fun run(
        original: OriginalFile,
        size: Long = originalBytes.size.toLong(),
        usableSpace: () -> Long = { Long.MAX_VALUE },
        verify: () -> Unit = {},
    ) = replaceWithRestore(original, staged, backupDir, recoveryDir, "original.jpg", size, usableSpace, verify)

    private fun leftovers(dir: File) = dir.listFiles().orEmpty().toList()

    @Test
    fun `a clean replace writes the new bytes and leaves no backup behind`() {
        run(FileOriginal())

        assertArrayEquals(newBytes, originalFile.readBytes())
        assertTrue(leftovers(backupDir).isEmpty())
        assertFalse(recoveryDir.exists())
    }

    @Test
    fun `a write that dies partway is rolled back to the exact original bytes`() {
        val failure = runCatching { run(FileOriginal(failFirstWriteAfter = 300)) }.exceptionOrNull()

        val failed = failure as OverwriteException.FailedAfterWrite
        assertTrue(failed.restored)
        assertNull(failed.recoveryFile)
        assertArrayEquals(originalBytes, originalFile.readBytes())
        assertTrue("the backup is deleted once restored", leftovers(backupDir).isEmpty())
        assertTrue("put back" in failed.message!!)
    }

    @Test
    fun `a write that completes but does not verify is rolled back too`() {
        val failure = runCatching {
            run(FileOriginal()) { error("does not read back as written") }
        }.exceptionOrNull()

        val failed = failure as OverwriteException.FailedAfterWrite
        assertTrue(failed.restored)
        assertArrayEquals(originalBytes, originalFile.readBytes())
    }

    @Test
    fun `when the restore also fails the safety copy is kept and the message says so`() {
        val failure = runCatching {
            run(FileOriginal(failFirstWriteAfter = 100, failRestoreOpen = true))
        }.exceptionOrNull()

        val failed = failure as OverwriteException.FailedAfterWrite
        assertFalse(failed.restored)
        val kept = failed.recoveryFile!!
        assertTrue(kept.absolutePath.startsWith(recoveryDir.absolutePath))
        assertArrayEquals("the recovery file holds the original's bytes", originalBytes, kept.readBytes())
        assertTrue(leftovers(backupDir).isEmpty())
        assertTrue("safety copy" in failed.message!!)
    }

    @Test
    fun `a refused open is rethrown as it was and the original is untouched`() {
        val failure = runCatching { run(FileOriginal(failOpenWrite = true)) }.exceptionOrNull()

        assertTrue(failure is SecurityException)
        assertArrayEquals(originalBytes, originalFile.readBytes())
        assertTrue(leftovers(backupDir).isEmpty())
    }

    @Test
    fun `an unreadable original is refused before anything is opened for writing`() {
        val original = FileOriginal(unreadable = true)

        val failure = runCatching { run(original) }.exceptionOrNull()

        val refused = failure as OverwriteException.Refused
        assertEquals(OverwriteException.Refused.Reason.BACKUP_FAILED, refused.reason)
        assertEquals("no truncating open may happen without a backup", 0, original.writeOpens)
        assertArrayEquals(originalBytes, originalFile.readBytes())
        assertTrue(leftovers(backupDir).isEmpty())
    }

    @Test
    fun `not enough room for the backup refuses up front`() {
        val original = FileOriginal()

        val failure = runCatching { run(original, usableSpace = { 100L }) }.exceptionOrNull()

        val refused = failure as OverwriteException.Refused
        assertEquals(OverwriteException.Refused.Reason.BACKUP_FAILED, refused.reason)
        assertTrue("free" in refused.message!!)
        assertEquals(0, original.writeOpens)
        assertArrayEquals(originalBytes, originalFile.readBytes())
    }

    @Test
    fun `sameBytes tells identical streams from different ones`() {
        val a = ByteArray(20_000) { (it % 97).toByte() }
        assertTrue(sameBytes(a.inputStream(), a.copyOf().inputStream()))
        assertFalse(sameBytes(a.inputStream(), a.copyOf().also { it[19_999] = 1 }.inputStream()))
        assertFalse(sameBytes(a.inputStream(), a.copyOf(19_999).inputStream()))
        assertTrue(sameBytes(ByteArray(0).inputStream(), ByteArray(0).inputStream()))
    }
}
