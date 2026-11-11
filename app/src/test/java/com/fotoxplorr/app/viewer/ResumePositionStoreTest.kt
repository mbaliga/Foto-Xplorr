package com.fotoxplorr.app.viewer

import com.fotoxplorr.core.model.MediaId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

class ShouldRememberPositionTest {

    @Test
    fun `a position in the middle of the video is worth remembering`() {
        assertTrue(shouldRememberPosition(positionMs = 60_000L, durationMs = 120_000L))
    }

    @Test
    fun `a position near the very start is not worth remembering`() {
        assertFalse(shouldRememberPosition(positionMs = 500L, durationMs = 120_000L))
    }

    @Test
    fun `a position near the very end is not worth remembering`() {
        assertFalse(shouldRememberPosition(positionMs = 119_500L, durationMs = 120_000L))
    }

    @Test
    fun `an unknown zero duration is never worth remembering`() {
        assertFalse(shouldRememberPosition(positionMs = 10_000L, durationMs = 0L))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResumePositionStoreTest {

    private val store by lazy { ResumePositionStore(RuntimeEnvironment.getApplication()) }

    @Test
    fun `a never-saved video has no remembered position`() {
        assertNull(store.get(MediaId(1L)))
    }

    @Test
    fun `a mid-video position round-trips`() {
        val id = MediaId(2L)
        store.save(id, positionMs = 30_000L, durationMs = 100_000L)
        assertEquals(30_000L, store.get(id))
    }

    @Test
    fun `saving a near-the-end position clears any previous entry instead of keeping a stale one`() {
        val id = MediaId(3L)
        store.save(id, positionMs = 30_000L, durationMs = 100_000L)
        store.save(id, positionMs = 99_900L, durationMs = 100_000L)
        assertNull(store.get(id))
    }

    @Test
    fun `clear removes a remembered position`() {
        val id = MediaId(4L)
        store.save(id, positionMs = 30_000L, durationMs = 100_000L)
        store.clear(id)
        assertNull(store.get(id))
    }

    @Test
    fun `two different videos keep independent positions`() {
        val a = MediaId(5L)
        val b = MediaId(6L)
        store.save(a, positionMs = 10_000L, durationMs = 100_000L)
        store.save(b, positionMs = 20_000L, durationMs = 100_000L)
        assertEquals(10_000L, store.get(a))
        assertEquals(20_000L, store.get(b))
    }

    @Test
    fun `nothing is ever remembered for the shared external id`() {
        store.save(MediaId.EXTERNAL, positionMs = 30_000L, durationMs = 100_000L)
        assertNull(store.get(MediaId.EXTERNAL))
        assertTrue(prefsFile().all.isEmpty())
    }

    @Test
    fun `nothing is remembered for a synthetic negative id`() {
        val external = MediaId(-123_456L)
        store.save(external, positionMs = 30_000L, durationMs = 100_000L)
        assertNull(store.get(external))
        assertTrue(prefsFile().all.isEmpty())
    }

    @Test
    fun `two different external videos cannot share a stored position`() {
        val a = MediaId(-1L)
        val b = MediaId(-2L)
        store.save(a, positionMs = 10_000L, durationMs = 100_000L)
        assertNull(store.get(b))
    }

    @Test
    fun `clearing a synthetic id leaves real entries alone`() {
        val real = MediaId(7L)
        store.save(real, positionMs = 30_000L, durationMs = 100_000L)
        store.clear(MediaId(-7L))
        assertEquals(30_000L, store.get(real))
    }

    private fun prefsFile() = RuntimeEnvironment.getApplication()
        .getSharedPreferences("video_resume_positions", android.content.Context.MODE_PRIVATE)
}
