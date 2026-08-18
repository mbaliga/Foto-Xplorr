package com.fotoxplorr.app.spatial

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.media.MediaMetadataRetriever
import androidx.exifinterface.media.ExifInterface
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

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

class GeoMetadataRepository(context: Context) {
    private val appContext = context.applicationContext
    private val helper = GeoOpenHelper(appContext)
    private val mutex = Mutex()
    private val state = MutableStateFlow(helper.readState())

    fun observe(): StateFlow<GeoIndexState> = state.asStateFlow()

    suspend fun indexMissing(assets: List<MediaAsset>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val existingIds = helper.readIndexedIds().toMutableSet()
            val missing = assets.filterNot { it.id in existingIds }
            if (missing.isEmpty()) {
                state.value = helper.readState().copy(totalCount = assets.size)
                return@withLock
            }

            state.value = helper.readState().copy(
                totalCount = assets.size,
                isIndexing = true,
                errorMessage = null,
            )

            var completed = 0
            missing.chunked(INDEX_BATCH_SIZE).forEach { batch ->
                val rows = batch.map { asset ->
                    runCatching { readMetadata(asset) }
                        .getOrNull()
                        ?.let { GeoRow(asset.id, it) }
                        ?: GeoRow(asset.id, null)
                }
                helper.upsert(rows)
                completed += batch.size
                state.value = helper.readState().copy(
                    totalCount = assets.size,
                    isIndexing = completed < missing.size,
                )
            }
            state.value = helper.readState().copy(totalCount = assets.size, isIndexing = false)
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

    private fun readMetadata(asset: MediaAsset): GeoMetadata? = if (asset.isVideo) {
        readVideoLocation(asset)
    } else {
        readImageLocation(asset)
    }

    private fun readImageLocation(asset: MediaAsset): GeoMetadata? {
        val descriptor = appContext.contentResolver.openFileDescriptor(asset.contentUri, "r") ?: return null
        return descriptor.use {
            val exif = ExifInterface(it.fileDescriptor)
            val coordinates = FloatArray(2)
            if (!exif.getLatLong(coordinates)) return@use null
            val altitude = exif.getAltitude(Double.NaN).takeUnless(Double::isNaN)
            val direction = exif.getAttributeDouble(
                ExifInterface.TAG_GPS_IMG_DIRECTION,
                Double.NaN,
            ).takeUnless(Double::isNaN)?.normalizeDegrees()
            GeoMetadata(
                mediaId = asset.id,
                latitude = coordinates[0].toDouble(),
                longitude = coordinates[1].toDouble(),
                altitudeMeters = altitude,
                captureDirectionDegrees = direction,
            )
        }
    }

    private fun readVideoLocation(asset: MediaAsset): GeoMetadata? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(appContext, asset.contentUri)
            val raw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION) ?: return null
            val match = VIDEO_LOCATION_PATTERN.find(raw) ?: return null
            val latitude = match.groupValues[1].toDoubleOrNull() ?: return null
            val longitude = match.groupValues[2].toDoubleOrNull() ?: return null
            GeoMetadata(
                mediaId = asset.id,
                latitude = latitude,
                longitude = longitude,
                altitudeMeters = null,
                captureDirectionDegrees = null,
            )
        } finally {
            retriever.release()
        }
    }

    private companion object {
        const val INDEX_BATCH_SIZE = 32
        val VIDEO_LOCATION_PATTERN = Regex("([+-]\\d+(?:\\.\\d+)?)([+-]\\d+(?:\\.\\d+)?)")
    }
}

private data class GeoRow(
    val mediaId: MediaId,
    val metadata: GeoMetadata?,
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
                $COL_MANUAL INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
    }

    /**
     * Adds the manual-location flag to an index built before it existed.
     *
     * `onUpgrade` used to be `= Unit`, which is the shape that silently breaks the first time
     * anyone changes the schema: every existing install keeps a table without the new column and
     * every query naming it throws. The ALTER is guarded because upgrade paths get replayed --
     * a user on version 1 and a user on version 2 both arrive here on the next bump.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            runCatching {
                db.execSQL("ALTER TABLE $TABLE_GEO ADD COLUMN $COL_MANUAL INTEGER NOT NULL DEFAULT 0")
            }
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
                val values = ContentValues(7).apply {
                    put(COL_MEDIA_ID, row.mediaId.value)
                    put(COL_SCANNED, 1)
                    put(COL_HAS_LOCATION, if (metadata == null) 0 else 1)
                    put(COL_LATITUDE, metadata?.latitude)
                    put(COL_LONGITUDE, metadata?.longitude)
                    put(COL_ALTITUDE, metadata?.altitudeMeters)
                    put(COL_DIRECTION, metadata?.captureDirectionDegrees)
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

    private companion object {
        const val DATABASE_NAME = "foto_xplorr_geo.db"
        const val DATABASE_VERSION = 2
        const val TABLE_GEO = "geo_metadata"
        const val COL_MEDIA_ID = "media_id"
        const val COL_SCANNED = "scanned"
        const val COL_HAS_LOCATION = "has_location"
        const val COL_MANUAL = "manual"
        const val COL_LATITUDE = "latitude"
        const val COL_LONGITUDE = "longitude"
        const val COL_ALTITUDE = "altitude"
        const val COL_DIRECTION = "direction"
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
