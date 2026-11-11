package com.fotoxplorr.app.basemap

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.OpenableColumns
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** Plain words for the import row. One short sentence each. */
object MapFileCopy {
    const val TITLE = "Map file"
    const val SUPPORTED = "Raster MBTiles only; vector MBTiles and PMTiles are not supported in this build."
    const val NONE = "No map file imported."
    const val IMPORT = "Import a map file"
    const val DELETE = "Delete map file"
    const val NOT_SQLITE = "That is not an MBTiles file."
    const val IS_PMTILES = "PMTiles is not supported; choose a raster MBTiles file."
    const val IS_VECTOR = "That file holds vector tiles, which this build cannot draw."
    const val NO_SPACE = "There is not enough free space for that map file."
    const val NO_TILES = "That map file holds no tiles."
    const val BAD_ZOOM = "That map file declares a zoom range this build cannot use."
    const val UNREADABLE = "That map file could not be read."
    const val DONE = "Map file imported."
    fun size(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
        else -> "${(bytes / 1024).coerceAtLeast(1)} KB"
    }
}

/** The imported raster MBTiles file, copied into app-private storage. No permission is needed. */
object MapFileStore {
    /** Bumps whenever a file is imported or deleted so maps can reopen it. */
    val changes = AtomicInteger(0)

    private fun file(context: Context) = File(File(context.filesDir, "maps").apply { mkdirs() }, "imported.mbtiles")

    /** The size in bytes of the imported file, or null when there is none. */
    fun sizeBytes(context: Context): Long? = file(context).takeIf { it.isFile }?.length()

    fun delete(context: Context) {
        file(context).delete()
        changes.incrementAndGet()
    }

    /** Opens the imported file for reading, or null when none or unreadable. */
    fun open(context: Context): TileSource? {
        val f = file(context).takeIf { it.isFile } ?: return null
        return runCatching { SqliteMbTiles(f.path) }.getOrNull()
    }

    sealed interface Result {
        data object Imported : Result
        data class Rejected(val message: String) : Result
    }

    /** Copies [uri] in, checks it is a raster MBTiles file with tiles, and replaces any earlier import. */
    fun import(context: Context, uri: Uri): Result {
        val target = file(context)
        val temp = File(target.parentFile, "incoming.tmp")
        temp.delete() // a copy that was killed halfway leaves this behind
        try {
            declaredSize(context, uri)?.let { declared ->
                if (declared > target.parentFile!!.usableSpace) return Result.Rejected(MapFileCopy.NO_SPACE)
            }
            context.contentResolver.openInputStream(uri).use { input ->
                if (input == null) return Result.Rejected(MapFileCopy.UNREADABLE)
                val head = ByteArray(16)
                var got = 0
                while (got < head.size) {
                    val n = input.read(head, got, head.size - got)
                    if (n < 0) break
                    got += n
                }
                when (MbTilesMath.sniff(head.copyOf(got))) {
                    MbTilesMath.FileKind.PMTILES -> return Result.Rejected(MapFileCopy.IS_PMTILES)
                    MbTilesMath.FileKind.OTHER -> return Result.Rejected(MapFileCopy.NOT_SQLITE)
                    MbTilesMath.FileKind.SQLITE -> Unit
                }
                temp.outputStream().use { out ->
                    out.write(head, 0, got)
                    input.copyTo(out)
                }
            }
            val source = try {
                SqliteMbTiles(temp.path)
            } catch (_: IllegalArgumentException) {
                return Result.Rejected(MapFileCopy.BAD_ZOOM)
            } catch (_: RuntimeException) {
                return Result.Rejected(MapFileCopy.UNREADABLE)
            }
            val verdict = try {
                when {
                    !MbTilesMath.isRasterFormat(source.format) ->
                        if (source.format.equals("pbf", true)) MapFileCopy.IS_VECTOR else MapFileCopy.UNREADABLE
                    !source.hasTiles() -> MapFileCopy.NO_TILES
                    else -> null
                }
            } finally {
                source.close()
            }
            if (verdict != null) return Result.Rejected(verdict)
            // One atomic replace: the earlier import survives unless the new file is fully in place.
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            changes.incrementAndGet()
            return Result.Imported
        } catch (e: IOException) {
            val full = target.parentFile!!.usableSpace < LOW_SPACE_BYTES || e.message?.contains("ENOSPC") == true ||
                e.message?.contains("No space", ignoreCase = true) == true
            return Result.Rejected(if (full) MapFileCopy.NO_SPACE else MapFileCopy.UNREADABLE)
        } catch (_: SecurityException) {
            return Result.Rejected(MapFileCopy.UNREADABLE)
        } finally {
            temp.delete()
        }
    }

    private const val LOW_SPACE_BYTES = 8L * 1024 * 1024

    private fun declaredSize(context: Context, uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0).takeIf { n -> n > 0 } else null
        }
    }.getOrNull()
}

/** Reads an MBTiles file with the platform's SQLite; nothing to add to the build. */
class SqliteMbTiles(path: String) : TileSource {
    private val db: SQLiteDatabase = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)

    val format: String? = meta("format")
    override val minZoom: Int = meta("minzoom")?.toIntOrNull() ?: zoomExtreme("MIN")
    override val maxZoom: Int = meta("maxzoom")?.toIntOrNull() ?: zoomExtreme("MAX")

    init {
        if (!MbTilesMath.isValidZoomRange(minZoom, maxZoom)) {
            db.close()
            throw IllegalArgumentException("Bad MBTiles zoom range $minZoom..$maxZoom")
        }
    }

    /** True when the file has at least one tile row. */
    fun hasTiles(): Boolean = runCatching {
        db.rawQuery("SELECT 1 FROM tiles LIMIT 1", null).use { it.moveToFirst() }
    }.getOrDefault(false)

    private fun meta(name: String): String? =
        runCatching {
            db.rawQuery("SELECT value FROM metadata WHERE name = ?", arrayOf(name)).use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()

    private fun zoomExtreme(fn: String): Int =
        runCatching {
            db.rawQuery("SELECT $fn(zoom_level) FROM tiles", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        }.getOrDefault(0)

    override fun tile(z: Int, x: Int, yXyz: Int): ByteArray? = runCatching {
        db.rawQuery(
            "SELECT tile_data FROM tiles WHERE zoom_level = ? AND tile_column = ? AND tile_row = ?",
            arrayOf(z.toString(), x.toString(), MbTilesMath.tmsRow(z, yXyz).toString()),
        ).use { if (it.moveToFirst()) it.getBlob(0) else null }
    }.getOrNull()

    override fun close() {
        runCatching { db.close() }
    }
}
