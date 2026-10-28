package com.fotoxplorr.app.animate

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.fotoxplorr.app.editor.Adjustments
import com.fotoxplorr.app.editor.CropRect
import com.fotoxplorr.app.editor.EditRecipe
import com.fotoxplorr.app.editor.RecipeStore
import com.fotoxplorr.app.experimental.ExperimentEnv
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.spatial.GeoIndexState
import com.fotoxplorr.core.model.MediaId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * [ExperimentAnimateHost] over a real [ExperimentEnv] and real JPEG files: the host's own `mayExport`
 * is what decides, a photo that is not in the browsable list is never read, the pixels come back
 * upright (EXIF orientation applied, TRAPS #34), a saved edit is applied with the editor's own
 * renderer, and every bitmap the decoder made is given back.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ExperimentAnimateHostTest {
    private val context = RuntimeEnvironment.getApplication()

    private fun jpeg(name: String, w: Int, h: Int, orientation: Int? = null, red: Int = 200): File {
        val file = File(context.cacheDir, name).apply { deleteOnExit() }
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(red, 40, 90))
            // A marker in the top-left corner, so a turn or a crop can be seen in the pixels.
            for (y in 0 until minOf(h, 20)) for (x in 0 until minOf(w, 20)) setPixel(x, y, Color.rgb(250, 250, 10))
        }
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        if (orientation != null) {
            val exif = ExifInterface(file.path)
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            exif.saveAttributes()
        }
        return file
    }

    private fun asset(id: Long, file: File, w: Int, h: Int, mime: String = "image/jpeg") = MediaAsset(
        id = MediaId(id), contentUriString = Uri.fromFile(file).toString(), displayName = file.name, mimeType = mime,
        bucketName = null, dateTakenMillis = 0, dateModifiedSeconds = 7, width = w, height = h, sizeBytes = file.length(),
        relativePath = null, isFavorite = false, isTrashed = false,
    )

    private fun host(browsable: Set<MediaId>, assets: List<MediaAsset>) = ExperimentAnimateHost(env({ browsable }, assets))

    /** An env whose browsable list is read through [browsable] every time, as the real host's is. */
    private fun env(browsable: () -> Set<MediaId>, assets: List<MediaAsset>) = ExperimentEnv(
        assets = assets, indexInput = assets, geoState = GeoIndexState(), onIndexLocations = {},
        onOpenAsset = { _, _ -> }, context = context, currentBrowsableIds = browsable,
    )

    // ---- photos --------------------------------------------------------------------------------------------

    @Test
    fun `a browsable photo is read upright, no larger than the edge`() {
        val a = asset(1, jpeg("anim-host-a.jpg", 320, 240), 320, 240)
        val h = host(setOf(a.id), listOf(a))
        val f = runBlocking { h.still(a, 160, null) }
        assertNotNull(f)
        assertEquals(160, f!!.width)
        assertEquals(120, f.height)
        val p = f.argb[100 * 160 + 100]
        assertTrue("the colour came through (red ${(p shr 16) and 0xFF})", ((p shr 16) and 0xFF) in 185..215)
    }

    @Test
    fun `a photo is never enlarged`() {
        val a = asset(1, jpeg("anim-host-b.jpg", 320, 240), 320, 240)
        val f = runBlocking { host(setOf(a.id), listOf(a)).still(a, 1000, null) }!!
        assertEquals(320, f.width)
        assertEquals(240, f.height)
    }

    @Test
    fun `EXIF orientation is applied, so a sideways photo is read upright`() {
        val a = asset(1, jpeg("anim-host-c.jpg", 320, 240, orientation = ExifInterface.ORIENTATION_ROTATE_90), 240, 320)
        val f = runBlocking { host(setOf(a.id), listOf(a)).still(a, 500, null) }!!
        assertEquals(240, f.width)
        assertEquals(320, f.height)
    }

    @Test
    fun `a photo that is not browsable is never read`() {
        val file = jpeg("anim-host-d.jpg", 320, 240)
        val a = asset(1, file, 320, 240)
        val h = host(emptySet(), listOf(a))
        file.delete()
        assertNull(runBlocking { h.still(a, 200, null) })
        assertNull(runBlocking { h.bytes(a, 1_000_000) })
        assertFalse(h.mayExport(a))
        assertNull("a missing file for a browsable photo is just unreadable", runBlocking { host(setOf(a.id), listOf(a)).still(a, 200, null) })
    }

    @Test
    fun `a saved edit is applied with the editor's renderer`() {
        val a = asset(1, jpeg("anim-host-e.jpg", 320, 240), 320, 240)
        val h = host(setOf(a.id), listOf(a))
        val turned = runBlocking { h.still(a, 500, EditRecipe(quarterTurns = 1)) }!!
        assertEquals("a quarter turn swaps the sides", 240, turned.width)
        assertEquals(320, turned.height)
        val cropped = runBlocking { h.still(a, 500, EditRecipe(crop = CropRect(0f, 0f, 0.5f, 1f))) }!!
        assertEquals(160, cropped.width)
        assertEquals(240, cropped.height)
        val marker = cropped.argb[5 * cropped.width + 5]
        assertTrue("the marker corner is in the kept part: ${Integer.toHexString(marker)}", ((marker shr 8) and 0xFF) > 200)
        val flipped = runBlocking { h.still(a, 500, EditRecipe(flipHorizontal = true)) }!!
        assertTrue("the marker is on the right after a flip", ((flipped.argb[5 * 320 + 314] shr 8) and 0xFF) > 200)
        assertTrue("and not on the left", ((flipped.argb[5 * 320 + 5] shr 8) and 0xFF) < 100)
    }

    @Test
    fun `a colour edit changes the pixels, and an identity edit does not`() {
        val a = asset(1, jpeg("anim-host-f.jpg", 64, 48), 64, 48)
        val h = host(setOf(a.id), listOf(a))
        val plain = runBlocking { h.still(a, 500, null) }!!
        val same = runBlocking { h.still(a, 500, EditRecipe()) }!!
        assertEquals(plain.argb.toList(), same.argb.toList())
        val darker = runBlocking { h.still(a, 500, EditRecipe(adjustments = Adjustments(exposure = -1f))) }!!
        val before = (plain.argb[40 * 64 + 40] shr 16) and 0xFF
        val after = (darker.argb[40 * 64 + 40] shr 16) and 0xFF
        assertTrue("darker: $before -> $after", after < before - 20)
    }

    @Test
    fun `the editor's autosaved recipe is found by the host`() {
        val a = asset(1, jpeg("anim-host-g.jpg", 64, 48), 64, 48)
        val h = host(setOf(a.id), listOf(a))
        assertNull(h.savedEdit(a))
        RecipeStore(context).save(a, EditRecipe(quarterTurns = 2))
        assertEquals(2, h.savedEdit(a)?.quarterTurns)
        RecipeStore(context).revert(a)
    }

    // ---- animated files ---------------------------------------------------------------------------------------------

    @Test
    fun `the bytes of a browsable file are read whole, a file over the limit is refused with a sentence`() {
        val file = File(context.cacheDir, "anim-host.gif").apply { writeBytes(ByteArray(5000) { it.toByte() }); deleteOnExit() }
        val a = asset(1, file, 10, 10, "image/gif")
        val h = host(setOf(a.id), listOf(a))
        assertEquals(5000, runBlocking { h.bytes(a, 10_000) }!!.size)
        try {
            runBlocking { h.bytes(a, 4000) }
            fail("expected a refusal")
        } catch (e: AnimSourceException) {
            assertEquals(AnimateCopy.FILE_TOO_BIG, e.message)
        }
    }

    @Test
    fun `a file that cannot be opened has no bytes`() {
        val a = asset(1, File(context.cacheDir, "not-there.gif"), 10, 10, "image/gif")
        assertNull(runBlocking { host(setOf(a.id), listOf(a)).bytes(a, 10_000) })
    }

    // ---- videos -------------------------------------------------------------------------------------------------------

    @Test
    fun `a video may be used while its id is in the live browsable list, and not after`() {
        val live = mutableSetOf(MediaId(3))
        val clip = AnimateKit.video(3)
        val photo = AnimateKit.asset(3)
        val h = ExperimentAnimateHost(env({ live }, listOf(clip)))
        assertTrue(h.mayUseVideo(clip))
        assertFalse("a photo is not a video", h.mayUseVideo(photo))
        assertFalse("the host's own check says no to every video, by design", h.mayExport(clip))
        live.clear()
        assertFalse("the list is read live, not as it was when the host was made", h.mayUseVideo(clip))
        assertNull(h.openVideo(clip))
    }

    @Test
    fun `asking whether this phone can encode H264 never throws`() {
        // Robolectric has no codec list to speak of; the answer is a boolean either way.
        val answer = host(emptySet(), emptyList()).canEncodeVideo()
        assertTrue(answer || !answer)
    }
}
