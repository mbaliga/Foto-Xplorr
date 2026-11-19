package com.fotoxplorr.app.sources.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fotoxplorr.app.sources.previews.InMemoryPreviewSettings
import com.fotoxplorr.app.sources.previews.PreviewPolicy
import com.fotoxplorr.app.sources.previews.SourcePreviewStore
import com.fotoxplorr.app.sources.ui.SourcesCopy
import com.fotoxplorr.core.db.sources.FotozSourcesDatabase
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Removing a source takes its offline previews and their settings with it, and nothing else. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SourcesRuntimePreviewsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val dcim = "content://auth/tree/primary%3ADCIM"
    private val pictures = "content://auth/tree/primary%3APictures"
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: FotozSourcesDatabase
    private val grants = FakeGrants()
    private val meta = InMemorySourceMetaStore()
    private val settings = InMemoryPreviewSettings()
    private val store by lazy { SourcePreviewStore(File(tmp.root, "p"), capOf = settings::capBytes, io = Dispatchers.Unconfined) }

    @Before fun setUp() { db = inMemorySourcesDb(context) }

    @After fun tearDown() { db.close() }

    private fun runtime(onRemoved: suspend (Long) -> Unit = { row -> store.forget(row); settings.forget(row) }) = SourcesRuntime(
        dbExists = { true }, dbFactory = { db }, grants = grants, meta = meta,
        indexerFor = { error("no index in this test") }, sampleMs = 0, onRemoved = onRemoved,
    )

    @Test
    fun `removing a source clears its previews and settings and leaves another source alone`() = runBlocking {
        val r = runtime()
        val a = (r.addSource(dcim) as AddResult.Added).sourceRow
        val b = (r.addSource(pictures) as AddResult.Added).sourceRow
        assertTrue(store.write(a, 1, 10, ByteArray(300)))
        assertTrue(store.write(b, 1, 10, ByteArray(500)))
        settings.setCapBytes(a, 250_000_000L); settings.setKeep(a, false); settings.setAppliedDrivePolicy(a, 2)
        settings.setCapBytes(b, 250_000_000L)

        r.removeSource(a)

        assertNull("the size is gone from the live sizes too", store.sizes.value[a])
        assertFalse(File(tmp.root, "p/$a").exists())
        assertEquals(0L, store.sizeBytes(a))
        assertEquals(PreviewPolicy.DEFAULT_CAP_BYTES, settings.capBytes(a))
        assertTrue(settings.keep(a))
        assertNull(settings.appliedDrivePolicy(a))
        assertEquals("the other source keeps its previews and settings", 500L, store.sizeBytes(b))
        assertEquals(250_000_000L, settings.capBytes(b))
    }

    @Test
    fun `the hook runs once per removal with the removed row and a failing hook does not undo the removal`() = runBlocking {
        val seen = ArrayList<Long>()
        val r = runtime { row -> seen += row; error("disk full") }
        val row = (r.addSource(dcim) as AddResult.Added).sourceRow
        r.removeSource(row)
        assertEquals(listOf(row), seen)
        assertNull(db.sourcesDao().get(row))
    }

    @Test
    fun `a removal of a source that is not there touches no previews`() = runBlocking {
        val seen = ArrayList<Long>()
        val r = runtime { seen += it }
        r.removeSource(99)
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `with no sources the hook is not reached and nothing is opened`() = runBlocking {
        val seen = ArrayList<Long>()
        var opened = 0
        val r = SourcesRuntime(
            dbExists = { false }, dbFactory = { opened++; db }, grants = grants, meta = meta,
            indexerFor = { error("none") }, sampleMs = 0, onRemoved = { seen += it },
        )
        r.removeSource(1)
        assertTrue(seen.isEmpty())
        assertEquals(0, opened)
    }

    @Test
    fun `the confirm text says the saved previews go too`() {
        assertTrue(SourcesCopy.REMOVE_BODY.contains("previews"))
        assertTrue(SourcesCopy.REMOVE_BODY.contains("never touches your files"))
    }
}
