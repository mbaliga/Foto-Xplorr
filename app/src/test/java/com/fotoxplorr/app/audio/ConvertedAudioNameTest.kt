package com.fotoxplorr.app.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/** Mirrors `com.fotoxplorr.app.video.ConvertedNameTest` — same shape, same real-world cases, for
 *  the audio counterpart of the same naming convention. */
class ConvertedAudioNameTest {

    @Test
    fun `a converted copy is named from the source`() {
        assertEquals("track-converted.m4a", convertedAudioName("track.mp3"))
        assertEquals("voice-memo-converted.m4a", convertedAudioName("voice-memo.wav"))
    }

    @Test
    fun `re-converting a conversion does not stack suffixes`() {
        assertEquals("track-converted.m4a", convertedAudioName("track-converted.m4a"))
        assertEquals("track-converted.m4a", convertedAudioName(convertedAudioName(convertedAudioName("track.mp3"))))
    }

    @Test
    fun `naming copes with the awkward real-world cases`() {
        assertEquals("no-extension-converted.m4a", convertedAudioName("no-extension"))
        assertEquals("a.b.c-converted.m4a", convertedAudioName("a.b.c.mp3"))
        assertEquals(".hidden-converted.m4a", convertedAudioName(".hidden"))
        assertEquals("audio-converted.m4a", convertedAudioName("   "))
    }
}
