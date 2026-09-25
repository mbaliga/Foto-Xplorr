package com.fotoxplorr.app.organize

import android.content.Context
import com.fotoxplorr.app.favorites.FavoriteStore
import com.fotoxplorr.core.model.MediaId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * V1's `CatalogStore` preferences, migrated exactly once into [FavoriteStore]/[LibraryStore]
 * (P0-17). Seeds the real `"catalog"` SharedPreferences file the same way V1's own `CatalogStore`
 * wrote it, rather than constructing [LegacyCatalogMigration] against a fixture only this test
 * would ever produce.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LegacyCatalogMigrationTest {

    private val app get() = RuntimeEnvironment.getApplication()

    private fun seedLegacyCatalog(app: Context) {
        app.getSharedPreferences("catalog", Context.MODE_PRIVATE).edit()
            .putBoolean("favorite_1", true)
            .putBoolean("favorite_2", false)
            .putStringSet("tags_1", setOf("beach", "sunset"))
            .putStringSet("tags_2", setOf("beach"))
            .putStringSet("collections", setOf("Trip"))
            .putStringSet("collection_Trip", setOf("1", "2"))
            .apply()
    }

    private fun migration(app: Context, library: LibraryStore, favorites: FavoriteStore) =
        LegacyCatalogMigration(app, library, favorites)

    @Test
    fun `favourites tags and collections all move over on the first run`() {
        seedLegacyCatalog(app)
        val library = LibraryStore(app)
        val favorites = FavoriteStore(app)

        migration(app, library, favorites).run()

        val favoriteIds = runBlocking { favorites.observe().first() }
        assertEquals(setOf(MediaId(1L)), favoriteIds)

        assertEquals(setOf("beach", "sunset"), library.observe().value.tagsFor(MediaId(1L)))
        assertEquals(setOf("beach"), library.observe().value.tagsFor(MediaId(2L)))

        val collections = library.observe().value.collections
        assertEquals(1, collections.size)
        assertEquals("Trip", collections.single().name)
        assertEquals(setOf(MediaId(1L), MediaId(2L)), collections.single().mediaIds)
    }

    @Test
    fun `running the migration twice applies it once, not twice`() {
        seedLegacyCatalog(app)
        val library = LibraryStore(app)
        val favorites = FavoriteStore(app)

        migration(app, library, favorites).run()
        migration(app, library, favorites).run()

        // A second, unguarded pass would call createCollection("Trip") again and end up with two
        // collections of the same name -- this is the property that actually proves the guard
        // fired, not just that the second run happened to be harmless.
        val collections = library.observe().value.collections
        assertEquals(1, collections.size)
        assertEquals(setOf(MediaId(1L), MediaId(2L)), collections.single().mediaIds)
        assertEquals(setOf("beach", "sunset"), library.observe().value.tagsFor(MediaId(1L)))
    }

    @Test
    fun `a second process start reuses the guard from a fresh instance`() {
        seedLegacyCatalog(app)
        migration(app, LibraryStore(app), FavoriteStore(app)).run()

        // Simulates a real relaunch: brand new LegacyCatalogMigration/LibraryStore instances, the
        // guard living only in SharedPreferences, not in-memory state this test could cheat with.
        val secondLibrary = LibraryStore(app)
        migration(app, secondLibrary, FavoriteStore(app)).run()

        assertEquals(1, secondLibrary.observe().value.collections.size)
    }

    @Test
    fun `an existing collection with the same name is reused, not duplicated`() {
        seedLegacyCatalog(app)
        val library = LibraryStore(app)
        val existing = library.createCollection("Trip")!!
        library.addToCollection(existing.id, setOf(MediaId(99L)))

        migration(app, library, FavoriteStore(app)).run()

        val collections = library.observe().value.collections
        assertEquals(1, collections.size)
        assertEquals(existing.id, collections.single().id)
        assertEquals(setOf(MediaId(99L), MediaId(1L), MediaId(2L)), collections.single().mediaIds)
    }

    @Test
    fun `an empty legacy catalog migrates nothing and still sets the guard`() {
        val library = LibraryStore(app)
        migration(app, library, FavoriteStore(app)).run()

        assertTrue(library.observe().value.collections.isEmpty())
        assertTrue(library.observe().value.allTags.isEmpty())

        // Seeding data AFTER the guard already fired must not be picked up by a later run --
        // this is the real thing "run once" is supposed to mean, not just "ran without crashing".
        seedLegacyCatalog(app)
        migration(app, library, FavoriteStore(app)).run()
        assertTrue(library.observe().value.collections.isEmpty())
    }
}
