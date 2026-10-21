package com.fotoxplorr.app.viewer

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [VideoPlayer]'s decisions, as pure functions. Nothing on a real player: the Int constants from
 * [Player] and [PlaybackException] are compile-time inlined, so these run on a bare JVM.
 */
class PlaybackLogicTest {

    // ---- transport state --------------------------------------------------------------------

    @Test
    fun `a buffering video that wants to play offers pause not play`() {
        // The old transport keyed on isPlaying, which is false while buffering: Pause was
        // unreachable until the first frame.
        assertFalse(transportShowsPlay(playWhenReady = true, playbackState = Player.STATE_BUFFERING))
        assertFalse(transportShowsPlay(playWhenReady = true, playbackState = Player.STATE_READY))
    }

    @Test
    fun `paused idle and finished videos offer play`() {
        assertTrue(transportShowsPlay(playWhenReady = false, playbackState = Player.STATE_READY))
        assertTrue(transportShowsPlay(playWhenReady = false, playbackState = Player.STATE_BUFFERING))
        assertTrue(transportShowsPlay(playWhenReady = true, playbackState = Player.STATE_IDLE))
        assertTrue(transportShowsPlay(playWhenReady = true, playbackState = Player.STATE_ENDED))
    }

    @Test
    fun `the centre glyph never flashes while autoplay is about to start`() {
        // Idle but wanting to play = created, not yet prepared (or a silent retry in flight).
        assertFalse(centreGlyphVisible(playWhenReady = true, playbackState = Player.STATE_IDLE, failed = false))
        assertTrue(centreGlyphVisible(playWhenReady = false, playbackState = Player.STATE_IDLE, failed = false))
        assertTrue(centreGlyphVisible(playWhenReady = false, playbackState = Player.STATE_READY, failed = false))
    }

    @Test
    fun `a finished video shows the glyph so it can be replayed`() {
        assertTrue(centreGlyphVisible(playWhenReady = true, playbackState = Player.STATE_ENDED, failed = false))
    }

    @Test
    fun `a failure panel owns the centre`() {
        assertFalse(centreGlyphVisible(playWhenReady = false, playbackState = Player.STATE_IDLE, failed = true))
    }

    @Test
    fun `playback is active only while wanting to play and not idle or ended`() {
        assertTrue(isPlaybackActive(playWhenReady = true, playbackState = Player.STATE_READY))
        assertTrue(isPlaybackActive(playWhenReady = true, playbackState = Player.STATE_BUFFERING))
        assertFalse(isPlaybackActive(playWhenReady = true, playbackState = Player.STATE_IDLE))
        assertFalse(isPlaybackActive(playWhenReady = true, playbackState = Player.STATE_ENDED))
        assertFalse(isPlaybackActive(playWhenReady = false, playbackState = Player.STATE_READY))
    }

    @Test
    fun `active playback is exactly when the transport shows pause outside idle and ended`() {
        // Keep-screen-on and the transport glyph must never disagree about a READY/BUFFERING player.
        for (state in listOf(Player.STATE_BUFFERING, Player.STATE_READY)) {
            for (wantsPlay in listOf(true, false)) {
                assertEquals(
                    isPlaybackActive(wantsPlay, state),
                    !transportShowsPlay(wantsPlay, state),
                )
            }
        }
    }

    // ---- duration ---------------------------------------------------------------------------

    @Test
    fun `the player's own duration wins once real`() {
        assertEquals(90_000L, resolveDurationMs(playerDurationMs = 90_000L, currentMs = 64_000L, mediaStoreMs = 64_000L))
    }

    @Test
    fun `an unset player duration falls back to the last known one`() {
        assertEquals(64_000L, resolveDurationMs(C.TIME_UNSET, currentMs = 64_000L, mediaStoreMs = 1L))
    }

    @Test
    fun `a zero MediaStore duration is replaced as soon as the player knows`() {
        assertEquals(0L, resolveDurationMs(C.TIME_UNSET, currentMs = 0L, mediaStoreMs = 0L))
        assertEquals(12_000L, resolveDurationMs(12_000L, currentMs = 0L, mediaStoreMs = 0L))
    }

    @Test
    fun `MediaStore is the floor when nothing else is known`() {
        assertEquals(30_000L, resolveDurationMs(C.TIME_UNSET, currentMs = 0L, mediaStoreMs = 30_000L))
    }

    @Test
    fun `a non-positive player duration is treated as unknown`() {
        assertEquals(5_000L, resolveDurationMs(0L, currentMs = 5_000L, mediaStoreMs = 0L))
        assertEquals(5_000L, resolveDurationMs(-7L, currentMs = 5_000L, mediaStoreMs = 0L))
    }

    @Test
    fun `a rememberable position needs a duration the player supplied when MediaStore has none`() {
        val duration = resolveDurationMs(C.TIME_UNSET, currentMs = 0L, mediaStoreMs = 0L)
        assertFalse(shouldRememberPosition(60_000L, duration))
        val known = resolveDurationMs(120_000L, currentMs = 0L, mediaStoreMs = 0L)
        assertTrue(shouldRememberPosition(60_000L, known))
    }

    // ---- catalogue gating and resume --------------------------------------------------------

    @Test
    fun `an external asset is never catalogued even when the host allows catalogue actions`() {
        assertTrue(isCatalogued(catalogueActions = true, isExternal = false))
        assertFalse(isCatalogued(catalogueActions = true, isExternal = true))
        assertFalse(isCatalogued(catalogueActions = false, isExternal = false))
        assertFalse(isCatalogued(catalogueActions = false, isExternal = true))
    }

    @Test
    fun `resume is eligible only for a catalogued video with a real id`() {
        assertTrue(resumeEligible(catalogued = true, mediaIdValue = 42L))
        assertTrue(resumeEligible(catalogued = true, mediaIdValue = 0L))
        assertFalse(resumeEligible(catalogued = false, mediaIdValue = 42L))
        assertFalse(resumeEligible(catalogued = true, mediaIdValue = -1L))
        assertFalse(resumeEligible(catalogued = true, mediaIdValue = Long.MIN_VALUE))
    }

    @Test
    fun `resume never writes for a video that is not eligible`() {
        assertEquals(
            ResumeWrite.SKIP,
            resumeWriteFor(eligible = false, playbackState = Player.STATE_READY, engaged = true, durationMs = 100_000L),
        )
    }

    @Test
    fun `open-and-leave does not touch a remembered position`() {
        // Saving 0:00 would make the store CLEAR the position the user came back to resume.
        assertEquals(
            ResumeWrite.SKIP,
            resumeWriteFor(eligible = true, playbackState = Player.STATE_READY, engaged = false, durationMs = 100_000L),
        )
        assertEquals(
            ResumeWrite.SKIP,
            resumeWriteFor(eligible = true, playbackState = Player.STATE_ENDED, engaged = false, durationMs = 100_000L),
        )
    }

    @Test
    fun `an engaged video saves on any trigger while the duration is known`() {
        for (state in listOf(Player.STATE_IDLE, Player.STATE_BUFFERING, Player.STATE_READY)) {
            assertEquals(
                ResumeWrite.SAVE,
                resumeWriteFor(eligible = true, playbackState = state, engaged = true, durationMs = 100_000L),
            )
        }
    }

    @Test
    fun `an unknown duration skips rather than clears`() {
        assertEquals(
            ResumeWrite.SKIP,
            resumeWriteFor(eligible = true, playbackState = Player.STATE_READY, engaged = true, durationMs = 0L),
        )
    }

    @Test
    fun `a finished video clears its position`() {
        assertEquals(
            ResumeWrite.CLEAR,
            resumeWriteFor(eligible = true, playbackState = Player.STATE_ENDED, engaged = true, durationMs = 100_000L),
        )
    }

    @Test
    fun `with autoplay a remembered position resumes silently`() {
        val start = resumeStartFor(rememberedMs = 83_000L, autoplay = true)
        assertEquals(83_000L, start.startPositionMs)
        assertNull(start.chipPositionMs)
    }

    @Test
    fun `without autoplay a remembered position waits behind the chip`() {
        val start = resumeStartFor(rememberedMs = 83_000L, autoplay = false)
        assertEquals(0L, start.startPositionMs)
        assertEquals(83_000L, start.chipPositionMs)
    }

    @Test
    fun `nothing remembered starts at zero with no chip`() {
        for (autoplay in listOf(true, false)) {
            val start = resumeStartFor(rememberedMs = null, autoplay = autoplay)
            assertEquals(0L, start.startPositionMs)
            assertNull(start.chipPositionMs)
        }
        assertNull(resumeStartFor(rememberedMs = 0L, autoplay = false).chipPositionMs)
    }

    // ---- error classification ---------------------------------------------------------------

    @Test
    fun `every checked error code maps to a distinct kind where it should`() {
        val expected = mapOf(
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND to FailureKind.NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION to FailureKind.NO_PERMISSION,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED to FailureKind.IO,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE to FailureKind.IO,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED to FailureKind.CORRUPT,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED to FailureKind.UNSUPPORTED_FORMAT,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED to FailureKind.DECODER,
            PlaybackException.ERROR_CODE_DECODING_FAILED to FailureKind.DECODER,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES to FailureKind.UNSUPPORTED_FORMAT,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED to FailureKind.UNSUPPORTED_FORMAT,
            PlaybackException.ERROR_CODE_UNSPECIFIED to FailureKind.UNKNOWN,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED to FailureKind.UNKNOWN,
            9999 to FailureKind.UNKNOWN,
        )
        expected.forEach { (code, kind) -> assertEquals("code $code", kind, playbackFailureFor(code).kind) }
    }

    @Test
    fun `messages are plain language and distinct per kind`() {
        val messages = FailureKind.entries.map { kind ->
            val code = when (kind) {
                FailureKind.NOT_FOUND -> PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
                FailureKind.NO_PERMISSION -> PlaybackException.ERROR_CODE_IO_NO_PERMISSION
                FailureKind.IO -> PlaybackException.ERROR_CODE_IO_UNSPECIFIED
                FailureKind.UNSUPPORTED_FORMAT -> PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
                FailureKind.DECODER -> PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
                FailureKind.CORRUPT -> PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
                FailureKind.UNKNOWN -> PlaybackException.ERROR_CODE_UNSPECIFIED
            }
            playbackFailureFor(code).message
        }
        assertEquals(messages.size, messages.toSet().size)
        messages.forEach { message ->
            assertTrue(message.isNotBlank())
            assertFalse("no raw error codes in user text: $message", message.contains("ERROR_CODE"))
        }
    }

    @Test
    fun `retry is offered where trying again can help and withheld where it cannot`() {
        assertTrue(playbackFailureFor(PlaybackException.ERROR_CODE_IO_UNSPECIFIED).canRetry)
        assertTrue(playbackFailureFor(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED).canRetry)
        assertTrue(playbackFailureFor(PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED).canRetry)
        assertTrue(playbackFailureFor(PlaybackException.ERROR_CODE_UNSPECIFIED).canRetry)
        assertFalse(playbackFailureFor(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND).canRetry)
        assertFalse(playbackFailureFor(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED).canRetry)
        assertFalse(playbackFailureFor(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED).canRetry)
    }

    @Test
    fun `open with is offered for format and decoder errors but not for access errors`() {
        assertTrue(playbackFailureFor(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED).offerOpenWith)
        assertTrue(playbackFailureFor(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED).offerOpenWith)
        assertTrue(playbackFailureFor(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED).offerOpenWith)
        assertFalse(playbackFailureFor(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND).offerOpenWith)
        assertFalse(playbackFailureFor(PlaybackException.ERROR_CODE_IO_NO_PERMISSION).offerOpenWith)
    }

    // ---- retry without subtitles ------------------------------------------------------------

    @Test
    fun `an io or parse error with sidecars attached is retried once without them`() {
        listOf(
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_UNSPECIFIED,
        ).forEach { code ->
            assertTrue("code $code", shouldRetryWithoutSubtitles(code, hadSidecars = true, alreadyRetried = false))
        }
    }

    @Test
    fun `the subtitle retry happens at most once`() {
        assertFalse(
            shouldRetryWithoutSubtitles(
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED, hadSidecars = true, alreadyRetried = true,
            ),
        )
    }

    @Test
    fun `no sidecars means nothing to drop`() {
        assertFalse(
            shouldRetryWithoutSubtitles(
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED, hadSidecars = false, alreadyRetried = false,
            ),
        )
    }

    @Test
    fun `a decoder error is about the video not the subtitles`() {
        listOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        ).forEach { code ->
            assertFalse("code $code", shouldRetryWithoutSubtitles(code, hadSidecars = true, alreadyRetried = false))
        }
    }

    // ---- armed start ------------------------------------------------------------------------

    @Test
    fun `a player arms once it has settled and the strip is not being touched`() {
        assertTrue(mayArm(armed = false, scrubSuspended = false))
    }

    @Test
    fun `a finger on the filmstrip holds the start back`() {
        assertFalse(mayArm(armed = false, scrubSuspended = true))
    }

    @Test
    fun `an armed player does not re-arm`() {
        assertFalse(mayArm(armed = true, scrubSuspended = false))
        assertFalse(mayArm(armed = true, scrubSuspended = true))
    }

    @Test
    fun `the arming delay is long enough to skip a scrub and short enough to feel instant`() {
        assertTrue(ARMED_START_DELAY_MS in 150L..400L)
        assertTrue(SIDECAR_QUERY_TIMEOUT_MS > ARMED_START_DELAY_MS)
    }

    // ---- transport layout -------------------------------------------------------------------

    @Test
    fun `no filmstrip inset never collapses the second row`() {
        assertFalse(shouldCollapseSecondRow(screenHeightDp = 360, bottomInsetDp = 0f))
    }

    @Test
    fun `a 360dp tall landscape window with the filmstrip collapses the second row`() {
        assertTrue(shouldCollapseSecondRow(screenHeightDp = 360, bottomInsetDp = 74f))
    }

    @Test
    fun `a tall portrait window keeps both rows`() {
        assertFalse(shouldCollapseSecondRow(screenHeightDp = 800, bottomInsetDp = 74f))
    }

    @Test
    fun `an unknown screen height does not collapse`() {
        assertFalse(shouldCollapseSecondRow(screenHeightDp = 0, bottomInsetDp = 74f))
    }

    // ---- sidecar readability ----------------------------------------------------------------

    @Test
    fun `unreadable sidecars are dropped and order is kept`() {
        val a = SidecarCandidate("a.srt", "Movies/", "content://files/1")
        val b = SidecarCandidate("b.srt", "Movies/", "content://files/2")
        val c = SidecarCandidate("c.vtt", "Movies/", "content://files/3")
        val readable = filterReadableSidecars(listOf(a, b, c)) { it != b }
        assertEquals(listOf(a, c), readable)
    }

    @Test
    fun `a probe that throws counts as unreadable and does not fail the lookup`() {
        val a = SidecarCandidate("a.srt", "Movies/", "content://files/1")
        val b = SidecarCandidate("b.srt", "Movies/", "content://files/2")
        val readable = filterReadableSidecars(listOf(a, b)) { candidate ->
            if (candidate == a) throw SecurityException("no grant") else true
        }
        assertEquals(listOf(b), readable)
    }

    @Test
    fun `no candidates stays empty`() {
        assertTrue(filterReadableSidecars(emptyList()) { true }.isEmpty())
        assertNotEquals(0, ARMED_START_DELAY_MS)
    }
}
