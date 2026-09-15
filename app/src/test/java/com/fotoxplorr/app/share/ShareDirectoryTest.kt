package com.fotoxplorr.app.share

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The share directory and the FileProvider's declared roots must name the same folder.
 *
 * This exists because they broke apart in exactly the way nothing else could catch. The share
 * pipeline was rewritten and its cache directory renamed `clean-share` -> `outgoing-share`, while
 * `res/xml/file_paths.xml` still declared the old name. Nothing failed to compile, no lint check
 * fired, and no unit test covered it — the app simply threw
 *
 *     Failed to find configured root that contains /data/.../cache/outgoing-share/....png
 *
 * at the moment the user tapped Share, on a device, for every single share.
 *
 * The coupling is a string in Kotlin against a string in XML, so a test that reads both is the
 * only thing that can hold them together.
 */
class ShareDirectoryTest {

    @Test
    fun `the FileProvider declares the directory SharePreparer actually writes to`() {
        val declared = declaredCachePaths()
        assertTrue(
            "file_paths.xml declares $declared, which does not cover " +
                "'${SharePreparer.SHARE_DIRECTORY}' — sharing will throw on every attempt",
            declared.any { it.trimEnd('/') == SharePreparer.SHARE_DIRECTORY.trimEnd('/') },
        )
    }

    @Test
    fun `the share directory is not empty or absolute`() {
        // An empty path would grant the whole cache directory, and an absolute one is not what
        // cache-path means. Both are easy to write and neither would fail anywhere else.
        val directory = SharePreparer.SHARE_DIRECTORY
        assertTrue("share directory must not be blank", directory.isNotBlank())
        assertTrue("share directory must be relative to the cache dir", !directory.startsWith("/"))
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
