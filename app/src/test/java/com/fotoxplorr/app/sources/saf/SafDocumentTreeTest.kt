package com.fotoxplorr.app.sources.saf

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fotoxplorr.app.sources.saf.FakeDocumentsProvider.Companion.DIR
import com.fotoxplorr.app.sources.saf.FakeDocumentsProvider.Row
import com.fotoxplorr.core.formats.MediaFormat
import com.fotoxplorr.core.index.walker.TreeAccessException
import com.fotoxplorr.core.index.walker.TreeFailure
import com.fotoxplorr.core.index.walker.TreeWalker
import com.fotoxplorr.core.index.walker.WalkStatus
import com.fotoxplorr.core.index.walker.WalkedFile
import java.io.File
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafDocumentTreeTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        FakeDocumentsProvider.reset()
        FakeDocumentsProvider.install()
    }

    @After fun tearDown() = FakeDocumentsProvider.reset()

    private fun tree(held: () -> Boolean = { true }) =
        SafDocumentTree(context, FakeDocumentsProvider.treeUri, held, retryDelaysMillis = listOf(0L, 0L, 0L), pause = {})

    @Test
    fun oneQueryPerDirectoryWithTheFullProjectionAndEveryColumnRead() {
        FakeDocumentsProvider.dirs["root"] = listOf(
            Row("root/a.jpg", "a.jpg", "image/jpeg", size = 123, modified = 42_000, flags = 1),
            Row("root/sub", "sub", DIR),
            Row("root/n.jpg", "n.jpg", "image/jpeg", size = null, modified = null),
        )
        val t = tree()
        val rows = t.children("root")
        assertEquals(1, t.queryCount)
        assertEquals(listOf("root"), FakeDocumentsProvider.queries)
        assertEquals(3, rows.size)
        val a = rows[0]
        assertEquals("root/a.jpg", a.documentId)
        assertEquals(123L, a.sizeBytes)
        assertEquals(42_000L, a.lastModifiedMillis)
        assertEquals(1, a.flags)
        assertFalse(a.isDirectory)
        assertTrue(rows[1].isDirectory)
        assertEquals(-1L, rows[2].sizeBytes)
        assertEquals(0L, rows[2].lastModifiedMillis)
        assertEquals(6, SafDocumentTree.PROJECTION.size)
    }

    @Test
    fun aLoadingFolderIsAskedAgainAndTheSettledAnswerIsUsed() {
        FakeDocumentsProvider.dirs["root"] = listOf(Row("root/a.jpg", "a.jpg", "image/jpeg"))
        FakeDocumentsProvider.loadingAnswers = 2
        val t = tree()
        assertEquals(1, t.children("root").size)
        assertEquals(3, t.queryCount)
    }

    @Test
    fun aFolderThatNeverFinishesLoadingFailsInsteadOfBeingEmpty() {
        FakeDocumentsProvider.loadingAnswers = 100
        val e = runCatching { tree().children("root") }.exceptionOrNull() as TreeAccessException
        assertEquals(TreeFailure.ERROR, e.failure)
    }

    @Test
    fun extraErrorIsAFailureNotAnEmptyList() {
        FakeDocumentsProvider.errorText = "account signed out"
        val e = runCatching { tree().children("root") }.exceptionOrNull() as TreeAccessException
        assertEquals(TreeFailure.ERROR, e.failure)
        assertTrue(e.message!!.contains("account signed out"))
    }

    @Test
    fun aNullCursorAndAThrowingProviderAreFailures() {
        FakeDocumentsProvider.nullCursor = true
        assertEquals(TreeFailure.ERROR, (runCatching { tree().children("root") }.exceptionOrNull() as TreeAccessException).failure)
        FakeDocumentsProvider.nullCursor = false
        FakeDocumentsProvider.throwOnQuery = SecurityException("denied")
        // The grant is still held: an ordinary provider problem, not a revoke.
        assertEquals(TreeFailure.ERROR, (runCatching { tree().children("root") }.exceptionOrNull() as TreeAccessException).failure)
        // The grant is gone: REVOKED.
        assertEquals(TreeFailure.REVOKED, (runCatching { tree { false }.children("root") }.exceptionOrNull() as TreeAccessException).failure)
    }

    @Test
    fun aGrantThatIsGoneIsRevokedWithoutAskingTheProvider() {
        val t = tree { false }
        val e = runCatching { t.children("root") }.exceptionOrNull() as TreeAccessException
        assertEquals(TreeFailure.REVOKED, e.failure)
        assertEquals(0, t.queryCount)
    }

    @Test
    fun readHeadIsBoundedAndNullForAMissingDocument() {
        val file = File.createTempFile("fx-head", ".bin").apply { deleteOnExit(); writeBytes(ByteArray(5000) { (it % 251).toByte() }) }
        FakeDocumentsProvider.files["root/big"] = file
        val t = tree()
        val head = t.readHead("root/big", 64)!!
        assertEquals(64, head.size)
        assertArrayEquals(ByteArray(64) { it.toByte() }, head)
        assertNull(t.readHead("root/missing", 64))
    }

    @Test
    fun theWalkerWalksARegisteredProviderEndToEndAndSniffsALyingExtension() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val file = File.createTempFile("fx-lie", ".jpg").apply { deleteOnExit(); writeBytes(png) }
        FakeDocumentsProvider.files["root/lie.jpg"] = file
        FakeDocumentsProvider.dirs["root"] = listOf(
            Row("root/lie.jpg", "lie.jpg", "image/jpeg", size = 8),
            Row("root/Trip", "Trip", DIR),
            Row("root/.fotoz-trash", ".fotoz-trash", DIR),
        )
        FakeDocumentsProvider.dirs["root/Trip"] = listOf(Row("root/Trip/x.jpg", "x.jpg", "image/jpeg"))
        FakeDocumentsProvider.dirs["root/.fotoz-trash"] = listOf(Row("root/.fotoz-trash/gone.jpg", "gone.jpg", "image/jpeg"))
        val t = tree()
        val files = ArrayList<WalkedFile>()
        val status = TreeWalker(t).run { files += it.files }
        assertEquals(WalkStatus.Complete(true), status)
        assertEquals(setOf("lie.jpg", "x.jpg"), files.map { it.name }.toSet())
        assertEquals(MediaFormat.Png, files.first { it.name == "lie.jpg" }.format)
        assertEquals(2, t.queryCount) // root and Trip; the trash folder was never listed
    }
}
