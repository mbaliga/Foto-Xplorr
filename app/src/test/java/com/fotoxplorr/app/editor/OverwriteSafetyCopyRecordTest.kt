package com.fotoxplorr.app.editor

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The safety copy of a truncated original knows which photo it belongs to, is not mistaken for a
 * crashed replace while its own replace is running, is announced until the person has answered,
 * and only then ages out; a copy can be staged for the share sheet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OverwriteSafetyCopyRecordTest {

    private val context = RuntimeEnvironment.getApplication()
    private val dir = OverwriteRecovery.dir(context)
    private val originalBytes = ByteArray(3000) { (it % 200).toByte() }
    private val workDir = File(context.cacheDir, "fx-record-test").also { it.mkdirs() }
    private val originalFile = File(workDir, "original.jpg").also { it.writeBytes(originalBytes) }
    private val staged = File(workDir, "staged.jpg").also { it.writeBytes(ByteArray(500) { 7 }) }

    @After
    fun cleanUp() {
        dir.deleteRecursively()
        workDir.deleteRecursively()
        File(context.cacheDir, "outgoing-share").deleteRecursively()
        context.getSharedPreferences("overwrite_recovery_notice", 0).edit().clear().commit()
    }

    private open inner class FileOriginal(val failEverything: Boolean = false, val onTruncate: () -> Unit = {}) : OriginalFile {
        var opens = 0
        override fun openForRead(): InputStream? = originalFile.inputStream()
        override fun openForTruncatingWrite(): OutputStream? {
            opens++
            if (opens == 1) onTruncate()
            // A restore that cannot even open, so the copy has to be kept.
            if (opens > 1 && failEverything) throw IOException("provider gone")
            return originalFile.outputStream()
        }
        override fun currentSizeBytes(): Long = originalBytes.size.toLong()
    }

    private fun replace(original: OriginalFile, verify: () -> Unit = {}) = replaceWithRestore(
        original, staged, dir, dir, "IMG_0042.jpg", originalBytes.size.toLong(),
        verifyWritten = verify, originUri = "content://media/external/images/media/42", mimeType = "image/jpeg",
    )

    private fun leftovers() = dir.listFiles().orEmpty().filter { isSafetyCopy(it) }

    @Test
    fun `the sidecar exists before the original is truncated and names the photo`() {
        var atTruncate: SafetyCopy? = null
        replace(FileOriginal(onTruncate = { atTruncate = leftovers().singleOrNull()?.let(::readSidecar) }))

        val record = atTruncate!!
        assertEquals("IMG_0042.jpg", record.photoName)
        assertEquals("content://media/external/images/media/42", record.contentUri)
        assertEquals("image/jpeg", record.mimeType)
        assertEquals(originalBytes.size.toLong(), record.originalSizeBytes)
    }

    @Test
    fun `a replace that verifies leaves neither a copy nor a sidecar`() {
        replace(FileOriginal())

        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `a failure that is put back leaves neither a copy nor a sidecar`() {
        val failure = runCatching { replace(FileOriginal()) { error("does not read back") } }.exceptionOrNull()

        assertTrue((failure as OverwriteException.FailedAfterWrite).restored)
        assertTrue(dir.listFiles().orEmpty().isEmpty())
        assertArrayEquals(originalBytes, originalFile.readBytes())
    }

    @Test
    fun `a failure that cannot be put back keeps the copy and the sidecar under the new name`() {
        val failure = runCatching { replace(FileOriginal(failEverything = true)) { error("does not read back") } }
            .exceptionOrNull() as OverwriteException.FailedAfterWrite

        val kept = failure.recoveryFile!!
        assertFalse(failure.restored)
        assertTrue(kept.name.endsWith(".bak"))
        assertArrayEquals(originalBytes, kept.readBytes())
        val record = readSidecar(kept)
        assertEquals("IMG_0042.jpg", record.photoName)
        assertEquals("content://media/external/images/media/42", record.contentUri)
        assertEquals(1, dir.listFiles().orEmpty().count { it.name.endsWith(".meta") })
        assertEquals(listOf(kept.name), OverwriteRecovery.pendingCopies(context).map { it.file.name })
    }

    @Test
    fun `the copy of a replace that is still running is neither announced nor deleted`() {
        var pendingDuring: List<SafetyCopy>? = null
        var deletedDuring = -1
        var sweptDuring: List<File>? = null
        replace(FileOriginal()) {
            pendingDuring = OverwriteRecovery.pendingCopies(context)
            deletedDuring = OverwriteRecovery.deleteCopies(context, leftovers().map { it.name })
            sweptDuring = OverwriteRecovery.sweepOrphans(context, nowMillis = Long.MAX_VALUE / 2)
        }

        assertTrue(pendingDuring!!.isEmpty())
        assertEquals(0, deletedDuring)
        assertTrue("the live copy was not reported as a leftover", sweptDuring!!.isEmpty())
        assertEquals("it was in place the whole time and went only when the replace verified", 0, leftovers().size)
    }

    @Test
    fun `a copy left by a crashed process is announced, again and again until it is answered`() {
        val orphan = File(dir.also { it.mkdirs() }, "backup-crashed.bin").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
        writeSidecar(orphan, "content://m/9", "IMG_0009.jpg", "image/jpeg", 3L)

        val first = OverwriteRecovery.pendingCopies(context)
        val second = OverwriteRecovery.pendingCopies(context)

        assertEquals(listOf("backup-crashed.bin"), first.map { it.file.name })
        assertEquals("IMG_0009.jpg", first.single().photoName)
        assertEquals(first.map { it.file.name }, second.map { it.file.name })
    }

    @Test
    fun `answering the notice stops the announcements and starts the clock`() {
        val bak = File(dir.also { it.mkdirs() }, "IMG_7.jpg.1700000000000.bak").also { it.writeBytes(byteArrayOf(1)) }
        writeSidecar(bak, "content://m/7", "IMG_7.jpg", "image/jpeg", 1L)
        val day = 24L * 60 * 60 * 1000
        val answeredAt = 1_000_000_000_000L

        assertEquals(1, OverwriteRecovery.pendingCopies(context).size)
        OverwriteRecovery.markNoticeSeen(context, listOf(bak.name), nowMillis = answeredAt)
        assertTrue(OverwriteRecovery.pendingCopies(context).isEmpty())

        OverwriteRecovery.sweepOrphans(context, nowMillis = answeredAt + 29 * day)
        assertTrue("a recovery file is kept 30 days after it was answered", bak.exists())

        OverwriteRecovery.sweepOrphans(context, nowMillis = answeredAt + 31 * day)
        assertFalse(bak.exists() || sidecarOf(bak).exists())
    }

    @Test
    fun `a copy never announced is kept however long ago it was made`() {
        val bak = File(dir.also { it.mkdirs() }, "IMG_8.jpg.1500000000000.bak").also { it.writeBytes(byteArrayOf(1)); it.setLastModified(1000L) }

        val remaining = OverwriteRecovery.sweepOrphans(context, nowMillis = Long.MAX_VALUE / 2)

        assertTrue(bak.exists())
        assertEquals(listOf(bak.name), remaining.map { it.name })
        assertEquals(1, OverwriteRecovery.pendingCopies(context).size)
    }

    @Test
    fun `deleting from the dialog removes the named copies and their sidecars`() {
        val bak = File(dir.also { it.mkdirs() }, "IMG_6.jpg.1.bak").also { it.writeBytes(byteArrayOf(1)) }
        writeSidecar(bak, "content://m/6", "IMG_6.jpg", "image/jpeg", 1L)

        assertEquals(1, OverwriteRecovery.deleteCopies(context, listOf(bak.name)))

        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `a copy is staged for sharing under the photo's own name and the private file stays put`() {
        val bak = File(dir.also { it.mkdirs() }, "IMG_5.jpg.1.bak").also { it.writeBytes(originalBytes) }
        writeSidecar(bak, "content://m/5", "IMG_5.jpg", "image/jpeg", originalBytes.size.toLong())

        val (shared, mime) = OverwriteRecovery.stageForShare(context, bak.name)!!

        assertEquals("image/jpeg", mime)
        assertEquals("IMG_5.jpg", shared.name)
        assertArrayEquals(originalBytes, shared.readBytes())
        assertTrue(shared.absolutePath.startsWith(File(context.cacheDir, "outgoing-share").absolutePath))
        assertTrue(bak.exists())
    }

    @Test
    fun `a copy without a sidecar is staged under a generic name`() {
        val bak = File(dir.also { it.mkdirs() }, "backup-77.bin").also { it.writeBytes(originalBytes) }

        val (shared, mime) = OverwriteRecovery.stageForShare(context, bak.name)!!

        assertEquals("photo.jpg", shared.name)
        assertEquals("image/*", mime)
    }

    @Test
    fun `staging refuses a name that leaves the directory or is not a safety copy`() {
        File(dir.also { it.mkdirs() }, "notes.txt").writeBytes(byteArrayOf(1))
        File(context.filesDir, "secret.bak").writeBytes(byteArrayOf(1))

        assertNull(OverwriteRecovery.stageForShare(context, "../secret.bak"))
        assertNull(OverwriteRecovery.stageForShare(context, "notes.txt"))
        assertNull(OverwriteRecovery.stageForShare(context, "missing.bak"))
    }

    @Test
    fun `a sidecar that is missing or broken leaves the photo unnamed and nothing else`() {
        val bak = File(dir.also { it.mkdirs() }, "x.1.bak").also { it.writeBytes(byteArrayOf(1)) }
        assertNull(readSidecar(bak).photoName)
        sidecarOf(bak).writeBytes(byteArrayOf(0x5C, 0x75, 0x00)) // "\u" with no digits: malformed
        assertNotNull(readSidecar(bak).file)
    }
}
