package com.fotoxplorr.app.viewer

import com.fotoxplorr.app.media.MediaId
import com.fotoxplorr.app.moments.MomentSource
import com.fotoxplorr.app.moments.VideoMoment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [activeMomentAt] is the one decision the whole key-moment chrome hangs off: whether the pill
 * shows at all, which stored row "Remove marker" deletes, and which moment thumbs feedback is
 * recorded against. See its own KDoc in KeyMomentBar.kt for why "nearest within tolerance" rather
 * than an exact match is the right rule given how [VideoPlayer] reports position.
 */
class ActiveMomentTest {

    private fun moment(positionMs: Long, source: MomentSource = MomentSource.AUTO) =
        VideoMoment(mediaId = MediaId(7L), positionMs = positionMs, source = source)

    @Test
    fun `exactly on a moment matches it`() {
        val moments = listOf(moment(5_000L))

        assertEquals(moments[0], activeMomentAt(moments, 5_000L, toleranceMs = 1_000L))
    }

    @Test
    fun `within tolerance on either side still matches`() {
        val moments = listOf(moment(5_000L))

        assertEquals(moments[0], activeMomentAt(moments, 5_800L, toleranceMs = 1_000L))
        assertEquals(moments[0], activeMomentAt(moments, 4_200L, toleranceMs = 1_000L))
    }

    @Test
    fun `outside tolerance matches nothing`() {
        val moments = listOf(moment(5_000L))

        assertNull(activeMomentAt(moments, 6_500L, toleranceMs = 1_000L))
    }

    @Test
    fun `exactly at the tolerance boundary still matches`() {
        val moments = listOf(moment(5_000L))

        assertEquals(moments[0], activeMomentAt(moments, 6_000L, toleranceMs = 1_000L))
    }

    @Test
    fun `one millisecond past the tolerance boundary matches nothing`() {
        val moments = listOf(moment(5_000L))

        assertNull(activeMomentAt(moments, 6_001L, toleranceMs = 1_000L))
    }

    @Test
    fun `an empty moment list matches nothing`() {
        assertNull(activeMomentAt(emptyList(), 5_000L, toleranceMs = 1_000L))
    }

    @Test
    fun `two moments in range pick whichever is closer`() {
        val near = moment(5_000L)
        val far = moment(5_900L)

        assertEquals(near, activeMomentAt(listOf(far, near), 5_100L, toleranceMs = 2_000L))
    }

    @Test
    fun `zero tolerance requires an exact match`() {
        val moments = listOf(moment(5_000L))

        assertEquals(moments[0], activeMomentAt(moments, 5_000L, toleranceMs = 0L))
        assertNull(activeMomentAt(moments, 5_001L, toleranceMs = 0L))
    }

    @Test
    fun `works the same regardless of whether the moment is AUTO or MANUAL`() {
        val manual = moment(5_000L, source = MomentSource.MANUAL)

        assertEquals(manual, activeMomentAt(listOf(manual), 5_000L, toleranceMs = 500L))
    }
}
