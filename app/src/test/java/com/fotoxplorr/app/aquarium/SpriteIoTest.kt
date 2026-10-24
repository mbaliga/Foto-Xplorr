package com.fotoxplorr.app.aquarium

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The Android half of the store on real files and the real decoder: a pair that lists but will
 * not decode (a power cut can leave a truncated or empty PNG) is removed on load, so it neither
 * hides in the folder nor counts toward the cap.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class SpriteIoTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dir get() = File(tmp.root, "aquarium")
    private fun store() = CreatureStore(dir)

    private fun goodPng(w: Int = 40, h: Int = 30): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (x in 0 until w) for (y in 0 until h) bmp.setPixel(x, y, Color.argb(255, (x * 6) % 256, (y * 8) % 256, (x * y) % 256))
        return SpriteIo.encodePng(bmp)!!
    }

    private fun savedId(r: SaveResult) = (r as SaveResult.Saved).creature.id

    @Test
    fun `a good pair is loaded with its picture and kept`() {
        val s = store()
        val id = savedId(s.save(goodPng(), 40, 30, false))
        val loaded = SpriteIo.loadAndSweep(s)
        assertEquals(listOf(id), loaded.map { it.record.id })
        assertEquals(40, loaded.single().sprite.width)
        assertEquals(1, s.list().size)
    }

    @Test
    fun `a truncated picture is swept and does not count toward the cap`() {
        val s = store()
        val good = goodPng()
        val ids = List(CreatureStore.MAX_CREATURES - 1) { savedId(s.save(good, 40, 30, false)) }
        // The twelfth is a PNG cut off after its header, as a power cut could leave it.
        val brokenId = savedId(s.save(good.copyOf(60), 40, 30, false))
        assertEquals(SaveResult.Full, s.save(good, 40, 30, false))

        val loaded = SpriteIo.loadAndSweep(s)

        assertEquals(ids.toSet(), loaded.map { it.record.id }.toSet())
        assertFalse(File(dir, "$brokenId.png").exists())
        assertFalse(File(dir, "$brokenId.json").exists())
        assertEquals(CreatureStore.MAX_CREATURES - 1, s.list().size)
        assertTrue("room again", s.save(good, 40, 30, false) is SaveResult.Saved)
    }

    @Test
    fun `an empty picture file and a pair of the wrong size are swept too`() {
        val s = store()
        val keep = savedId(s.save(goodPng(), 40, 30, false))
        val empty = savedId(s.save(byteArrayOf(0), 40, 30, false))
        File(dir, "$empty.png").writeBytes(ByteArray(0))
        // A real picture, but not the size its record says.
        val wrongSize = savedId(s.save(goodPng(20, 10), 40, 30, false))

        val loaded = SpriteIo.loadAndSweep(s)

        assertEquals(listOf(keep), loaded.map { it.record.id })
        assertEquals(listOf(keep), s.list().map { it.record.id })
        assertFalse(File(dir, "$empty.json").exists())
        assertFalse(File(dir, "$wrongSize.json").exists())
    }

    @Test
    fun `a folder that does not exist loads as empty`() {
        assertTrue(SpriteIo.loadAndSweep(CreatureStore(File(tmp.root, "nothing-here"))).isEmpty())
    }
}
