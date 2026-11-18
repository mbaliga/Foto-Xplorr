package com.fotoxplorr.core.index

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [LocalDirSource] against an in-memory [DirectoryLister]: no filesystem, so it is exact and fast. */
class LocalDirSourceTest {
    private class FakeFs(private val dirs: Map<String, List<DirEntry>?>) : DirectoryLister {
        override fun list(path: String): List<DirEntry>? = dirs[path]
    }

    private fun file(name: String, size: Long = 10, mtime: Long = 1_000) = DirEntry(name, false, sizeBytes = size, mtimeMs = mtime)
    private fun dir(name: String) = DirEntry(name, true)

    private fun run(src: LocalDirSource): List<SourceEvent> = runBlocking { src.enumerate(null).toList() }
    private fun items(ev: List<SourceEvent>) = ev.filterIsInstance<SourceEvent.ItemFound>().map { it.item }

    @Test
    fun `walks breadth first, sorted, and derives folder identity from the root name`() {
        val fs = FakeFs(
            mapOf(
                "/home/u/Pictures" to listOf(file("b.jpg"), dir("Trip"), file("a.PNG")),
                "/home/u/Pictures/Trip" to listOf(file("c.mp4", size = 99, mtime = 5)),
            ),
        )
        val ev = run(LocalDirSource("/home/u/Pictures/", fs))
        val found = items(ev)
        assertEquals(listOf("a.PNG", "b.jpg", "c.mp4"), found.map { it.displayName })
        assertEquals(listOf("image/png", "image/jpeg", "video/mp4"), found.map { it.mime })
        assertEquals("Pictures", found[0].relativePath)
        assertEquals("Pictures/Trip", found[2].relativePath)
        assertEquals("Trip", found[2].bucketName)
        assertEquals("/home/u/Pictures/Trip/c.mp4", found[2].locator)
        assertEquals(99, found[2].sizeBytes)
        assertEquals(5, found[2].dateModifiedMs)
        assertEquals(SourceEvent.Completed(token = null, fullyEnumerated = true), ev.last())
    }

    @Test
    fun `skips dotfiles, symlinks and non-media`() {
        val fs = FakeFs(
            mapOf(
                "/r" to listOf(
                    file(".hidden.jpg"), dir(".thumbnails"), file("notes.txt"), file("noext"), file("trail."),
                    DirEntry("link.jpg", false, isSymlink = true), DirEntry("loop", true, isSymlink = true),
                    file("ok.heic"),
                ),
            ),
        )
        assertEquals(listOf("ok.heic"), items(run(LocalDirSource("/r", fs))).map { it.displayName })
    }

    @Test
    fun `an unreadable subdirectory completes the pass but refuses to claim full enumeration`() {
        val fs = FakeFs(mapOf("/r" to listOf(dir("locked"), file("a.jpg")), "/r/locked" to null))
        val ev = run(LocalDirSource("/r", fs))
        assertEquals(listOf("a.jpg"), items(ev).map { it.displayName })
        assertEquals(SourceEvent.Completed(null, fullyEnumerated = false), ev.last())
    }

    @Test
    fun `an unreadable root is a Failed event, never Completed`() {
        val ev = run(LocalDirSource("/nope", FakeFs(emptyMap())))
        assertEquals(1, ev.size)
        assertIs<SourceEvent.Failed>(ev[0])
    }

    @Test
    fun `depth cap is not silent -- it forfeits full enumeration`() {
        val fs = FakeFs(mapOf("/r" to listOf(dir("a")), "/r/a" to listOf(dir("b")), "/r/a/b" to listOf(file("deep.jpg"))))
        val ev = run(LocalDirSource("/r", fs, maxDepth = 1))
        assertTrue(items(ev).isEmpty())
        assertEquals(SourceEvent.Completed(null, false), ev.last())
    }

    @Test
    fun `root slash and uri encoding`() {
        val fs = FakeFs(mapOf("/" to listOf(file("a b#1.jpg"))))
        val it = items(run(LocalDirSource("/", fs))).single()
        assertEquals("/a b#1.jpg", it.locator)
        assertEquals("file:///a%20b%231.jpg", it.contentUri)
        assertEquals("root", it.relativePath)
    }

    @Test
    fun `mime table is case insensitive and closed`() {
        assertEquals("image/jpeg", LocalDirMime.forName("X.JPG"))
        assertNull(LocalDirMime.forName("x.exe"))
        assertNull(LocalDirMime.forName(".jpg-less"))
    }
}
