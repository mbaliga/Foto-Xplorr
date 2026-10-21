package com.fotoxplorr.app.recognition

import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.palette.PaletteSampling
import com.fotoxplorr.app.palette.PaletteSwatch
import com.fotoxplorr.core.model.MediaId
import com.fotoxplorr.core.search.ColourSearch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.abs

/**
 * The palette-only pass: a photo recognised before palettes were stored gets one from a single
 * sample-size decode, with no detector involved, and its faces/labels/text are left exactly as
 * they were.
 *
 * "No detector involved" is proven by construction rather than by a fake: [RecognitionIndexer]
 * only creates its ML Kit clients inside the detector pass, which only runs when
 * [RecognitionStore.pendingAssets] is non-empty, and creating one under Robolectric (no Play
 * services, no MlKitContext) throws -- so [RecognitionIndexer.index] returning success here is
 * itself the evidence that no client was built, on top of the row contents being untouched.
 *
 * NATIVE graphics, same as `BitmapDecodingImageDecoderExifTest`: the pass decodes a real PNG.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class RecognitionIndexerPaletteTest {

    private val context get() = RuntimeEnvironment.getApplication()

    private fun solidPng(name: String, argb: Int, width: Int = 40, height: Int = 30): File {
        val file = File(context.cacheDir, name)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(argb)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }

    /**
     * A catalogue asset backed by [file]. The decode path reads the asset through the
     * ContentResolver (orientation first, then pixels), so the file is registered with
     * Robolectric's resolver as a fresh stream per open rather than relying on the shadow's own
     * handling of the `file` scheme.
     */
    private fun asset(id: Long, file: File, width: Int = 40, height: Int = 30): MediaAsset {
        val uri = Uri.fromFile(file)
        Shadows.shadowOf(context.contentResolver).registerInputStreamSupplier(uri) { FileInputStream(file) }
        return MediaAsset(
            id = MediaId(id),
            contentUriString = uri.toString(),
            displayName = file.name,
            mimeType = "image/png",
            bucketName = "Camera",
            dateTakenMillis = id,
            dateModifiedSeconds = 1_000L,
            width = width,
            height = height,
            sizeBytes = file.length(),
            relativePath = "DCIM/Camera/",
            isFavorite = false,
            isTrashed = false,
        )
    }

    /** A recognition row as a pre-v5 install left it: faces and labels stored, no palette rows at all. */
    private suspend fun storeLegacyRow(store: RecognitionStore, asset: MediaAsset) {
        store.upsert(
            listOf(
                AssetRecognition(
                    mediaId = asset.id,
                    sourceRevision = asset.recognitionRevision(),
                    faceCount = 2,
                    labels = listOf("Flower"),
                ),
            ),
        )
        // upsert records "sampled, nothing visible" for an empty palette; a legacy row has no
        // palette rows at all, which is the one state the palette-only pass is for.
        SQLiteDatabase.openDatabase(
            context.getDatabasePath("foto_xplorr_recognition.db").path, null, SQLiteDatabase.OPEN_READWRITE,
        ).use { db -> db.execSQL("DELETE FROM asset_palette WHERE media_id = ${asset.id.value}") }
    }

    @Test
    fun `the palette-only pass samples a legacy row without touching what the detectors stored`() = runBlocking {
        val red = Color.argb(255, 200, 30, 30)
        val photo = asset(7, solidPng("legacy-red.png", red))
        val store = RecognitionStore(context)
        storeLegacyRow(store, photo)
        assertEquals(listOf(photo), store.pendingPaletteOnly(listOf(photo)))
        assertTrue(store.pendingAssets(listOf(photo)).isEmpty())

        val result = RecognitionIndexer(context, store).index(listOf(photo))

        // Success at all means no ML Kit client was ever created -- see the class KDoc.
        assertTrue(result.exceptionOrNull()?.toString().orEmpty(), result.isSuccess)
        assertEquals(0, result.getOrThrow())

        val index = store.observe().value
        // PNG is lossless and 40x30 is already under the sample size, so the colour is exact.
        assertEquals(listOf(PaletteSwatch(red, 1f)), index.paletteFor(photo.id))
        // And search sees the same photo as red, through the pre-converted sample.
        assertTrue(ColourSearch.matches("red", index.searchPaletteFor(photo.id)))
        assertFalse(ColourSearch.matches("blue", index.searchPaletteFor(photo.id)))

        // The detector results are exactly as the legacy row had them.
        assertEquals(setOf(photo.id), index.peopleMediaIds)
        assertEquals(listOf("Flower"), index.labelsByMedia[photo.id])

        // Done once: nothing is pending for either pass any more.
        assertTrue(store.pendingPaletteOnly(listOf(photo)).isEmpty())
        assertTrue(store.pendingAssets(listOf(photo)).isEmpty())
        assertFalse(store.observeProgress().value.running)
    }

    @Test
    fun `a photo that cannot be decoded is retried a bounded number of times, never stored as empty`() = runBlocking {
        val garbage = File(context.cacheDir, "not-an-image.png").apply { writeBytes(ByteArray(64) { 0x5A }) }
        val photo = asset(8, garbage)
        val store = RecognitionStore(context)
        storeLegacyRow(store, photo)
        val indexer = RecognitionIndexer(context, store)

        repeat(2) {
            assertTrue(indexer.index(listOf(photo)).isSuccess)
            // Still pending: a decode failure is a failure to retry, not a fact about the photo.
            assertEquals(listOf(photo), store.pendingPaletteOnly(listOf(photo)))
            assertTrue(store.observe().value.paletteFor(photo.id).isEmpty())
        }
        assertTrue(indexer.index(listOf(photo)).isSuccess)
        // Third strike (P0-12's MAX_FAILURE_ATTEMPTS): no longer asked.
        assertTrue(store.pendingPaletteOnly(listOf(photo)).isEmpty())
        assertTrue(store.observe().value.paletteFor(photo.id).isEmpty())
    }

    @Test
    fun `fromBitmap downsamples before quantising and keeps the proportions`() {
        // 640x480, left half red and right half blue -- the analysis-size bitmap analyse() holds.
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLUE)
        canvas.clipRect(0, 0, 320, 480)
        canvas.drawColor(Color.RED)

        val swatches = PaletteSampling.fromBitmap(bitmap)

        assertFalse("the source bitmap is the caller's; it must not be recycled", bitmap.isRecycled)
        // The filtered downscale blends the seam into a sliver of purple, so exact counts are not
        // the point -- the two real colours must come first and each hold about half the image.
        assertTrue("expected at least two swatches, got $swatches", swatches.size >= 2)
        val (first, second) = swatches
        assertTrue("first swatch ${first.hex} should be ~half, was ${first.proportion}", first.proportion in 0.45f..0.55f)
        assertTrue("second swatch ${second.hex} should be ~half, was ${second.proportion}", second.proportion in 0.45f..0.55f)
        val colours = listOf(first.argb, second.argb)
        assertTrue("expected red among $swatches", colours.any { near(it, Color.RED) })
        assertTrue("expected blue among $swatches", colours.any { near(it, Color.BLUE) })
        assertEquals(1f, swatches.sumOf { it.proportion.toDouble() }.toFloat(), 0.001f)
    }

    @Test
    fun `fromBitmap on a solid bitmap is one swatch at full proportion`() {
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(30, 140, 200)) }
        assertEquals(listOf(PaletteSwatch(Color.rgb(30, 140, 200), 1f)), PaletteSampling.fromBitmap(bitmap))
    }

    private fun near(a: Int, b: Int): Boolean =
        abs(Color.red(a) - Color.red(b)) <= 8 && abs(Color.green(a) - Color.green(b)) <= 8 && abs(Color.blue(a) - Color.blue(b)) <= 8
}
