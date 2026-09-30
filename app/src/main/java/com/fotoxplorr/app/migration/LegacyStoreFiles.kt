package com.fotoxplorr.app.migration

/**
 * Where the old, pre-`fotoz.db` stores live on disk (ADR-011 §5). [AndroidLegacySource] reads
 * these and [AndroidMigrationEnvironment] backs them up and later deletes them.
 *
 * Every name is repeated here rather than imported because each one is a `private const` inside
 * its store's own `private class *OpenHelper` or companion, so no other file can see it. Each
 * constant names the file it was copied from. If a store is ever renamed, the new name must be
 * added here too. A store missing from this list is never migrated, backed up or cleaned up.
 *
 * ADR-011's Context section and `MigrationEnvironment`'s KDoc both say "seven" old SQLite files.
 * This app declares six `SQLiteOpenHelper`s ([DATABASES]), which matches the ADR's own store
 * table, so six is what gets backed up and deleted.
 */
internal object LegacyStoreFiles {
    /** `media/SqliteMediaRepository.kt`, `CatalogueOpenHelper.DATABASE_NAME` (v3). */
    const val CATALOGUE_DB = "foto_xplorr_catalogue.db"

    /** `spatial/GeoMetadataRepository.kt`, `GeoOpenHelper.DATABASE_NAME` (v3). */
    const val GEO_DB = "foto_xplorr_geo.db"

    /** `recognition/RecognitionStore.kt`, `RecognitionOpenHelper.DATABASE_NAME` (v4). */
    const val RECOGNITION_DB = "foto_xplorr_recognition.db"

    /** `ai/EmbeddingRepository.kt`, `EmbeddingOpenHelper.DATABASE_NAME` (v2). */
    const val EMBEDDINGS_DB = "foto_xplorr_embeddings.db"

    /** `formats/AnimationIndex.kt`, `AnimationOpenHelper.DATABASE_NAME` (v1). */
    const val TRAITS_DB = "foto_xplorr_traits.db"

    /** `moments/VideoMoment.kt`, `MomentOpenHelper.DATABASE_NAME` (v2). */
    const val MOMENTS_DB = "foto_xplorr_moments.db"

    val DATABASES: List<String> = listOf(CATALOGUE_DB, GEO_DB, RECOGNITION_DB, EMBEDDINGS_DB, TRAITS_DB, MOMENTS_DB)

    /** `favorites/FavoriteStore.kt`, `PREFERENCES_NAME`. */
    const val FAVORITES_PREFS = "foto_xplorr_favorites"

    /** `privacy/SensitiveStore.kt`, `PREFERENCES_NAME`. */
    const val SENSITIVE_PREFS = "foto_xplorr_sensitive"

    /** `organize/LibraryStore.kt`, `PREFERENCES_NAME`. */
    const val LIBRARY_PREFS = "foto_xplorr_library"

    /** `privacy/PrivateFolderStore.kt`, `PREFERENCES_NAME`. */
    const val PRIVATE_FOLDERS_PREFS = "foto_xplorr_private_folders"

    /**
     * The asset-keyed preferences ADR-011 migrates. The ADR's Context section lists the other
     * preferences files (gallery preferences, work rules, AI providers, secrets, pro, scan
     * watermarks, the V1 legacy-migration flag). Those aren't asset-keyed and stay as they are,
     * so they're deliberately not in this list.
     */
    val ASSET_KEYED_PREFERENCES: List<String> =
        listOf(FAVORITES_PREFS, SENSITIVE_PREFS, LIBRARY_PREFS, PRIVATE_FOLDERS_PREFS)
}
