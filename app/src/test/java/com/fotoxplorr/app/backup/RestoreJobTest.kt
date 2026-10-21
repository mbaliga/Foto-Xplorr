package com.fotoxplorr.app.backup

import com.fotoxplorr.app.backup.BackupTestSupport.newKeys
import com.fotoxplorr.app.editor.JsonValue
import com.fotoxplorr.app.editor.asArray
import com.fotoxplorr.app.editor.asObject
import com.fotoxplorr.app.editor.get
import com.fotoxplorr.app.editor.parseJson
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * The restore end to end on the JVM, through the real layers: BackupJob writes a folder, the
 * loader opens it, the preparer plans, RestoreJob reads the packs through the cipher and writes to
 * an in-memory phone. The folder is a plain directory with 128-byte segments and packs of a few
 * files, so every boundary is crossed with a few hundred bytes of data.
 */
class RestoreJobTest {
    private lateinit var dir: File

    @Before
    fun folder() {
        dir = Files.createTempDirectory("fotoz-restore-test").toFile()
    }

    @After
    fun clean() {
        dir.deleteRecursively()
    }

    private fun backup(count: Int = 7, size: Int = 300, transform: (List<BackupCandidate>) -> List<BackupCandidate> = { it }) =
        RestoreFixtures.backup(dir, count, size, transform)

    private fun bytesOf(made: MadeBackup, id: Long) = made.source.bytes(id)

    private fun assertEveryRestoredPhotoIsExact(made: MadeBackup, phone: FakePhone) {
        val original = made.candidates.associate { it.displayName to bytesOf(made, it.mediaId) }
        for (photo in phone.photos.values) {
            val name = photo.name.replace(Regex(" \\(restored( \\d+)?\\)"), "")
            val expected = original[name] ?: continue
            assertTrue("${photo.name} is not the photo that was backed up", expected.contentEquals(photo.bytes))
        }
    }

    // ---- the round trip ----

    @Test
    fun `a backup restores onto an empty phone byte for byte, in its own folders, with new ids`() {
        val made = backup()
        val phone = FakePhone()
        val outcome = RestoreFixtures.restore(made, phone)

        assertEquals(7, outcome.restored)
        assertEquals(0, outcome.restoredAsCopy)
        assertTrue(outcome.failed.isEmpty())
        assertEquals(7, phone.photos.size)
        for (c in made.candidates) {
            val photo = phone.find(c.displayName).single()
            assertTrue(bytesOf(made, c.mediaId).contentEquals(photo.bytes))
            assertEquals("DCIM/Camera/", photo.dir)
            assertEquals(c.dateTakenMillis, photo.taken)
            assertTrue("new id, not the old one", photo.id >= 9_000)
        }
        assertEquals(made.candidates.sumOf { it.sizeBytes }, outcome.bytesWritten)
    }

    @Test
    fun `restoring again writes nothing, because every photo is already here`() {
        val made = backup()
        val phone = FakePhone()
        RestoreFixtures.restore(made, phone)
        val after = phone.photos.keys.toSet()

        val again = RestoreFixtures.restore(made, phone)

        assertEquals(0, again.restored + again.restoredAsCopy)
        assertEquals(7, again.alreadyHere)
        assertEquals(after, phone.photos.keys)
        assertTrue(phone.aborted.isEmpty())
    }

    @Test
    fun `a name taken by a different file gives a restored copy and the other file is untouched`() {
        val made = backup(count = 3)
        val phone = FakePhone()
        val theirs = BackupTestSupport.plaintext(77, seed = 5)
        phone.put(100, "IMG_2.jpg", "DCIM/Camera/", theirs)

        val outcome = RestoreFixtures.restore(made, phone)

        assertEquals(2, outcome.restored)
        assertEquals(1, outcome.restoredAsCopy)
        assertTrue("the person's own file is untouched", theirs.contentEquals(phone.photos.getValue(100).bytes))
        assertTrue(bytesOf(made, 2).contentEquals(phone.find("IMG_2 (restored).jpg").single().bytes))
        // ...and the copy is itself recognised as "already here" next time.
        val again = RestoreFixtures.restore(made, phone)
        assertEquals(0, again.restored + again.restoredAsCopy)
        assertEquals(3, again.alreadyHere)
    }

    @Test
    fun `a restore interrupted half way finishes on the next run without duplicates`() {
        val made = backup(count = 9)
        val phone = FakePhone()
        val applier = RecordingApplier(phone.events)
        val prep = RestoreFixtures.prepare(made.set(), phone)
        lateinit var run: Deferred<RestoreOutcome>
        var commits = 0
        phone.onCommit = { if (++commits == 4) run.cancel() }

        var thrown: Throwable? = null
        runBlocking {
            run = async(start = CoroutineStart.LAZY) { RestoreFixtures.job(made, phone, applier).run(prep) }
            try {
                run.await()
            } catch (e: Throwable) {
                thrown = e
            }
        }
        assertTrue("cancellation, not a failure message: $thrown", thrown is CancellationException)
        assertTrue("some photos came back", phone.photos.size in 4..8)
        assertTrue("no tags were applied from a half restore", applier.metadata.isEmpty())

        phone.onCommit = {}
        val outcome = RestoreFixtures.restore(made, phone, applier)

        assertEquals(9, phone.photos.size)
        assertEquals(9, phone.photos.values.map { it.name }.toSet().size)
        assertTrue(outcome.alreadyHere >= 4)
        assertEquals(9, outcome.alreadyHere + outcome.restored)
        assertEveryRestoredPhotoIsExact(made, phone)
        assertEquals(1, applier.metadata.size)
    }

    // ---- hostile and damaged input ----

    @Test
    fun `a manifest with hostile paths and names cannot climb out of the media folders`() {
        val made = backup(count = 3) { list ->
            listOf(
                list[0].copy(relativePath = "../../../data/data/com.fotoxplorr.app/", displayName = "../../evil.jpg"),
                list[1].copy(relativePath = "/etc/", displayName = "C:\\windows\\x.jpg"),
                list[2].copy(relativePath = "DCIM/./Camera//..//Trips/", displayName = "ok.jpg"),
            )
        }
        val phone = FakePhone()
        RestoreFixtures.restore(made, phone)

        assertEquals(3, phone.photos.size)
        for (photo in phone.photos.values) {
            assertFalse(photo.dir, photo.dir.contains(".."))
            assertFalse(photo.dir, photo.dir.startsWith("/"))
            assertFalse(photo.name, photo.name.contains('/') || photo.name.contains('\\'))
            assertTrue(photo.dir, photo.dir.startsWith("DCIM/") || photo.dir.startsWith("Pictures/"))
        }
        assertEquals("evil.jpg", phone.photos.values.first().name)
    }

    @Test
    fun `a wrong passphrase opens nothing and writes nothing`() {
        val made = backup(count = 3)
        val wrong = newKeys(passphrase = "a different passphrase entirely")
        try {
            runBlocking { BackupSetLoader.load(made.destination, wrong) }
            fail("a wrong passphrase must not open the backup")
        } catch (e: BackupException.WrongPassphraseOrDamaged) {
            // The restore flow stops here, before it plans or writes anything.
        } finally {
            wrong.close()
        }
    }

    @Test
    fun `a tampered pack never puts a wrong photo on the phone`() {
        val made = backup(count = 9)
        val pack = made.packs().first()
        val bytes = made.destination.bytes(pack)
        bytes[bytes.size * 3 / 4] = (bytes[bytes.size * 3 / 4].toInt() xor 0x5A).toByte()
        File(dir, pack).writeBytes(bytes)

        val phone = FakePhone()
        val outcome = RestoreFixtures.restore(made, phone)

        assertTrue("the damage is reported", outcome.failed.isNotEmpty())
        assertTrue(outcome.failed.all { it.reason == RestoreFailure.DAMAGED })
        assertTrue(outcome.needsAttention)
        assertEveryRestoredPhotoIsExact(made, phone)
        assertEquals(9, phone.photos.size + outcome.failed.size)
        // Other packs were restored in full.
        val damagedIds = outcome.failed.map { it.entry.entryId }.toSet()
        assertTrue(made.set().entries.filter { it.packName != pack }.none { it.entryId in damagedIds })
    }

    @Test
    fun `a pack cut short restores what is whole before the cut and reports the rest`() {
        val made = backup(count = 9)
        val pack = made.packs().last()
        val file = File(dir, pack)
        file.writeBytes(file.readBytes().copyOf((file.length() / 2).toInt()))

        val phone = FakePhone()
        val outcome = RestoreFixtures.restore(made, phone)

        assertTrue(outcome.failed.isNotEmpty())
        assertTrue(outcome.restored > 0)
        assertEveryRestoredPhotoIsExact(made, phone)
        assertEquals(9, phone.photos.size + outcome.failed.size)
    }

    @Test
    fun `a missing pack is reported by name count, and every other pack is restored`() {
        val made = backup(count = 9)
        val pack = made.packs()[1]
        val inPack = made.set().entries.count { it.packName == pack }
        assertTrue(inPack > 0)
        File(dir, pack).delete()

        val set = made.set()
        assertEquals(inPack, set.info.missing)
        val phone = FakePhone()
        val outcome = RestoreFixtures.restore(made, phone)

        assertEquals(inPack, outcome.missing)
        assertEquals(9 - inPack, outcome.restored)
        assertTrue(outcome.needsAttention)
        assertTrue(outcome.failed.isEmpty())
        assertEveryRestoredPhotoIsExact(made, phone)
    }

    @Test
    fun `a tampered manifest is skipped and the packs' own indexes still restore every photo`() {
        val made = backup(count = 5)
        val manifest = BackupLayout.manifestName(1)
        val bytes = made.destination.bytes(manifest)
        bytes[bytes.size - 20] = (bytes[bytes.size - 20].toInt() xor 0x33).toByte()
        File(dir, manifest).writeBytes(bytes)

        val loaded = made.loaded()
        assertNull("the damaged manifest is not used", loaded.manifest)
        assertEquals(1, loaded.skippedManifests)
        val set = RestoreSet.from(loaded)
        assertEquals(5, set.entries.size)
        assertNull("sections live only in a manifest", set.section(BackupSections.METADATA))

        val phone = FakePhone()
        val applier = RecordingApplier(phone.events)
        val prep = RestoreFixtures.prepare(set, phone)
        val outcome = runBlocking { RestoreFixtures.job(made, phone, applier).run(prep) }

        assertEquals(5, outcome.restored)
        assertTrue("no tags, because there was no manifest to read them from", applier.metadata.isEmpty())
        assertEveryRestoredPhotoIsExact(made, phone)
    }

    @Test
    fun `an entry whose bytes do not match the recorded checksum is thrown away, not kept`() {
        val made = backup(count = 3)
        // The manifest says photo 2 has a different checksum than the bytes in the pack.
        val set = made.set()
        val lie = set.entries.map { if (it.mediaId == 2L) it.copy(sha256 = "ab".repeat(32)) else it }
        val liar = RestoreSet(lie, null)
        val phone = FakePhone()
        val prep = runBlocking { RestorePreparer.prepare(liar, RestoreScope.EVERYTHING, phone, io = kotlinx.coroutines.Dispatchers.Unconfined) }
        val outcome = runBlocking { RestoreFixtures.job(made, phone, RecordingApplier(phone.events)).run(prep) }

        assertEquals(2, outcome.restored)
        assertEquals(listOf(RestoreFailure.DAMAGED), outcome.failed.map { it.reason })
        assertTrue("the half-checked file was thrown away", phone.aborted.any { it.endsWith("IMG_2.jpg") })
        assertTrue(phone.find("IMG_2.jpg").isEmpty())
    }

    // ---- the phone's side failing ----

    @Test
    fun `a file the phone refuses to create is reported and the rest carry on`() {
        val made = backup(count = 4)
        val phone = FakePhone()
        phone.refuseCreate = { it.targetDisplayName == "IMG_2.jpg" }
        val outcome = RestoreFixtures.restore(made, phone)
        assertEquals(3, outcome.restored)
        assertEquals(listOf(RestoreFailure.CANNOT_CREATE), outcome.failed.map { it.reason })
        assertEveryRestoredPhotoIsExact(made, phone)
    }

    @Test
    fun `a write that fails half way throws the file away and the next file is still restored`() {
        val made = backup(count = 4, size = 300)
        val phone = FakePhone()
        phone.failWriteAfter = { if (it.targetDisplayName == "IMG_2.jpg") 100L else null }
        val outcome = RestoreFixtures.restore(made, phone)

        assertEquals(3, outcome.restored)
        assertEquals(listOf(RestoreFailure.CANNOT_WRITE), outcome.failed.map { it.reason })
        assertTrue(phone.aborted.any { it.endsWith("IMG_2.jpg") })
        assertEquals(setOf("IMG_1.jpg", "IMG_3.jpg", "IMG_4.jpg"), phone.photos.values.map { it.name }.toSet())
        assertEveryRestoredPhotoIsExact(made, phone)
    }

    @Test
    fun `a full phone ends the run with a sentence, keeps what came back, and the next run finishes`() {
        val made = backup(count = 6, size = 300)
        val phone = FakePhone()
        phone.capacityBytes = 3 * 300L + 10
        val applier = RecordingApplier(phone.events)

        val prep = RestoreFixtures.prepare(made.set(), phone)
        try {
            runBlocking { RestoreFixtures.job(made, phone, applier).run(prep) }
            fail("a full phone must stop the restore")
        } catch (e: BackupRefused) {
            assertTrue(e.message!!, e.message!!.contains("storage is full"))
            assertTrue(e.message!!, e.message!!.contains("again"))
        }
        assertEquals(3, phone.photos.size)
        assertTrue("the file in flight was thrown away", phone.aborted.isNotEmpty())
        assertTrue("no tags from a restore that did not finish", applier.metadata.isEmpty())

        phone.capacityBytes = Long.MAX_VALUE
        val outcome = RestoreFixtures.restore(made, phone, applier)
        assertEquals(6, phone.photos.size)
        assertEquals(3, outcome.alreadyHere)
        assertEquals(3, outcome.restored)
        assertEveryRestoredPhotoIsExact(made, phone)
    }

    // ---- tags, pins, edits ----

    @Test
    fun `tags and collections are merged under the new ids`() {
        val made = backup(count = 4)
        val phone = FakePhone()
        val applier = RecordingApplier(phone.events)
        val outcome = RestoreFixtures.restore(made, phone, applier)

        val prepared = applier.metadata.single()
        val idOf = { name: String -> phone.find(name).single().id }
        val tags = prepared.library["tags"].asObject()
        assertEquals(setOf(idOf("IMG_1.jpg"), idOf("IMG_3.jpg")), tags.getValue("beach").asArray().map { (it as JsonValue.JsonNumber).value.toLong() }.toSet())
        val collection = prepared.library["collections"].asArray().single()
        assertEquals(setOf(idOf("IMG_1.jpg"), idOf("IMG_2.jpg")), collection["mediaIds"].asArray().map { (it as JsonValue.JsonNumber).value.toLong() }.toSet())
        assertEquals(setOf(idOf("IMG_2.jpg")), prepared.favoriteIds)
        assertEquals(setOf(idOf("IMG_3.jpg")), prepared.sensitiveIds)
        assertNotNull(outcome.metadata)
        assertEquals(0, outcome.metadata!!.photosMissing)
    }

    @Test
    fun `tags for a photo already on the phone under another id go to that id`() {
        val made = backup(count = 3)
        val phone = FakePhone()
        // Photo 1 is on the phone already, as photo 700.
        phone.put(700, "IMG_1.jpg", "DCIM/Camera/", bytesOf(made, 1), taken = made.candidates[0].dateTakenMillis, modified = 321)
        val applier = RecordingApplier(phone.events)
        val outcome = RestoreFixtures.restore(made, phone, applier)

        assertEquals(1, outcome.alreadyHere)
        assertEquals(2, outcome.restored)
        val tags = applier.metadata.single().library["tags"].asObject().getValue("beach").asArray().map { (it as JsonValue.JsonNumber).value.toLong() }
        assertTrue(700L in tags)
    }

    @Test
    fun `a tags-only restore matches photos by identity, reads no pack and writes no photo`() {
        val made = backup(count = 4)
        // The packs are not even needed: delete them all.
        made.packs().forEach { File(dir, it).delete() }
        val phone = FakePhone()
        made.candidates.take(3).forEachIndexed { i, c ->
            phone.put(500L + i, c.displayName, c.relativePath!!, bytesOf(made, c.mediaId), taken = c.dateTakenMillis)
        }
        val applier = RecordingApplier(phone.events)
        val set = RestoreSet.from(made.loaded())

        val prep = RestoreFixtures.prepare(set, phone, RestoreScope.TAGS_ONLY)
        assertEquals(3, prep.counts.matched)
        assertEquals(1, prep.counts.notOnPhone)
        val outcome = runBlocking { RestoreFixtures.job(made, phone, applier).run(prep) }

        assertEquals(3, phone.photos.size)
        assertEquals(0, outcome.restored)
        assertEquals(listOf("metadata"), phone.events)
        assertEquals(1, applier.metadata.size)
        assertTrue(applier.locks.isEmpty() && applier.pins.isEmpty() && applier.recipes.isEmpty())
        assertEquals(1, outcome.notOnPhone)
    }

    @Test
    fun `a failure to apply the tags is reported and does not undo the photos`() {
        val made = backup(count = 3)
        val phone = FakePhone()
        val applier = RecordingApplier(phone.events).also { it.failMetadata = true }
        val outcome = RestoreFixtures.restore(made, phone, applier)
        assertEquals(3, outcome.restored)
        assertTrue(outcome.metadataFailed)
        assertTrue(outcome.needsAttention)
        assertEquals(3, phone.photos.size)
    }

    @Test
    fun `map pins follow the new ids and saved edits follow the new revision`() {
        val recipe = parseJson("""{"quarterTurns":1}""")!!
        val made = RestoreFixtures.backup(
            dir = dir,
            count = 3,
            sections = { list ->
                RestoreFixtures.sections(
                    list,
                    pins = listOf(BackupSections.Pin(2, 51.5, -0.12), BackupSections.Pin(99, 1.0, 1.0)),
                    recipes = listOf(
                        BackupSections.Recipe(2, list[1].dateModifiedSeconds, recipe),
                        BackupSections.Recipe(3, 5, recipe), // a revision the file no longer has
                    ),
                )
            },
        )
        val phone = FakePhone()
        // Photo 3 is already here, with its own revision.
        phone.put(800, "IMG_3.jpg", "DCIM/Camera/", bytesOf(made, 3), modified = 4_242)
        val applier = RecordingApplier(phone.events)
        val outcome = RestoreFixtures.restore(made, phone, applier)

        val newTwo = phone.find("IMG_2.jpg").single()
        assertEquals(listOf(newTwo.id), applier.pins.map { it.mediaId })
        assertEquals(1, outcome.pins)
        // Photo 2's recipe is stored against the revision the restored file has; photo 3's is stale and dropped.
        assertEquals(listOf(newTwo.id to newTwo.modified), applier.recipes.map { it.mediaId to it.dateModifiedSeconds })
    }

    @Test
    fun `a sensitive photo is marked as soon as it has its new id, long before the tags arrive`() {
        val made = backup(count = 6) { list -> list.map { if (it.mediaId == 1L || it.mediaId == 2L) it.copy(isSensitive = true) else it } }
        val phone = FakePhone()
        val applier = RecordingApplier(phone.events)
        RestoreFixtures.restore(made, phone, applier)

        val marked = applier.sensitive.flatten().toSet()
        assertEquals(setOf(phone.find("IMG_1.jpg").single().id, phone.find("IMG_2.jpg").single().id), marked)
        val firstMark = phone.events.indexOf("sensitive")
        assertTrue("marked while files were still being written", firstMark > 0)
        assertTrue(firstMark < phone.events.indexOf("metadata"))
        assertTrue("and before the last photo came back", firstMark < phone.events.indexOfLast { it.startsWith("write:") })
    }

    @Test
    fun `sensitive marks reach the store even when the restore stops half way`() {
        val made = backup(count = 6) { list -> list.map { it.copy(isSensitive = true) } }
        val phone = FakePhone()
        phone.capacityBytes = 2 * 300L + 10
        val applier = RecordingApplier(phone.events)
        val prep = RestoreFixtures.prepare(made.set(), phone)
        try {
            runBlocking { RestoreFixtures.job(made, phone, applier).run(prep) }
            fail("a full phone must stop the restore")
        } catch (e: BackupRefused) {
            // expected
        }
        assertEquals("the two photos that came back are hidden", phone.photos.keys, applier.sensitive.flatten().toSet())
    }

    @Test
    fun `a photo that was not sensitive is never marked`() {
        val made = backup(count = 3)
        val phone = FakePhone()
        val applier = RecordingApplier(phone.events)
        RestoreFixtures.restore(made, phone, applier)
        assertTrue(applier.sensitive.isEmpty())
    }

    // ---- folder locks ----

    private val privateLock = BackupSections.Lock("path:dcim/private", "c2FsdHNhbHRzYWx0c2FsdA==", "aGFzaGhhc2hoYXNoaGFzaGhhc2hoYXNoaGFzaGhhc2g=")

    private fun protectedBackup(path: String): MadeBackup = RestoreFixtures.backup(
        dir = dir,
        count = 4,
        transform = { list -> list.map { if (it.mediaId <= 2) it.copy(relativePath = path, isProtected = true) else it } },
        sections = { list -> RestoreFixtures.sections(list, locks = listOf(BackupSections.Lock(ProtectedPlacement.lockKey(path), privateLock.saltBase64, privateLock.hashBase64))) },
    )

    @Test
    fun `locks are applied before the first protected photo is written`() {
        val made = protectedBackup("DCIM/Private/")
        val phone = FakePhone()
        val applier = RecordingApplier(phone.events)
        val outcome = RestoreFixtures.restore(made, phone, applier)

        assertEquals(4, outcome.restored)
        assertEquals(listOf("path:dcim/private"), applier.locks.single().map { it.folderKey })
        assertEquals("locks", phone.events.first())
        assertEquals("DCIM/Private/", phone.find("IMG_1.jpg").single().dir)
        assertEquals(1, outcome.locks!!.added)
    }

    @Test
    fun `a protected photo that cannot return to its folder gets a protected folder of its own with the same lock`() {
        val made = protectedBackup("Download/Private/")
        val phone = FakePhone()
        val applier = RecordingApplier(phone.events)
        RestoreFixtures.restore(made, phone, applier)

        val own = "Pictures/Fotoz/Restored protected/Download/Private/"
        assertEquals(own, phone.find("IMG_1.jpg").single().dir)
        assertEquals(own, phone.find("IMG_2.jpg").single().dir)
        assertEquals("DCIM/Camera/", phone.find("IMG_3.jpg").single().dir)
        val applied = applier.locks.single().single()
        assertEquals(ProtectedPlacement.lockKey(own), applied.folderKey)
        assertEquals(privateLock.saltBase64, applied.saltBase64)
        assertEquals(privateLock.hashBase64, applied.hashBase64)
        assertEquals("locks", phone.events.first())
    }

    @Test
    fun `a folder with photos in it and no lock here is not locked by a restore`() {
        val made = protectedBackup("DCIM/Private/")
        val phone = FakePhone()
        phone.put(50, "mine.jpg", "DCIM/Private/", BackupTestSupport.plaintext(40, seed = 9))
        val applier = RecordingApplier(phone.events)
        RestoreFixtures.restore(made, phone, applier)

        val own = "Pictures/Fotoz/Restored protected/DCIM/Private/"
        assertEquals(own, phone.find("IMG_1.jpg").single().dir)
        assertEquals(ProtectedPlacement.lockKey(own), applier.locks.single().single().folderKey)
    }

    @Test
    fun `before Android 10 a protected photo is left out rather than restored where it would be visible`() {
        val made = protectedBackup("DCIM/Private/")
        val phone = FakePhone(canChooseFolder = false)
        val applier = RecordingApplier(phone.events)
        val outcome = RestoreFixtures.restore(made, phone, applier)

        assertEquals(2, outcome.restored)
        assertEquals(2, outcome.skipped)
        assertTrue(phone.find("IMG_1.jpg").isEmpty() && phone.find("IMG_2.jpg").isEmpty())
        assertTrue(applier.locks.isEmpty())
        assertTrue(outcome.needsAttention)
    }

    @Test
    fun `a protected photo whose lock is not in the backup is restored and counted`() {
        val made = RestoreFixtures.backup(
            dir = dir,
            count = 3,
            transform = { list -> list.map { if (it.mediaId == 1L) it.copy(isProtected = true) else it } },
        )
        val phone = FakePhone()
        val outcome = RestoreFixtures.restore(made, phone)
        assertEquals(3, outcome.restored)
        assertEquals(1, outcome.protectedWithoutLock)
        assertTrue(outcome.needsAttention)
    }

    // ---- what the person is told ----

    @Test
    fun `the job title says nothing about files or protected photos`() {
        assertFalse(RestoreCopy.JOB_TITLE.any { it.isDigit() })
        assertFalse(RestoreCopy.JOB_TITLE.contains("protected", ignoreCase = true))
        assertFalse(RestoreCopy.JOB_TITLE.contains(".jpg"))
    }
}
