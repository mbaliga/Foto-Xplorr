package com.fotoxplorr.app.aquarium

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The Android half of the creature store: a [Bitmap] to PNG bytes and back. Kept out of
 * [CreatureStore] so the store's rules (order of writes, damaged pairs, the cap) run in a plain
 * JVM test.
 */
internal object SpriteIo {
    /** A stored picture claiming to be bigger than this on a side is refused before it is decoded. */
    const val MAX_LOAD_SIDE = 1024

    fun storeIn(context: Context): CreatureStore =
        CreatureStore(File(context.filesDir, CreatureStore.DIRECTORY_NAME))

    /** The PNG bytes of [sprite], or null when it could not be encoded. */
    fun encodePng(sprite: Bitmap): ByteArray? = try {
        ByteArrayOutputStream().use { out ->
            if (sprite.compress(Bitmap.CompressFormat.PNG, 100, out)) out.toByteArray() else null
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Saves [sprite] as a new creature. PNG encoding and the writes are slow; call off the main thread.
     */
    fun save(store: CreatureStore, sprite: Bitmap, headLeft: Boolean): SaveResult {
        val png = encodePng(sprite) ?: return SaveResult.Failed
        return store.save(png, sprite.width, sprite.height, headLeft)
    }

    /** A creature read back: its record and its decoded picture. */
    class LoadedCreature(val record: StoredCreature, val sprite: Bitmap)

    /**
     * Every creature in [store] whose picture decodes, and the removal of the ones that do not: a
     * pair that lists but is not an image (a power cut can leave a cut-off or empty PNG, since the
     * write is not synced) would otherwise be hidden from the person yet count toward
     * [CreatureStore.MAX_CREATURES], with nothing visible to remove. A picture that fails only for
     * lack of memory is skipped for this run and kept. Reads and deletes files; call off the main thread.
     */
    fun loadAndSweep(store: CreatureStore): List<LoadedCreature> {
        val loaded = ArrayList<LoadedCreature>()
        for (stored in store.list()) {
            try {
                val bitmap = decodeOrNull(stored)
                if (bitmap != null) loaded.add(LoadedCreature(stored.record, bitmap)) else store.delete(stored.record.id)
            } catch (e: OutOfMemoryError) {
                // Not the file's fault: leave it for the next run.
            }
        }
        return loaded
    }

    /**
     * The picture of [stored] as an ARGB bitmap, or null when the PNG is not an image, is bigger
     * than [MAX_LOAD_SIDE], or is not the size its record says (a pair that does not belong together).
     */
    fun decode(stored: StoredSprite): Bitmap? = try {
        decodeOrNull(stored)
    } catch (e: OutOfMemoryError) {
        null
    }

    /** [decode], except that running out of memory is thrown, so a caller can tell it from a damaged file. */
    private fun decodeOrNull(stored: StoredSprite): Bitmap? {
        val record = stored.record
        if (record.widthPx > MAX_LOAD_SIDE || record.heightPx > MAX_LOAD_SIDE) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(stored.png, 0, stored.png.size, bounds)
            if (bounds.outWidth != record.widthPx || bounds.outHeight != record.heightPx) return null
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            BitmapFactory.decodeByteArray(stored.png, 0, stored.png.size, opts)
        } catch (e: Exception) {
            null
        }
    }
}
