package com.fotoxplorr.app.sources.drives

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DriveSettingsStoreTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun file() = File(context.applicationInfo.dataDir, "shared_prefs/fotoz-drives.xml")

    @Test
    fun `reading and defaults write no file and the defaults are index off and previews as viewed`() {
        val s = PrefsDriveSettingsStore(context)
        val d = s.get("1234-ABCD")
        assertFalse(d.indexOnConnect)
        assertFalse(d.never)
        assertTrue(d.included)
        assertEquals(PreviewPolicy.AS_VIEWED, d.previews)
        assertTrue(s.all().value.isEmpty())
        s.forget("1234-ABCD")
        assertFalse(file().exists())
    }

    @Test
    fun `answers survive a new store and forgetting removes them`() {
        val a = PrefsDriveSettingsStore(context)
        a.update("1234-ABCD") { it.copy(label = "SD", never = true, asked = true, indexOnConnect = true, previews = PreviewPolicy.ALL) }
        a.update("ABCD-0001") { it.copy(asked = true) }
        val b = PrefsDriveSettingsStore(context)
        assertEquals(DriveSettings("SD", never = true, asked = true, included = true, indexOnConnect = true, previews = PreviewPolicy.ALL), b.get("1234-ABCD"))
        assertEquals(setOf("1234-ABCD", "ABCD-0001"), b.all().value.keys)
        b.forget("1234-ABCD")
        assertEquals(setOf("ABCD-0001"), PrefsDriveSettingsStore(context).all().value.keys)
    }

    @Test
    fun `it is a file of its own and touches no other store`() {
        PrefsDriveSettingsStore(context).update("X-1") { it.copy(asked = true) }
        assertTrue(file().exists())
        assertFalse(File(context.applicationInfo.dataDir, "shared_prefs/fotoz-sources-meta.xml").exists())
    }
}
