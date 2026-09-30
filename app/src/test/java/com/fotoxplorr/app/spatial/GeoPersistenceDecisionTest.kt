package com.fotoxplorr.app.spatial

import android.os.Build
import com.fotoxplorr.core.model.MediaId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0-02: the pure persist/don't-persist decision behind [GeoMetadataRepository.indexMissing].
 *
 * A located fix is always safe. An absence is only safe to record when the read actually asked
 * the platform for the unredacted original, or the platform never redacts to begin with (below
 * API 29) -- otherwise "no location" might just be Android's own redaction of a photo that really
 * does have GPS, and the app would remember that wrong answer forever.
 */
class GeoPersistenceDecisionTest {

    private val located = LocationReadOutcome.Located(
        GeoMetadata(
            mediaId = MediaId(1L),
            latitude = 12.5,
            longitude = 45.5,
            altitudeMeters = null,
            captureDirectionDegrees = null,
        ),
    )

    @Test
    fun `a located fix is always persisted`() {
        assertTrue(shouldPersist(located, apiLevel = Build.VERSION_CODES.Q, original = true))
        assertTrue(shouldPersist(located, apiLevel = Build.VERSION_CODES.Q, original = false))
        assertTrue(shouldPersist(located, apiLevel = Build.VERSION_CODES.P, original = false))
    }

    @Test
    fun `no location is persisted when the read used the unredacted original`() {
        assertTrue(shouldPersist(LocationReadOutcome.NoLocation, apiLevel = Build.VERSION_CODES.Q, original = true))
    }

    @Test
    fun `no location is persisted below API 29 regardless of original access`() {
        assertTrue(shouldPersist(LocationReadOutcome.NoLocation, apiLevel = Build.VERSION_CODES.P, original = false))
        assertTrue(shouldPersist(LocationReadOutcome.NoLocation, apiLevel = Build.VERSION_CODES.P, original = true))
    }

    @Test
    fun `no location on API 29+ without original access is treated as unknown, not persisted`() {
        assertFalse(shouldPersist(LocationReadOutcome.NoLocation, apiLevel = Build.VERSION_CODES.Q, original = false))
    }

    @Test
    fun `unreadable is never persisted`() {
        assertFalse(shouldPersist(LocationReadOutcome.Unreadable, apiLevel = Build.VERSION_CODES.Q, original = true))
        assertFalse(shouldPersist(LocationReadOutcome.Unreadable, apiLevel = Build.VERSION_CODES.P, original = false))
    }
}
