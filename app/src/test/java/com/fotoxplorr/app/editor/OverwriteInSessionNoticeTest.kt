package com.fotoxplorr.app.editor

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files

/**
 * A replace that truncates the original and cannot put it back names the photo in its failure text
 * and leaves a safety copy that the recovery listing returns straight away, in the same process,
 * so the named dialog can be shown in-session rather than at the next cold start.
 */
class OverwriteInSessionNoticeTest {

    private val root: File = Files.createTempDirectory("fx-insession").toFile()
    private val dir = File(root, "overwrite-recovery")
    private val originalBytes = ByteArray(2000) { (it % 200).toByte() }
    private val originalFile = File(root, "original.jpg").also { it.writeBytes(originalBytes) }
    private val staged = File(root, "staged.jpg").also { it.writeBytes(ByteArray(300) { 9 }) }

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    private val unrestorable = object : OriginalFile {
        var opens = 0
        override fun openForRead(): InputStream? = originalFile.inputStream()
        override fun openForTruncatingWrite(): OutputStream? = if (opens++ == 0) originalFile.outputStream() else null
        override fun currentSizeBytes(): Long = originalBytes.size.toLong()
    }

    private fun failWith(label: String): OverwriteException.FailedAfterWrite = runCatching {
        replaceWithRestore(
            unrestorable, staged, dir, dir, label, originalBytes.size.toLong(),
            verifyWritten = { error("does not read back") },
            originUri = "content://media/external/images/media/42", mimeType = "image/jpeg",
        )
    }.exceptionOrNull() as OverwriteException.FailedAfterWrite

    @Test
    fun `the not-restored message names the photo`() {
        val failure = failWith("IMG_0042.jpg")

        assertFalse(failure.restored)
        val message = failure.message!!
        assertTrue(message, "\"IMG_0042.jpg\"" in message)
        assertTrue(message, "could not be put back" in message)
        assertFalse(message, "next time Fotoz starts" in message)
    }

    @Test
    fun `the message builder falls back to the photo when no name is known`() {
        val message = originalNotPutBackMessage("  ")

        assertTrue(message, message.startsWith("Replacing the photo failed"))
        assertFalse(message, "\"\"" in message)
    }

    @Test
    fun `the safety copy is listed right after the failure and the dialog names the same photo`() {
        val failure = failWith("IMG_0042.jpg")

        // The same call the activity re-runs after an at-risk failure, in the same process.
        val pending = unresolvedSafetyCopies(dir, emptySet()) { false }
        assertEquals(listOf(failure.recoveryFile!!.name), pending.map { it.file.name })

        val entries = pending.toRecoveryEntries()
        assertEquals("IMG_0042.jpg", entries.single().photoName)
        val dialog = recoveryNoticeMessage(decodeRecoveryEntries(entries.encodeRecoveryEntries()))!!
        assertTrue(dialog, "\"IMG_0042.jpg\"" in dialog)
    }

    @Test
    fun `a replace that was put back leaves nothing for the dialog`() {
        val restorable = object : OriginalFile {
            override fun openForRead(): InputStream? = originalFile.inputStream()
            override fun openForTruncatingWrite(): OutputStream? = originalFile.outputStream()
            override fun currentSizeBytes(): Long = originalBytes.size.toLong()
        }
        runCatching {
            replaceWithRestore(restorable, staged, dir, dir, "IMG_0042.jpg", originalBytes.size.toLong(), verifyWritten = { error("no") })
        }

        assertTrue(unresolvedSafetyCopies(dir, emptySet()) { false }.isEmpty())
    }
}
