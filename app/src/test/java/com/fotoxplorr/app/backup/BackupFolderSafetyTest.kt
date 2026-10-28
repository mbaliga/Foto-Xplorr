package com.fotoxplorr.app.backup

import com.fotoxplorr.app.backup.BackupTestSupport.newKeys
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * A partial or still-loading listing must never lead to a second backup set written beside the
 * real one, and "remove the leftovers" must never delete a file of the real set. The folder is a
 * plain directory whose listing can be made to lie, the way a cloud-backed folder's does while it
 * syncs.
 */
class BackupFolderSafetyTest {
    private lateinit var dir: File
    private lateinit var destination: DirectoryDestination
    private lateinit var candidates: List<BackupCandidate>
    private lateinit var source: FakeSource

    @Before
    fun backedUpFolder() {
        dir = Files.createTempDirectory("fotoz-safety-test").toFile()
        destination = DirectoryDestination(dir)
        val (c, s) = JobFixtures.library(6)
        candidates = c
        source = s
        val keys = newKeys()
        val prep = runBlocking { BackupPreparer.prepare(destination, keys, candidates, BackupOptions(), source, JobFixtures.tinyLimits) }
        keys.close()
        runBlocking { BackupJob(destination, source, newKeys(), "test", io = Dispatchers.Unconfined).run(prep) }
    }

    @After
    fun clean() {
        dir.deleteRecursively()
    }

    private fun load(keys: BackupKeys = newKeys()): LoadedSet = try {
        runBlocking { BackupSetLoader.load(destination, keys) }
    } finally {
        keys.close()
    }

    private fun foreignKeys() = BackupTestSupport.newKeys(BackupTestSupport.otherSalt, "the passphrase of some other backup")

    private fun write(name: String, bytes: ByteArray) = File(dir, name).writeBytes(bytes)

    private fun foreignFile(kind: BackupKind) = BackupTestSupport.seal(ByteArray(300) { 5 }, keys = foreignKeys(), kind = kind)

    // ---- 1: a listing taken while the folder was loading ----

    @Test
    fun `a listing that was empty while the folder loaded cannot start a second set beside the real one`() {
        val before = destination.snapshot()

        destination.listingOverride = emptyList()
        // What the old SAF layer would have handed back: the folder looks empty, so the person is
        // offered "Choose a passphrase". (SafListing now waits or fails instead; see SafListingTest.)
        assertEquals(SetState.Empty, BackupSetLoader.inspect(destination))

        // By the time the job lists again the real files are there, with the old set's salt.
        destination.listingOverride = null
        val fresh = BackupKeys.newSet("a brand new passphrase".toCharArray(), BackupFormat.MIN_ITERATIONS)
        try {
            runBlocking { BackupPreparer.prepare(destination, fresh, candidates, BackupOptions(), source, JobFixtures.tinyLimits) }
            fail("a new set must not be started beside the existing one")
        } catch (e: BackupException.AnotherBackupHere) {
            assertTrue(e.message, e.message!!.contains("already holds a backup"))
        } finally {
            fresh.close()
        }
        assertEquals("nothing was written or removed", before, destination.snapshot())
    }

    @Test
    fun `a partial listing with only some packs of the real set is refused for a new passphrase too`() {
        val somePacks = destination.list().filter { BackupLayout.isPackName(it.name) }.take(1)
        destination.listingOverride = somePacks
        assertTrue(BackupSetLoader.inspect(destination) is SetState.Existing)
        // ...and if the person had been shown Empty and chosen a new passphrase, the load refuses.
        val fresh = BackupKeys.newSet("another brand new passphrase".toCharArray(), BackupFormat.MIN_ITERATIONS)
        try {
            runBlocking { BackupSetLoader.load(destination, fresh) }
            fail()
        } catch (e: BackupException.AnotherBackupHere) {
            // expected
        } finally {
            fresh.close()
        }
    }

    @Test
    fun `a folder that cannot be read is an error, not an empty folder`() {
        destination.listFailure = FolderNotReadyException("The folder is still loading.")
        try {
            BackupSetLoader.inspect(destination)
            fail("an unreadable folder must not look empty")
        } catch (e: FolderNotReadyException) {
            // expected
        }
    }

    @Test
    fun `the right passphrase still opens the set and a wrong one is still a wrong passphrase`() {
        assertTrue(load().manifest != null)
        val wrong = newKeys(passphrase = "not the passphrase at all")
        try {
            runBlocking { BackupSetLoader.load(destination, wrong) }
            fail()
        } catch (e: BackupException.WrongPassphraseOrDamaged) {
            // The salt is the set's own, so this is a wrong guess, not another backup.
        } finally {
            wrong.close()
        }
    }

    // ---- foreign files are never leftovers ----

    @Test
    fun `files of another set are left alone and are not leftovers`() {
        write(BackupLayout.packName(50), foreignFile(BackupKind.PACK))
        write(BackupLayout.indexName(60), foreignFile(BackupKind.PACK_INDEX)) // an index with no pack
        write(BackupLayout.packName(51), foreignFile(BackupKind.PACK))
        write(BackupLayout.indexName(51), foreignFile(BackupKind.PACK_INDEX)) // a pack with its index

        val loaded = load()
        assertEquals(
            setOf(BackupLayout.packName(50), BackupLayout.indexName(60), BackupLayout.packName(51), BackupLayout.indexName(51)),
            loaded.foreignFiles.map { it.name }.toSet(),
        )
        assertTrue("nothing of another set is offered for deletion: ${loaded.leftovers.map { it.name }}", loaded.leftovers.isEmpty())
        assertTrue(60 in loaded.reservedPackNumbers)
    }

    @Test
    fun `a genuine pack under a manifest's name is foreign to the loader and the set still opens`() {
        val ourPack = destination.bytes(destination.names().first { BackupLayout.isPackName(it) })
        write(BackupLayout.manifestName(9), ourPack)
        val loaded = load()
        assertTrue(BackupLayout.manifestName(9) in loaded.foreignFiles.map { it.name })
        assertEquals(1, loaded.manifest!!.header.generation)
        assertEquals(10, loaded.nextGeneration)
    }

    @Test
    fun `a stray pack of our own set is a leftover and a file that is not a backup file is neither`() {
        write(BackupLayout.packName(70), BackupTestSupport.seal(ByteArray(300), keys = newKeys(), kind = BackupKind.PACK))
        write("holiday.jpg", ByteArray(10))
        val loaded = load()
        assertEquals(listOf(BackupLayout.packName(70)), loaded.leftovers.map { it.name })
        assertTrue(loaded.foreignFiles.isEmpty())
    }

    // ---- 1c: removing leftovers re-checks ----

    @Test
    fun `removing leftovers deletes only the shown files that are still leftovers`() {
        write(BackupLayout.packName(70), BackupTestSupport.seal(ByteArray(300), keys = newKeys(), kind = BackupKind.PACK))
        write(BackupLayout.packName(71), foreignFile(BackupKind.PACK))
        val realPack = destination.names().first { BackupLayout.isPackName(it) && BackupLayout.packNumber(it)!! < 50 }
        val manifest = BackupLayout.manifestName(1)
        val before = destination.snapshot()

        val keys = newKeys()
        val removed = runBlocking {
            // The person was (wrongly) shown more than the leftovers; only a current leftover goes.
            LeftoverRemoval.remove(destination, keys, listOf(BackupLayout.packName(70), BackupLayout.packName(71), realPack, manifest))
        }
        keys.close()

        assertEquals(listOf(BackupLayout.packName(70)), removed)
        assertEquals(before - BackupLayout.packName(70), destination.snapshot())
    }

    @Test
    fun `files that looked like leftovers in a stale listing but belong to the set are kept`() {
        // The first listing hides the manifest and the indexes (a folder still syncing): every pack
        // looks like a leftover, because only a pack's first segment proves the key.
        val hidden = destination.names().filter { BackupLayout.isIndexName(it) || BackupLayout.manifestGeneration(it) != null }.toSet()
        destination.listingOverride = destination.list().filter { it.name !in hidden }
        val shown = load().leftovers.map { it.name }
        assertTrue("the stale listing shows packs as leftovers", shown.isNotEmpty() && shown.all { BackupLayout.isPackName(it) })

        // The folder finishes syncing before the person confirms.
        destination.listingOverride = null
        val before = destination.snapshot()
        val keys = newKeys()
        val removed = runBlocking { LeftoverRemoval.remove(destination, keys, shown) }
        keys.close()

        assertTrue(removed.isEmpty())
        assertEquals(before, destination.snapshot())
        assertTrue(load().manifest != null)
    }

    @Test
    fun `a removal that cannot re-list deletes nothing`() {
        write(BackupLayout.packName(70), BackupTestSupport.seal(ByteArray(300), keys = newKeys(), kind = BackupKind.PACK))
        destination.listFailure = IOException("The folder could not be read")
        val keys = newKeys()
        try {
            runBlocking { LeftoverRemoval.remove(destination, keys, listOf(BackupLayout.packName(70))) }
            fail()
        } catch (e: IOException) {
            // expected
        } finally {
            keys.close()
        }
        assertTrue(File(dir, BackupLayout.packName(70)).exists())
        assertFalse(destination.deleted.contains(BackupLayout.packName(70)))
    }

    // ---- 2: a complete pack is never offered for deletion on the strength of a snapshot that was not read ----

    /** A second run, so there are packs (and indexes) that only the second manifest lists. */
    private fun secondRun(): List<String> {
        val before = destination.names().filter { BackupLayout.isPackName(it) }.toSet()
        val (more, moreSource) = JobFixtures.library(10)
        val keys = newKeys()
        val prep = runBlocking { BackupPreparer.prepare(destination, keys, more, BackupOptions(), moreSource, JobFixtures.tinyLimits) }
        keys.close()
        runBlocking { BackupJob(destination, moreSource, newKeys(), "test", io = Dispatchers.Unconfined).run(prep) }
        return destination.names().filter { BackupLayout.isPackName(it) && it !in before }.sorted()
    }

    private fun cutNewestManifest() {
        val newest = File(dir, BackupLayout.manifestName(2))
        newest.writeBytes(newest.readBytes().copyOf(BackupFormat.HEADER_SIZE + 40))
    }

    private fun cutShort(pack: String) {
        val file = File(dir, pack)
        file.writeBytes(file.readBytes().copyOf(file.length().toInt() - 10))
    }

    private fun indexOf(pack: String) = BackupLayout.indexName(BackupLayout.packNumber(pack)!!)

    @Test
    fun `a pack whose valid index disagrees about its length is neither recovered nor a leftover`() {
        val pack = secondRun().first()
        File(dir, BackupLayout.manifestName(2)).delete()
        cutShort(pack)

        val loaded = load()
        assertFalse(pack in loaded.recovered.map { it.pack.name })
        assertTrue("not offered for deletion: ${loaded.leftovers.map { it.name }}", loaded.leftovers.none { it.name == pack || it.name == indexOf(pack) })
        assertEquals(setOf(pack, indexOf(pack)), loaded.unverified.map { it.name }.toSet())
        assertTrue(loaded.foreignFiles.isEmpty())
    }

    @Test
    fun `removing leftovers never deletes an unverified pack, even when it is named`() {
        val pack = secondRun().first()
        File(dir, BackupLayout.manifestName(2)).delete()
        cutShort(pack)
        val before = destination.snapshot()

        val keys = newKeys()
        val removed = runBlocking { LeftoverRemoval.remove(destination, keys, listOf(pack, indexOf(pack))) }
        keys.close()

        assertTrue(removed.isEmpty())
        assertEquals(before, destination.snapshot())
    }

    @Test
    fun `while the newest snapshot cannot be read the leftovers are reported but nothing is removable`() {
        val pack = secondRun().first()
        cutNewestManifest()
        cutShort(pack)
        write(BackupLayout.packName(77), BackupTestSupport.seal(ByteArray(300), keys = newKeys(), kind = BackupKind.PACK))

        val loaded = load()
        assertEquals(1, loaded.skippedManifests)
        assertTrue("the stray pack is still reported", loaded.leftovers.any { it.name == BackupLayout.packName(77) })
        assertFalse(loaded.leftoversRemovable)
        assertTrue(loaded.leftovers.none { it.name == pack })

        val before = destination.snapshot()
        val keys = newKeys()
        try {
            runBlocking { LeftoverRemoval.remove(destination, keys, loaded.leftovers.map { it.name } + pack) }
            fail("nothing may be removed without a snapshot to prove what is unused")
        } catch (e: BackupRefused) {
            // expected
        } finally {
            keys.close()
        }
        assertEquals(before, destination.snapshot())
        assertTrue(destination.deleted.isEmpty())
    }

    @Test
    fun `when no manifest can be read at all the packs are not removable either`() {
        // The only manifest is cut short: every pack is "not listed", which proves nothing.
        cutManifest(1)
        val loaded = load()
        assertTrue(loaded.manifest == null)
        assertFalse(loaded.leftoversRemovable)
        val before = destination.snapshot()
        val keys = newKeys()
        try {
            runBlocking { LeftoverRemoval.remove(destination, keys, loaded.leftovers.map { it.name }) }
            fail()
        } catch (e: BackupRefused) {
            // expected
        } finally {
            keys.close()
        }
        assertEquals(before, destination.snapshot())
    }

    @Test
    fun `with a whole snapshot the leftovers stay removable`() {
        write(BackupLayout.packName(70), BackupTestSupport.seal(ByteArray(300), keys = newKeys(), kind = BackupKind.PACK))
        val loaded = load()
        assertTrue(loaded.leftoversRemovable)
        assertTrue(loaded.unverified.isEmpty())
    }

    @Test
    fun `the preflight says unverified files are left alone and that leftovers stay while the snapshot is unread`() {
        val text = BackupCopy.preflight(preflightFacts(unverified = 2, leftoversRemovable = false, leftovers = 1)).map { it.text }
        assertTrue(text.toString(), text.any { it.startsWith("2 files in this folder do not match the backup that lists them") && it.contains("left alone") })
        assertTrue(text.toString(), text.any { it.contains("1 leftover file") && !it.contains("unless you remove them") })
        val plain = BackupCopy.preflight(preflightFacts(unverified = 0, leftoversRemovable = true, leftovers = 1)).map { it.text }
        assertTrue(plain.toString(), plain.any { it.contains("They stay unless you remove them") })
        assertTrue(plain.none { it.contains("do not match") })
    }

    private fun cutManifest(generation: Int) {
        val file = File(dir, BackupLayout.manifestName(generation))
        file.writeBytes(file.readBytes().copyOf(BackupFormat.HEADER_SIZE + 40))
    }

    private fun preflightFacts(unverified: Int, leftoversRemovable: Boolean, leftovers: Int) = BackupCopy.PreflightFacts(
        newFiles = 3, newSourceBytes = 3_000_000, estimatedBytes = 3_100_000, packs = 1, alreadyBackedUp = 2,
        tooLargeNames = emptyList(), tooLargeCount = 0, leftoverCount = leftovers, leftoverBytes = 1_000, freeBytes = 64_000_000_000,
        photosSeen = 5, locationApplicable = true, locationGranted = true, onPhoneStorage = false, managedByAnotherApp = false,
        batteryLowAndNotCharging = false, newSet = false, unverifiedCount = unverified, leftoversRemovable = leftoversRemovable,
    )
}
