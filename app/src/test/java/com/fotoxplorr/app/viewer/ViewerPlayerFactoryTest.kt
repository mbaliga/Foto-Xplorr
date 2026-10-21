package com.fotoxplorr.app.viewer

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The foreground player's configuration (audio attributes, decoder fallback) and the holder that
 * owns its lifetime. Audio FOCUS and becoming-noisy behaviour are not observable under Robolectric
 * and stay a device check; what is pinned here is that the player is built, carries movie audio
 * attributes, and that releasing it twice is harmless.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewerPlayerFactoryTest {

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `the viewer player carries movie media audio attributes`() {
        val player = buildViewerPlayer(context)
        try {
            assertEquals(C.USAGE_MEDIA, player.audioAttributes.usage)
            assertEquals(C.AUDIO_CONTENT_TYPE_MOVIE, player.audioAttributes.contentType)
        } finally {
            player.release()
        }
    }

    @Test
    fun `releasing a holder twice is harmless and the second call is a no-op`() {
        val holder = ViewerPlayerHolder(context)
        assertNotNull(holder.player)
        holder.release()
        holder.release()
        // The observer callbacks Compose uses on removal and abandonment funnel into the same
        // idempotent release.
        holder.onForgotten()
        holder.onAbandoned()
    }
}
