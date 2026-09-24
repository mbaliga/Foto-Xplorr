package com.fotoxplorr.app.spatial

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.openForLocationRead
import com.fotoxplorr.app.media.uriForLocationRead
import com.fotoxplorr.core.model.MediaId
import java.io.FileDescriptor
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class GeoMetadata(
    val mediaId: MediaId,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?,
    val captureDirectionDegrees: Double?,
)

data class GeoIndexState(
    val metadataById: Map<MediaId, GeoMetadata> = emptyMap(),
    val scannedCount: Int = 0,
    val totalCount: Int = 0,
    val isIndexing: Boolean = false,
    val errorMessage: String? = null,
) {
    val locatedCount: Int
        get() = metadataById.size
}

/** The result of attempting to read one asset's location, before the persistence decision
 * ([shouldPersist]) is applied. */
internal sealed interface LocationReadOutcome {
    data class Located(val metadata: GeoMetadata) : LocationReadOutcome
    data object NoLocation : LocationReadOutcome
    data object Unreadable : LocationReadOutcome
}

/**
 * Whether [outcome] is safe to write to the index (P0-02).
 *
 * A located fix is always safe to persist. An absence is safe only when the read actually asked
 * for the unredacted original ([original]) or the platform never redacts location to begin with
 * (below API 29) -- otherwise "no location" might just be Android's own redaction of a photo that
 * really does have GPS, and persisting it would permanently hide that photo's location the moment
 * `ACCESS_MEDIA_LOCATION` was denied or not yet granted. [LocationReadOutcome.Unreadable] is never
 * persisted: a transient open failure is not a fact about the photo.
 */
internal fun shouldPersist(outcome: LocationReadOutcome, apiLevel: Int, original: Boolean): Boolean =
    when (outcome) {
        is LocationReadOutcome.Located -> true
        LocationReadOutcome.NoLocation -> original || apiLevel < Build.VERSION_CODES.Q
        LocationReadOutcome.Unreadable -> false
    }

/** A GPS fix parsed from an ISO 6709 string -- the shape `MediaMetadataRetriever`'s
 * `METADATA_KEY_LOCATION` returns for video, e.g. `+27.5916+086.5640+8850/`. */
internal data class Iso6709Location(val latitude: Double, val longitude: Double, val altitudeMeters: Double?)

/**
 * Parses an ISO 6709 coordinate string: `±DD.DDDD±DDD.DDDD[±AAA.AAA]/`. Returns null for anything
 * that doesn't parse, a latitude or longitude out of range, or the exact all-zero coordinate
 * `+0.0000+000.0000` some encoders write in place of omitting location entirely.
 */
internal fun parseIso6709(raw: String): Iso6709Location? {
    val match = ISO6709_PATTERN.find(raw) ?: return null
    val latitude = match.groupValues[1].toDoubleOrNull() ?: return null
    val longitude = match.groupValues[2].toDoubleOrNull() ?: return null
    if (abs(latitude) > 90.0 || abs(longitude) > 180.0) return null
    if (latitude == 0.0 && longitude == 0.0) return null
    val altitude = match.groupValues[3].takeIf(String::isNotEmpty)?.toDoubleOrNull()
    return Iso6709Location(latitude, longitude, altitude)
}

private val ISO6709_PATTERN =
    Regex("([+-]\\d+(?:\\.\\d+)?)([+-]\\d+(?:\\.\\d+)?)([+-]\\d+(?:\\.\\d+)?)?/?")

class GeoMetadataRepository(context: Context) {
    private val appContext = context.applicationContext
    private val helper = GeoOpenHelper(appContext)
    private val mutex = Mutex()
    private val state = MutableStateFlow(helper.readState())

    fun observe(): StateFlow<GeoIndexState> = state.asStateFlow()

    suspend fun indexMissing(assets: List<MediaAsset>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val existingIds = helper.readIndexedIds()
            val missing = assets.filterNot { it.id in existingIds }
            if (missing.isEmpty()) {
                state.value = state.value.copy(totalCount = assets.size)
                return@withLock
            }

            // Applied as an in-memory delta below, batch by batch, rather than re-reading the
            // whole table after every batch (was O(n^2) for a large missing set).
            var metadataById = state.value.metadataById
            state.value = state.value.copy(totalCount = assets.size, isIndexing = true, errorMessage = null)

            var completed = 0
            missing.chunked(INDEX_BATCH_SIZE).forEach { batch ->
                val toPersist = batch.mapNotNull { asset ->
                    val read = readMetadata(asset)
                    if (!shouldPersist(read.outcome, Build.VERSION.SDK_INT, read.original)) return@mapNotNull null
                    GeoRow(
                        mediaId = asset.id,
                        metadata = (read.outcome as? LocationReadOutcome.Located)?.metadata,
                        checkedWithOriginal = read.original,
                        sourceRevision = asset.dateModifiedSeconds,
                    )
                }
                if (toPersist.isNotEmpty()) helper.upsert(toPersist)
                completed += batch.size

                if (toPersist.isNotEmpty()) {
                    val updated = metadataById.toMutableMap()
                    toPersist.forEach { row ->
                        if (row.metadata != null) updated[row.mediaId] = row.metadata else updated.remove(row.mediaId)
                    }
                    metadataById = updated
                }
                state.value = state.value.copy(
                    metadataById = metadataById,
                    totalCount = assets.size,
                    isIndexing = completed < missing.size,
                )
            }
            state.value = state.value.copy(isIndexing = false)
        }
    }

    /**
     * Record a location the user placed by hand, for a photo whose file carries none.
     *
     * Written to Foto Xplorr's own index rather than into the photo's EXIF. Editing the user's
     * original file to add a GPS tag is a destructive change to their data made on their behalf,
     * and it needs a MediaStore write grant per file on modern Android; neither belongs behind a
     * pin drag. The map, the compass and the detail room all read this index, so a hand-placed
     * location behaves exactly like an embedded one everywhere it matters — it simply does not
     * travel with the file if they copy it elsewhere, which the UI says.
     */
    suspend fun setManualLocation(
        mediaId: MediaId,
        latitude: Double,
        longitude: Double,
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            helper.upsertManual(mediaId, latitude, longitude)
            state.value = helper.readState().copy(totalCount = state.value.totalCount)
        }
    }

    /** Forget a hand-placed location, returning the photo to whatever its file says (usually none). */
    suspend fun clearManualLocation(mediaId: MediaId) = withContext(Dispatchers.IO) {
        mutex.withLock {
            helper.clearManual(mediaId)
            state.value = helper.readState().copy(totalCount = state.value.totalCount)
        }
    }

    suspend fun clearAndReindex(assets: List<MediaAsset>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            helper.clear()
            state.value = GeoIndexState(totalCount = assets.size)
        }
        indexMissing(assets)
    }

    /** Removes index rows for media no longer in the catalogue. [allCatalogueIds] must be every
     * id currently known to the app, trashed included -- anything not in it is gone for good. */
    suspend fun pruneTo(allCatalogueIds: Set<MediaId>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val removed = helper.pruneTo(allCatalogueIds)
            if (removed.isNotEmpty()) {
                state.value = state.value.copy(metadataById = state.value.metadataById - removed)
            }
        }
    }

    private data class MetadataRead(val outcome: LocationReadOutcome, val original: Boolean)

    private fun readMetadata(asset: MediaAsset): MetadataRead = if (asset.isVideo) {
        // MediaMetadataRetriever's video location is never subject to scoped-storage redaction
        // (see uriForLocationRead's KDoc), so a video read is always "checked" for persistence
        // purposes even though setRequireOriginal is an image/MediaStore-only concept.
        MetadataRead(readVideoLocation(asset), original = true)
    } else {
        readImageLocation(asset)
    }

    private fun readImageLocation(asset: MediaAsset): MetadataRead {
        val requested = appContext.uriForLocationRead(asset.contentUri)
        val opened = openForLocationRead(requested, asset.contentUri) { uri ->
            appContext.contentResolver.openFileDescriptor(uri, "r")
        } ?: return MetadataRead(LocationReadOutcome.Unreadable, original = false)
        val (descriptor, original) = opened
        return descriptor.use {
            MetadataRead(exifLocationOutcome(it.fileDescriptor, asset.id), original)
        }
    }

    private fun exifLocationOutcome(fd: FileDescriptor, mediaId: MediaId): LocationReadOutcome = try {
        val exif = ExifInterface(fd)
        val coordinates = FloatArray(2)
        if (!exif.getLatLong(coordinates)) {
            LocationReadOutcome.NoLocation
        } else {
            val altitude = exif.getAltitude(Double.NaN).takeUnless(Double::isNaN)
            val direction = exif.getAttributeDouble(
                ExifInterface.TAG_GPS_IMG_DIRECTION,
                Double.NaN,
            ).takeUnless(Double::isNaN)?.normalizeDegrees()
            LocationReadOutcome.Located(
                GeoMetadata(
                    mediaId = mediaId,
                    latitude = coordinates[0].toDouble(),
                    longitude = coordinates[1].toDouble(),
                    altitudeMeters = altitude,
                    captureDirectionDegrees = direction,
                ),
            )
        }
    } catch (failure: Exception) {
        LocationReadOutcome.Unreadable
    }

    private fun readVideoLocation(asset: MediaAsset): LocationReadOutcome {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(appContext, asset.contentUri)
            val raw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)
                ?: return LocationReadOutcome.NoLocation
            val parsed = parseIso6709(raw) ?: return LocationReadOutcome.NoLocation
            LocationReadOutcome.Located(
                GeoMetadata(
                    mediaId = asset.id,
                    latitude = parsed.latitude,
                    longitude = parsed.longitude,
                    altitudeMeters = parsed.altitudeMeters,
                    captureDirectionDegrees = null,
                ),
            )
        } catch (failure: Exception) {
            LocationReadOutcome.Unreadable
        } finally {
            retriever.release()
        }
    }

    private companion object {
        const val INDEX_BATCH_SIZE = 32
    }
}

private data class GeoRow(
    val mediaId: MediaId,
    val metadata: GeoMetadata?,
    val checkedWithOriginal: Boolean,
    val sourceRevision: Long,
)

private class GeoOpenHelper(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_GEO (
                $COL_MEDIA_ID INTEGER PRIMARY KEY,
                $COL_SCANNED INTEGER NOT NULL,
                $COL_HAS_LOCATION INTEGER NOT NULL,
                $COL_LATITUDE REAL,
                $COL_LONGITUDE REAL,
                $COL_ALTITUDE REAL,
                $COL_DIRECTION REAL,
                $COL_MANUAL INTEGER NOT NULL DEFAULT 0,
                $COL_CHECKED_ORIGINAL INTEGER NOT NULL DEFAULT 0,
                $COL_SOURCE_REVISION INTEGER
            )
            """.trimIndent(),
        )
    }

    /**
     * `onUpgrade` used to be `= Unit`, which is the shape that silently breaks the first time
     * anyone changes the schema: every existing install keeps a table without the new column and
     * every query naming it throws. Each ALTER is guarded because upgrade paths get replayed --
     * an install on version 1 and one on version 2 both arrive here on the next bump.
     *
     * Version 3 (P0-02): every existing "no location" row was written by code that never called
     * `MediaStore.setRequireOriginal`, so on Android 10+ that absence may just be the platform's
     * own redaction, not the photo's real EXIF. Those rows are dropped so `indexMissing` re-reads
     * each one once, correctly, through `uriForLocationRead`. `Located` rows and manual pins
     * (which always have `has_location = 1`) are untouched.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            runCatching {
                db.execSQL("ALTER TABLE $TABLE_GEO ADD COLUMN $COL_MANUAL INTEGER NOT NULL DEFAULT 0")
            }
        }
        if (oldVersion < 3) {
            runCatching {
                db.execSQL("ALTER TABLE $TABLE_GEO ADD COLUMN $COL_CHECKED_ORIGINAL INTEGER NOT NULL DEFAULT 0")
            }
            runCatching {
                db.execSQL("ALTER TABLE $TABLE_GEO ADD COLUMN $COL_SOURCE_REVISION INTEGER")
            }
            db.execSQL("DELETE FROM $TABLE_GEO WHERE $COL_HAS_LOCATION = 0")
        }
    }

    fun upsertManual(mediaId: MediaId, latitude: Double, longitude: Double) {
        val values = ContentValues(5).apply {
            put(COL_MEDIA_ID, mediaId.value)
            put(COL_SCANNED, 1)
            put(COL_HAS_LOCATION, 1)
            put(COL_LATITUDE, latitude)
            put(COL_LONGITUDE, longitude)
            put(COL_MANUAL, 1)
        }
        writableDatabase.insertWithOnConflict(
            TABLE_GEO,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun clearManual(mediaId: MediaId) {
        val values = ContentValues(3).apply {
            put(COL_HAS_LOCATION, 0)
            putNull(COL_LATITUDE)
            putNull(COL_LONGITUDE)
            put(COL_MANUAL, 0)
        }
        writableDatabase.update(
            TABLE_GEO,
            values,
            "$COL_MEDIA_ID = ? AND $COL_MANUAL = 1",
            arrayOf(mediaId.value.toString()),
        )
    }

    fun upsert(rows: List<GeoRow>) {
        writableDatabase.inTransaction { db ->
            rows.forEach { row ->
                val metadata = row.metadata
                val values = ContentValues(9).apply {
                    put(COL_MEDIA_ID, row.mediaId.value)
                    put(COL_SCANNED, 1)
                    put(COL_HAS_LOCATION, if (metadata == null) 0 else 1)
                    put(COL_LATITUDE, metadata?.latitude)
                    put(COL_LONGITUDE, metadata?.longitude)
                    put(COL_ALTITUDE, metadata?.altitudeMeters)
                    put(COL_DIRECTION, metadata?.captureDirectionDegrees)
                    put(COL_CHECKED_ORIGINAL, if (row.checkedWithOriginal) 1 else 0)
                    put(COL_SOURCE_REVISION, row.sourceRevision)
                }
                db.insertWithOnConflict(TABLE_GEO, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
    }

    fun readIndexedIds(): Set<MediaId> = readableDatabase.query(
        TABLE_GEO,
        arrayOf(COL_MEDIA_ID),
        null,
        null,
        null,
        null,
        null,
    ).use { cursor ->
        buildSet(cursor.count) {
            val idColumn = cursor.getColumnIndexOrThrow(COL_MEDIA_ID)
            while (cursor.moveToNext()) add(MediaId(cursor.getLong(idColumn)))
        }
    }

    fun readState(): GeoIndexState = readableDatabase.query(
        TABLE_GEO,
        ALL_COLUMNS,
        null,
        null,
        null,
        null,
        null,
    ).use { cursor ->
        val metadata = linkedMapOf<MediaId, GeoMetadata>()
        val idColumn = cursor.getColumnIndexOrThrow(COL_MEDIA_ID)
        val hasLocationColumn = cursor.getColumnIndexOrThrow(COL_HAS_LOCATION)
        val latitudeColumn = cursor.getColumnIndexOrThrow(COL_LATITUDE)
        val longitudeColumn = cursor.getColumnIndexOrThrow(COL_LONGITUDE)
        val altitudeColumn = cursor.getColumnIndexOrThrow(COL_ALTITUDE)
        val directionColumn = cursor.getColumnIndexOrThrow(COL_DIRECTION)
        while (cursor.moveToNext()) {
            if (cursor.getInt(hasLocationColumn) == 0) continue
            val id = MediaId(cursor.getLong(idColumn))
            metadata[id] = GeoMetadata(
                mediaId = id,
                latitude = cursor.getDouble(latitudeColumn),
                longitude = cursor.getDouble(longitudeColumn),
                altitudeMeters = cursor.doubleOrNull(altitudeColumn),
                captureDirectionDegrees = cursor.doubleOrNull(directionColumn),
            )
        }
        GeoIndexState(
            metadataById = metadata,
            scannedCount = cursor.count,
            totalCount = cursor.count,
        )
    }

    fun clear() {
        writableDatabase.delete(TABLE_GEO, null, null)
    }

    /** Deletes rows for ids not in [allCatalogueIds], returning the ids actually removed. */
    fun pruneTo(allCatalogueIds: Set<MediaId>): Set<MediaId> {
        val toRemove = readIndexedIds() - allCatalogueIds
        if (toRemove.isEmpty()) return emptySet()
        writableDatabase.inTransaction { db ->
            toRemove.chunked(PRUNE_CHUNK_SIZE).forEach { chunk ->
                val placeholders = chunk.joinToString(",") { "?" }
                db.delete(
                    TABLE_GEO,
                    "$COL_MEDIA_ID IN ($placeholders)",
                    chunk.map { it.value.toString() }.toTypedArray(),
                )
            }
        }
        return toRemove
    }

    private companion object {
        const val DATABASE_NAME = "foto_xplorr_geo.db"
        const val DATABASE_VERSION = 3
        const val PRUNE_CHUNK_SIZE = 500
        const val TABLE_GEO = "geo_metadata"
        const val COL_MEDIA_ID = "media_id"
        const val COL_SCANNED = "scanned"
        const val COL_HAS_LOCATION = "has_location"
        const val COL_MANUAL = "manual"
        const val COL_LATITUDE = "latitude"
        const val COL_LONGITUDE = "longitude"
        const val COL_ALTITUDE = "altitude"
        const val COL_DIRECTION = "direction"
        const val COL_CHECKED_ORIGINAL = "checked_with_original"
        const val COL_SOURCE_REVISION = "source_revision"
        val ALL_COLUMNS = arrayOf(
            COL_MEDIA_ID,
            COL_SCANNED,
            COL_HAS_LOCATION,
            COL_LATITUDE,
            COL_LONGITUDE,
            COL_ALTITUDE,
            COL_DIRECTION,
        )
    }
}

private fun SQLiteDatabase.inTransaction(block: (SQLiteDatabase) -> Unit) {
    beginTransaction()
    try {
        block(this)
        setTransactionSuccessful()
    } finally {
        endTransaction()
    }
}

private fun Cursor.doubleOrNull(index: Int): Double? =
    if (isNull(index)) null else getDouble(index)

private fun Double.normalizeDegrees(): Double = ((this % 360.0) + 360.0) % 360.0
