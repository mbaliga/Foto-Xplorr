package com.fotoxplorr.app.wall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WallDraftTest {

    private val hung = WallDraft(
        step = WallStep.HANG,
        wallId = 7,
        wallWidthCm = 240.5f,
        distanceCm = 210f,
        kind = SetKind.TRIO,
        pictures = listOf(11, 12, 13),
        frames = listOf(
            WallFrame(11, 0.5f, 0.3f, 30f, 40f, 0f),
            WallFrame(12, 0.25f, 0.31f, 21f, 28f, -2.5f),
            WallFrame(13, 0.75f, 0.31f, 21f, 28f, 3f),
        ),
        selected = 2,
        style = FrameStyle.WOOD,
        matCm = 4f,
        inches = true,
    )

    @Test
    fun `a draft survives being written down and read back`() {
        assertEquals(hung, WallDraft.decode(hung.encode()))
        assertEquals(WallDraft(), WallDraft.decode(WallDraft().encode()))
    }

    @Test
    fun `text that is not a draft reads as nothing, not a crash`() {
        assertNull(WallDraft.decode(""))
        assertNull(WallDraft.decode("2;0;;0.0;0.0;single;0;black;0.0;0;;"))
        assertNull(WallDraft.decode(hung.encode().dropLast(9)))
        assertNull(WallDraft.decode(hung.encode().replace("wood", "gold")))
        assertNull(WallDraft.decode("1;99;;0.0;0.0;single;0;black;0.0;0;;"))
        assertNull(WallDraft.decode("1;0;abc;0.0;0.0;single;0;black;0.0;0;;"))
    }

    @Test
    fun `a photo that is no longer shown leaves the draft`() {
        val r = hung.reconciled(setOf(7L, 11L, 13L))
        assertEquals(listOf(11L, 13L), r.pictures)
        assertEquals(listOf(11L, 13L), r.frames.map { it.assetId })
        assertEquals(1, r.selected)
        assertEquals(WallStep.HANG, r.step)
    }

    @Test
    fun `a wall photo that is no longer shown sends the person back to choose one`() {
        val r = hung.reconciled(setOf(11L, 12L, 13L))
        assertNull(r.wallId)
        assertFalse(r.isCalibrated)
        assertEquals(WallStep.WALL, r.step)
    }

    @Test
    fun `losing every picture on the hanging step goes back to choosing pictures`() {
        assertEquals(WallStep.PICTURES, hung.reconciled(setOf(7L)).step)
    }

    @Test
    fun `nothing changes when everything is still shown`() {
        assertEquals(hung, hung.reconciled(setOf(7L, 11L, 12L, 13L)))
    }

    @Test
    fun `arranging hangs each picture in its box, centred on the wall, at a size that fits`() {
        val d = WallDraft(
            wallId = 1, wallWidthCm = 300f, kind = SetKind.PAIR, pictures = listOf(5, 6), step = WallStep.PICTURES,
        ).arranged(wallHeightCm = 225f)
        assertEquals(listOf(5L, 6L), d.frames.map { it.assetId })
        assertEquals(1f, d.frames.sumOf { it.cx.toDouble() }.toFloat(), 1e-3f)
        assertEquals(d.frames[0].cy, d.frames[1].cy, 0f)
        assertTrue(d.frames.all { it.wCm in 20f..61f })
    }

    private fun cropShare(f: WallFrame, srcW: Int, srcH: Int): Float {
        val crop = PrintMath.coverCrop(srcW, srcH, f.wCm.toDouble(), f.hCm.toDouble())
        return (crop.keptWidth * crop.keptHeight).toFloat()
    }

    @Test
    fun `a landscape photo is hung lying down, so the default frame does not cut about 45 percent off it`() {
        val wall = WallDraft(wallId = 1, wallWidthCm = 300f, pictures = listOf(5), step = WallStep.PICTURES)
        val upright = wall.arranged(225f).frames.single()
        val lying = wall.arranged(225f, mapOf(5L to (4000 to 3000))).frames.single()

        assertTrue("was ${cropShare(upright, 4000, 3000)}", cropShare(upright, 4000, 3000) < 0.6f)
        assertTrue(lying.wCm > lying.hCm)
        assertEquals(upright.hCm, lying.wCm, 1e-3f)
        assertEquals(upright.wCm, lying.hCm, 1e-3f)
        assertTrue("was ${cropShare(lying, 4000, 3000)}", cropShare(lying, 4000, 3000) > 0.9f)
    }

    @Test
    fun `a portrait photo, a square one and a photo of unknown size stay upright`() {
        val wall = WallDraft(wallId = 1, wallWidthCm = 300f, pictures = listOf(5, 6, 7), kind = SetKind.TRIO, step = WallStep.PICTURES)
        val frames = wall.arranged(225f, mapOf(5L to (3000 to 4000), 6L to (3000 to 3000))).frames
        assertTrue(frames.all { it.wCm <= it.hCm })
    }

    @Test
    fun `every picture of a pair takes the orientation its own photo suits, and they do not overlap`() {
        val wall = WallDraft(wallId = 1, wallWidthCm = 300f, kind = SetKind.PAIR, pictures = listOf(5, 6), step = WallStep.PICTURES)
        val sizes = mapOf(5L to (4000 to 3000), 6L to (3000 to 4000))
        val (a, b) = wall.arranged(225f, sizes).frames
        assertTrue(a.wCm > a.hCm)
        assertTrue(b.wCm < b.hCm)
        val gap = kotlin.math.abs(a.cx - b.cx) * wall.wallWidthCm - (a.wCm + b.wCm) / 2 - 2 * wall.borderCm
        assertTrue("gap $gap", gap >= SetLayouts.GAP_CM - 0.01f)
        assertEquals(a.cy, b.cy, 0f)
    }

    @Test
    fun `a trio of landscape photos lies down and still fits the share of the wall`() {
        val wall = WallDraft(wallId = 1, wallWidthCm = 300f, kind = SetKind.TRIO, pictures = listOf(1, 2, 3), step = WallStep.PICTURES)
        val frames = wall.arranged(225f, mapOf(1L to (4000 to 3000), 2L to (4000 to 3000), 3L to (4000 to 3000))).frames
        assertTrue(frames.all { it.wCm > it.hCm })
        val left = frames.minOf { it.cx * 300f - it.wCm / 2 - wall.borderCm }
        val right = frames.maxOf { it.cx * 300f + it.wCm / 2 + wall.borderCm }
        assertTrue(right - left <= 300f * SetLayouts.WALL_SHARE_W + 0.01f)
    }

    @Test
    fun `arranging an uncalibrated wall or with no pictures hangs nothing`() {
        assertTrue(WallDraft(pictures = listOf(1)).arranged(200f).frames.isEmpty())
        assertTrue(WallDraft(wallWidthCm = 200f).arranged(200f).frames.isEmpty())
    }

    @Test
    fun `arranging uses only as many pictures as the layout holds`() {
        val d = WallDraft(wallWidthCm = 300f, kind = SetKind.PAIR, pictures = listOf(1, 2, 3, 4)).arranged(225f)
        assertEquals(2, d.frames.size)
    }

    @Test
    fun `changing one frame leaves the others and ignores a bad index`() {
        val moved = hung.withFrame(1) { it.copy(rotationDeg = 9f) }
        assertEquals(9f, moved.frames[1].rotationDeg, 0f)
        assertEquals(hung.frames[0], moved.frames[0])
        assertEquals(hung, hung.withFrame(9) { it.copy(rotationDeg = 9f) })
    }
}
