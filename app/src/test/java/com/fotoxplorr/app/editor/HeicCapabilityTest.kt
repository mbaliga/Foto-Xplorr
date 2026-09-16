package com.fotoxplorr.app.editor

import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [isHeicExportSupported] below API 28 -- the one part of this check a JVM/Robolectric test can
 * assert with confidence. Whether a REAL device's [android.media.MediaCodecList] happens to carry
 * an HEVC encoder is exactly the kind of fact this test environment has no way to fake honestly;
 * that half is a device-checklist question, not a unit test's to answer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class HeicCapabilityTest {

    @Test
    fun `HEIC export is never offered below API 28, regardless of what MediaCodecList would say`() {
        assertFalse(isHeicExportSupported())
    }
}
