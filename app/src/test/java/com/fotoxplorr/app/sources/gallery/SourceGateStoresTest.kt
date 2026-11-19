package com.fotoxplorr.app.sources.gallery

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fotoxplorr.app.favorites.FavoriteStore
import com.fotoxplorr.app.organize.LibraryStore
import com.fotoxplorr.app.privacy.SensitiveStore
import com.fotoxplorr.app.render.noOpActions
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The gate against the REAL stores: wired to FavoriteStore, SensitiveStore and LibraryStore the way
 * the activity wires them, every write fired with a source id leaves their in-memory state AND every
 * preferences file on disk byte-identical. This is the safety net for the finding that
 * `SensitiveIdCodec` keeps negative ids and `FavoriteStore` holds them until restart.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SourceGateStoresTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun prefsSnapshot(): Map<String, String> {
        val dir = File(context.applicationInfo.dataDir, "shared_prefs")
        return dir.listFiles().orEmpty().filter { it.isFile }.associate { it.name to it.readText() }
    }

    @Test
    fun `source ids never reach the favourite sensitive or library stores`() {
        val favorites = FavoriteStore(context)
        val sensitive = SensitiveStore(context)
        val library = LibraryStore(context)
        val source = SourceAssetMapper.toMediaAsset(entry(sourceRow(1)))!!
        val plain = libraryAsset(1)

        val gate = SourceActionGate.wrap(
            noOpActions().copy(
                onSetFavorite = favorites::setFavorite,
                onSetSensitive = sensitive::setSensitive,
                onSetArchived = library::setArchived,
                onAddToCollection = library::addToCollection,
                onRemoveFromCollection = library::removeFromCollection,
                onAddTag = library::addTag,
                onRemoveTag = library::removeTag,
                onRejectArchiveSuggestions = library::rejectArchiveSuggestions,
            ),
        )
        val ids = setOf(source.id)
        val collection = library.createCollection("Trip")!!.id

        val beforeFiles = prefsSnapshot()
        val beforeFavs = runBlocking { favorites.observe().first() }
        val beforeSensitive = sensitive.observe().value
        val beforeLibrary = library.observe().value

        gate.onSetFavorite(ids, true)
        gate.onSetSensitive(ids, true)
        gate.onSetArchived(ids, true)
        gate.onAddToCollection(collection, ids)
        gate.onAddTag(ids, "holiday")
        gate.onRemoveTag(ids, "holiday")
        gate.onRemoveFromCollection(collection, ids)
        gate.onRejectArchiveSuggestions(ids)

        assertEquals(beforeFavs, runBlocking { favorites.observe().first() })
        assertEquals(beforeSensitive, sensitive.observe().value)
        assertEquals(beforeLibrary, library.observe().value)
        assertEquals("a preferences file changed", beforeFiles, prefsSnapshot())

        // The control: the same wiring does write for a library photo, so the assertions above bite.
        gate.onSetSensitive(setOf(plain.id), true)
        assertTrue(plain.id in sensitive.observe().value)
        assertTrue(source.id !in sensitive.observe().value)
    }
}
