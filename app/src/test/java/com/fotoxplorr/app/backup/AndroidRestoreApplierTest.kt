package com.fotoxplorr.app.backup

import android.util.Base64
import com.fotoxplorr.app.editor.EditRecipe
import com.fotoxplorr.app.editor.RecipeStore
import com.fotoxplorr.app.editor.parseJson
import com.fotoxplorr.app.editor.toJson
import com.fotoxplorr.app.favorites.FavoriteStore
import com.fotoxplorr.app.organize.LibraryStore
import com.fotoxplorr.app.privacy.FolderLockRecord
import com.fotoxplorr.app.privacy.PrivateFolderStore
import com.fotoxplorr.app.privacy.SensitiveStore
import com.fotoxplorr.app.spatial.GeoMetadataRepository
import com.fotoxplorr.core.model.MediaId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The restore's connection to the phone's real stores: tags merged into the library the person
 * already has, locks that never replace one, pins and edits that are kept if they exist.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidRestoreApplierTest {
    private val app = RuntimeEnvironment.getApplication()
    private val library = LibraryStore(app)
    private val favorites = FavoriteStore(app)
    private val sensitive = SensitiveStore(app)
    private val locks = PrivateFolderStore(app)
    private val geo = GeoMetadataRepository(app)
    private val recipes = RecipeStore(app)
    private var scans = 0
    private var onPhone: Set<Long> = emptySet()

    private fun applier(wait: Long = 300) = AndroidRestoreApplier(
        context = app,
        libraryStore = library,
        favoriteStore = favorites,
        sensitiveStore = sensitive,
        privateFolderStore = locks,
        geoRepository = geo,
        recipeStore = recipes,
        catalogueIds = { onPhone },
        requestScan = { scans++ },
        catalogueWaitMillis = wait,
    )

    private fun b64(size: Int) = Base64.encodeToString(ByteArray(size) { it.toByte() }, Base64.NO_WRAP)

    private fun prepared(): MetadataBackup.PreparedRestore {
        val identity = mapOf(
            10L to MediaIdentity("a.jpg", 100, 5, "DCIM/Camera/"),
            11L to MediaIdentity("b.jpg", 200, 6, "DCIM/Camera/"),
        )
        val lib = parseJson(
            """{"schema":1,"collections":[{"id":"c9","name":"Trip","createdAtMillis":5,"mediaIds":[10,11]}],"tags":{"beach":[10]},"captions":{"11":"hello"}}""",
        )!!
        val built = MetadataBackup.buildFile(lib, setOf(10L), setOf(11L), identity, 1L)
        val file = (MetadataBackup.readTree(built.json) as MetadataBackup.FileRead.Usable).file
        return MetadataBackup.prepare(file, emptyMap(), known = mapOf(10L to 510L, 11L to 511L))
    }

    @Test
    fun `tags are merged into the library, favourites and marks are added, and nothing already there goes`() {
        library.importJson(org.json.JSONObject("""{"schema":1,"tags":{"mine":[1]},"captions":{"1":"my caption"}}""")).getOrThrow()
        favorites.setFavorite(setOf(MediaId(1)), true)
        sensitive.setSensitive(setOf(MediaId(2)), true)

        runBlocking { applier().applyMetadata(prepared()) }

        val text = library.exportJson().toString()
        assertTrue(text, text.contains("\"mine\"") && text.contains("\"beach\"") && text.contains("my caption") && text.contains("hello") && text.contains("Trip"))
        assertEquals(setOf(MediaId(1), MediaId(510)), runBlocking { favorites.observe().first() })
        assertEquals(setOf(MediaId(2), MediaId(511)), sensitive.observe().value)
    }

    @Test
    fun `a lock is put back and a lock the phone has is kept`() {
        locks.restoreLocks(listOf(FolderLockRecord("path:keep", b64(16), b64(32))))
        val result = runBlocking {
            applier().applyLocks(
                listOf(
                    BackupSections.Lock("path:keep", b64(16), b64(32)),
                    BackupSections.Lock("path:new", b64(16), b64(32)),
                ),
            )
        }
        assertEquals(1, result.added)
        assertEquals(1, result.kept)
        assertEquals(setOf("path:keep", "path:new"), locks.observeLockedFolders().value)
    }

    @Test
    fun `a lock that is not a lock stops the restore before any photo is written`() {
        try {
            runBlocking { applier().applyLocks(listOf(BackupSections.Lock("path:bad", "xx", "yy"))) }
            fail("a bad lock must stop the restore")
        } catch (e: BackupRefused) {
            assertTrue(e.message!!, e.message!!.contains("nothing was restored"))
        }
        assertTrue(locks.observeLockedFolders().value.isEmpty())
    }

    @Test
    fun `pins and edits are added and counted, and ones that exist are kept`() {
        runBlocking { geo.setManualLocation(MediaId(5), 1.0, 2.0) }
        val pins = runBlocking { applier().applyPins(listOf(BackupSections.Pin(5, 9.0, 9.0), BackupSections.Pin(6, 3.0, 4.0))) }
        assertEquals(1, pins)
        assertEquals(1.0, geo.observe().value.metadataById.getValue(MediaId(5)).latitude, 0.0001)

        val json = EditRecipe(quarterTurns = 1).toJson()
        val added = runBlocking {
            applier().applyRecipes(listOf(RestoredRecipe(41, 7_000, json), RestoredRecipe(41, 7_000, json)))
        }
        assertEquals("the second is the same file and is kept as it is", 1, added)
    }

    @Test
    fun `waiting for the catalogue ends as soon as it lists the new photos and asks for a rescan once`() {
        onPhone = setOf(1, 2, 3)
        val started = System.currentTimeMillis()
        runBlocking { applier(wait = 60_000).awaitCatalogue(setOf(1, 2)) }
        assertTrue(System.currentTimeMillis() - started < 5_000)
        assertEquals(1, scans)
    }

    @Test
    fun `waiting for a catalogue that never lists them gives up quietly`() {
        onPhone = setOf(1)
        runBlocking { applier(wait = 200).awaitCatalogue(setOf(1, 2)) }
        assertFalse(2L in onPhone)
    }

    @Test
    fun `no new photos means no rescan and no wait`() {
        runBlocking { applier().awaitCatalogue(emptySet()) }
        assertEquals(0, scans)
    }
}
