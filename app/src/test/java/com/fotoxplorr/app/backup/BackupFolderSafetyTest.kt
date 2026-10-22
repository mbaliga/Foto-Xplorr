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
}
