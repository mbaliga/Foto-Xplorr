package com.fotoxplorr.app.backup

import com.fotoxplorr.app.media.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestorePlannerTest {
    private fun sha(n: Int) = "%02x".format(n and 0xFF).repeat(32)

    private fun entry(
        id: Long,
        name: String = "IMG_$id.jpg",
        dir: String = "DCIM/Camera/",
        size: Long = 1000 + id,
        sha: String = sha(id.toInt()),
        mime: String = "image/jpeg",
        mediaId: Long = 100 + id,
    ) = ManifestEntry(
        entryId = id, mediaId = mediaId, displayName = name, relativePath = dir, mimeType = mime, sizeBytes = size,
        dateTakenMillis = 0, dateModifiedSeconds = 0, sha256 = sha, quickHash = null, packName = "pack-0001.fzbp",
    )

    private fun local(id: Long, name: String, dir: String? = "DCIM/Camera/", size: Long) = RestoreLocalFile(id, name, dir, size)

    private fun plan(entries: List<ManifestEntry>, local: List<RestoreLocalFile> = emptyList(), hashes: Map<Long, String> = emptyMap()) =
        RestorePlanner.plan(entries, local, hashes)

    @Test
    fun `a file missing on the phone is restored under its own name and folder`() {
        val item = plan(listOf(entry(1))).items.single()
        assertEquals(RestoreAction.RESTORE, item.action)
        assertEquals("DCIM/Camera/", item.targetRelativePath)
        assertEquals("IMG_1.jpg", item.targetDisplayName)
        assertEquals(MediaKind.IMAGE, item.kind)
    }

    @Test
    fun `a photo whose pack is missing from the backup is reported and never written`() {
        val missing = entry(1).copy(flags = setOf(EntryFlag.MISSING))
        val plan = plan(listOf(missing, entry(2)))
        assertEquals(listOf(RestoreAction.SKIP_MISSING, RestoreAction.RESTORE), plan.items.map { it.action })
        assertEquals(1, plan.count(RestoreAction.SKIP_MISSING))
        assertEquals(listOf(2L), plan.toWrite.map { it.entry.entryId })
    }

    @Test
    fun `videos go to the video collection and other types are not written`() {
        val items = plan(
            listOf(
                entry(1, name = "a.mp4", mime = "video/mp4", dir = "Movies/Trips/"),
                entry(2, name = "b.sh", mime = "application/x-sh"),
                entry(3, name = "c.jpg", mime = "text/plain"),
            ),
        ).items
        assertEquals(MediaKind.VIDEO, items[0].kind)
        assertEquals("Movies/Trips/", items[0].targetRelativePath)
        assertEquals(listOf(RestoreAction.RESTORE, RestoreAction.SKIP_UNSUPPORTED, RestoreAction.SKIP_UNSUPPORTED), items.map { it.action })
    }

    @Test
    fun `identical bytes already on the phone are skipped`() {
        val e = entry(1)
        val here = local(900, "IMG_1.jpg", size = e.sizeBytes)
        val plan = plan(listOf(e), listOf(here), mapOf(900L to e.sha256))
        assertEquals(RestoreAction.SKIP_ALREADY_HERE, plan.items.single().action)
        assertEquals(900L, plan.items.single().matchedMediaId)
        assertEquals(mapOf(e.mediaId to 900L), plan.alreadyHereIdMap())
        assertEquals(0L, plan.bytesToWrite)
    }

    @Test
    fun `already here ignores the folder and the case of the name`() {
        val e = entry(1)
        val here = local(900, "img_1.JPG", dir = "Pictures/Elsewhere/", size = e.sizeBytes)
        assertEquals(RestoreAction.SKIP_ALREADY_HERE, plan(listOf(e), listOf(here), mapOf(900L to e.sha256)).items.single().action)
    }

    @Test
    fun `same name and size but different content is restored as a copy and never overwrites`() {
        val e = entry(1)
        val here = local(900, "IMG_1.jpg", size = e.sizeBytes)
        val item = plan(listOf(e), listOf(here), mapOf(900L to sha(99))).items.single()
        assertEquals(RestoreAction.RESTORE_AS_COPY, item.action)
        assertEquals("IMG_1 (restored).jpg", item.targetDisplayName)
        assertEquals("DCIM/Camera/", item.targetRelativePath)
        assertNull(item.matchedMediaId)
    }

    @Test
    fun `an unhashed local file with the same name is treated as different, which can only make a copy`() {
        val e = entry(1)
        val item = plan(listOf(e), listOf(local(900, "IMG_1.jpg", size = e.sizeBytes))).items.single()
        assertEquals(RestoreAction.RESTORE_AS_COPY, item.action)
    }

    @Test
    fun `the name is taken case-insensitively`() {
        val e = entry(1, name = "Photo.JPG")
        val item = plan(listOf(e), listOf(local(900, "photo.jpg", size = 5))).items.single()
        assertEquals(RestoreAction.RESTORE_AS_COPY, item.action)
    }

    @Test
    fun `names that are taken twice get a numbered copy`() {
        val a = entry(1, name = "x.jpg", sha = sha(1))
        val b = entry(2, name = "x.jpg", sha = sha(2))
        val c = entry(3, name = "x.jpg", sha = sha(3))
        val items = plan(listOf(a, b, c), listOf(local(900, "x.jpg", size = 1))).items
        assertEquals(listOf("x (restored).jpg", "x (restored 2).jpg", "x (restored 3).jpg"), items.map { it.targetDisplayName })
        assertTrue(items.all { it.action == RestoreAction.RESTORE_AS_COPY })
    }

    @Test
    fun `two entries wanting the same new name do not collide with each other`() {
        val items = plan(listOf(entry(1, name = "dup.jpg"), entry(2, name = "dup.jpg"))).items
        assertEquals(RestoreAction.RESTORE, items[0].action)
        assertEquals(RestoreAction.RESTORE_AS_COPY, items[1].action)
        assertEquals(2, items.map { it.targetDisplayName }.distinct().size)
    }

    @Test
    fun `restoring twice writes nothing the second time, even for renamed copies`() {
        val a = entry(1, name = "x.jpg", sha = sha(1), size = 10)
        val b = entry(2, name = "x.jpg", sha = sha(2), size = 20)
        val first = plan(listOf(a, b))
        assertEquals(listOf("x.jpg", "x (restored).jpg"), first.items.map { it.targetDisplayName })

        // The phone now holds what the first run wrote.
        val after = first.items.mapIndexed { i, item ->
            local(1000L + i, item.targetDisplayName, item.targetRelativePath, item.entry.sizeBytes)
        }
        val hashes = first.items.mapIndexed { i, item -> 1000L + i to item.entry.sha256 }.toMap()
        val needed = RestorePlanner.candidatesToHash(listOf(a, b), after)
        assertEquals(2, needed.size)
        val second = plan(listOf(a, b), after, hashes)
        assertEquals(List(2) { RestoreAction.SKIP_ALREADY_HERE }, second.items.map { it.action })
        assertEquals(mapOf(a.mediaId to 1000L, b.mediaId to 1001L), second.alreadyHereIdMap())
    }

    @Test
    fun `only local files that could be a match need hashing`() {
        val e = entry(1, size = 500)
        val files = listOf(
            local(1, "IMG_1.jpg", size = 500), // same name and size
            local(2, "IMG_1 (restored).jpg", size = 500), // a renamed restore
            local(3, "IMG_1 (restored 7).jpg", size = 500),
            local(4, "IMG_1.jpg", size = 501), // other size
            local(5, "other.jpg", size = 500), // other name
            local(6, "IMG_1 (restored) 2.jpg", size = 500),
            local(7, "IMG_1.png", size = 500),
        )
        assertEquals(listOf(1L, 2L, 3L), RestorePlanner.candidatesToHash(listOf(e), files).map { it.mediaId })
    }

    // ---- hostile manifests ----

    @Test
    fun `traversal and absolute folders are sanitised`() {
        assertEquals("DCIM/Camera/", RestorePlanner.sanitiseRelativePath("DCIM/Camera/"))
        assertEquals("DCIM/x/", RestorePlanner.sanitiseRelativePath("../../DCIM/./x/../"))
        assertEquals("storage/emulated/0/DCIM/", RestorePlanner.sanitiseRelativePath("/storage/emulated/0/DCIM/"))
        assertEquals("DCIM/Camera/", RestorePlanner.sanitiseRelativePath("DCIM\\Camera\\"))
        assertEquals("", RestorePlanner.sanitiseRelativePath("../.."))
        assertEquals("", RestorePlanner.sanitiseRelativePath("/"))
        assertEquals("", RestorePlanner.sanitiseRelativePath(""))
        assertEquals("C_/Users/", RestorePlanner.sanitiseRelativePath("C:\\Users"))
        assertEquals("a_b/", RestorePlanner.sanitiseRelativePath("a*b"))
        assertFalse(RestorePlanner.sanitiseRelativePath("a/\u0000/b").contains('\u0000'))
        assertEquals(32, RestorePlanner.sanitiseRelativePath(List(100) { "d" }.joinToString("/")).trimEnd('/').split('/').size)
    }

    @Test
    fun `file names lose separators, control characters, hidden dots and lookalike overrides`() {
        val s = { raw: String -> RestorePlanner.sanitiseDisplayName(raw, 7) }
        assertEquals("passwd", s("../../etc/passwd"))
        assertEquals("evil.jpg", s("C:\\temp\\evil.jpg"))
        assertEquals("restored-7", s(".."))
        assertEquals("restored-7", s(""))
        assertEquals("restored-7", s("   "))
        assertEquals("restored-7", s("/"))
        assertEquals("_hidden.jpg", s(".hidden.jpg"))
        assertEquals("a_b.jpg", s("a\u0001b.jpg"))
        assertEquals("photo_gpj.exe", s("photo\u202Egpj.exe"))
        assertEquals("a_b_c.jpg", s("a:b|c.jpg"))
        assertEquals("trailing", s("trailing. . "))
    }

    @Test
    fun `a long name is cut on a character boundary under 255 bytes and copies still fit`() {
        val long = "é".repeat(200) + ".jpg" // 400 bytes of base
        val clean = RestorePlanner.sanitiseDisplayName(long, 1)
        assertTrue(clean.toByteArray().size <= 255)
        assertTrue("the extension survives the cut", clean.endsWith(".jpg"))
        assertFalse(clean.contains('\uFFFD'))
        val copy = RestorePlanner.restoredName(RestorePlanner.sanitiseDisplayName("x".repeat(300) + ".jpg", 1), 12)
        assertTrue(copy.toByteArray().size <= 255)
        assertTrue(copy.endsWith(" (restored 12).jpg"))
    }

    @Test
    fun `a truncated copy name is still recognised as a rename of the original`() {
        val name = RestorePlanner.sanitiseDisplayName("y".repeat(300) + ".jpg", 1)
        assertEquals(255, name.length)
        val copy = RestorePlanner.restoredName(name, 1)
        assertTrue(RestorePlanner.isRestoredNameOf(name, copy))
        assertFalse(RestorePlanner.isRestoredNameOf(name, "z" + copy))
    }

    @Test
    fun `a hostile entry cannot leave the media folders`() {
        val hostile = listOf(
            entry(1, name = "../../../../data/data/com.fotoxplorr.app/databases/x.jpg", dir = "../../../data/"),
            entry(2, name = "ok.jpg", dir = "/sdcard/DCIM/"),
            entry(3, name = "ok2.jpg", dir = "DCIM/../../root/"),
        )
        for (item in plan(hostile).toWrite) {
            assertFalse(item.targetRelativePath, item.targetRelativePath.contains(".."))
            assertFalse(item.targetRelativePath.startsWith("/"))
            assertFalse(item.targetDisplayName.contains('/'))
            assertTrue(item.targetRelativePath.isEmpty().not() && item.targetRelativePath.endsWith("/"))
            assertTrue(
                item.targetRelativePath,
                item.targetRelativePath.startsWith("DCIM/") || item.targetRelativePath.startsWith("Pictures/"),
            )
        }
        assertEquals("x.jpg", plan(hostile).items[0].targetDisplayName)
    }

    @Test
    fun `a folder the media store would refuse falls back to the app folder`() {
        assertEquals("Pictures/Fotoz/", plan(listOf(entry(1, dir = "Download/stuff/"))).items.single().targetRelativePath)
        assertEquals("Movies/Fotoz/", plan(listOf(entry(1, name = "v.mp4", mime = "video/mp4", dir = ""))).items.single().targetRelativePath)
        assertEquals("Pictures/Fotoz/", plan(listOf(entry(1, dir = "../.."))).items.single().targetRelativePath)
    }

    @Test
    fun `an entry named like a path component cannot be both the folder and the file`() {
        // "..", "." and empty names get a safe placeholder instead of escaping or vanishing.
        val items = plan(listOf(entry(1, name = ".."), entry(2, name = "."), entry(3, name = ""))).items
        assertEquals(listOf("restored-1", "restored-2", "restored-3"), items.map { it.targetDisplayName })
    }

    @Test
    fun `the plan is stable and sorted by entry id`() {
        val entries = (1L..20L).map { entry(it) }
        val a = plan(entries).items.map { it.entry.entryId }
        val b = plan(entries.reversed()).items.map { it.entry.entryId }
        assertEquals(a, b)
        assertEquals(entries.map { it.entryId }, a)
    }

    @Test
    fun `counts`() {
        val e = entry(1)
        val plan = plan(
            listOf(e, entry(2), entry(3, mime = "audio/mp3")),
            listOf(local(900, "IMG_1.jpg", size = e.sizeBytes)),
            mapOf(900L to e.sha256),
        )
        assertEquals(1, plan.count(RestoreAction.SKIP_ALREADY_HERE))
        assertEquals(1, plan.count(RestoreAction.RESTORE))
        assertEquals(1, plan.count(RestoreAction.SKIP_UNSUPPORTED))
        assertEquals(entry(2).sizeBytes, plan.bytesToWrite)
    }

    @Test
    fun `a type that is not a plain image or video type is not written`() {
        // The type goes straight into a MediaStore insert; anything that does not look like type/subtype is refused.
        val odd = listOf(
            "image/jpeg\u0000x",
            "image/ jpeg",
            "image/",
            "image",
            "image/jpeg; charset=x",
            "video/mp4\n",
            "image/" + "a".repeat(200),
        )
        for (mime in odd) assertNull(mime, RestorePlanner.kindOf(mime))
        assertEquals(MediaKind.IMAGE, RestorePlanner.kindOf("image/jpeg"))
        assertEquals(MediaKind.IMAGE, RestorePlanner.kindOf("IMAGE/HEIC"))
        assertEquals(MediaKind.IMAGE, RestorePlanner.kindOf("image/svg+xml"))
        assertEquals(MediaKind.VIDEO, RestorePlanner.kindOf("video/x-matroska"))
        assertEquals(RestoreAction.SKIP_UNSUPPORTED, plan(listOf(entry(1, mime = "image/jpeg\u0000x"))).items.single().action)
    }

    @Test
    fun `a local file carries the identity the metadata restore matches by`() {
        val file = RestoreLocalFile(1, "a.jpg", "DCIM/Camera/", 10, dateTakenMillis = 5, dateModifiedSeconds = 6)
        assertEquals(MediaIdentity("a.jpg", 10, 5, "DCIM/Camera/"), file.identity)
        assertNull(RestoreLocalFile(1, "a.jpg", "", 10).identity.relativePath)
        assertNull(RestoreLocalFile(1, "a.jpg", null, 10).identity.relativePath)
    }
}
