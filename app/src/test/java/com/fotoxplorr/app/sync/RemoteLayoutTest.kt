package com.fotoxplorr.app.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteLayoutTest {
    private fun candidate(name: String, taken: Long = 1_700_000_000_000L, modified: Long = 0) =
        SyncCandidate(1, name, "DCIM/Camera/", "image/jpeg", 10, taken, modified, "dcim/camera", false)

    @Test
    fun `a photo goes to year and month of the day it was taken in UTC`() {
        // 2023-11-14T22:13:20Z
        assertEquals("Fotoz/2023/2023-11/IMG_1.jpg", RemoteLayout.pathFor(candidate("IMG_1.jpg")))
        // 2023-12-31T23:59:59Z stays in December whatever the phone's zone says.
        assertEquals("Fotoz/2023/2023-12", RemoteLayout.folderFor(1_704_067_199_000L - 1_000L, 0))
        assertEquals("Fotoz/2024/2024-01", RemoteLayout.folderFor(1_704_067_200_000L, 0))
    }

    @Test
    fun `no taken date falls back to the modified date and then to Undated`() {
        assertEquals("Fotoz/2023/2023-11", RemoteLayout.folderFor(0, 1_700_000_000))
        assertEquals("Fotoz/Undated", RemoteLayout.folderFor(0, 0))
        assertEquals("Fotoz/Undated", RemoteLayout.folderFor(-5, -5))
    }

    @Test
    fun `separators and reserved characters are replaced`() {
        assertEquals("a_b_c_d_e_f_g_h_i_j.jpg", RemoteLayout.safeName("a/b\\c:d*e?f\"g<h>i|j.jpg"))
    }

    @Test
    fun `dot segments and empty names can never be produced`() {
        for (raw in listOf("", " ", ".", "..", "...", "../..", "../../etc/passwd", " .. ", "\u0000\u0001")) {
            val name = RemoteLayout.safeName(raw)
            assertTrue("'$raw' -> '$name'", name != "." && name != ".." && name.isNotEmpty())
            assertTrue(RemoteLayout.isSafePath("Fotoz/2023/2023-11/$name"))
            assertFalse(name.contains('/'))
        }
        assertEquals("file", RemoteLayout.safeName(".."))
    }

    @Test
    fun `control characters are dropped and text is normalised to NFC`() {
        assertEquals("ab.jpg", RemoteLayout.safeName("a\u0007b.jpg"))
        assertEquals("é.jpg", RemoteLayout.safeName("é.jpg"))
    }

    @Test
    fun `a long name is cut to the byte budget and keeps its extension`() {
        val name = RemoteLayout.safeName("x".repeat(400) + ".jpeg")
        assertTrue(name.toByteArray().size <= RemoteLayout.MAX_NAME_BYTES)
        assertTrue(name.endsWith(".jpeg"))
        val withSuffix = RemoteLayout.safeName("x".repeat(400) + ".jpeg", ".fzs")
        assertTrue(withSuffix.toByteArray().size <= RemoteLayout.MAX_NAME_BYTES)
        assertTrue(withSuffix.endsWith(".jpeg.fzs"))
    }

    @Test
    fun `unicode is cut on a character boundary, never in the middle of one`() {
        val cjk = RemoteLayout.safeName("日本語".repeat(100) + ".jpg")
        assertTrue(cjk.toByteArray(Charsets.UTF_8).size <= RemoteLayout.MAX_NAME_BYTES)
        assertEquals(cjk, String(cjk.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
        val emoji = RemoteLayout.safeName("📷".repeat(120) + ".png")
        assertEquals(emoji, String(emoji.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
        assertTrue(emoji.toByteArray(Charsets.UTF_8).size <= RemoteLayout.MAX_NAME_BYTES)
        assertFalse(emoji.contains('�'))
    }

    @Test
    fun `a lone surrogate is repaired so the name encodes`() {
        val name = RemoteLayout.safeName("a\uD83Db.jpg")
        assertEquals("a_b.jpg", name)
    }

    @Test
    fun `collisions get a numbered name, compared without case`() {
        val taken = setOf("fotoz/2023/2023-11/img_1.jpg", "fotoz/2023/2023-11/img_1 (2).jpg")
        val path = RemoteLayout.unique("Fotoz/2023/2023-11/IMG_1.jpg") { it.lowercase() in taken }
        assertEquals("Fotoz/2023/2023-11/IMG_1 (3).jpg", path)
        assertEquals("Fotoz/a/free.jpg", RemoteLayout.unique("Fotoz/a/free.jpg") { false })
        assertEquals("Fotoz/a/noext (2)", RemoteLayout.unique("Fotoz/a/noext") { it == "Fotoz/a/noext" })
    }

    @Test
    fun `a numbered name still fits the byte budget`() {
        val first = RemoteLayout.pathFor(candidate("y".repeat(400) + ".jpg"))
        val second = RemoteLayout.unique(first) { it == first }
        assertTrue(second.substringAfterLast('/').toByteArray().size <= RemoteLayout.MAX_NAME_BYTES)
        assertTrue(second.endsWith(" (2).jpg"))
    }

    @Test
    fun `folders on the way to a path are listed outermost first`() {
        assertEquals(listOf("Fotoz", "Fotoz/2023", "Fotoz/2023/2023-11"), RemoteLayout.foldersOf("Fotoz/2023/2023-11/a.jpg"))
        assertEquals(emptyList<String>(), RemoteLayout.foldersOf("a.jpg"))
    }

    @Test
    fun `path safety rejects traversal and odd shapes`() {
        assertTrue(RemoteLayout.isSafePath("Fotoz/a/b.jpg"))
        for (bad in listOf("", "/abs", "a/../b", "a/./b", "a//b", "a\\b", "..", "a/..", "a\u0000b")) assertFalse(bad, RemoteLayout.isSafePath(bad))
    }

    @Test
    fun `a custom root is sanitised too`() {
        assertEquals("a_b/2023/2023-11", RemoteLayout.folderFor(1_700_000_000_000L, 0, "a/b"))
    }
}
