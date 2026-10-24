package com.fotoxplorr.app.backup

import android.content.pm.ServiceInfo
import android.os.Build
import com.fotoxplorr.app.hyle.ActivityKind
import com.fotoxplorr.app.jobs.JobForegroundService
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.model.MediaId
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPlumbingTest {
    @Test
    fun `document ids name their volume and a label`() {
        assertEquals("primary", SafDocumentIds.volumeOf("primary:Backups/Fotoz"))
        assertEquals("1A2B-3C4D", SafDocumentIds.volumeOf("1A2B-3C4D:Photos"))
        assertEquals("Fotoz", SafDocumentIds.fallbackLabel("primary:Backups/Fotoz"))
        assertEquals("the folder", SafDocumentIds.fallbackLabel("primary:"))
    }

    @Test
    fun `only the device's own storage and Downloads are not another app's folder`() {
        assertFalse(SafDocumentIds.isManagedByAnotherApp(SafDocumentIds.EXTERNAL_STORAGE_AUTHORITY))
        assertFalse(SafDocumentIds.isManagedByAnotherApp(SafDocumentIds.DOWNLOADS_AUTHORITY))
        assertTrue(SafDocumentIds.isManagedByAnotherApp("com.example.cloud.documents"))
        assertFalse(SafDocumentIds.isManagedByAnotherApp(null))
    }

    @Test
    fun `a full disk is recognised by its errno or its words, anywhere in the cause chain`() {
        assertTrue(DestinationErrors.isNoSpace(IOException("write failed: ENOSPC (No space left on device)")))
        assertTrue(DestinationErrors.isNoSpace(IOException("outer", IOException("No space left on device"))))
        assertTrue(DestinationErrors.isNoSpace(IOException("There is not enough space on the disk")))
        assertFalse(DestinationErrors.isNoSpace(IOException("Connection reset")))
        assertFalse(DestinationErrors.isNoSpace(IOException()))
    }

    @Test
    fun `a backup is the data-sync service type on every version that has types`() {
        val backup = listOf(ActivityKind.BACKING_UP)
        assertEquals(0, JobForegroundService.foregroundTypeFor(backup, Build.VERSION_CODES.P))
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, JobForegroundService.foregroundTypeFor(backup, Build.VERSION_CODES.Q))
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, JobForegroundService.foregroundTypeFor(backup, Build.VERSION_CODES.VANILLA_ICE_CREAM))
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, JobForegroundService.foregroundTypeFor(backup, 36))
    }

    @Test
    fun `other jobs keep media processing on Android 15 and data sync before it`() {
        val other = listOf(ActivityKind.EXPORTING, ActivityKind.COPYING)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, JobForegroundService.foregroundTypeFor(other, Build.VERSION_CODES.UPSIDE_DOWN_CAKE))
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING, JobForegroundService.foregroundTypeFor(other, Build.VERSION_CODES.VANILLA_ICE_CREAM))
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
            JobForegroundService.foregroundTypeFor(other + ActivityKind.BACKING_UP, Build.VERSION_CODES.VANILLA_ICE_CREAM),
        )
    }

    private fun asset(id: Long, name: String = "IMG_$id.jpg", external: Boolean = false, trashed: Boolean = false, path: String? = "DCIM/Camera/") = MediaAsset(
        id = MediaId(id), contentUriString = "content://media/external/images/media/$id", displayName = name, mimeType = "image/jpeg",
        bucketName = "Camera", dateTakenMillis = 10, dateModifiedSeconds = 20, width = 4, height = 3, sizeBytes = 99,
        relativePath = path, isFavorite = false, isTrashed = trashed, isExternal = external,
    )

    @Test
    fun `candidates carry the lock, the sensitive mark and the archive flag, and drop shared-in files`() {
        val assets = listOf(
            asset(1),
            asset(2, path = "DCIM/Private/"),
            asset(3),
            asset(4),
            asset(5, external = true),
            asset(6, trashed = true),
        )
        val locked = setOf(com.fotoxplorr.app.gallery.folderIdentity(assets[1]).key.value)
        val candidates = BackupCandidates.from(assets, locked, sensitiveIds = setOf(MediaId(3)), archivedIds = setOf(MediaId(4)))
        assertEquals(listOf(1L, 2L, 3L, 4L, 6L), candidates.map { it.mediaId })
        val byId = candidates.associateBy { it.mediaId }
        assertTrue(byId.getValue(2).isProtected)
        assertFalse(byId.getValue(1).isProtected)
        assertTrue(byId.getValue(3).isSensitive)
        assertTrue(byId.getValue(4).isArchived)
        assertTrue(byId.getValue(6).isTrashed)
        assertEquals("IMG_1.jpg", byId.getValue(1).displayName)
        assertEquals(99L, byId.getValue(1).sizeBytes)
        assertNull(null)
    }
}
