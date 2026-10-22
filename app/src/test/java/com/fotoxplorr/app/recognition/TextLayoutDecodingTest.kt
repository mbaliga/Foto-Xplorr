package com.fotoxplorr.app.recognition

import android.graphics.Bitmap
import android.net.Uri
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.textselect.Quad
import com.fotoxplorr.app.textselect.RawBlock
import com.fotoxplorr.app.textselect.RawLine
import com.fotoxplorr.app.textselect.RawWord
import com.fotoxplorr.core.model.MediaId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * The decode half of the on-demand pass: [DecodingEngine] reads a photo upright at no more than
 * 2048 px, hands that bitmap to a recogniser (a fake here; ML Kit cannot run under a JVM test),
 * builds the layout in the BITMAP's own frame, and never leaves the bitmap or the recogniser
 * behind. NATIVE graphics, like the other tests that decode a real PNG.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class TextLayoutDecodingTest {

    private val context get() = RuntimeEnvironment.getApplication()

    private class FakeRecogniser(val onRecognise: (Bitmap) -> List<RawBlock>) : WordRecogniser {
        var seen: Bitmap? = null
        var closed = 0
        override suspend fun recognise(bitmap: Bitmap): List<RawBlock> {
            seen = bitmap
            return onRecognise(bitmap)
        }

        override fun close() {
            closed += 1
        }
    }

    private fun png(name: String, width: Int, height: Int): File {
        val file = File(context.cacheDir, name)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF336699.toInt())
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }

    private fun asset(file: File, width: Int, height: Int): MediaAsset {
        val uri = Uri.fromFile(file)
        Shadows.shadowOf(context.contentResolver).registerInputStreamSupplier(uri) { FileInputStream(file) }
        return MediaAsset(
            id = MediaId(11),
            contentUriString = uri.toString(),
            displayName = file.name,
            mimeType = "image/png",
            bucketName = "Camera",
            dateTakenMillis = 1L,
            dateModifiedSeconds = 1_000L,
            width = width,
            height = height,
            sizeBytes = file.length(),
            relativePath = "DCIM/Camera/",
            isFavorite = false,
            isTrashed = false,
        )
    }

    private fun oneWord(text: String) =
        listOf(RawBlock(listOf(RawLine(listOf(RawWord(text, Quad.fromBounds(0.25f, 0.25f, 0.5f, 0.5f)))))))

    @Test
    fun `a large photo is read at no more than 2048 px and the layout is in that bitmap's frame`() = runBlocking {
        val file = png("wide.png", 3000, 1500)
        val recogniser = FakeRecogniser { oneWord("Exit") }
        val engine = DecodingEngine(context) { recogniser }

        val layout = engine.read(asset(file, 3000, 1500))

        assertNotNull(layout)
        val bitmap = recogniser.seen!!
        assertEquals(2048, maxOf(bitmap.width, bitmap.height))
        assertEquals(bitmap.width.toFloat(), layout!!.frameWidth, 0f)
        assertEquals(bitmap.height.toFloat(), layout.frameHeight, 0f)
        assertEquals(bitmap.width * 0.25f, layout.words.single().quad.left, 1e-2f)
        assertTrue(layout.wordLevel)
        assertTrue("the decode is freed once the pass is over", bitmap.isRecycled)
    }

    @Test
    fun `a photo that cannot be decoded is null and never reaches the recogniser`() = runBlocking {
        val created = AtomicInteger()
        val engine = DecodingEngine(context) { created.incrementAndGet(); FakeRecogniser { oneWord("x") } }
        val missing = MediaAsset(
            id = MediaId(12), contentUriString = "content://nothing/here", displayName = "gone.jpg", mimeType = "image/jpeg",
            bucketName = null, dateTakenMillis = 1L, dateModifiedSeconds = 1L, width = 10, height = 10, sizeBytes = 1L,
            relativePath = null, isFavorite = false, isTrashed = false,
        )

        assertNull(engine.read(missing))
        assertEquals(0, created.get())
    }

    @Test
    fun `a recogniser that fails gives null and still frees the bitmap`() = runBlocking {
        val file = png("small.png", 200, 100)
        val recogniser = FakeRecogniser { throw IllegalStateException("model missing") }
        val engine = DecodingEngine(context) { recogniser }

        assertNull(engine.read(asset(file, 200, 100)))
        assertTrue(recogniser.seen!!.isRecycled)
    }

    @Test
    fun `the recogniser is made once, reused, and closed once`() = runBlocking {
        val file = png("reuse.png", 200, 100)
        val made = AtomicInteger()
        val recogniser = FakeRecogniser { emptyList() }
        val engine = DecodingEngine(context) { made.incrementAndGet(); recogniser }
        val photo = asset(file, 200, 100)

        assertTrue(engine.read(photo)!!.isEmpty)
        engine.read(photo)
        assertEquals(1, made.get())

        engine.close()
        engine.close()
        assertEquals(1, recogniser.closed)

        engine.read(photo)
        assertEquals("a read after close builds a new recogniser", 2, made.get())
    }
}
