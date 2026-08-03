package com.fotoxplorr.app.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FolderIdentityTest {
    @Test
    fun `same display name in different paths gets different keys`() {
        val camera = folderIdentity("Camera", "DCIM/Camera/")
        val downloads = folderIdentity("Camera", "Pictures/Camera/")

        assertEquals("Camera", camera.displayName)
        assertEquals("Camera", downloads.displayName)
        assertNotEquals(camera.key, downloads.key)
    }

    @Test
    fun `path normalization is case and slash insensitive`() {
        val first = folderIdentity(null, "DCIM\\Camera\\")
        val second = folderIdentity(null, "dcim/camera/")

        assertEquals(first.key, second.key)
        assertEquals("Camera", first.displayName)
    }

    @Test
    fun `missing folder metadata maps to other`() {
        val identity = folderIdentity(null, null)

        assertEquals(FolderKey("other"), identity.key)
        assertEquals("Other", identity.displayName)
    }
}
