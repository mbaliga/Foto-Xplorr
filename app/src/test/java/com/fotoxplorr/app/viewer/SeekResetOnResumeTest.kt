package com.fotoxplorr.app.viewer

import android.os.Looper
import androidx.media3.exoplayer.SeekParameters
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A frame step sets exact seeks; ANY resume -- the play button, Retry, a re-prepare -- puts the
 * player back on its ordinary seek mode, because the reset listens on the player itself rather than
 * living in one button's handler.
 *
 * Found while writing this: in media3 [SeekParameters.DEFAULT] is [SeekParameters.EXACT], so with
 * today's [ORDINARY_SEEK] the reset is not observable. The mechanism is therefore pinned with a
 * cheaper ordinary mode (CLOSEST_SYNC), where it is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SeekResetOnResumeTest {
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `media3's default seek mode is exact, so the ordinary mode today is exact too`() {
        assertEquals(SeekParameters.EXACT, SeekParameters.DEFAULT)
        assertEquals(SeekParameters.DEFAULT, ORDINARY_SEEK)
    }

    @Test
    fun `resuming by setting play-when-ready (the Retry path) restores the ordinary mode`() {
        val player = buildViewerPlayer(RuntimeEnvironment.getApplication())
        try {
            player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
            player.addListener(SeekResetOnResume(player, ordinary = SeekParameters.CLOSEST_SYNC))
            player.playWhenReady = false
            // What stepFrames does.
            player.setSeekParameters(SeekParameters.EXACT)
            idle()
            assertEquals(SeekParameters.EXACT, player.seekParameters)
            // What retryPlayback, or any other resume, does to the player.
            player.playWhenReady = true
            idle()
            assertEquals(SeekParameters.CLOSEST_SYNC, player.seekParameters)
        } finally {
            player.release()
        }
    }

    @Test
    fun `pausing keeps exact seeks for the next step`() {
        val player = buildViewerPlayer(RuntimeEnvironment.getApplication())
        try {
            player.addListener(SeekResetOnResume(player, ordinary = SeekParameters.CLOSEST_SYNC))
            player.playWhenReady = true
            player.setSeekParameters(SeekParameters.EXACT)
            player.playWhenReady = false
            idle()
            assertEquals(SeekParameters.EXACT, player.seekParameters)
        } finally {
            player.release()
        }
    }

    @Test
    fun `the viewer's own player carries the reset`() {
        val holder = ViewerPlayerHolder(RuntimeEnvironment.getApplication())
        try {
            holder.player.playWhenReady = false
            holder.player.setSeekParameters(SeekParameters.EXACT)
            holder.player.playWhenReady = true
            idle()
            assertEquals(ORDINARY_SEEK, holder.player.seekParameters)
        } finally {
            holder.release()
        }
    }
}
