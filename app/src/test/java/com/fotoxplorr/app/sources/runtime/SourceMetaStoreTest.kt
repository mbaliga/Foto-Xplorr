package com.fotoxplorr.app.sources.runtime

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
class SourceMetaStoreTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun file() = File(context.applicationInfo.dataDir, "shared_prefs/fotoz-sources-meta.xml")

    @Test
    fun `reading and defaults write no file`() {
        val s = PrefsSourceMetaStore(context)
        assertEquals(SourceMeta(), s.get(4))
        assertTrue(s.all().value.isEmpty())
        s.remove(4)
        assertFalse(file().exists())
    }

    @Test
    fun `choices survive a new store and removing forgets them`() {
        val s = PrefsSourceMetaStore(context)
        s.update(3) { it.copy(included = false, indexOnConnect = true, tookGrant = true, lastIndexedMs = 99, lastError = "x") }
        s.update(5) { it.copy(lastError = null) }
        val again = PrefsSourceMetaStore(context)
        assertEquals(SourceMeta(included = false, indexOnConnect = true, tookGrant = true, lastIndexedMs = 99, lastError = "x"), again.get(3))
        assertEquals(SourceMeta(), again.get(5))
        assertEquals(setOf(3L, 5L), again.all().value.keys)
        again.remove(3)
        assertEquals(setOf(5L), PrefsSourceMetaStore(context).all().value.keys)
    }
}
