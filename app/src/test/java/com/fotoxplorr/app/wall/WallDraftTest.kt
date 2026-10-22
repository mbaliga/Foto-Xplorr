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
