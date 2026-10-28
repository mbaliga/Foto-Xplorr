package com.fotoxplorr.app.backup

import com.fotoxplorr.app.backup.BackupTestSupport.newKeys
import com.fotoxplorr.app.editor.parseJson
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The scenario of a wiped phone: the person runs "Back up everything" into the existing folder
 * before restoring. The run writes its own (empty) sections; the manifest must still carry the
 * backup's tags, locks, pins and edits.
 */
class BackupJobSectionsTest {
    private lateinit var dir: File
    private lateinit var destination: DirectoryDestination

    @Before
    fun folder() {
        dir = Files.createTempDirectory("fotoz-sections-test").toFile()
        destination = DirectoryDestination(dir)
    }

    @After
    fun clean() {
        dir.deleteRecursively()
    }

    private fun run(count: Int, sections: List<ManifestSection>) {
        val (candidates, source) = JobFixtures.library(count)
        val keys = newKeys()
        val prep = runBlocking { BackupPreparer.prepare(destination, keys, candidates, BackupOptions(), source, JobFixtures.tinyLimits) }
        keys.close()
        runBlocking { BackupJob(destination, source, newKeys(), "test", sections = { sections }, io = Dispatchers.Unconfined).run(prep) }
    }

    private fun newest(): BackupManifest {
        val keys = newKeys()
        return try {
            checkNotNull(runBlocking { BackupSetLoader.load(destination, keys) }.manifest)
        } finally {
            keys.close()
        }
    }

    private fun sections(withData: Boolean): List<ManifestSection> {
        val identities = if (withData) mapOf(1L to MediaIdentity("IMG_1.jpg", 300, 1_700_000_000_001, "DCIM/Camera/")) else emptyMap()
        val library = checkNotNull(parseJson(if (withData) """{"schema":1,"tags":{"beach":[1]}}""" else """{"schema":1}"""))
        val file = MetadataBackup.buildFile(library, emptySet(), emptySet(), identities, 1).json
        return BackupSections.build(
            file,
            locks = if (withData) listOf(BackupSections.Lock("path:dcim/private", "c2FsdA==", "aGFzaA==")) else emptyList(),
            pins = if (withData) listOf(BackupSections.Pin(1, 1.0, 2.0)) else emptyList(),
            recipes = emptyList(),
        )
    }

    @Test
    fun `a run on a phone with no tags or locks keeps the ones the backup already had`() {
        run(4, sections(withData = true))
        run(4, sections(withData = false)) // the wiped phone's run: nothing new to copy, empty sections

        val manifest = newest()
        assertEquals(2, manifest.header.generation)
        assertEquals(listOf("path:dcim/private"), BackupSections.locks(manifest.section(BackupSections.LOCKS)).map { it.folderKey })
        assertEquals(listOf(1L), BackupSections.pins(manifest.section(BackupSections.PINS)).map { it.mediaId })
        val file = (MetadataBackup.readTree(manifest.section(BackupSections.METADATA)) as MetadataBackup.FileRead.Usable).file
        assertTrue(MetadataBackup.mentionedIds(file.envelope).contains(1L))
        assertTrue(SectionMerge.sizeOf(manifest.sections).locks == 1)
    }

    @Test
    fun `the first run writes exactly the sections it was given`() {
        run(3, sections(withData = true))
        val manifest = newest()
        assertEquals(listOf(BackupSections.METADATA, BackupSections.LOCKS, BackupSections.PINS, BackupSections.RECIPES), manifest.sections.map { it.name })
        assertEquals(1, BackupSections.locks(manifest.section(BackupSections.LOCKS)).size)
    }
}
