package com.fotoxplorr.app.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * [SampleStore]'s own round trip (P0-11): 10k samples of random sizes come back byte-, pts- and
 * flags-identical, and its spill file is deleted afterward. Plain `java.io`/`java.nio` underneath
 * ([java.io.File], [java.io.FileOutputStream], [java.io.RandomAccessFile]), so no Robolectric or
 * real device is needed — a real JVM temp directory stands in for `Context.cacheDir`.
 */
class SampleStoreTest {

    @Test
    fun `10,000 samples of random sizes round-trip exactly, and the spill file is deleted after`() {
        val cacheDir = createTempCacheDir()
        val random = Random(seed = 42)
        val samples = List(10_000) { index ->
            val size = random.nextInt(1, 4_000)
            Triple(ByteArray(size) { random.nextInt().toByte() }, index * 1_000L, if (index % 7 == 0) 1 else 0)
        }

        val store = SampleStore.create(cacheDir)
        val spillFiles = spillFilesIn(cacheDir)
        assertEquals("exactly one spill file must exist while the store is open", 1, spillFiles.size)

        samples.forEach { (data, pts, flags) -> store.append(data, pts, flags) }

        val readBack = mutableListOf<Triple<ByteArray, Long, Int>>()
        store.forEachSample { buffer, presentationTimeUs, flags ->
            val copy = ByteArray(buffer.remaining())
            buffer.get(copy)
            readBack += Triple(copy, presentationTimeUs, flags)
        }

        assertEquals(samples.size, readBack.size)
        samples.zip(readBack).forEach { (expected, actual) ->
            assertArrayEquals(expected.first, actual.first)
            assertEquals(expected.second, actual.second)
            assertEquals(expected.third, actual.third)
        }

        store.close()
        assertTrue("the spill file must be deleted once the store is closed", spillFilesIn(cacheDir).isEmpty())
    }

    @Test
    fun `an empty store's forEachSample never opens the spill file for reading`() {
        val cacheDir = createTempCacheDir()
        val store = SampleStore.create(cacheDir)

        var invoked = false
        store.forEachSample { _, _, _ -> invoked = true }

        assertFalse(invoked)
        store.close()
    }

    @Test
    fun `close deletes the spill file even when nothing was ever appended`() {
        val cacheDir = createTempCacheDir()
        val store = SampleStore.create(cacheDir)

        assertEquals(1, spillFilesIn(cacheDir).size)
        store.close()
        assertTrue(spillFilesIn(cacheDir).isEmpty())
    }

    private fun createTempCacheDir() = kotlin.io.path.createTempDirectory("sample-store-test").toFile()

    private fun spillFilesIn(cacheDir: java.io.File): List<java.io.File> =
        java.io.File(cacheDir, "transcode-spill").listFiles()?.toList().orEmpty()
}
