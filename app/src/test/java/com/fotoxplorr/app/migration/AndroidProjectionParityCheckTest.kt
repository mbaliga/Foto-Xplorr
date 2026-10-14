package com.fotoxplorr.app.migration

import android.database.sqlite.SQLiteDatabase
import com.fotoxplorr.app.favorites.FavoriteStore
import com.fotoxplorr.app.formats.AnimationIndex
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.SqliteMediaRepository
import com.fotoxplorr.app.organize.LibraryStore
import com.fotoxplorr.app.privacy.PrivateFolderStore
import com.fotoxplorr.app.privacy.SensitiveStore
import com.fotoxplorr.app.recognition.AssetRecognition
import com.fotoxplorr.app.recognition.IdentityVerdict
import com.fotoxplorr.app.recognition.PetVerdict
import com.fotoxplorr.app.recognition.RecognitionStore
import com.fotoxplorr.core.db.dao.AssetDao
import com.fotoxplorr.core.db.dao.AssetUserDao
import com.fotoxplorr.core.db.dao.FolderLockDao
import com.fotoxplorr.core.db.dao.KeywordDao
import com.fotoxplorr.core.db.dao.RecognitionDao
import com.fotoxplorr.core.db.dao.TraitDao
import com.fotoxplorr.core.db.entity.AssetEntity
import com.fotoxplorr.core.db.entity.AssetKeywordEntity
import com.fotoxplorr.core.db.entity.AssetUserEntity
import com.fotoxplorr.core.db.entity.FolderLockEntity
import com.fotoxplorr.core.db.entity.KeywordEntity
import com.fotoxplorr.core.db.entity.RecognitionEntity
import com.fotoxplorr.core.model.AssetId
import com.fotoxplorr.core.model.FormatId
import com.fotoxplorr.core.model.MediaId
import com.fotoxplorr.core.model.SourceId
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [AndroidProjectionParityCheck] over a small library. The old side is written through the
 * real stores. The new side is fake DAOs holding what a correct migration of that library
 * produces. The fakes are `:core:db`'s real DAO interfaces, with every method the check doesn't
 * read throwing.
 *
 * The fixture includes both of the migration's deliberate differences (a MediaStore-only
 * favourite, and an auto-tag member missing from its tag), a locked folder, a trashed asset, a
 * recognition row for an id outside the catalogue, and an animated GIF. Parity must hold across
 * all of them. Dropping one migrated fact must break exactly the projections that read it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidProjectionParityCheckTest {
    private val context = RuntimeEnvironment.getApplication()

    private val vaultKey = "path:pictures/vault"

    private val library = listOf(
        asset(1),
        asset(2, relativePath = "Pictures/Vault/", bucketName = "Vault"), // in the locked folder
        asset(3, trashed = true),
        asset(4, mediaStoreFavorite = true), // favourite only in MediaStore, never in FavoriteStore
        asset(5, name = "a.gif", mime = "image/gif"),
        asset(6, name = "v.mp4", mime = "video/mp4"),
        asset(7, name = "Screenshot_7.png", mime = "image/png"),
    )

    private lateinit var repository: SqliteMediaRepository
    private lateinit var animationIndex: AnimationIndex

    @Before
    fun writeOldStores() = runBlocking {
        repository = SqliteMediaRepository(context)
        repository.awaitLoaded()
        repository.upsert(library)

        FavoriteStore(context).setFavorite(setOf(MediaId(1), MediaId(2)), true)
        SensitiveStore(context).setSensitive(setOf(MediaId(6)), true)
        // LibraryStore is a process-wide singleton that can outlive a Robolectric test, so its
        // state is replaced wholesale. importJson is also the one real write path that produces
        // the "auto-tag member not in its tag" anomaly (5 under sunset) MigrationToV2 links anyway.
        LibraryStore.get(context).importJson(
            JSONObject("""{"schema":1,"tags":{"trip":[1],"sunset":[2]},"autoTags":{"sunset":[2,5]},"archivedIds":[7]}"""),
        ).getOrThrow()
        assertTrue(PrivateFolderStore(context).protect(vaultKey, "secret-pw".toCharArray()).isSuccess)
        RecognitionStore(context).upsert(
            listOf(
                AssetRecognition(MediaId(1), sourceRevision = 1, faceCount = 2),
                AssetRecognition(MediaId(2), sourceRevision = 1, faceCount = 0, petVerdict = PetVerdict.CAT),
                AssetRecognition(MediaId(7), sourceRevision = 1, faceCount = 0, identityVerdict = IdentityVerdict.DOCUMENT),
                // Not in the catalogue: MigrationToV2 drops it, and no projection can see it.
                AssetRecognition(MediaId(99), sourceRevision = 1, faceCount = 1),
            ),
        )
        val traits = context.getDatabasePath("foto_xplorr_traits.db").also { it.parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(traits, null).use { db ->
            db.execSQL("CREATE TABLE animation (media_id INTEGER PRIMARY KEY, date_modified INTEGER NOT NULL, animated INTEGER NOT NULL)")
            db.execSQL("INSERT INTO animation VALUES (5, 1, 1)")
            db.version = 1
        }
        animationIndex = AnimationIndex(context)
    }

    @Test
    fun `a faithful migration has parity on every projection in both privacy views`() = runBlocking {
        val check = check(MigratedLibrary())

        val old = check.oldFingerprints()
        val new = check.newFingerprints()

        assertEquals(old, new)
        assertEquals(26 * 2, old.size)
        assertTrue(old.keys.containsAll(listOf("destination:PHOTOS", "smart:UNTAGGED", "sort:SIZE", "timeline:stops")))
        // The fixture exercises what it claims to. The locked view hides asset 2, and FAVORITES
        // counts 4, the MediaStore-only favourite.
        assertTrue(old.getValue("smart:FAVORITES").startsWith("2:"))
        assertTrue(old.getValue("unlocked/smart:FAVORITES").startsWith("3:"))
        // 5 carries the stray auto-tag, so only 4, 6 and 7 are untagged.
        assertTrue(old.getValue("smart:UNTAGGED").startsWith("3:"))
    }

    @Test
    fun `a lost favourite breaks exactly the favourites projections`() = runBlocking {
        val check = check(MigratedLibrary(favorites = setOf(1L, 2L)))

        assertEquals(
            setOf(
                "destination:FAVOURITES", "smart:FAVORITES",
                "unlocked/destination:FAVOURITES", "unlocked/smart:FAVORITES",
            ),
            mismatches(check),
        )
    }

    @Test
    fun `a lost lock shows up in the locked view only`() = runBlocking {
        val mismatched = mismatches(check(MigratedLibrary(locks = emptySet())))

        assertTrue("destination:PHOTOS" in mismatched)
        assertTrue(mismatched.none { it.startsWith(AndroidProjectionParityCheck.UNLOCKED_PREFIX) })
    }

    @Test
    fun `a dropped stray auto-tag link breaks UNTAGGED`() = runBlocking {
        val mismatched = mismatches(check(MigratedLibrary(sunsetMembers = setOf(2L))))

        assertEquals(setOf("smart:UNTAGGED", "unlocked/smart:UNTAGGED"), mismatched)
    }

    // ---- fixture helpers -------------------------------------------------------------------

    private suspend fun mismatches(check: AndroidProjectionParityCheck): Set<String> {
        val old = check.oldFingerprints()
        val new = check.newFingerprints()
        return (old.keys + new.keys).filterTo(sortedSetOf()) { old[it] != new[it] }
    }

    private fun check(migrated: MigratedLibrary) = AndroidProjectionParityCheck(
        context = context,
        repository = repository,
        animationIndex = animationIndex,
        assetDao = object : AssetDao by unsupported<AssetDao>() {
            override suspend fun getAll() = library.map { it.toEntity() }
        },
        assetUserDao = object : AssetUserDao by unsupported<AssetUserDao>() {
            override fun observeAll(): Flow<List<AssetUserEntity>> = flowOf(
                library.map {
                    AssetUserEntity(
                        assetId = assetIdOf(it.id.value),
                        favorite = it.id.value in migrated.favorites,
                        archived = it.id.value in migrated.archived,
                        sensitive = it.id.value in migrated.sensitive,
                        caption = null,
                        sidecarState = null,
                    )
                },
            )
        },
        keywordDao = object : KeywordDao by unsupported<KeywordDao>() {
            override suspend fun getAllRoot() = listOf(KeywordEntity(1, null, "trip"), KeywordEntity(2, null, "sunset"))
            override suspend fun linksFor(assetId: AssetId): List<AssetKeywordEntity> = buildList {
                val mediaId = assetId.value - ASSET_ID_OFFSET
                if (mediaId == 1L) add(AssetKeywordEntity(assetId, 1, "USER"))
                if (mediaId in migrated.sunsetMembers) add(AssetKeywordEntity(assetId, 2, "AUTO"))
            }
        },
        folderLockDao = object : FolderLockDao by unsupported<FolderLockDao>() {
            override suspend fun getAll() = migrated.locks.map { FolderLockEntity(it, ByteArray(16), ByteArray(32), 210_000, legacyKey = true) }
        },
        recognitionDao = object : RecognitionDao by unsupported<RecognitionDao>() {
            override fun observeAll(): Flow<List<RecognitionEntity>> = flowOf(
                listOf(
                    RecognitionEntity(assetIdOf(1), 0, faceCount = 2, petVerdict = "NONE", identityVerdict = "NONE"),
                    RecognitionEntity(assetIdOf(2), 0, faceCount = 0, petVerdict = "CAT", identityVerdict = "NONE"),
                    RecognitionEntity(assetIdOf(7), 0, faceCount = 0, petVerdict = "NONE", identityVerdict = "DOCUMENT"),
                ),
            )
        },
        traitDao = object : TraitDao by unsupported<TraitDao>() {
            override suspend fun getAnimatedIds() = listOf(assetIdOf(5))
        },
        referenceNowMs = 1_800_000_000_000L,
    )

    /** What MigrationToV2 writes for [library], with a knob per fact so one can be dropped. */
    private data class MigratedLibrary(
        /** FavoriteStore {1, 2} ∪ MediaStore IS_FAVORITE {4}. */
        val favorites: Set<Long> = setOf(1L, 2L, 4L),
        val sensitive: Set<Long> = setOf(6L),
        val archived: Set<Long> = setOf(7L),
        /** tag_media {2} plus the stray auto_tag_media member 5. */
        val sunsetMembers: Set<Long> = setOf(2L, 5L),
        val locks: Set<String> = setOf("path:pictures/vault"),
    )

    private fun assetIdOf(mediaId: Long) = AssetId(ASSET_ID_OFFSET + mediaId)

    /** A migrated row. asset_id is deliberately NOT the media id, as after a real migration. */
    private fun MediaAsset.toEntity() = AssetEntity(
        assetId = assetIdOf(id.value),
        sourceId = SourceId(1),
        locator = id.value.toString(),
        contentUri = contentUriString,
        displayName = displayName,
        mime = mimeType,
        formatId = FormatId("test"),
        sizeBytes = sizeBytes,
        width = width,
        height = height,
        durationMs = durationMillis,
        dateTakenMs = dateTakenMillis,
        dateModifiedMs = dateModifiedSeconds * 1_000L,
        dateAddedMs = null,
        relativePath = relativePath,
        folderKey = "1:unused-by-the-old-projections",
        bucketId = bucketId,
        bucketName = bucketName,
        fingerprint = null,
        contentHash = null,
        xmpDocumentId = null,
        availability = "ONLINE",
        trashed = isTrashed,
        trashedAtMs = null,
        msGenerationModified = null,
        revision = 0,
    )

    private companion object {
        const val ASSET_ID_OFFSET = 1_000L

        fun asset(
            id: Long,
            name: String = "IMG_$id.jpg",
            mime: String = "image/jpeg",
            relativePath: String = "DCIM/Camera/",
            bucketName: String = "Camera",
            trashed: Boolean = false,
            mediaStoreFavorite: Boolean = false,
        ) = MediaAsset(
            id = MediaId(id),
            contentUriString = "content://media/external/file/$id",
            displayName = name,
            mimeType = mime,
            bucketName = bucketName,
            bucketId = null,
            // Newest ~now, so RECENT has members, and distinct per id so ordering is meaningful.
            dateTakenMillis = 1_800_000_000_000L - id * 86_400_000L,
            dateModifiedSeconds = 1_800_000_000L - id * 86_400L,
            width = 1000 + id.toInt(),
            height = 800,
            sizeBytes = 1_000_000L * id,
            relativePath = relativePath,
            isFavorite = mediaStoreFavorite,
            isTrashed = trashed,
        )

        /** Every member of [T] throws, except `Any`'s own, so a fake only needs to override
         *  what the code under test actually calls. */
        inline fun <reified T : Any> unsupported(): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
                when (method.name) {
                    "toString" -> "Unsupported${T::class.java.simpleName}"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> throw UnsupportedOperationException("${T::class.java.simpleName}.${method.name} is not faked")
                }
            } as T
    }
}
