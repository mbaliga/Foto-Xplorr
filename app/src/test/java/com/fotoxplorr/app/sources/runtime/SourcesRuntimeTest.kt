package com.fotoxplorr.app.sources.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fotoxplorr.app.jobs.JobRunner
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.sources.gallery.mergeSourceAssets
import com.fotoxplorr.core.db.sources.FotozSourcesDatabase
import com.fotoxplorr.core.index.sources.SourceAssetIds
import com.fotoxplorr.core.index.sources.SourceFolderKey
import com.fotoxplorr.core.model.MediaId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SourcesRuntimeTest {
    private val dcim = "content://auth/tree/primary%3ADCIM"
    private val camera = "content://auth/tree/primary%3ADCIM%2FCamera"
    private val pictures = "content://auth/tree/primary%3APictures"
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: FotozSourcesDatabase
    private lateinit var grants: FakeGrants
    private lateinit var meta: InMemorySourceMetaStore
    private lateinit var tree: MemoryTree
    private val facts = FakeFacts()

    @Before fun setUp() {
        SourcesRuntime.forgetInstance()
        db = inMemorySourcesDb(context)
        grants = FakeGrants()
        meta = InMemorySourceMetaStore()
        tree = MemoryTree.generate(120)
    }

    @After fun tearDown() {
        db.close()
        SourcesRuntime.forgetInstance()
    }

    private fun runtime(exists: Boolean = true) = SourcesRuntime(
        dbExists = { exists },
        dbFactory = { db },
        grants = grants,
        meta = meta,
        indexerFor = { d ->
            SourceIndexer(d, grants, { tree }, { _, id -> "content://auth/document/$id" }, facts.reader, meta, enrichConfig = EnrichConfig(localAuthorities = setOf("auth")))
        },
        sampleMs = 0,
    )

    private suspend fun sourceNames(r: SourcesRuntime) = r.statuses().first().map { it.info.displayName }

    @Test
    fun `adding a folder keeps its grant and lists it online`() = runBlocking {
        val r = runtime()
        val added = r.addSource(dcim) as AddResult.Added
        assertTrue(dcim in grants.heldUris)
        val status = r.statuses().first().single()
        assertEquals(added.sourceRow, status.info.row)
        assertEquals("DCIM", status.info.displayName)
        assertEquals("primary", status.info.volumeUuid)
        assertEquals("ONLINE", status.info.state.wire)
        assertTrue(status.meta.included)
        assertFalse(status.meta.indexOnConnect)
        assertTrue("took the grant itself", status.meta.tookGrant)
    }

    @Test
    fun `the same folder twice is refused`() = runBlocking {
        val r = runtime()
        r.addSource(dcim)
        assertEquals(AddResult.Duplicate("DCIM"), r.addSource(dcim))
        assertEquals(1, r.statuses().first().size)
    }

    @Test
    fun `a folder inside one already added is refused and so is one around it`() = runBlocking {
        val r = runtime()
        r.addSource(dcim)
        assertEquals(AddResult.InsideExisting("DCIM"), r.addSource(camera))
        r.removeSource(r.statuses().first().single().info.row)
        r.addSource(camera)
        assertEquals(AddResult.ContainsExisting("Camera"), r.addSource(dcim))
        assertTrue("a sibling is fine", r.addSource(pictures) is AddResult.Added)
        assertEquals(2, r.statuses().first().size)
        assertFalse("a refused folder is not kept", dcim in grants.heldUris)
    }

    @Test
    fun `a refused grant adds nothing`() = runBlocking {
        grants.refuse = "Android would not keep access to that folder."
        val r = runtime()
        val result = r.addSource(dcim)
        assertEquals(AddResult.GrantRefused("Android would not keep access to that folder."), result)
        assertTrue(r.statuses().first().isEmpty())
    }

    @Test
    fun `removing a source deletes only its index rows and never reads a folder`() = runBlocking {
        val r = runtime()
        val a = (r.addSource(dcim) as AddResult.Added).sourceRow
        val b = (r.addSource(pictures) as AddResult.Added).sourceRow
        r.index(a)
        r.index(b)
        val listingsBefore = tree.listings
        val dirsBefore = tree.dirs.toMap()
        val keep = db.sourceAssetsDao().count(b)
        assertTrue(db.sourceAssetsDao().count(a) > 0)

        r.removeSource(a)

        assertEquals(0, db.sourceAssetsDao().count(a))
        assertNull(db.sourcesDao().get(a))
        assertTrue(db.sourceFoldersDao().forSource(a).isEmpty())
        assertEquals("the other source is untouched", keep, db.sourceAssetsDao().count(b))
        assertEquals("not one folder was listed to remove a source", listingsBefore, tree.listings)
        assertEquals("the tree itself is unchanged", dirsBefore, tree.dirs)
        assertEquals(listOf(dcim), grants.forgotten)
        assertEquals(listOf("Pictures"), sourceNames(r))
        assertTrue(meta.get(b).tookGrant)
        assertEquals(SourceMeta(), meta.get(a))
    }

    @Test
    fun `a grant another feature already held is never given back`() = runBlocking {
        grants.heldUris += dcim
        val r = runtime()
        val row = (r.addSource(dcim) as AddResult.Added).sourceRow
        assertFalse(meta.get(row).tookGrant)
        r.removeSource(row)
        assertTrue(grants.forgotten.isEmpty())
        assertTrue(dcim in grants.heldUris)
    }

    @Test
    fun `a grant that another feature uses after the source was added is kept too`() = runBlocking {
        val r = runtime()
        val row = (r.addSource(dcim) as AddResult.Added).sourceRow
        grants.otherFeatures += dcim
        r.removeSource(row)
        assertTrue(grants.forgotten.isEmpty())
    }

    @Test
    fun `a lost grant makes the source revoked and keeps its rows and a restored one lifts it`() = runBlocking {
        val r = runtime()
        val row = (r.addSource(dcim) as AddResult.Added).sourceRow
        r.index(row)
        val files = db.sourceAssetsDao().count(row)
        grants.heldUris -= dcim
        r.refreshStates()
        val revoked = r.statuses().first().single()
        assertEquals("REVOKED", revoked.info.state.wire)
        assertEquals(files, revoked.files)
        assertEquals(files, db.sourceAssetsDao().count(row))
        grants.heldUris += dcim
        r.refreshStates()
        assertEquals("ONLINE", r.statuses().first().single().info.state.wire)
    }

    @Test
    fun `status counts are the present files and their bytes and the sizing result`() = runBlocking {
        val r = runtime()
        val row = (r.addSource(dcim) as AddResult.Added).sourceRow
        facts.failFor += tree.dirs.getValue("root").first { !it.isDirectory }.documentId
        r.index(row)
        val s = r.statuses().first().single()
        assertEquals(120, s.files)
        assertEquals(1, s.unreadable)
        assertEquals(119, s.sized)
        assertEquals((0 until 120).sumOf { 1000L + it }, s.bytes)
        assertTrue(s.meta.lastIndexedMs > 0)
        assertNull(s.progress)
    }

    @Test
    fun `a second index of the same source while one runs is turned away`() = runBlocking {
        val r = runtime()
        val row = (r.addSource(dcim) as AddResult.Added).sourceRow
        val gate = CompletableDeferred<Unit>()
        tree.beforeListing = { runBlocking { gate.await() } }
        val first = async(Dispatchers.Default) { r.index(row) }
        withTimeout(10_000) { while (!r.isIndexing(row)) delay(5) }
        assertEquals(IndexOutcome.Busy, r.index(row))
        assertNotNull(r.progress().value[row])
        gate.complete(Unit)
        assertTrue(first.await() is IndexOutcome.Completed)
        assertFalse(r.isIndexing(row))
        assertTrue(r.progress().value.isEmpty())
    }

    @Test
    fun `removing a source while it is indexed stops the index`() = runBlocking {
        val r = runtime()
        val row = (r.addSource(dcim) as AddResult.Added).sourceRow
        val gate = CompletableDeferred<Unit>()
        tree.beforeListing = { runBlocking { gate.await() } }
        val job = async(Dispatchers.Default) { runCatching { r.index(row) } }
        withTimeout(10_000) { while (!r.isIndexing(row)) delay(5) }
        val removal = async(Dispatchers.Default) { r.removeSource(row) }
        delay(50)
        gate.complete(Unit)
        removal.await()
        try { job.await() } catch (e: kotlinx.coroutines.CancellationException) { }
        assertNull(db.sourcesDao().get(row))
    }

    @Test
    fun `the provider hands the gallery rows joined with their source and hides an excluded one`() = runBlocking {
        val r = runtime()
        val a = (r.addSource(dcim) as AddResult.Added).sourceRow
        val b = (r.addSource(pictures) as AddResult.Added).sourceRow
        r.index(a)
        r.index(b)
        val entries = withTimeout(10_000) { r.provider.assets().first { it.size == 240 } }
        assertEquals(setOf(a, b), entries.map { it.source.row }.toSet())
        val any = entries.first { it.source.row == a }
        assertEquals("DCIM", any.source.displayName)
        assertEquals(a, any.row.sourceRow)
        assertEquals(400, any.row.width)
        assertTrue(entries.all { it.row.present && it.row.contentUri!!.startsWith("content://auth/document/") })

        r.setIncluded(b, false)
        val shown = withTimeout(10_000) { r.provider.assets().first { e -> e.none { it.source.row == b } } }
        assertEquals(120, shown.size)
        assertEquals("the rows are kept", 120, db.sourceAssetsDao().count(b))
    }

    @Test
    fun `the merged list the activity draws carries source photos with their own folder and id band`() = runBlocking {
        val r = runtime()
        val a = (r.addSource(dcim) as AddResult.Added).sourceRow
        r.index(a)
        val entries = withTimeout(10_000) { r.provider.assets().first { it.size == 120 } }
        val library = listOf(libraryAsset(7))
        val merged = mergeSourceAssets(library, entries)
        assertEquals(121, merged.size)
        assertSame(library[0], merged[0])
        val source = merged.drop(1)
        assertTrue(source.all { SourceAssetIds.isSourceId(it.id) })
        assertTrue(source.all { SourceFolderKey.sourceRowOf(it.relativePath) == a })
        assertEquals(source.size, source.map { it.id }.toSet().size)
        // A revoked source is not drawn, but nothing is deleted.
        grants.heldUris -= dcim
        r.refreshStates()
        val hidden = withTimeout(10_000) { r.provider.assets().first { e -> e.all { it.source.state.wire == "REVOKED" } } }
        assertEquals(library, mergeSourceAssets(library, hidden))
        assertEquals(120, db.sourceAssetsDao().count(a))
    }

    @Test
    fun `index on connect candidates are those that asked and still hold their grant`() = runBlocking {
        val r = runtime()
        val a = (r.addSource(dcim) as AddResult.Added).sourceRow
        val b = (r.addSource(pictures) as AddResult.Added).sourceRow
        assertTrue(r.indexOnConnectCandidates().isEmpty())
        r.setIndexOnConnect(a, true)
        r.setIndexOnConnect(b, true)
        assertEquals(listOf(a, b), r.indexOnConnectCandidates())
        grants.heldUris -= pictures
        assertEquals(listOf(a), r.indexOnConnectCandidates())
    }

    @Test
    fun `with no sources nothing opens the database`() = runBlocking {
        var opened = 0
        val r = SourcesRuntime(
            dbExists = { false }, dbFactory = { opened++; db }, grants = grants, meta = meta,
            indexerFor = { error("no index without a source") }, sampleMs = 0,
        )
        assertTrue(r.provider.assets().first().isEmpty())
        assertTrue(r.statuses().first().isEmpty())
        r.refreshStates()
        r.removeSource(1)
        assertTrue(r.indexOnConnectCandidates().isEmpty())
        assertEquals(IndexOutcome.Missing, r.index(1))
        assertEquals(0, opened)
        assertFalse(r.databaseOpened)
    }

    @Test
    fun `the production runtime never creates fotoz-sources-db for a phone with no sources`() = runBlocking {
        val file = context.getDatabasePath("fotoz-sources.db")
        assertFalse(file.exists())
        val r = SourcesRuntime.get(context)
        assertTrue(r.provider.assets().first().isEmpty())
        assertTrue(r.statuses().first().isEmpty())
        r.refreshStates()
        assertTrue(r.indexOnConnectCandidates().isEmpty())
        assertFalse(r.databaseOpened)
        assertFalse("the database file was created", file.exists())
        assertFalse("the meta file was created", context.applicationInfo.dataDir.let { java.io.File(it, "shared_prefs/fotoz-sources-meta.xml").exists() })
    }

    @Test
    fun `adding the first source opens the database and the provider follows`() = runBlocking {
        var opened = 0
        val r = SourcesRuntime(
            dbExists = { false }, dbFactory = { opened++; db }, grants = grants, meta = meta,
            indexerFor = { d -> SourceIndexer(d, grants, { tree }, { _, id -> "content://auth/document/$id" }, facts.reader, meta, enrichConfig = EnrichConfig(localAuthorities = setOf("auth"))) },
            sampleMs = 0,
        )
        val seen = async(Dispatchers.Default) { r.provider.assets().first { it.isNotEmpty() } }
        delay(100)
        assertEquals(0, opened)
        val row = (r.addSource(dcim) as AddResult.Added).sourceRow
        r.index(row)
        assertEquals(120, withTimeout(10_000) { seen.await().size.coerceAtLeast(120) })
        assertEquals(1, opened)
        assertTrue(r.databaseOpened)
    }

    @Test
    fun `an index started as a job shows in the runner as scanning with progress and can be stopped`() = runBlocking {
        val r = runtime()
        val row = (r.addSource(dcim) as AddResult.Added).sourceRow
        val gate = CompletableDeferred<Unit>()
        tree.beforeListing = { runBlocking { gate.await() } }
        val runner = JobRunner(CoroutineScope(Dispatchers.Default))
        val handle = SourceJobs.launch(runner, r, row, "DCIM")
        assertNotNull(handle)
        withTimeout(10_000) { while (!r.isIndexing(row)) delay(5) }
        val job = runner.jobs.value.single()
        assertEquals(com.fotoxplorr.app.hyle.ActivityKind.SCANNING, job.kind)
        assertEquals("Reading DCIM", job.title)
        assertNull("one job per source", SourceJobs.launch(runner, r, row, "DCIM"))
        gate.complete(Unit)
        withTimeout(20_000) { while (runner.hasRunningJobs()) delay(10) }
        assertEquals(120, db.sourceAssetsDao().count(row))
    }

    private fun libraryAsset(id: Long) = MediaAsset(
        id = MediaId(id), contentUriString = "content://media/external/images/media/$id", displayName = "IMG_$id.jpg",
        mimeType = "image/jpeg", bucketName = "Camera", dateTakenMillis = 5, dateModifiedSeconds = 0,
        width = 10, height = 10, sizeBytes = 100, relativePath = "DCIM/Camera/", isFavorite = false, isTrashed = false,
    )
}
