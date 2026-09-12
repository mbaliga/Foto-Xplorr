package com.fotoxplorr.app.moments

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The moment export directory and the FileProvider's declared roots must name the same folder.
 *
 * Mirrors `com.fotoxplorr.app.share.ShareDirectoryTest` exactly, for exactly the same reason: a
 * rename of [MomentExportStorage.DIRECTORY] without a matching edit to
 * `res/xml/file_paths.xml` would compile cleanly, pass every other test, and then throw
 *
 *     Failed to find configured root that contains /data/.../cache/moments-export/....jpg
 *
 * at the moment a user taps "save frame" or "save clip" -- on a device, the first time either
 * export runs after the rename. Nothing but a test that reads both files can catch that before a
 * user does.
 */
class MomentExportDirectoryTest {

    @Test
    fun `the FileProvider declares the directory MomentExportStorage actually writes to`() {
        val declared = declaredCachePaths()
        assertTrue(
            "file_paths.xml declares $declared, which does not cover " +
                "'${MomentExportStorage.DIRECTORY}' — exporting a frame or a clip will throw on every attempt",
            declared.any { it.trimEnd('/') == MomentExportStorage.DIRECTORY.trimEnd('/') },
        )
    }

    @Test
    fun `the moment export directory is not empty or absolute`() {
        // An empty path would grant the whole cache directory, and an absolute one is not what
        // cache-path means. Both are easy to write and neither would fail anywhere else.
        val directory = MomentExportStorage.DIRECTORY
        assertTrue("moment export directory must not be blank", directory.isNotBlank())
        assertTrue("moment export directory must be relative to the cache dir", !directory.startsWith("/"))
    }

    private fun declaredCachePaths(): List<String> {
        val xml = filePathsXml().readText()
        // Deliberately a regex over the raw file rather than an XML parse: the point is to read
        // exactly what ships in the APK, and a parser that silently tolerated a malformed file
        // would defeat the test.
        return CACHE_PATH.findAll(xml).map { it.groupValues[1] }.toList()
    }

    private fun filePathsXml(): File {
        // Unit tests run with the module directory as the working directory, but that is a
        // convention rather than a guarantee, so try the repository root as well before failing.
        val candidates = listOf(
            File("src/main/res/xml/file_paths.xml"),
            File("app/src/main/res/xml/file_paths.xml"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("file_paths.xml not found; looked in ${candidates.map { it.absolutePath }}")
    }

    private companion object {
        val CACHE_PATH = Regex("""<cache-path[^>]*\bpath\s*=\s*"([^"]*)"""", RegexOption.DOT_MATCHES_ALL)
    }
}
