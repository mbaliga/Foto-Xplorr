package com.fotoxplorr.app.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PackPlannerTest {
    private val mib = 1L shl 20

    // Small, round limits so the arithmetic is readable: the cipher adds a few hundred bytes of
    // framing per pack, so sizes are chosen with that slack.
    private val limits = PackLimits(targetBytes = 10 * mib, hardCapBytes = 30 * mib)

    private fun layout(sizes: List<Long>, first: Int = 1, l: PackLimits = limits) =
        PackPlanner.layout(sizes, { it }, first, l)

    @Test
    fun `nothing in, nothing out`() {
        val result = layout(emptyList())
        assertTrue(result.packs.isEmpty())
        assertTrue(result.oversize.isEmpty())
    }

    @Test
    fun `packs close at the target and keep the order given`() {
        val result = layout(List(8) { 3 * mib })
        assertEquals(listOf(3, 3, 2), result.packs.map { it.items.size })
        assertEquals(List(8) { 3 * mib }, result.packs.flatMap { it.items })
        result.packs.forEach { assertTrue(it.fileBytes <= limits.targetBytes) }
    }

    @Test
    fun `a pack never passes the target unless one file alone does`() {
        val result = layout(listOf(4 * mib, 4 * mib, 4 * mib, 25 * mib, 1 * mib))
        assertEquals(listOf(listOf(4 * mib, 4 * mib), listOf(4 * mib), listOf(25 * mib), listOf(1 * mib)), result.packs.map { it.items })
        assertTrue(result.packs[2].fileBytes > limits.targetBytes)
        assertTrue(result.packs[2].fileBytes <= limits.hardCapBytes)
    }

    @Test
    fun `no pack exceeds the hard cap and the files that cannot fit are reported`() {
        val result = layout(listOf(1 * mib, 31 * mib, 2 * mib, 40 * mib))
        assertEquals(listOf(31 * mib, 40 * mib), result.oversize)
        assertEquals(listOf(listOf(1 * mib, 2 * mib)), result.packs.map { it.items })
        result.packs.forEach { assertTrue(it.fileBytes <= limits.hardCapBytes) }
    }

    @Test
    fun `the edge of the hard cap is decided by the real file size`() {
        // A file whose pack is exactly the cap fits; one byte more does not.
        val cap = limits.hardCapBytes
        var size = cap
        while (limits.fileBytes(PackRecord.bytesFor(size)) > cap) size--
        assertEquals(listOf(size), layout(listOf(size)).packs.flatMap { it.items })
        assertEquals(listOf(size + 1), layout(listOf(size + 1)).oversize)
    }

    @Test
    fun `zero byte files still make a record`() {
        val result = layout(listOf(0L, 0L))
        assertEquals(1, result.packs.size)
        assertEquals(2 * PackRecord.OVERHEAD.toLong(), result.packs[0].plaintextBytes)
    }

    @Test
    fun `pack numbers continue from where the set stopped`() {
        val result = layout(List(4) { 6 * mib }, first = 7)
        assertEquals(listOf("pack-0007.fzbp", "pack-0008.fzbp", "pack-0009.fzbp", "pack-0010.fzbp"), result.packs.map { it.name })
    }

    @Test
    fun `the plan is the same when run twice`() {
        val sizes = List(50) { (it * 37L % 9 + 1) * mib }
        assertEquals(layout(sizes).packs.map { it.items }, layout(sizes).packs.map { it.items })
    }

    @Test
    fun `sizes match what the cipher layer really writes`() {
        val keys = BackupTestSupport.keys
        val body = BackupTestSupport.plaintext(PackRecord.bytesFor(300).toInt())
        val small = PackLimits(targetBytes = 1000, hardCapBytes = 100_000, segmentSize = BackupTestSupport.SEGMENT)
        val file = BackupTestSupport.seal(body, keys, BackupKind.PACK, BackupTestSupport.SEGMENT)
        assertEquals(file.size.toLong(), small.fileBytes(body.size.toLong()))
        val bin = PackPlanner.layout(listOf(300L), { it }, 1, small).packs.single()
        assertEquals(file.size.toLong(), bin.fileBytes)
    }

    @Test
    fun `limits are checked`() {
        try {
            PackLimits(targetBytes = 10, hardCapBytes = 5)
            org.junit.Assert.fail()
        } catch (_: IllegalArgumentException) {
        }
        assertTrue(PackLimits.DEFAULT_HARD_CAP_BYTES < (4L shl 30))
        assertTrue(PackLimits().targetBytes == 1L shl 30)
    }

    @Test
    fun `record headers round-trip and reject the impossible`() {
        val bytes = PackRecord.encodeHeader(42L, 1L shl 33)
        assertEquals(PackRecord.HEADER_BYTES, bytes.size)
        assertEquals(42L to (1L shl 33), PackRecord.decodeHeader(bytes))
        assertArrayEquals(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 42), bytes.copyOfRange(0, 8))
        assertNull(PackRecord.decodeHeader(bytes.copyOf(15)))
        assertNull(PackRecord.decodeHeader(PackRecord.encodeHeader(-1, 5)))
        assertNull(PackRecord.decodeHeader(PackRecord.encodeHeader(1, -5)))
    }
}
