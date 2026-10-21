package com.fotoxplorr.app.migration

import android.database.sqlite.SQLiteDatabase
import com.fotoxplorr.app.ai.EmbeddingRepository
import com.fotoxplorr.app.ai.StoredEmbedding
import com.fotoxplorr.app.favorites.FavoriteStore
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.SqliteMediaRepository
import com.fotoxplorr.app.moments.MomentFeedback
import com.fotoxplorr.app.moments.MomentSource
import com.fotoxplorr.app.moments.VideoMoment
import com.fotoxplorr.app.moments.VideoMomentStore
import com.fotoxplorr.app.organize.LibraryStore
import com.fotoxplorr.app.privacy.PrivateFolderStore
import com.fotoxplorr.app.privacy.SensitiveStore
import com.fotoxplorr.app.recognition.AssetRecognition
import com.fotoxplorr.app.recognition.FaceDescriptor
import com.fotoxplorr.app.recognition.IdentityVerdict
import com.fotoxplorr.app.recognition.PetVerdict
import com.fotoxplorr.app.recognition.RecognitionStore
import com.fotoxplorr.app.recognition.SceneCategory
import com.fotoxplorr.app.recognition.TextBlock
import com.fotoxplorr.app.recognition.decodeVector
import com.fotoxplorr.app.recognition.encodeVector
import com.fotoxplorr.app.spatial.GeoMetadataRepository
import com.fotoxplorr.core.db.migration.LegacyCollection
import com.fotoxplorr.core.db.migration.LegacyGeoRow
import com.fotoxplorr.core.db.migration.LegacyMediaRow
import com.fotoxplorr.core.db.migration.LegacyMomentFeedbackRow
import com.fotoxplorr.core.db.migration.LegacyRecognitionRow
import com.fotoxplorr.core.db.migration.LegacyTextBlockRow
import com.fotoxplorr.core.db.migration.LegacyTraitRow
import com.fotoxplorr.core.db.migration.LegacyVideoMomentRow
import com.fotoxplorr.core.model.MediaId
import java.io.File
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * ADR-011 WP1.3: [AndroidLegacySource] must read back exactly what each old store WROTE. Every
 * fixture here goes through the real store's own public write path (never hand-written SQL or
 * prefs, except the one store that can only write after sniffing real file bytes). The schema,
 * key and encoding details this reader copies (URL-safe base64 tag keys, seconds-not-millis
 * modification times, verbatim vector blobs, PBKDF2's iteration count) are therefore checked
 * against their writers, not against a second transcription of them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidLegacySourceTest {
    private val context = RuntimeEnvironment.getApplication()

    private fun ids(vararg values: Long): Set<MediaId> = values.mapTo(linkedSetOf()) { MediaId(it) }

    private fun asset(
        id: Long,
        trashed: Boolean = false,
        favorite: Boolean = false,
        relativePath: String? = "DCIM/Camera/",
        bucketId: Long? = 7L,
    ) = MediaAsset(
        id = MediaId(id),
        contentUriString = "content://media/external/images/media/$id",
        displayName = "IMG_$id.jpg",
        mimeType = "image/jpeg",
        bucketName = "Camera",
        bucketId = bucketId,
        dateTakenMillis = 1_700_000_000_000L + id,
        dateModifiedSeconds = 1_700_000_000L + id,
        width = 4000,
        height = 3000,
        sizeBytes = 2_000_000L + id,
        durationMillis = 0L,
        relativePath = relativePath,
        isFavorite = favorite,
        isTrashed = trashed,
    )

    @Test
    fun `stores that were never created read as empty and are not created by reading`() = runBlocking {
        val source = AndroidLegacySource(context)

        assertTrue(source.mediaRowsAfter(afterId = 0, limit = 10).isEmpty())
        assertTrue(source.geoRows().isEmpty())
        assertTrue(source.recognitionRows().isEmpty())
        assertTrue(source.faceRows().isEmpty())
        assertTrue(source.textBlockRows().isEmpty())
        assertTrue(source.recognitionFailures().isEmpty())
        assertTrue(source.traitRows().isEmpty())
        assertTrue(source.embeddingRows().isEmpty())
        assertTrue(source.embeddingFailures().isEmpty())
        assertTrue(source.videoMomentRows().isEmpty())
        assertTrue(source.videoScannedIds().isEmpty())
        assertTrue(source.videoMomentFeedbackRows().isEmpty())
        assertTrue(source.favoriteIds().isEmpty())
        assertTrue(source.sensitiveIds().isEmpty())
        assertTrue(source.folderLocks().isEmpty())
        val library = source.libraryData()
        assertTrue(library.collections.isEmpty() && library.tagMembers.isEmpty() && library.captions.isEmpty())

        LegacyStoreFiles.DATABASES.forEach { name ->
            assertFalse("$name must not be created by a read", context.getDatabasePath(name).exists())
        }
    }

    @Test
    fun `media rows page by ascending id after the cursor with every column mapped`() = runBlocking {
        val repository = SqliteMediaRepository(context)
        repository.awaitLoaded()
        repository.upsert(
            listOf(
                asset(9, favorite = true),
                asset(2, trashed = true, relativePath = null, bucketId = null),
                asset(5),
            ),
        )
        val source = AndroidLegacySource(context)

        val first = source.mediaRowsAfter(afterId = 0, limit = 2)
        assertEquals(listOf(2L, 5L), first.map { it.mediaId })
        val second = source.mediaRowsAfter(afterId = 5, limit = 2)
        assertEquals(listOf(9L), second.map { it.mediaId })
        assertTrue(source.mediaRowsAfter(afterId = 9, limit = 2).isEmpty())
        assertTrue(source.mediaRowsAfter(afterId = 0, limit = 0).isEmpty())

        assertEquals(
            LegacyMediaRow(
                mediaId = 2,
                contentUri = "content://media/external/images/media/2",
                displayName = "IMG_2.jpg",
                mimeType = "image/jpeg",
                bucketName = "Camera",
                bucketId = null,
                dateTakenMs = 1_700_000_000_002L,
                dateModifiedSeconds = 1_700_000_002L,
                width = 4000,
                height = 3000,
                sizeBytes = 2_000_002L,
                durationMillis = 0L,
                relativePath = null,
                isFavorite = false,
                isTrashed = true,
            ),
            first[0],
        )
        assertEquals(7L, first[1].bucketId)
        assertEquals("DCIM/Camera/", first[1].relativePath)
        assertTrue(second.single().isFavorite)
    }

    @Test
    fun `favourite and sensitive ids decode exactly as their own stores do`() = runBlocking {
        FavoriteStore(context).setFavorite(ids(3, 1), true)
        SensitiveStore(context).setSensitive(ids(4), true)
        // Raw values the stores' own codecs disagree on: FavoriteIdCodec drops a negative id,
        // SensitiveIdCodec keeps it, and both drop non-numbers.
        context.getSharedPreferences("foto_xplorr_favorites", 0).edit()
            .putStringSet("favorite_media_ids", setOf("1", "3", "-2", "junk")).commit()
        context.getSharedPreferences("foto_xplorr_sensitive", 0).edit()
            .putStringSet("sensitive_media_ids", setOf("4", "-2", "junk")).commit()

        val source = AndroidLegacySource(context)
        assertEquals(setOf(1L, 3L), source.favoriteIds())
        assertEquals(setOf(4L, -2L), source.sensitiveIds())
    }

    @Test
    fun `library data mirrors what LibraryStore wrote, key encodings included`() = runBlocking {
        val store = LibraryStore(context)
        val collection = requireNotNull(store.createCollection("Holiday"))
        store.addToCollection(collection.id, ids(1, 2))
        // "b??" encodes to "Yj8/" in standard base64 but "Yj8_" in LibraryStore's URL-safe
        // alphabet, so reading with the wrong alphabet would find no members at all.
        store.addTag(ids(1, 2), "b??")
        store.addAutoTags(MediaId(3), setOf("sunset"))
        store.addAutoTags(MediaId(4), setOf("sunset"))
        store.removeTag(ids(4), "sunset") // a person removing an auto tag is remembered as a rejection
        store.setArchived(ids(6, 7), true)
        store.setArchived(ids(7), false) // un-archiving is remembered permanently
        store.rejectArchiveSuggestions(ids(8))
        store.setCaption(MediaId(1), "  Beach day ")
        assertTrue(store.applyMachineCaption(MediaId(2), "A dog"))
        assertTrue(store.applyMachineCaption(MediaId(3), "A cat"))
        store.clearMachineCaptions(ids(3)) // suppressed, with no caption text left behind

        val data = AndroidLegacySource(context).libraryData()

        assertEquals(
            listOf(LegacyCollection(collection.id, "Holiday", collection.createdAtMillis, setOf(1L, 2L))),
            data.collections,
        )
        assertEquals(mapOf("b??" to setOf(1L, 2L), "sunset" to setOf(3L)), data.tagMembers)
        assertEquals(mapOf("sunset" to setOf(3L)), data.autoTagMembers)
        assertEquals(mapOf("sunset" to setOf(4L)), data.rejectedAutoTagMembers)
        assertEquals(setOf(6L), data.archivedIds)
        assertEquals(setOf(7L), data.everUnarchivedIds)
        assertEquals(setOf(8L), data.rejectedArchiveSuggestionIds)
        assertEquals(mapOf(1L to "Beach day", 2L to "A dog"), data.captions)
        assertEquals(setOf(2L), data.machineCaptionIds)
        assertEquals(setOf(3L), data.suppressedMachineCaptionIds)

        // The same membership LibraryStore's own decoded view reports.
        val byTag = HashMap<String, MutableSet<Long>>()
        store.observe().value.tagsByMediaId.forEach { (id, tags) -> tags.forEach { byTag.getOrPut(it) { linkedSetOf() } += id.value } }
        assertEquals(byTag, data.tagMembers)
    }

    @Test
    fun `folder locks carry the salt and hash PrivateFolderStore derived, at its iteration count`() = runBlocking {
        assertTrue(PrivateFolderStore(context).protect("path:pictures/vault", "secret-pw".toCharArray()).isSuccess)

        val lock = AndroidLegacySource(context).folderLocks().single()

        assertEquals("path:pictures/vault", lock.folderKey)
        assertEquals(210_000, lock.iterations)
        assertEquals(16, lock.salt.size)
        // Re-deriving from the migrated salt and iteration count must reproduce the stored hash.
        // Otherwise a migrated lock could never be unlocked with the right password.
        val spec = PBEKeySpec("secret-pw".toCharArray(), lock.salt, lock.iterations, 256)
        val derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        assertArrayEquals(derived, lock.hash)
    }

    @Test
    fun `derived stores round-trip through their real writers`() = runBlocking {
        GeoMetadataRepository(context).setManualLocation(MediaId(5), 51.5, -0.12)

        val recognition = RecognitionStore(context)
        val faceVector = floatArrayOf(0.5f, -1f, 0.125f)
        recognition.upsert(
            listOf(
                AssetRecognition(
                    mediaId = MediaId(5),
                    sourceRevision = 77L,
                    faceCount = 1,
                    faceDescriptors = listOf(FaceDescriptor(MediaId(5), 0, faceVector, 0.25f)),
                    petVerdict = PetVerdict.DOG,
                    identityVerdict = IdentityVerdict.NONE,
                    labels = listOf("dog", "grass"),
                    textBlocks = listOf(TextBlock("hi", 0.5f, 0.25f, 0.75f, 1f)),
                    categories = setOf(SceneCategory.FAUNA),
                    caption = "A dog",
                    hashtags = listOf("#dog"),
                ),
            ),
        )
        recognition.recordFailure(MediaId(6), revision = 9L)

        val embeddings = EmbeddingRepository(context)
        embeddings.upsertBatch(
            listOf(StoredEmbedding(MediaId(5), 11L, "sha", byteArrayOf(1, -2, 3), signature = -5, x = 0.5f, y = null)),
        )
        embeddings.recordFailure(MediaId(6), "sha", 12L)

        val moments = VideoMomentStore(context)
        moments.add(VideoMoment(MediaId(9), 1_000L, MomentSource.MANUAL))
        moments.markScanned(MediaId(9))
        moments.setFeedback(MediaId(9), 2_000L, MomentFeedback.BAD)

        // AnimationIndex only writes after sniffing real bytes through a content resolver, so its
        // v1 table is built directly, exactly as AnimationOpenHelper.onCreate declares it.
        val traits = context.getDatabasePath("foto_xplorr_traits.db").also { it.parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(traits, null).use { db ->
            db.execSQL("CREATE TABLE animation (media_id INTEGER PRIMARY KEY, date_modified INTEGER NOT NULL, animated INTEGER NOT NULL)")
            db.execSQL("INSERT INTO animation VALUES (5, 1700000005, 1), (6, 1700000006, 0)")
            db.version = 1
        }

        val source = AndroidLegacySource(context)

        assertEquals(
            listOf(LegacyGeoRow(5, true, 51.5, -0.12, null, null, manual = true, checkedWithOriginal = false, sourceRevisionSeconds = null)),
            source.geoRows(),
        )
        assertEquals(
            LegacyRecognitionRow(5, 77L, 1, "DOG", "NONE", listOf("dog", "grass"), listOf("FAUNA"), "A dog", listOf("#dog")),
            source.recognitionRows().single(),
        )
        val face = source.faceRows().single()
        assertEquals(5L, face.mediaId)
        assertEquals(0, face.faceIndex)
        assertEquals(0.25, face.relativeArea, 0.0)
        assertArrayEquals(encodeVector(faceVector), face.vector)
        assertArrayEquals(faceVector, decodeVector(face.vector), 0f)
        assertEquals(listOf(LegacyTextBlockRow(5, 0, "hi", 0.5, 0.25, 0.75, 1.0)), source.textBlockRows())
        source.recognitionFailures().single().let {
            assertEquals(6L, it.mediaId)
            assertNull(it.modelSha)
            assertEquals(9L, it.revision)
            assertEquals(1, it.attempts)
        }

        source.embeddingRows().single().let {
            assertEquals(5L, it.mediaId)
            assertEquals(11L, it.sourceRevision)
            assertEquals("sha", it.modelSha)
            assertArrayEquals(byteArrayOf(1, -2, 3), it.vector)
            assertEquals(-5L, it.signature)
            assertEquals(0.5, it.x!!, 0.0)
            assertNull(it.y)
        }
        source.embeddingFailures().single().let {
            assertEquals(6L, it.mediaId)
            assertEquals("sha", it.modelSha)
            assertEquals(12L, it.revision)
        }

        assertEquals(listOf(LegacyTraitRow(5, 1_700_000_005L, true), LegacyTraitRow(6, 1_700_000_006L, false)), source.traitRows())
        assertEquals(listOf(LegacyVideoMomentRow(9, 1_000L, "MANUAL", 0.0, "")), source.videoMomentRows())
        assertEquals(setOf(9L), source.videoScannedIds())
        assertEquals(listOf(LegacyMomentFeedbackRow(9, 2_000L, "BAD")), source.videoMomentFeedbackRows())
    }

    @Test
    fun `columns an older schema version lacks read as their defaults`() = runBlocking {
        // A geo database still at v1: no manual / checked_with_original / source_revision.
        val geo = context.getDatabasePath("foto_xplorr_geo.db").also { it.parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(geo, null).use { db ->
            db.execSQL(
                "CREATE TABLE geo_metadata (media_id INTEGER PRIMARY KEY, scanned INTEGER NOT NULL, " +
                    "has_location INTEGER NOT NULL, latitude REAL, longitude REAL, altitude REAL, direction REAL)",
            )
            db.execSQL("INSERT INTO geo_metadata VALUES (3, 1, 1, 1.0, 2.0, 3.0, 90.0)")
            db.version = 1
        }

        assertEquals(
            listOf(LegacyGeoRow(3, true, 1.0, 2.0, 3.0, 90.0, manual = false, checkedWithOriginal = false, sourceRevisionSeconds = null)),
            AndroidLegacySource(context).geoRows(),
        )
    }

    @Test
    fun `an unreachable MediaStore fails the lookup instead of reporting every volume unknown`() {
        // No MediaStore provider is registered under Robolectric, so the query returns no cursor.
        assertThrows(IllegalStateException::class.java) {
            runBlocking { AndroidLegacySource(context).volumeForMediaId(1) }
        }
    }

    @Test
    fun `environment backs up, cuts over, counts starts and cleans up`() = runBlocking {
        val repository = SqliteMediaRepository(context)
        repository.awaitLoaded()
        repository.upsert(listOf(asset(1)))
        FavoriteStore(context).setFavorite(ids(1), true) // apply(): may not have reached disk yet
        val environment = AndroidMigrationEnvironment(context)

        environment.writeExportJson("{\"x\":1}", 1234L)
        assertEquals("{\"x\":1}", File(context.filesDir, "migration/pre-v2-1234.json").readText())

        environment.copyOldStoreFiles()
        val catalogueCopy = File(context.filesDir, "migration/pre-v2/foto_xplorr_catalogue.db")
        SQLiteDatabase.openDatabase(catalogueCopy.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT id FROM media", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1L, cursor.getLong(0))
            }
        }
        assertFalse("a store never created has nothing to copy", File(context.filesDir, "migration/pre-v2/foto_xplorr_geo.db").exists())
        assertTrue(
            File(context.filesDir, "migration/pre-v2/shared_prefs/foto_xplorr_favorites.xml").readText().contains("favorite_media_ids"),
        )

        assertFalse(environment.isCutoverComplete())
        assertEquals("no start counts before cutover", 0, environment.recordSuccessfulStartSinceCutover())
        environment.markCutoverComplete()
        assertTrue(environment.isCutoverComplete())
        assertEquals(1, environment.recordSuccessfulStartSinceCutover())
        environment.markCutoverComplete() // idempotent: must not reset the count
        assertEquals(2, environment.recordSuccessfulStartSinceCutover())
        assertTrue(MigrationStateStore(context).isCutoverComplete())

        environment.deleteOldStoresAtLiveLocation()
        assertFalse(context.getDatabasePath("foto_xplorr_catalogue.db").exists())
        assertFalse(File(context.dataDir, "shared_prefs/foto_xplorr_favorites.xml").exists())
        assertTrue(context.getSharedPreferences("foto_xplorr_favorites", 0).all.isEmpty())
        assertTrue("backups are never cleaned up here", catalogueCopy.isFile)
    }
}
