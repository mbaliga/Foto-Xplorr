package com.fotoxplorr.app.backup

import com.fotoxplorr.app.backup.BackupTestSupport.newKeys
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * What a folder is, before and after a key is involved: empty, a set, a newer version's, junk; and
 * that a wrong passphrase is refused while a folder of leftovers that never opened with any key is
 * not mistaken for one.
 */
class BackupSetLoaderTest {
    private lateinit var dir: File
    private lateinit var destination: DirectoryDestination

    @Before
    fun folder() {
        dir = Files.createTempDirectory("fotoz-loader-test").toFile()
        destination = DirectoryDestination(dir)
    }

    @After
    fun clean() {
        dir.deleteRecursively()
    }

    private fun inspect() = BackupSetLoader.inspect(destination)

    private fun load(keys: BackupKeys = newKeys()): LoadedSet = try {
        runBlocking { BackupSetLoader.load(destination, keys) }
    } finally {
        keys.close()
    }

    private fun backedUp(count: Int = 4) {
        val (candidates, source) = JobFixtures.library(count)
        val keys = newKeys()
        val prep = runBlocking { BackupPreparer.prepare(destination, keys, candidates, BackupOptions(), source, JobFixtures.tinyLimits) }
        keys.close()
        runBlocking { BackupJob(destination, source, newKeys(), "test", io = Dispatchers.Unconfined).run(prep) }
    }

    private fun write(name: String, bytes: ByteArray) = File(dir, name).writeBytes(bytes)

    @Test
    fun `an empty folder, or one with only other people's files, is empty`() {
        assertEquals(SetState.Empty, inspect())
        write("holiday.jpg", ByteArray(40))
        write("notes.txt", ByteArray(3))
        assertEquals(SetState.Empty, inspect())
    }

    @Test
    fun `a finished backup is a set whose header carries the salt and the rounds`() {
        backedUp()
        val state = inspect()
        assertTrue(state is SetState.Existing)
        val header = (state as SetState.Existing).header
        assertEquals(BackupFormat.MIN_ITERATIONS, header.iterations)
        assertTrue(header.setSalt.contentEquals(BackupTestSupport.salt))
    }

    @Test
    fun `a pack that died as it was created does not make the folder unusable`() {
        write(BackupLayout.packName(1), ByteArray(0))
        assertEquals(SetState.Empty, inspect())
        write(BackupLayout.packName(2), ByteArray(20) { 1 })
        assertEquals(SetState.Empty, inspect())
        // ...and a run with fresh keys is allowed into it; the broken packs are leftovers.
        val loaded = load()
        assertEquals(setOf(BackupLayout.packName(1), BackupLayout.packName(2)), loaded.leftovers.map { it.name }.toSet())
        assertEquals(1 + 2, loaded.packFileNames.map { BackupLayout.packNumber(it)!! }.sum())
    }

    @Test
    fun `an index with no pack beside it does not make a set`() {
        write(BackupLayout.indexName(5), ByteArray(100) { 3 })
        assertEquals(SetState.Empty, inspect())
        val loaded = load()
        assertTrue(5 in loaded.reservedPackNumbers)
    }

    @Test
    fun `a manifest from a newer version of Fotoz is said to be newer`() {
        val header = BackupHeader(BackupKind.MANIFEST, BackupFormat.MIN_ITERATIONS, BackupTestSupport.salt, BackupTestSupport.SEGMENT).encode()
        header[8] = 0
        header[9] = 2 // version 2
        write(BackupLayout.manifestName(1), header + ByteArray(64))
        val state = inspect()
        assertTrue(state is SetState.Unreadable)
        assertTrue((state as SetState.Unreadable).reason.contains("newer version"))
    }

    @Test
    fun `a manifest that is not a backup file at all is unreadable and says to choose another folder`() {
        write(BackupLayout.manifestName(1), ByteArray(200) { 9 })
        val state = inspect()
        assertTrue(state is SetState.Unreadable)
        assertTrue((state as SetState.Unreadable).reason.contains("another folder"))
    }

    @Test
    fun `a wrong passphrase is refused even when the manifest and indexes are gone and only packs remain`() {
        backedUp()
        destination.names().filter { BackupLayout.isIndexName(it) || BackupLayout.manifestGeneration(it) != null }.forEach { File(dir, it).delete() }

        val wrong = BackupTestSupport.newKeys(passphrase = "this is the wrong passphrase")
        try {
            runBlocking { BackupSetLoader.load(destination, wrong) }
            fail("keys that opened nothing must not be allowed to write into a set")
        } catch (e: BackupException.WrongPassphraseOrDamaged) {
            // expected
        } finally {
            wrong.close()
        }
        // The right keys open the first segment of a pack, so the run may carry on; the packs are leftovers.
        val loaded = load()
        assertTrue(loaded.leftovers.isNotEmpty())
        assertTrue(loaded.leftovers.all { BackupLayout.isPackName(it.name) })
    }

    @Test
    fun `a pack of another set is not this set's and says nothing about the passphrase`() {
        val foreign = java.io.ByteArrayOutputStream()
        val other = BackupTestSupport.newKeys(BackupTestSupport.otherSalt, "another set's passphrase")
        BackupStreams.encrypt(foreign, BackupKind.PACK, other, BackupTestSupport.SEGMENT).use { it.write(ByteArray(300)) }
        write(BackupLayout.packName(1), foreign.toByteArray())
        val loaded = load()
        assertEquals(listOf(BackupLayout.packName(1)), loaded.leftovers.map { it.name })
    }

    @Test
    fun `the next generation is one past the highest manifest, even one that cannot be read`() {
        backedUp()
        write(BackupLayout.manifestName(7), ByteArray(10))
        val loaded = load()
        assertEquals(7, loaded.highestGeneration)
        assertEquals(8, loaded.nextGeneration)
        assertEquals(1, loaded.manifest!!.header.generation)
    }
}
