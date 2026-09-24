package com.fotoxplorr.app.organize

import android.content.Context
import com.fotoxplorr.app.favorites.FavoriteStore
import com.fotoxplorr.app.media.MediaId

/**
 * Moves V1's `CatalogStore` data (favourites, tags, collections) into the real stores once, on the
 * first launch that carries it forward (P0-17). The V1 preferences file was never migrated, so
 * anyone upgrading from that version silently lost every favourite, tag and collection the moment
 * this app stopped reading it.
 *
 * Guarded by [KEY_MIGRATED] in a dedicated preferences file, not the legacy `"catalog"` one: the
 * brief asks to leave that file untouched as a backup, and a flag written into it would violate
 * that. The guard also means a second run -- this constructor is called from [LibraryRuntime]'s
 * `init`, so "a second run" really means "a second process start" -- is a cheap no-op rather than
 * re-reading and re-applying the whole legacy file on every launch forever.
 *
 * Both destination stores are read once into memory and each written with a SINGLE call per
 * distinct key (one [FavoriteStore.setFavorite] for every favourite id, one [LibraryStore.addTag]
 * per distinct tag name, covering every id V1 had under it) rather than one call per V1 row --
 * TRAPS #25's own "last write wins" trap is exactly what a per-row loop would hit for
 * [LibraryStore.addTag] and [FavoriteStore.setFavorite], both of which persist by replacing a
 * whole set key. [libraryStore] is a caller-supplied instance rather than this class reaching for
 * [LibraryStore.get] itself, so the one migration run goes through the exact same synchronized
 * instance every other caller does (TRAPS #26) instead of risking a second one over the same file.
 */
class LegacyCatalogMigration internal constructor(
    context: Context,
    private val libraryStore: LibraryStore,
    private val favoriteStore: FavoriteStore,
) {
    private val migrationPrefs = context.applicationContext
        .getSharedPreferences(MIGRATION_PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val legacyPrefs = context.applicationContext
        .getSharedPreferences(LEGACY_PREFERENCES_NAME, Context.MODE_PRIVATE)

    /** Runs the migration if (and only if) it has never completed for this install. */
    fun run() {
        if (migrationPrefs.getBoolean(KEY_MIGRATED, false)) return
        migrate()
        migrationPrefs.edit().putBoolean(KEY_MIGRATED, true).apply()
    }

    private fun migrate() {
        val favoriteIds = mutableSetOf<MediaId>()
        // V1 stored tags per photo ("tags_<id>" -> that photo's tag names); LibraryStore.addTag's
        // shape is the other way around (one tag name -> every id it applies to), so the rows are
        // regrouped by tag name first -- one addTag call per distinct tag, not one per V1 row.
        val idsByTag = mutableMapOf<String, MutableSet<MediaId>>()

        for ((key, value) in legacyPrefs.all) {
            when {
                key.startsWith(FAVORITE_PREFIX) && (value as? Boolean) == true ->
                    key.removePrefix(FAVORITE_PREFIX).toLongOrNull()?.let { favoriteIds += MediaId(it) }
                key.startsWith(TAGS_PREFIX) -> {
                    val id = key.removePrefix(TAGS_PREFIX).toLongOrNull() ?: continue
                    val tags = (value as? Set<*>)?.filterIsInstance<String>().orEmpty()
                    tags.forEach { tag -> idsByTag.getOrPut(tag) { mutableSetOf() } += MediaId(id) }
                }
            }
        }

        if (favoriteIds.isNotEmpty()) favoriteStore.setFavorite(favoriteIds, true)
        idsByTag.forEach { (tag, ids) -> libraryStore.addTag(ids, tag) }

        // Collections: V1's "collections" set names every collection; "collection_<name>" holds
        // the ids in it. Reuses an existing collection of the same name (created by this same
        // call, or already present from some earlier app action) rather than making a duplicate --
        // createCollection() always mints a fresh id, so this migration must do the "same name
        // already exists" check itself.
        val existingByName = libraryStore.observe().value.collections.associateBy { it.name }
        val collectionNames = legacyPrefs.getStringSet(COLLECTIONS_KEY, emptySet()).orEmpty()
        for (name in collectionNames) {
            val ids = legacyPrefs.getStringSet(collectionKey(name), emptySet()).orEmpty()
                .mapNotNullTo(mutableSetOf()) { it.toLongOrNull()?.let(::MediaId) }
            if (ids.isEmpty()) continue
            val collectionId = existingByName[name]?.id ?: libraryStore.createCollection(name)?.id
            if (collectionId != null) libraryStore.addToCollection(collectionId, ids)
        }
    }

    companion object {
        private const val LEGACY_PREFERENCES_NAME = "catalog"
        private const val MIGRATION_PREFERENCES_NAME = "foto_xplorr_legacy_migration"
        private const val KEY_MIGRATED = "catalog_migrated"
        private const val FAVORITE_PREFIX = "favorite_"
        private const val TAGS_PREFIX = "tags_"
        private const val COLLECTIONS_KEY = "collections"
        private fun collectionKey(name: String) = "collection_$name"
    }
}
