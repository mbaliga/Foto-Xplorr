package com.fotoxplorr.core.db

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import com.fotoxplorr.core.db.dao.AssetDao
import com.fotoxplorr.core.db.dao.AssetUserDao
import com.fotoxplorr.core.db.dao.AuditLogDao
import com.fotoxplorr.core.db.dao.CollectionDao
import com.fotoxplorr.core.db.dao.FolderLockDao
import com.fotoxplorr.core.db.dao.GeoDao
import com.fotoxplorr.core.db.dao.IndexFailureDao
import com.fotoxplorr.core.db.dao.KeywordDao
import com.fotoxplorr.core.db.dao.MigrationProgressDao
import com.fotoxplorr.core.db.dao.RawCheckDao
import com.fotoxplorr.core.db.dao.RecognitionDao
import com.fotoxplorr.core.db.dao.SourceDao
import com.fotoxplorr.core.db.dao.TraitDao
import com.fotoxplorr.core.db.dao.VideoMomentDao
import com.fotoxplorr.core.db.entity.AssetEntity
import com.fotoxplorr.core.db.entity.AssetKeywordEntity
import com.fotoxplorr.core.db.entity.AssetKeywordRejectionEntity
import com.fotoxplorr.core.db.entity.AssetUserEntity
import com.fotoxplorr.core.db.entity.AuditLogEntity
import com.fotoxplorr.core.db.entity.CollectionEntity
import com.fotoxplorr.core.db.entity.CollectionMemberEntity
import com.fotoxplorr.core.db.entity.FaceEntity
import com.fotoxplorr.core.db.entity.FolderLockEntity
import com.fotoxplorr.core.db.entity.GeoEntity
import com.fotoxplorr.core.db.entity.IndexFailureEntity
import com.fotoxplorr.core.db.entity.KeywordEntity
import com.fotoxplorr.core.db.entity.LegacyIdMapEntity
import com.fotoxplorr.core.db.entity.LegacyOrphanEntity
import com.fotoxplorr.core.db.entity.MigrationProgressEntity
import com.fotoxplorr.core.db.entity.RecognitionEntity
import com.fotoxplorr.core.db.entity.SourceEntity
import com.fotoxplorr.core.db.entity.TextBlockEntity
import com.fotoxplorr.core.db.entity.TraitEntity
import com.fotoxplorr.core.db.entity.VideoMomentEntity
import com.fotoxplorr.core.db.entity.VideoMomentFeedbackEntity
import com.fotoxplorr.core.db.entity.VideoMomentScanEntity
import com.fotoxplorr.core.db.gallery.GalleryProjectionDao

/**
 * ADR-011 §1/§2: the catalogue and its derived data, `fotoz.db`. Foreign keys must be verified
 * enabled per connection (`PRAGMA foreign_keys=ON`, TRAPS #10) -- WP1.3's own database-builder
 * test covers this. WAL is Room's own default with `BundledSQLiteDriver`.
 *
 * No hand-written `actual object FotozDatabaseConstructor` anywhere in this module, on any
 * target: Room's KSP processor generates it. See `:core:db`'s WP1.2 smoke-module history in
 * MASTER-PROGRESS.md's Decisions for exactly why that matters -- the same mistake here would
 * produce the same confusing failure on the JVM target.
 */
@Database(
    entities = [
        SourceEntity::class,
        AssetEntity::class,
        AssetUserEntity::class,
        KeywordEntity::class,
        AssetKeywordEntity::class,
        AssetKeywordRejectionEntity::class,
        CollectionEntity::class,
        CollectionMemberEntity::class,
        FolderLockEntity::class,
        GeoEntity::class,
        RecognitionEntity::class,
        FaceEntity::class,
        TextBlockEntity::class,
        TraitEntity::class,
        VideoMomentEntity::class,
        VideoMomentScanEntity::class,
        VideoMomentFeedbackEntity::class,
        IndexFailureEntity::class,
        AuditLogEntity::class,
        MigrationProgressEntity::class,
        LegacyIdMapEntity::class,
        LegacyOrphanEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@ConstructedBy(FotozDatabaseConstructor::class)
abstract class FotozDatabase : RoomDatabase() {
    abstract fun sourceDao(): SourceDao
    abstract fun assetDao(): AssetDao
    abstract fun assetUserDao(): AssetUserDao
    abstract fun keywordDao(): KeywordDao
    abstract fun collectionDao(): CollectionDao
    abstract fun folderLockDao(): FolderLockDao
    abstract fun geoDao(): GeoDao
    abstract fun recognitionDao(): RecognitionDao
    abstract fun traitDao(): TraitDao
    abstract fun videoMomentDao(): VideoMomentDao
    abstract fun indexFailureDao(): IndexFailureDao
    abstract fun auditLogDao(): AuditLogDao
    abstract fun migrationProgressDao(): MigrationProgressDao
    abstract fun rawCheckDao(): RawCheckDao
    abstract fun galleryProjectionDao(): GalleryProjectionDao
}

@Suppress("KotlinNoActualForExpect")
expect object FotozDatabaseConstructor : RoomDatabaseConstructor<FotozDatabase> {
    override fun initialize(): FotozDatabase
}
