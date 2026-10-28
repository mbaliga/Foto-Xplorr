package com.fotoxplorr.app.palettebuilder

import android.graphics.Bitmap
import com.fotoxplorr.app.media.DecodeLimits
import com.fotoxplorr.app.media.DecodedBitmap
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.model.MediaId
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.util.Collections

/** Fakes and builders the palette builder's Android-side tests share. Bitmaps need Robolectric. */
internal object PaletteTestKit {

    fun asset(id: Long, name: String = "photo$id.jpg", mime: String = "image/jpeg", taken: Long = id * 1000) = MediaAsset(
        id = MediaId(id), contentUriString = "content://media/external/images/media/$id", displayName = name,
        mimeType = mime, bucketName = null, dateTakenMillis = taken, dateModifiedSeconds = 0,
        width = 400, height = 300, sizeBytes = 1, relativePath = null, isFavorite = false, isTrashed = false,
    )

    fun bitmap(width: Int, height: Int, colour: (x: Int, y: Int) -> Int): Bitmap {
        val b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val px = IntArray(width * height) { colour(it % width, it / width) }
        b.setPixels(px, 0, width, 0, 0, width, height)
        return b
    }

    fun pixels(bitmap: Bitmap): IntArray = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }

    const val RED = 0xFFDC2828.toInt()
    const val GREEN = 0xFF28A028.toInt()
    const val BLUE = 0xFF1E3CDC.toInt()
    const val YELLOW = 0xFFE6D21E.toInt()

    /**
     * A [PaletteHost] whose photos are bitmaps made by [make] (null: unreadable). [answers] are the replies to
     * [mayExport], one per ask, the last repeating; [blocked] ids are always refused.
     */
    class FakeHost(
        private val make: (MediaAsset) -> Bitmap? = { null },
        private val answers: List<Boolean> = listOf(true),
        private val blocked: Set<Long> = emptySet(),
        private val outOfMemoryFirst: Int = 0,
        private val downscaled: Boolean = false,
        private val onDecode: suspend () -> Unit = {},
    ) : PaletteHost {
        val limits: MutableList<DecodeLimits> = Collections.synchronizedList(ArrayList())
        val decoded: MutableList<Long> = Collections.synchronizedList(ArrayList())
        @Volatile var asked = 0

        override fun mayExport(asset: MediaAsset): Boolean {
            if (asset.id.value in blocked) return false
            return answers[minOf(asked++, answers.lastIndex)]
        }

        override suspend fun decode(asset: MediaAsset, limits: DecodeLimits): DecodedBitmap? {
            this.limits += limits
            onDecode()
            if (this.limits.size <= outOfMemoryFirst) throw OutOfMemoryError("test")
            decoded += asset.id.value
            val bitmap = make(asset) ?: return null
            return DecodedBitmap(bitmap, bitmap.width, bitmap.height, downscaled = downscaled)
        }
    }

    class RecordingSink(
        private val result: Result<String> = Result.success("content://media/external/images/media/900"),
        private val onSave: suspend () -> Unit = {},
        private val stepDown: Boolean = false,
        /** Like the editor's writer, which finishes its write even when the job is cancelled. */
        private val uncancellable: Boolean = false,
    ) : RecolourSink {
        class Saved(val source: MediaAsset, val pixels: IntArray, val width: Int, val height: Int)

        val saved: MutableList<Saved> = Collections.synchronizedList(ArrayList())
        val discarded: MutableList<String> = Collections.synchronizedList(ArrayList())

        /** The job that called [save], seen from outside the uncancellable part, so a test can wait for its cancel. */
        @Volatile var callerJob: Job? = null

        override suspend fun save(source: MediaAsset, bitmap: Bitmap, onSteppedDown: () -> Unit): Result<String> {
            callerJob = currentCoroutineContext()[Job]
            // Copy now: the job recycles the bitmap when it is done.
            saved += Saved(source, pixels(bitmap), bitmap.width, bitmap.height)
            if (stepDown) onSteppedDown()
            if (uncancellable) withContext(NonCancellable) { onSave() } else onSave()
            return result
        }

        override suspend fun discard(ref: String) {
            discarded += ref
        }
    }

    /** A [PaletteLibrary] in memory, with the same name rule as [PaletteStore]. */
    class MemoryLibrary(initial: List<Palette> = emptyList(), private val full: Boolean = false, private val failing: Boolean = false) : PaletteLibrary {
        val palettes: MutableList<Palette> = initial.toMutableList()

        override suspend fun load(): SavedPalettes = SavedPalettes(palettes.toList())

        override suspend fun save(palette: Palette): StoreSave {
            if (failing) return StoreSave.Failed
            val replaced = palettes.removeAll { it.name.equals(palette.name, ignoreCase = true) }
            if (!replaced && full) return StoreSave.Full
            palettes.add(0, palette)
            return StoreSave.Saved(replaced)
        }

        override suspend fun delete(name: String): Boolean = palettes.removeAll { it.name.equals(name, ignoreCase = true) }
    }

    class FakeShare(private val works: Boolean = true) : PaletteShare {
        val texts = ArrayList<Pair<Palette, ExportFormat>>()
        val cards = ArrayList<Palette>()

        override fun text(palette: Palette, format: ExportFormat): Boolean {
            texts += palette to format
            return works
        }

        override suspend fun card(palette: Palette, stillAllowed: () -> Boolean): Boolean {
            if (!stillAllowed()) return false
            cards += palette
            return works
        }
    }
}
