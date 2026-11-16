package com.fotoxplorr.app.sources.drives

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fotoxplorr.app.sources.runtime.AddResult
import com.fotoxplorr.app.sources.runtime.FakeGrants
import com.fotoxplorr.app.sources.runtime.InMemorySourceMetaStore
import com.fotoxplorr.app.sources.runtime.SourcesRuntime
import com.fotoxplorr.app.sources.runtime.inMemorySourcesDb
import com.fotoxplorr.core.db.sources.FotozSourcesDatabase
import com.fotoxplorr.core.index.sources.SourceState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DriveSourcesRuntimeTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val usbTree = "content://auth/tree/1234-ABCD%3ADCIM"
    private val otherTree = "content://auth/tree/FFFF-9999%3ADCIM"
    private val phoneTree = "content://auth/tree/primary%3APictures"
    private lateinit var db: FotozSourcesDatabase
    private lateinit var grants: FakeGrants
    private lateinit var runtime: SourcesRuntime

    @Before fun setUp() {
        SourcesRuntime.forgetInstance()
        db = inMemorySourcesDb(context)
        grants = FakeGrants()
        runtime = SourcesRuntime(
            dbExists = { true }, dbFactory = { db }, grants = grants, meta = InMemorySourceMetaStore(),
            indexerFor = { error("a plug must never start a read by itself") }, sampleMs = 0,
        )
    }

    @After fun tearDown() {
        db.close()
        SourcesRuntime.forgetInstance()
    }

    private suspend fun states() = runtime.statuses().first().associate { it.info.displayName to it.info.state }

    @Test
    fun `unplug marks only that drive offline and replug brings it back with every row`() = runBlocking {
        val usb = (runtime.addSource(usbTree, "Stick") as AddResult.Added).sourceRow
        runtime.addSource(otherTree, "Other")
        runtime.addSource(phoneTree, "Phone")
        val before = db.sourcesDao().getAll()

        assertEquals(listOf(usb), runtime.setVolumeReachable("1234-abcd", reachable = false))
        assertEquals(mapOf("Stick" to SourceState.OFFLINE, "Other" to SourceState.ONLINE, "Phone" to SourceState.ONLINE), states())
        assertEquals("rows, locators and grants are untouched", before.map { it.rootLocator }, db.sourcesDao().getAll().map { it.rootLocator })
        assertEquals(emptyList<String>(), grants.forgotten)

        runtime.setVolumeReachable("1234-ABCD", reachable = true)
        assertEquals(SourceState.ONLINE, states().getValue("Stick"))
        assertNotNull(db.sourcesDao().get(usb))
    }

    @Test
    fun `a drive that comes back without its grant is revoked not online`() = runBlocking {
        runtime.addSource(usbTree, "Stick")
        runtime.setVolumeReachable("1234-ABCD", reachable = false)
        grants.heldUris.clear()
        runtime.setVolumeReachable("1234-ABCD", reachable = true)
        assertEquals(SourceState.REVOKED, states().getValue("Stick"))
    }

    @Test
    fun `the internal volume and an unknown drive change nothing`() = runBlocking {
        runtime.addSource(phoneTree, "Phone")
        assertEquals(emptyList<Long>(), runtime.setVolumeReachable("primary", reachable = false))
        assertEquals(emptyList<Long>(), runtime.setVolumeReachable("9999-0000", reachable = false))
        assertEquals(SourceState.ONLINE, states().getValue("Phone"))
    }

    @Test
    fun `with no source database it opens nothing`() = runBlocking {
        var built = false
        val empty = SourcesRuntime(
            dbExists = { false }, dbFactory = { built = true; db }, grants = grants, meta = InMemorySourceMetaStore(),
            indexerFor = { error("no") }, sampleMs = 0,
        )
        assertEquals(emptyList<Long>(), empty.setVolumeReachable("1234-ABCD", reachable = false))
        assertEquals(false, built)
    }
}
