package com.fotoxplorr.app.viewer.sphere

import java.io.FileNotFoundException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The 360 view's decode guard and memory cap, and the size GPano's defaults are read in. */
class SphereLoadingTest {
    @Test
    fun `a vanished file is the failure message, not a crash`() = runBlocking {
        val out = SphereLoading.guarded(onFailure = { SphereLoading.messageFor(it) }) {
            throw FileNotFoundException("the file was moved by another app")
        }
        assertEquals(SphereCopy.FAILED, out)
    }

    @Test
    fun `running out of memory is the memory message, not a crash`() = runBlocking {
        val out = SphereLoading.guarded(onFailure = { SphereLoading.messageFor(it) }) {
            throw OutOfMemoryError("IntArray(4096 * 2048)")
        }
        assertEquals(SphereCopy.NO_MEMORY, out)
    }

    @Test
    fun `cancellation is not a failure`() = runBlocking {
        try {
            SphereLoading.guarded(onFailure = { "failed" }) { throw CancellationException("left the 360 view") }
            fail("cancellation must propagate")
        } catch (e: CancellationException) {
            assertEquals("left the 360 view", e.message)
        }
    }

    @Test
    fun `a success passes through`() = runBlocking {
        assertEquals("ok", SphereLoading.guarded(onFailure = { "failed" }) { "ok" })
    }

    @Test
    fun `plenty of heap decodes at the full edge`() {
        assertEquals(SphereLoading.MAX_EDGE, SphereLoading.decodeEdge(512L * 1024 * 1024))
    }

    @Test
    fun `a tight heap decodes smaller, with the copy inside half of it`() {
        val heap = 24L * 1024 * 1024
        val edge = SphereLoading.decodeEdge(heap)!!
        assertTrue(edge < SphereLoading.MAX_EDGE)
        assertTrue(edge >= SphereLoading.MIN_EDGE)
        val copyBytes = edge.toLong() * (edge / 2) * 4L
        assertTrue("copy $copyBytes of heap $heap", copyBytes <= heap / 2)
    }

    @Test
    fun `too little heap is no decode at all`() {
        assertNull(SphereLoading.decodeEdge(2L * 1024 * 1024))
        assertNull(SphereLoading.decodeEdge(0L))
        assertNull(SphereLoading.decodeEdge(-1L))
    }

    @Test
    fun `GPano defaults are measured in the original size, not the decoded one`() {
        assertEquals(8192 to 4096, SphereLoading.metaSize(8192, 4096, 4096, 2048))
        assertEquals(4096 to 2048, SphereLoading.metaSize(0, 0, 4096, 2048))
    }

    @Test
    fun `a full sphere with FullPano but no cropped area stays full when read in original pixels`() {
        val props = mapOf(
            "ProjectionType" to "equirectangular",
            "FullPanoWidthPixels" to "8192",
            "FullPanoHeightPixels" to "4096",
        )::get
        // 8192 x 4096 decoded under the 4096 edge: the decoded size would make it half a sphere.
        val (w, h) = SphereLoading.metaSize(8192, 4096, 4096, 2048)
        val meta = SphereMeta.parse(props, w, h)!!
        assertTrue(meta.wrapsHorizontally)
        assertTrue(meta.isWholeSphere())
        assertEquals(360f, meta.lonSpan, 1e-3f)
        // What the decoded size did before: a full sphere mapped onto half of one.
        val wrong = SphereMeta.parse(props, 4096, 2048)!!
        assertEquals(180f, wrong.lonSpan, 1e-3f)
    }
}
