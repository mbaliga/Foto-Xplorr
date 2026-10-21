package com.fotoxplorr.app.lift

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Housekeeping around the sticker exporter: the cache sweep, and the two strings it shares with
 * the rest of the app (the FileProvider's declared directory and the gallery album).
 */
class StickerExporterCacheTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val now = 1_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun file(name: String, ageMillis: Long): File =
        folder.newFile(name).also { it.setLastModified(now - ageMillis) }

    @Test
    fun `files older than the limit are deleted and fresher ones are kept`() {
        val old = file("old.png", 2 * day)
        val fresh = file("fresh.png", day / 4)

        val removed = pruneStaleFiles(folder.root, now, day)

        assertEquals(1, removed)
        assertFalse(old.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun `a missing directory is nothing to prune`() {
        assertEquals(0, pruneStaleFiles(File(folder.root, "never-created"), now, day))
    }

    @Test
    fun `sub-directories are left alone`() {
        val dir = folder.newFolder("nested")
        dir.setLastModified(now - 5 * day)
        assertEquals(0, pruneStaleFiles(folder.root, now, day))
        assertTrue(dir.exists())
    }

    @Test
    fun `an empty directory prunes nothing`() {
        assertEquals(0, pruneStaleFiles(folder.root, now, day))
    }

    @Test
    fun `stickers save to their own album and export at 2048`() {
        assertEquals("Pictures/Fotoz/Stickers/", StickerExporter.GALLERY_RELATIVE_PATH)
        assertEquals(2048, StickerExporter.EXPORT_LONG_EDGE_PX)
    }

    @Test
    fun `the FileProvider declares the directory stickers are shared from`() {
        val candidates = listOf(File("src/main/res/xml/file_paths.xml"), File("app/src/main/res/xml/file_paths.xml"))
        val xml = (candidates.firstOrNull { it.isFile } ?: error("file_paths.xml not found")).readText()
        val declared = Regex("""<cache-path[^>]*\bpath\s*=\s*"([^"]*)"""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).map { it.groupValues[1].trimEnd('/') }.toList()
        assertTrue(
            "file_paths.xml declares $declared, which does not cover '${StickerExporter.SHARE_DIRECTORY}'",
            StickerExporter.SHARE_DIRECTORY in declared,
        )
    }
}
