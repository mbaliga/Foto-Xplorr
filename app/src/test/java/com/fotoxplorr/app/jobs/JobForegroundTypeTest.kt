package com.fotoxplorr.app.jobs

import android.content.pm.ServiceInfo
import android.os.Build
import com.fotoxplorr.app.hyle.ActivityKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which foreground-service type the notification declares for each kind of job. A map download
 * moves bytes over the network, which is data sync; declaring it as media processing on Android 15
 * and later would be a false declaration the system can stop the service for.
 */
class JobForegroundTypeTest {

    private val dataSync = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    private val media = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
    private val android15 = Build.VERSION_CODES.VANILLA_ICE_CREAM

    @Test
    fun `a map download is data sync on every version that has types`() {
        val download = listOf(ActivityKind.DOWNLOADING_MAP)
        assertEquals(0, JobForegroundService.foregroundTypeFor(download, Build.VERSION_CODES.P))
        assertEquals(dataSync, JobForegroundService.foregroundTypeFor(download, Build.VERSION_CODES.Q))
        assertEquals(dataSync, JobForegroundService.foregroundTypeFor(download, Build.VERSION_CODES.UPSIDE_DOWN_CAKE))
        assertEquals(dataSync, JobForegroundService.foregroundTypeFor(download, android15))
        assertEquals(dataSync, JobForegroundService.foregroundTypeFor(download, 36))
    }

    @Test
    fun `a map download next to a conversion asks for both types on Android 15`() {
        assertEquals(
            dataSync or media,
            JobForegroundService.foregroundTypeFor(listOf(ActivityKind.DOWNLOADING_MAP, ActivityKind.EXPORTING), android15),
        )
    }

    @Test
    fun `a map download and a backup together are still only data sync`() {
        assertEquals(
            dataSync,
            JobForegroundService.foregroundTypeFor(listOf(ActivityKind.DOWNLOADING_MAP, ActivityKind.BACKING_UP), android15),
        )
    }

    @Test
    fun `the other kinds keep their types`() {
        assertEquals(media, JobForegroundService.foregroundTypeFor(listOf(ActivityKind.COPYING), android15))
        assertEquals(dataSync, JobForegroundService.foregroundTypeFor(listOf(ActivityKind.BACKING_UP), android15))
    }

    @Test
    fun `the new kind has its own wording and a colour no other kind uses`() {
        val kind = ActivityKind.DOWNLOADING_MAP
        assertEquals("Downloading map", kind.label)
        ActivityKind.entries.filter { it != kind }.forEach { other ->
            assertNotEquals("${other.name} shares the accent", other.accent, kind.accent)
        }
        assertTrue(ActivityKind.entries.map { it.label }.toSet().size == ActivityKind.entries.size)
    }
}
