package com.fotoxplorr.app.receive

import android.provider.MediaStore
import com.fotoxplorr.app.board.BoardMediaProvider
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [ReceiveWriter] and [ReceiveSession] against stand-ins for the sender's provider and for
 * MediaStore's (the one [com.fotoxplorr.app.board.BoardWriterTest] uses, which keeps its rows). Not
 * shown: the real provider's refusals and renaming, and the scanner; those are device checks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReceiveWriterTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun attach() {
        BoardMediaProvider.reset(context.cacheDir)
        ReceiveSenderProvider.reset(context.cacheDir)
        Robolectric.buildContentProvider(BoardMediaProvider::class.java).create(MediaStore.AUTHORITY)
        Robolectric.buildContentProvider(ReceiveSenderProvider::class.java).create(ReceiveSenderProvider.AUTHORITY)
    }

    private fun item(name: String, kind: ReceiveKind = ReceiveKind.IMAGE, mime: String? = "image/jpeg") =
        ReceiveItem(ReceiveSenderProvider.uri(name).toString(), mime, kind, name)

    private fun names() = BoardMediaProvider.rows.values.map { it.getAsString(MediaStore.MediaColumns.DISPLAY_NAME) }

    @Test
    fun `a received photo becomes a new pending then visible row in Pictures Fotoz with the bytes`() = runBlocking {
        val saved = ReceiveWriter(context).save(item("a.jpg"), HashSet())
        assertEquals("a.jpg", saved)
        val row = BoardMediaProvider.rows.values.single()
        assertEquals("Pictures/Fotoz/", row.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
        assertEquals("image/jpeg", row.getAsString(MediaStore.MediaColumns.MIME_TYPE))
        assertEquals("pending while copying", 1, BoardMediaProvider.insertedPending.single())
        assertEquals("and visible at the end", 0, row.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        assertEquals(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, BoardMediaProvider.insertedInto.single())
        assertEquals("bytes of a.jpg", BoardMediaProvider.fileOf(BoardMediaProvider.rows.keys.single()).readText())
    }

    @Test
    fun `a video goes to the video collection and its own folder`() = runBlocking {
        ReceiveWriter(context).save(item("c.mp4", ReceiveKind.VIDEO, "video/mp4"), HashSet())
        assertEquals(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, BoardMediaProvider.insertedInto.single())
        assertEquals("Movies/Fotoz/", BoardMediaProvider.rows.values.single().getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
    }

    @Test
    fun `a name already in the folder is never overwritten and the new file gets the next free name`() = runBlocking {
        BoardMediaProvider.seed("a.jpg", "Pictures/Fotoz/")
        BoardMediaProvider.seed("a-2.jpg", "Pictures/Fotoz/")
        val saved = ReceiveWriter(context).save(item("a.jpg"), HashSet())
        assertEquals("a-3.jpg", saved)
        assertEquals(3, BoardMediaProvider.rows.size)
        // The two old rows are exactly as they were: no bytes, no pending flag, still there.
        val seeded = BoardMediaProvider.rows.entries.filter { it.value.getAsString(MediaStore.MediaColumns.DISPLAY_NAME) != "a-3.jpg" }
        assertEquals(2, seeded.size)
        assertTrue(seeded.all { !BoardMediaProvider.fileOf(it.key).exists() && !it.value.containsKey(MediaStore.MediaColumns.IS_PENDING) })
    }

    @Test
    fun `two files with one name in one run get two unique names`() = runBlocking {
        val writer = ReceiveWriter(context)
        val used = HashSet<String>()
        // The fake keeps pending rows queryable, so the run's own set is checked as well: clear the rows' names to prove it.
        val first = writer.save(item("same.jpg"), used)
        BoardMediaProvider.rows.values.forEach { it.remove(MediaStore.MediaColumns.DISPLAY_NAME) }
        val second = writer.save(item("same.jpg"), used)
        assertEquals("same.jpg", first)
        assertEquals("same-2.jpg", second)
    }

    @Test
    fun `a failing insert saves nothing and leaves no row`() = runBlocking {
        BoardMediaProvider.refuseInsert = true
        val session = ReceiveSession(listOf(item("a.jpg")))
        session.run { ReceiveWriter(context).save(it, HashSet()) }
        assertEquals(1, session.progress.value.failed)
        assertTrue(BoardMediaProvider.rows.isEmpty())
    }

    @Test
    fun `an unreadable source fails that item and writes no row`() = runBlocking {
        val session = ReceiveSession(listOf(item("missing.jpg"), item("b.jpg")))
        val used = HashSet<String>()
        session.run { ReceiveWriter(context).save(it, used) }
        assertEquals(1, session.progress.value.saved)
        assertEquals(1, session.progress.value.failed)
        assertEquals(listOf("b.jpg"), names())
    }

    @Test
    fun `a cancel in the middle of a copy leaves no pending row and keeps the finished ones`() = runBlocking {
        val items = listOf(item("a.jpg"), item("b.jpg"), item("c.jpg"))
        val session = ReceiveSession(items)
        val writer = ReceiveWriter(context)
        val used = HashSet<String>()
        var opens = 0
        lateinit var job: kotlinx.coroutines.Job
        // Each save opens the new row once; cancel while the second one is being written.
        BoardMediaProvider.onOpen = { if (++opens == 2) job.cancel() }
        job = async(start = CoroutineStart.UNDISPATCHED) { session.run { writer.save(it, used) } }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals("only the finished one remains", listOf("a.jpg"), names())
        assertTrue("and it is visible, not pending", BoardMediaProvider.rows.values.all { it.getAsInteger(MediaStore.MediaColumns.IS_PENDING) == 0 })
        assertEquals(ReceiveItemState.Pending, session.stateOf(1))

        BoardMediaProvider.onOpen = {}
        session.run { writer.save(it, used) }
        assertEquals(listOf("a.jpg", "b.jpg", "c.jpg"), names())
        assertFalse(session.failures().isNotEmpty())
    }

    @Test
    fun `safeName makes the sender's name safe and gives a nameless file an extension`() {
        val uri = ReceiveSenderProvider.uri("x")
        assertEquals("a path is cut to its last part", "b.jpg", safeName("a/b.jpg", uri, "image/jpeg") { "jpg" })
        assertEquals("Shared.jpg", safeName("  ", android.net.Uri.parse("content://s/"), "image/jpeg") { "jpg" })
        assertEquals("pic.png", safeName("pic", uri, "image/png") { "png" })
        assertEquals("we_ird.jpg", safeName("we:ird.jpg", uri, "image/jpeg") { "jpg" })
        assertEquals(80 + 4, safeName("x".repeat(200) + ".jpg", uri, "image/jpeg") { "jpg" }.length)
    }
}
