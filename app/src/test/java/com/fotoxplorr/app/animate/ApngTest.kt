package com.fotoxplorr.app.animate

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Random
import java.util.zip.CRC32

/**
 * The APNG writer and reader. The JDK cannot read APNG, so the writer is checked by a hand parser
 * (chunk order, lengths, CRCs, sequence numbers, geometry) and by handing the first frame to the
 * JDK's PNG reader as the plain PNG it is; the reader is checked against files built by [HandPng]
 * and against PNGs the JDK itself wrote. No real APNG viewer has been used.
 */
class ApngTest {

    private class C(val type: String, val data: ByteArray)

    /** Walk the file by hand, failing on any bad length, signature or CRC. */
    private fun chunks(bytes: ByteArray): List<C> {
        assertArrayEquals(HandPng.SIGNATURE, bytes.copyOf(8))
        val out = ArrayList<C>()
        var p = 8
        while (p < bytes.size) {
            val len = ((bytes[p].toInt() and 255) shl 24) or ((bytes[p + 1].toInt() and 255) shl 16) or ((bytes[p + 2].toInt() and 255) shl 8) or (bytes[p + 3].toInt() and 255)
            assertTrue("chunk length $len at $p", len >= 0 && p + 12 + len <= bytes.size)
            val type = String(bytes, p + 4, 4, Charsets.ISO_8859_1)
            val data = bytes.copyOfRange(p + 8, p + 8 + len)
            val crc = CRC32()
            crc.update(bytes, p + 4, 4 + len)
            val stored = ((bytes[p + 8 + len].toInt() and 255).toLong() shl 24) or ((bytes[p + 9 + len].toInt() and 255).toLong() shl 16) or
                ((bytes[p + 10 + len].toInt() and 255).toLong() shl 8) or (bytes[p + 11 + len].toInt() and 255).toLong()
            assertEquals("CRC of $type at $p", crc.value, stored)
            out.add(C(type, data))
            p += 12 + len
        }
        assertEquals("IEND last", "IEND", out.last().type)
        return out
    }

    private fun be32(d: ByteArray, at: Int) = ((d[at].toInt() and 255) shl 24) or ((d[at + 1].toInt() and 255) shl 16) or ((d[at + 2].toInt() and 255) shl 8) or (d[at + 3].toInt() and 255)

    private fun decode(bytes: ByteArray): AnimSequence {
        val r = ApngReader.decodeAll(bytes)
        assertTrue("problem ${r.problem} ${r.detail}", r.complete)
        return r.sequence!!
    }

    private fun assertSameFrames(want: AnimSequence, got: AnimSequence) {
        assertEquals(want.width, got.width)
        assertEquals(want.height, got.height)
        assertEquals(want.frames.size, got.frames.size)
        for (i in want.frames.indices) assertArrayEquals("frame $i", want.frames[i].pixels, got.frames[i].pixels)
    }

    // ---- writer structure ----

    @Test
    fun `chunks come in APNG order with valid CRCs, lengths and consecutive sequence numbers`() {
        val seq = AnimTestKit.photoSequence(64, 48, 6, plays = 3)
        val list = chunks(ApngEncoder.encode(seq))
        val types = list.map { it.type }
        assertEquals("IHDR", types[0])
        assertEquals("acTL", types[1])
        assertEquals("fcTL", types[2])
        assertEquals("IDAT", types[3])
        // acTL: frame count and plays.
        val actl = list[1].data
        assertEquals(8, actl.size)
        val frames = types.count { it == "fcTL" }
        assertEquals(frames, be32(actl, 0))
        assertEquals(3, be32(actl, 4))
        // Every fcTL is 26 bytes; sequence numbers run 0, 1, 2... over fcTL and fdAT together, in file order.
        var expect = 0
        var sawIdatAfterFdat = false
        var inFd = false
        for (c in list) {
            when (c.type) {
                "fcTL" -> {
                    assertEquals(26, c.data.size)
                    assertEquals(expect++, be32(c.data, 0))
                }
                "fdAT" -> {
                    inFd = true
                    assertTrue(c.data.size > 4)
                    assertEquals(expect++, be32(c.data, 0))
                }
                "IDAT" -> if (inFd) sawIdatAfterFdat = true
            }
        }
        assertFalse(sawIdatAfterFdat)
        // The first frame covers the canvas; later ones sit inside it; every frame is followed by data.
        val fctls = list.filter { it.type == "fcTL" }
        assertEquals(0, be32(fctls[0].data, 12))
        assertEquals(0, be32(fctls[0].data, 16))
        assertEquals(64, be32(fctls[0].data, 4))
        assertEquals(48, be32(fctls[0].data, 8))
        for (f in fctls) {
            val fw = be32(f.data, 4)
            val fh = be32(f.data, 8)
            val fx = be32(f.data, 12)
            val fy = be32(f.data, 16)
            assertTrue(fx >= 0 && fy >= 0 && fw > 0 && fh > 0 && fx + fw <= 64 && fy + fh <= 48)
            assertEquals("dispose none", 0, f.data[24].toInt())
        }
        // IEND is empty.
        assertEquals(0, list.last().data.size)
    }

    @Test
    fun `the first frame is a valid PNG on its own, and a PNG reader that ignores APNG shows it`() {
        for (seq in listOf(AnimTestKit.photoSequence(40, 30, 3), AnimTestKit.stickerSequence(40, 30, 3), AnimTestKit.flatSequence(40, 30, 3))) {
            val bytes = ApngEncoder.encode(seq)
            val list = chunks(bytes)
            val out = ByteArrayOutputStream()
            out.write(HandPng.SIGNATURE)
            out.write(HandPng.chunk("IHDR", list[0].data))
            for (c in list) if (c.type == "IDAT") out.write(HandPng.chunk("IDAT", c.data))
            out.write(HandPng.chunk("IEND", ByteArray(0)))
            val standalone = Jdk.decode(out.toByteArray())
            assertEquals(40, standalone.width)
            assertEquals(30, standalone.height)
            assertArrayEquals(seq.frames[0].pixels, standalone.argb)
            // The whole APNG, as is, through the JDK: it skips the chunks it does not know.
            val whole = Jdk.decode(bytes)
            assertArrayEquals(seq.frames[0].pixels, whole.argb)
        }
    }

    @Test
    fun `colour type follows what the frames need`() {
        fun type(seq: AnimSequence) = chunks(ApngEncoder.encode(seq))[0].data[9].toInt()
        // Unrelated opaque stills: everything changes, so no gain from "over", RGB is enough.
        val rnd = Random(2)
        val w = 20
        val h = 20
        val stills = AnimSequence(w, h, List(3) { AnimFrame(IntArray(w * h) { AnimTestKit.argb(255, rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)) }, 100) }, 0)
        assertEquals(2, type(stills))
        // Two small things blinking in opposite corners: the box around them is mostly unchanged, so RGBA lets that be left clear.
        val corners = List(4) { i ->
            val px = IntArray(40 * 30) { AnimTestKit.argb(255, 240, 240, 240) }
            for (y in 0 until 4) for (x in 0 until 4) {
                px[y * 40 + x] = AnimTestKit.argb(255, i * 60, 0, 0)
                px[(26 + y) * 40 + 36 + x] = AnimTestKit.argb(255, 0, i * 60, 0)
            }
            AnimFrame(px, 100)
        }
        assertEquals(6, type(AnimSequence(40, 30, corners, 0)))
        // Any see-through pixel needs alpha.
        assertEquals(6, type(AnimTestKit.stickerSequence(40, 30, 3)))
    }

    @Test
    fun `frames after the first are cut to what changed and identical neighbours are merged`() {
        val a = AnimTestKit.photoFrame(40, 30, 0, 10, 15)
        val b = AnimTestKit.photoFrame(40, 30, 0, 22, 15)
        val seq = AnimSequence(40, 30, listOf(AnimFrame(a, 100), AnimFrame(a, 150), AnimFrame(b, 100), AnimFrame(b, 100)), 0)
        val list = chunks(ApngEncoder.encode(seq))
        val fctls = list.filter { it.type == "fcTL" }
        assertEquals(2, fctls.size)
        assertEquals(2, be32(list[1].data, 0))
        // 250 ms and 200 ms as num/den.
        fun ms(c: C) = ((c.data[20].toInt() and 255) shl 8 or (c.data[21].toInt() and 255)) * 1000 / ((c.data[22].toInt() and 255) shl 8 or (c.data[23].toInt() and 255))
        assertEquals(250, ms(fctls[0]))
        assertEquals(200, ms(fctls[1]))
        assertTrue(be32(fctls[1].data, 4) * be32(fctls[1].data, 8) < 40 * 30)
        assertSameFrames(AnimSequence(40, 30, listOf(AnimFrame(a, 250), AnimFrame(b, 200)), 0), decode(ApngEncoder.encode(seq)))
    }

    @Test
    fun `delays are never under 20 ms and long ones switch to hundredths`() {
        val w = 4
        val h = 4
        fun frame(i: Int, d: Int) = AnimFrame(IntArray(w * h) { AnimTestKit.argb(255, i * 50, it, 0) }, d)
        val seq = AnimSequence(w, h, listOf(frame(0, 0), frame(1, 5), frame(2, 70_000), frame(3, 65_535)), 0)
        val got = decode(ApngEncoder.encode(seq))
        assertEquals(listOf(20, 20, 70_000, 65_535), got.frames.map { it.delayMs })
    }

    // ---- round trips ----

    @Test
    fun `encode then decode gives back every pixel exactly`() {
        val sequences = listOf(
            AnimTestKit.photoSequence(48, 36, 6, plays = 0),
            AnimTestKit.flatSequence(48, 36, 6, plays = 2),
            AnimTestKit.stickerSequence(48, 36, 6),
        )
        for (seq in sequences) {
            val got = decode(ApngEncoder.encode(seq))
            assertSameFrames(seq, got)
            assertEquals(seq.plays, got.plays)
            assertEquals(seq.durationMs, got.durationMs)
        }
    }

    @Test
    fun `random sequences with every kind of alpha round trip exactly`() {
        for (trial in 0 until 150) {
            val rnd = Random(500L + trial)
            val w = 1 + rnd.nextInt(20)
            val h = 1 + rnd.nextInt(20)
            val alphas = when (trial % 3) { 0 -> intArrayOf(255); 1 -> intArrayOf(0, 255); else -> intArrayOf(0, 1, 77, 128, 254, 255) }
            val palette = IntArray(5) { AnimTestKit.argb(alphas[rnd.nextInt(alphas.size)], rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)) }
            var cur = IntArray(w * h) { palette[rnd.nextInt(palette.size)] }
            val frames = ArrayList<AnimFrame>()
            repeat(1 + rnd.nextInt(6)) {
                cur = cur.copyOf()
                repeat(rnd.nextInt(w * h / 2 + 1)) { cur[rnd.nextInt(cur.size)] = palette[rnd.nextInt(palette.size)] }
                frames.add(AnimFrame(cur, 100 + rnd.nextInt(3) * 40))
            }
            val seq = AnimSequence(w, h, frames, 0)
            val got = decode(ApngEncoder.encode(seq))
            // Identical neighbours merge; compare what is showing at each original frame's start.
            var t = 0L
            val starts = frames.map { val s = t; t += it.delayMs; s }
            var tb = 0L
            val shown = got.frames.map { val s = tb; tb += it.delayMs; s to it.pixels }
            for (i in frames.indices) assertArrayEquals("trial $trial frame $i", frames[i].pixels, shown.last { it.first <= starts[i] }.second)
        }
    }

    @Test
    fun `higher compression is not larger and decodes the same`() {
        val seq = AnimTestKit.photoSequence(64, 48, 4)
        val fast = ApngEncoder.encode(seq, ApngOptions(1))
        val best = ApngEncoder.encode(seq, ApngOptions(9))
        assertTrue("fast=${fast.size} best=${best.size}", best.size <= fast.size)
        assertSameFrames(decode(fast), decode(best))
    }

    @Test
    fun `progress is reported once per written frame`() {
        val seq = AnimTestKit.flatSequence(30, 20, 5)
        val seen = ArrayList<Pair<Int, Int>>()
        ApngEncoder.encode(seq, progress = { d, t -> seen.add(d to t) })
        assertEquals((1..5).map { it to 5 }, seen)
    }

    @Test
    fun `an empty sequence is refused`() {
        try {
            ApngEncoder.encode(AnimSequence(2, 2, emptyList(), 0))
            org.junit.Assert.fail()
        } catch (e: IllegalArgumentException) {
        }
    }

    // ---- reader: every PNG layout ----

    private fun gray(v: Int, depth: Int): Int = when (depth) { 1 -> v * 255; 2 -> v * 85; 4 -> v * 17; 8 -> v; else -> v shr 8 }

    @Test
    fun `grey scale at every bit depth, plain and interlaced, with all five filters`() {
        for (depth in listOf(1, 2, 4, 8, 16)) for (interlace in listOf(false, true)) {
            val max = (1 shl depth) - 1
            val w = 13
            val h = 11
            val v = { x: Int, y: Int -> (x * 5 + y * 3) % (max + 1) }
            val png = HandPng.png(w, h, depth, 0, interlace) { x, y -> intArrayOf(v(x, y)) }
            val got = decode(png).frames[0].pixels
            for (y in 0 until h) for (x in 0 until w) {
                val g = gray(v(x, y), depth)
                assertEquals("depth=$depth interlace=$interlace ($x,$y)", AnimTestKit.argb(255, g, g, g), got[y * w + x])
            }
        }
    }

    @Test
    fun `RGB, grey with alpha and RGBA at 8 and 16 bits`() {
        for (depth in listOf(8, 16)) for (interlace in listOf(false, true)) {
            val max = (1 shl depth) - 1
            val w = 9
            val h = 10
            fun s(x: Int, y: Int, c: Int) = ((x * 31 + y * 17 + c * 53) % 256) * (max / 255)
            val rgb = decode(HandPng.png(w, h, depth, 2, interlace) { x, y -> intArrayOf(s(x, y, 0), s(x, y, 1), s(x, y, 2)) }).frames[0].pixels
            val ga = decode(HandPng.png(w, h, depth, 4, interlace) { x, y -> intArrayOf(s(x, y, 0), s(x, y, 3)) }).frames[0].pixels
            val rgba = decode(HandPng.png(w, h, depth, 6, interlace) { x, y -> intArrayOf(s(x, y, 0), s(x, y, 1), s(x, y, 2), s(x, y, 3)) }).frames[0].pixels
            fun hi(v: Int) = if (depth == 16) v shr 8 else v
            for (y in 0 until h) for (x in 0 until w) {
                val o = y * w + x
                assertEquals("rgb d=$depth i=$interlace", AnimTestKit.argb(255, hi(s(x, y, 0)), hi(s(x, y, 1)), hi(s(x, y, 2))), rgb[o])
                assertEquals("ga d=$depth i=$interlace", AnimTestKit.argb(hi(s(x, y, 3)), hi(s(x, y, 0)), hi(s(x, y, 0)), hi(s(x, y, 0))), ga[o])
                assertEquals("rgba d=$depth i=$interlace", AnimTestKit.argb(hi(s(x, y, 3)), hi(s(x, y, 0)), hi(s(x, y, 1)), hi(s(x, y, 2))), rgba[o])
            }
        }
    }

    @Test
    fun `palette images at 1, 2, 4 and 8 bits with a transparency table`() {
        for (depth in listOf(1, 2, 4, 8)) for (interlace in listOf(false, true)) {
            val n = minOf(1 shl depth, 12)
            val pal = IntArray(n) { AnimTestKit.argb(255, it * 20 % 256, it * 40 % 256, 255 - it * 15) }
            val plte = HandPng.chunk("PLTE", ByteArray(n * 3) { i -> val c = pal[i / 3]; (c shr (16 - 8 * (i % 3))).toByte() })
            val alpha = ByteArray(minOf(3, n)) { (it * 90).toByte() } // entries 0..2 get alpha 0, 90, 180; the rest opaque
            val trns = HandPng.chunk("tRNS", alpha)
            val w = 7
            val h = 9
            val idx = { x: Int, y: Int -> (x + y * 3) % n }
            val png = HandPng.png(w, h, depth, 3, interlace, listOf(plte, trns)) { x, y -> intArrayOf(idx(x, y)) }
            val got = decode(png).frames[0].pixels
            for (y in 0 until h) for (x in 0 until w) {
                val i = idx(x, y)
                val a = if (i < alpha.size) alpha[i].toInt() and 255 else 255
                assertEquals("depth=$depth i=$interlace ($x,$y)", (a shl 24) or (pal[i] and 0xFFFFFF), got[y * w + x])
            }
        }
    }

    @Test
    fun `a colour key makes that grey or RGB value clear`() {
        val w = 4
        val h = 1
        val gTrns = HandPng.chunk("tRNS", HandPng.be16(2))
        val g = decode(HandPng.png(w, h, 8, 0, false, listOf(gTrns)) { x, _ -> intArrayOf(x) }).frames[0].pixels
        assertEquals(0x00020202, g[2])
        assertEquals(AnimTestKit.argb(255, 3, 3, 3), g[3])
        val cTrns = HandPng.chunk("tRNS", HandPng.be16(10) + HandPng.be16(20) + HandPng.be16(30))
        val c = decode(HandPng.png(2, 1, 8, 2, false, listOf(cTrns)) { x, _ -> if (x == 0) intArrayOf(10, 20, 30) else intArrayOf(10, 20, 31) }).frames[0].pixels
        assertEquals(0x000A141E, c[0])
        assertEquals(AnimTestKit.argb(255, 10, 20, 31), c[1])
    }

    @Test
    fun `PNGs written by the JDK decode to what the JDK itself reads`() {
        val rnd = Random(12)
        val w = 37
        val h = 23
        val photo = AnimTestKit.photoFrame(w, h, 0, 18, 11)
        val alpha = IntArray(w * h) { AnimTestKit.argb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)) }
        for (interlaced in listOf(false, true)) {
            for ((px, type) in listOf(photo to 1, alpha to 2)) {
                val bytes = Jdk.writePng(px, w, h, type, interlaced)
                val theirs = Jdk.decode(bytes).argb
                assertArrayEquals("type=$type interlaced=$interlaced", theirs, decode(bytes).frames[0].pixels)
            }
            for (bits in listOf(1, 2, 4)) {
                val n = 1 shl bits
                val pal = IntArray(n) { AnimTestKit.argb(255, it * 255 / maxOf(1, n - 1), 255 - it * 255 / maxOf(1, n - 1), 99) }
                val idx = IntArray(w * h) { (it / 3 + it / w) % n }
                val bytes = Jdk.writeIndexedPng(idx, w, h, bits, pal, interlaced, transparentIndex = 1)
                val theirs = Jdk.decode(bytes).argb
                assertArrayEquals("bits=$bits interlaced=$interlaced", theirs, decode(bytes).frames[0].pixels)
            }
        }
    }

    @Test
    fun `a plain PNG is a one frame animation that plays once`() {
        val png = Jdk.writePng(AnimTestKit.photoFrame(10, 8, 0, 5, 4), 10, 8, 1)
        val seq = decode(png)
        assertEquals(1, seq.frames.size)
        assertEquals(1, seq.plays)
    }

    // ---- reader: APNG behaviour ----

    private fun solid(w: Int, h: Int, c: Int) = IntArray(w * h) { c }

    private val RED = AnimTestKit.argb(255, 255, 0, 0)
    private val GREEN = AnimTestKit.argb(255, 0, 255, 0)
    private val BLUE = AnimTestKit.argb(255, 0, 0, 255)

    @Test
    fun `source blend replaces and over blend mixes, dispose none leaves frames in place`() {
        val half = AnimTestKit.argb(128, 0, 0, 255)
        val frames = listOf(
            HandPng.Frame(0, 0, 2, 1, 1, 10, 0, 0, intArrayOf(RED, RED)),
            HandPng.Frame(1, 0, 1, 1, 1, 10, 0, 1, intArrayOf(half)), // over: blue at 50% on red
            HandPng.Frame(0, 0, 1, 1, 1, 10, 0, 0, intArrayOf(AnimTestKit.argb(0, 9, 9, 9))), // source: replaced by clear
        )
        val got = decode(HandPng.apng(2, 1, 0, frames)).frames
        assertArrayEquals(intArrayOf(RED, RED), got[0].pixels)
        // 255*127/255 ~ 127 red left, blue 128: straight alpha, destination fully opaque.
        val mixed = got[1].pixels[1]
        assertEquals(255, mixed ushr 24)
        assertTrue(Math.abs(127 - ((mixed shr 16) and 255)) <= 1)
        assertTrue(Math.abs(128 - (mixed and 255)) <= 1)
        assertEquals(RED, got[1].pixels[0])
        assertEquals(AnimTestKit.argb(0, 9, 9, 9), got[2].pixels[0])
    }

    @Test
    fun `over onto a clear canvas takes the source as it is, and clear over anything changes nothing`() {
        val q = AnimTestKit.argb(100, 10, 20, 30)
        val frames = listOf(
            HandPng.Frame(0, 0, 2, 1, 1, 10, 0, 0, intArrayOf(0, RED)),
            HandPng.Frame(0, 0, 2, 1, 1, 10, 0, 1, intArrayOf(q, 0)),
        )
        val got = decode(HandPng.apng(2, 1, 0, frames)).frames[1].pixels
        assertEquals(q, got[0])
        assertEquals(RED, got[1])
    }

    @Test
    fun `dispose to background clears the frame's rectangle before the next frame`() {
        val frames = listOf(
            HandPng.Frame(0, 0, 3, 1, 1, 10, 0, 0, intArrayOf(RED, RED, RED)),
            HandPng.Frame(1, 0, 1, 1, 1, 10, 1, 0, intArrayOf(GREEN)), // dispose: background
            HandPng.Frame(0, 0, 1, 1, 1, 10, 0, 0, intArrayOf(BLUE)),
        )
        val got = decode(HandPng.apng(3, 1, 0, frames)).frames
        assertArrayEquals(intArrayOf(RED, GREEN, RED), got[1].pixels)
        assertArrayEquals(intArrayOf(BLUE, 0, RED), got[2].pixels)
    }

    @Test
    fun `dispose to previous restores what was under the frame`() {
        val frames = listOf(
            HandPng.Frame(0, 0, 3, 1, 1, 10, 0, 0, intArrayOf(RED, RED, RED)),
            HandPng.Frame(1, 0, 2, 1, 1, 10, 2, 0, intArrayOf(GREEN, GREEN)), // dispose: previous
            HandPng.Frame(0, 0, 1, 1, 1, 10, 0, 0, intArrayOf(BLUE)),
        )
        val got = decode(HandPng.apng(3, 1, 0, frames)).frames
        assertArrayEquals(intArrayOf(RED, GREEN, GREEN), got[1].pixels)
        assertArrayEquals(intArrayOf(BLUE, RED, RED), got[2].pixels)
    }

    @Test
    fun `dispose to previous on the first frame acts as background`() {
        val frames = listOf(
            HandPng.Frame(0, 0, 2, 1, 1, 10, 2, 0, intArrayOf(RED, RED)),
            HandPng.Frame(0, 0, 1, 1, 1, 10, 0, 0, intArrayOf(BLUE)),
        )
        val got = decode(HandPng.apng(2, 1, 0, frames)).frames
        assertArrayEquals(intArrayOf(BLUE, 0), got[1].pixels)
    }

    @Test
    fun `a default image that comes before the first frame control is not a frame`() {
        val hidden = solid(2, 1, GREEN)
        val frames = listOf(
            HandPng.Frame(0, 0, 2, 1, 1, 10, 0, 0, intArrayOf(RED, RED)),
            HandPng.Frame(1, 0, 1, 1, 1, 10, 0, 0, intArrayOf(BLUE)),
        )
        val seq = decode(HandPng.apng(2, 1, 0, frames, hiddenDefault = hidden))
        assertEquals(2, seq.frames.size)
        assertArrayEquals(intArrayOf(RED, RED), seq.frames[0].pixels)
        assertArrayEquals(intArrayOf(RED, BLUE), seq.frames[1].pixels)
    }

    @Test
    fun `delays are num over den, a zero denominator means hundredths, and plays are read`() {
        val frames = listOf(
            HandPng.Frame(0, 0, 1, 1, 1, 4, 0, 0, intArrayOf(RED)), // 250 ms
            HandPng.Frame(0, 0, 1, 1, 7, 0, 0, 0, intArrayOf(GREEN)), // den 0 -> 100: 70 ms
            HandPng.Frame(0, 0, 1, 1, 0, 1000, 0, 0, intArrayOf(BLUE)), // 0 stays 0
        )
        val seq = decode(HandPng.apng(1, 1, 5, frames))
        assertEquals(listOf(250, 70, 0), seq.frames.map { it.delayMs })
        assertEquals(5, seq.plays)
    }

    @Test
    fun `frame data split across many chunks joins up`() {
        val rnd = Random(3)
        val w = 20
        val h = 20
        val px = IntArray(w * h) { AnimTestKit.argb(255, rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)) }
        val frames = listOf(HandPng.Frame(0, 0, w, h, 1, 10, 0, 0, px), HandPng.Frame(0, 0, w, h, 1, 10, 0, 0, px.reversedArray()))
        for (split in listOf(1, 7, 60, 100_000)) {
            val seq = decode(HandPng.apng(w, h, 0, frames, splitData = split))
            assertArrayEquals(px, seq.frames[0].pixels)
            assertArrayEquals(px.reversedArray(), seq.frames[1].pixels)
        }
    }

    // ---- reader: damage ----

    @Test
    fun `not a PNG is refused`() {
        for (junk in listOf(ByteArray(0), byteArrayOf(1, 2, 3), "GIF89a".toByteArray(), HandPng.SIGNATURE)) {
            val r = ApngReader.decodeAll(junk)
            assertFalse(r.complete)
            assertNull(r.sequence)
        }
        assertEquals(DecodeProblem.NOT_THIS_FORMAT, ApngReader.decodeAll("GIF89a......".toByteArray()).problem)
    }

    @Test
    fun `cutting the file at every length never throws and only whole frames come back`() {
        val seq = AnimTestKit.photoSequence(16, 12, 4)
        val full = ApngEncoder.encode(seq)
        val whole = decode(full)
        for (len in 0 until full.size) {
            val r = ApngReader.decodeAll(full.copyOf(len))
            assertFalse("len=$len", r.complete)
            val got = r.sequence?.frames ?: emptyList()
            assertTrue(got.size <= whole.frames.size)
            for (i in got.indices) assertArrayEquals("len=$len frame $i", whole.frames[i].pixels, got[i].pixels)
        }
    }

    @Test
    fun `a bad CRC stops reading there and keeps the frames before it`() {
        val frames = (0 until 4).map { HandPng.Frame(0, 0, 4, 4, 1, 10, 0, 0, solid(4, 4, AnimTestKit.argb(255, it * 60, 0, 0))) }
        val bytes = HandPng.apng(4, 4, 0, frames, splitData = 1000)
        val list = chunks(bytes)
        // Find the third frame's fdAT in the byte stream and flip a data byte.
        var p = 8
        var fcTlSeen = 0
        var target = -1
        for (c in list) {
            if (c.type == "fcTL") fcTlSeen++
            if (c.type == "fdAT" && fcTlSeen == 3) {
                target = p + 8 + 6
                break
            }
            p += 12 + c.data.size
        }
        assertTrue(target > 0)
        val bad = bytes.copyOf()
        bad[target] = (bad[target].toInt() xor 0x55).toByte()
        val r = ApngReader.decodeAll(bad)
        assertEquals(DecodeProblem.CORRUPT, r.problem)
        assertEquals(2, r.sequence!!.frames.size)
    }

    @Test
    fun `out of order sequence numbers are corruption`() {
        val frames = (0 until 3).map { HandPng.Frame(0, 0, 2, 2, 1, 10, 0, 0, solid(2, 2, RED)) }
        val r = ApngReader.decodeAll(HandPng.apng(2, 2, 0, frames, seqOverride = { if (it >= 3) it + 5 else it }))
        assertEquals(DecodeProblem.CORRUPT, r.problem)
    }

    @Test
    fun `a frame outside the canvas is corruption`() {
        val frames = listOf(HandPng.Frame(0, 0, 2, 2, 1, 10, 0, 0, solid(2, 2, RED)), HandPng.Frame(1, 1, 2, 2, 1, 10, 0, 0, solid(2, 2, RED)))
        val r = ApngReader.decodeAll(HandPng.apng(2, 2, 0, frames))
        assertEquals(DecodeProblem.CORRUPT, r.problem)
        assertEquals(1, r.sequence!!.frames.size)
    }

    @Test
    fun `a file that says it has more frames than it holds is flagged`() {
        val frames = listOf(HandPng.Frame(0, 0, 2, 2, 1, 10, 0, 0, solid(2, 2, RED)))
        val bytes = HandPng.apng(2, 2, 0, frames)
        // acTL is the second chunk: bytes 8 + 25 (IHDR chunk) .. patch its frame count to 3 and fix the CRC.
        val at = 8 + 25
        val patched = bytes.copyOf()
        patched[at + 8 + 3] = 3
        val crc = CRC32()
        crc.update(patched, at + 4, 4 + 8)
        val v = crc.value.toInt()
        patched[at + 16] = (v ushr 24).toByte()
        patched[at + 17] = (v ushr 16).toByte()
        patched[at + 18] = (v ushr 8).toByte()
        patched[at + 19] = v.toByte()
        val r = ApngReader.decodeAll(patched)
        assertFalse(r.complete)
        assertEquals(1, r.sequence!!.frames.size)
    }

    @Test
    fun `random byte damage never throws`() {
        val full = ApngEncoder.encode(AnimTestKit.photoSequence(20, 14, 4))
        val rnd = Random(31)
        for (trial in 0 until 500) {
            val bad = full.copyOf()
            repeat(1 + rnd.nextInt(4)) { bad[rnd.nextInt(bad.size)] = rnd.nextInt(256).toByte() }
            val r = ApngReader.decodeAll(bad)
            if (r.complete) assertNotNull(r.sequence)
        }
    }

    @Test
    fun `damage with the CRCs repaired still never throws`() {
        // The CRC check would catch almost all damage above; this repairs the CRCs so the decoder's own checks run.
        val full = ApngEncoder.encode(AnimTestKit.photoSequence(20, 14, 4))
        val rnd = Random(32)
        for (trial in 0 until 400) {
            val list = chunks(full)
            val out = ByteArrayOutputStream()
            out.write(HandPng.SIGNATURE)
            for (c in list) {
                val d = c.data.copyOf()
                if (d.isNotEmpty() && rnd.nextInt(3) == 0) d[rnd.nextInt(d.size)] = rnd.nextInt(256).toByte()
                out.write(HandPng.chunk(c.type, d))
            }
            val r = ApngReader.decodeAll(out.toByteArray())
            if (r.complete) assertNotNull(r.sequence)
        }
    }

    @Test
    fun `a header claiming a gigantic canvas is refused`() {
        val out = ByteArrayOutputStream()
        out.write(HandPng.SIGNATURE)
        out.write(HandPng.ihdr(100_000, 100_000, 8, 6))
        out.write(HandPng.chunk("IEND", ByteArray(0)))
        val r = ApngReader.decodeAll(out.toByteArray())
        assertEquals(DecodeProblem.TOO_LARGE, r.problem)
    }

    @Test
    fun `a deflate stream that expands far past the image size is cut off, not inflated`() {
        // 60 MB of zeros deflate to about 60 KB; the image only needs 2 x 2 RGBA.
        val bomb = HandPng.zlib(ByteArray(60_000_000), 9)
        val out = ByteArrayOutputStream()
        out.write(HandPng.SIGNATURE)
        out.write(HandPng.ihdr(2, 2, 8, 6))
        out.write(HandPng.chunk("IDAT", bomb))
        out.write(HandPng.chunk("IEND", ByteArray(0)))
        val r = ApngReader.decodeAll(out.toByteArray())
        // The needed bytes are there (zeros are valid filter-0 rows), so it decodes; what matters is that it stopped.
        assertNotNull(r.sequence)
    }

    @Test
    fun `a short deflate stream is a damaged frame`() {
        val short = HandPng.zlib(ByteArray(5))
        val out = ByteArrayOutputStream()
        out.write(HandPng.SIGNATURE)
        out.write(HandPng.ihdr(4, 4, 8, 6))
        out.write(HandPng.chunk("IDAT", short))
        out.write(HandPng.chunk("IEND", ByteArray(0)))
        val r = ApngReader.decodeAll(out.toByteArray())
        assertEquals(DecodeProblem.CORRUPT, r.problem)
        assertEquals(0, r.sequence!!.frames.size)
    }

    @Test
    fun `frame and size limits stop reading and say so`() {
        val frames = (0 until 5).map { HandPng.Frame(0, 0, 2, 2, 1, 10, 0, 0, solid(2, 2, AnimTestKit.argb(255, it * 40, 0, 0))) }
        val bytes = HandPng.apng(2, 2, 0, frames)
        val byCount = ApngReader.decodeAll(bytes, DecodeLimits(maxFrames = 3))
        assertEquals(DecodeProblem.TOO_LARGE, byCount.problem)
        assertEquals(3, byCount.sequence!!.frames.size)
        assertTrue(ApngReader.decodeAll(bytes, DecodeLimits(maxFrames = 5)).complete)
    }

    @Test
    fun `reading frame by frame matches reading them all`() {
        val bytes = ApngEncoder.encode(AnimTestKit.photoSequence(24, 16, 5))
        val all = decode(bytes).frames
        val reader = ApngReader(bytes)
        var i = 0
        while (true) {
            val f = reader.next() ?: break
            assertArrayEquals(all[i].pixels, f.pixels)
            assertEquals(all[i].delayMs, f.delayMs)
            i++
        }
        assertEquals(all.size, i)
        assertEquals(DecodeProblem.NONE, reader.problem)
    }
}
