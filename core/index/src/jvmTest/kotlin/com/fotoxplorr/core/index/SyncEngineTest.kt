package com.fotoxplorr.core.index

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.fotoxplorr.core.db.FotozDatabase
import com.fotoxplorr.core.db.entity.Availability
import com.fotoxplorr.core.db.entity.SourceEntity
import com.fotoxplorr.core.db.entity.SourceKind
import com.fotoxplorr.core.db.entity.SourceState
import com.fotoxplorr.core.model.SourceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [SyncEngine]'s own correctness tests, against a hand-built [FakeSource] (see its own KDoc) --
 * the same shape `MigrationToV2Test` uses against `FakeLegacySource`. Covers MASTER-PLAN.md
 * §2.3's acceptance bar verbatim: an unplugged source's rows go OFFLINE not deleted; a
 * mtime-preserved change is still picked up by the delta; partial access never sweeps -- plus
 * TRAPS #1/#8 pinning (a delta, and a failed pass, never sweep) and the FK-stability/baseline-row
 * invariants the migration engine already established for this schema.
 */
class SyncEngineTest {

    private lateinit var db: FotozDatabase
    private lateinit var engine: SyncEngine

    @BeforeTest
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder<FotozDatabase>()
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        engine = SyncEngine(db.sourceDao(), db.assetDao(), db.assetUserDao())
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private suspend fun seedSource(): SourceEntity {
        val sourceId = SourceId(db.sourceDao().insert(testSource()))
        return db.sourceDao().get(sourceId)!!
    }

    @Test
    fun `first sync has no baseline, does a full enumeration, and populates asset plus source`() = runBlocking {
        var source = seedSource()
        val fake = FakeSource()
        fake.put(testItem("1"))
        fake.put(testItem("2"))

        val result = engine.sync(fake, source, partialAccess = false, nowMs = 1_000L)

        assertEquals(SyncResult.Completed(itemsSeen = 2, fullyEnumerated = true), result)
        assertEquals(2, db.assetDao().count())
        val asset1 = db.assetDao().findByLocator(source.sourceId, "1")
        assertNotNull(asset1)
        assertEquals(Availability.ONLINE, asset1.availability)
        assertNotNull(db.assetUserDao().get(asset1.assetId), "a newly discovered asset gets a baseline asset_user row")

        source = db.sourceDao().get(source.sourceId)!!
        assertEquals("v1", source.syncVersion)
        assertNotNull(source.syncGeneration)
        assertEquals(1_000L, source.lastFullScanMs)
    }

    @Test
    fun `a delta pass never sweeps -- an item the delta didn't see stays exactly where it was`() = runBlocking {
        var source = seedSource()
        val fake = FakeSource()
        fake.put(testItem("1"))
        fake.put(testItem("2"))
        engine.sync(fake, source, partialAccess = false, nowMs = 1_000L)
        source = db.sourceDao().get(source.sourceId)!!

        // Only item 1 changes; item 2 is untouched and must not even be considered, let alone swept.
        fake.put(testItem("1", displayName = "IMG_1_edited.jpg"))
        val result = engine.sync(fake, source, partialAccess = false, nowMs = 2_000L)

        assertEquals(SyncResult.Completed(itemsSeen = 1, fullyEnumerated = false), result)
        val asset2 = db.assetDao().findByLocator(source.sourceId, "2")
        assertNotNull(asset2)
        assertEquals(Availability.ONLINE, asset2.availability, "a delta pass must never sweep -- TRAPS #1's generalisation")
    }

    @Test
    fun `mtime-preserved files are still picked up by the delta`() = runBlocking {
        var source = seedSource()
        val fake = FakeSource()
        val unchangedModifiedMs = 1_600_000_000_000L
        fake.put(testItem("1", dateModifiedMs = unchangedModifiedMs))
        engine.sync(fake, source, partialAccess = false, nowMs = 1_000L)
        source = db.sourceDao().get(source.sourceId)!!

        // The file's content/metadata changes (display name), but date_modified is deliberately
        // held constant -- the old DATE_MODIFIED-bounded delta would have missed this entirely;
        // generation tracking (FakeSource.put always bumps the volume generation) must not.
        fake.put(testItem("1", displayName = "IMG_1_renamed.jpg", dateModifiedMs = unchangedModifiedMs))
        val result = engine.sync(fake, source, partialAccess = false, nowMs = 2_000L)

        assertEquals(1, (result as SyncResult.Completed).itemsSeen)
        assertEquals("IMG_1_renamed.jpg", db.assetDao().findByLocator(source.sourceId, "1")?.displayName)
    }

    @Test
    fun `a full pass sweeps -- a locator not found becomes MISSING_CONFIRMED, never deleted`() = runBlocking {
        var source = seedSource()
        val fake = FakeSource()
        fake.put(testItem("1"))
        fake.put(testItem("2"))
        engine.sync(fake, source, partialAccess = false, nowMs = 1_000L)
        source = db.sourceDao().get(source.sourceId)!!

        fake.remove("2")
        // Forces the next pass to be full (no stored baseline) without disturbing FakeSource's
        // own item state the way reset() would -- a plain delta could never confirm a removal at
        // all (TRAPS #8: it doesn't see the whole picture), so this test needs a real full pass.
        db.sourceDao().setSyncState(source.sourceId, syncVersion = null, syncGeneration = null, lastFullScanMs = null)
        source = db.sourceDao().get(source.sourceId)!!
        engine.sync(fake, source, partialAccess = false, nowMs = 2_000L)

        assertEquals(2, db.assetDao().count(), "MISSING_CONFIRMED keeps the row -- ADR-011 §6, never a delete")
        val asset2 = db.assetDao().findByLocator(source.sourceId, "2")
        assertNotNull(asset2)
        assertEquals(Availability.MISSING_CONFIRMED, asset2.availability)
        val asset1 = db.assetDao().findByLocator(source.sourceId, "1")
        assertEquals(Availability.ONLINE, asset1?.availability)
    }

    @Test
    fun `partial access never sweeps even on a fully enumerated pass`() = runBlocking {
        var source = seedSource()
        val fake = FakeSource()
        fake.put(testItem("1"))
        fake.put(testItem("2"))
        engine.sync(fake, source, partialAccess = false, nowMs = 1_000L)
        source = db.sourceDao().get(source.sourceId)!!

        fake.remove("2")
        db.sourceDao().setSyncState(source.sourceId, syncVersion = null, syncGeneration = null, lastFullScanMs = null)
        source = db.sourceDao().get(source.sourceId)!!
        val result = engine.sync(fake, source, partialAccess = true, nowMs = 2_000L)

        assertTrue((result as SyncResult.Completed).fullyEnumerated, "the pass itself was complete...")
        assertEquals(
            Availability.ONLINE,
            db.assetDao().findByLocator(source.sourceId, "2")?.availability,
            "...but partial media access must still refuse to sweep -- MASTER-PLAN.md §2.3",
        )
    }

    @Test
    fun `an unplugged source's rows go OFFLINE, not deleted, with no enumeration attempted`() = runBlocking {
        val source = seedSource()
        val fake = FakeSource()
        fake.put(testItem("1"))
        fake.put(testItem("2"))
        engine.sync(fake, source, partialAccess = false, nowMs = 1_000L)

        // The volume is gone -- MediaStore.getExternalVolumeNames() no longer lists it. No
        // enumeration is even attempted; markSourceOffline is the entire mechanism.
        engine.markSourceOffline(source.sourceId)

        assertEquals(SourceState.OFFLINE, db.sourceDao().get(source.sourceId)?.state)
        assertEquals(2, db.assetDao().count(), "still not a delete")
        assertEquals(Availability.OFFLINE, db.assetDao().findByLocator(source.sourceId, "1")?.availability)
        assertEquals(Availability.OFFLINE, db.assetDao().findByLocator(source.sourceId, "2")?.availability)
    }

    @Test
    fun `reconnect forces a full re-sync that resolves every previously OFFLINE row`() = runBlocking {
        var source = seedSource()
        val fake = FakeSource()
        fake.put(testItem("1"))
        fake.put(testItem("2"))
        engine.sync(fake, source, partialAccess = false, nowMs = 1_000L)
        engine.markSourceOffline(source.sourceId)

        // Reconnects -- item 1 is still there, item 2 genuinely isn't (a different device wrote
        // to the card while it was elsewhere).
        engine.markSourceOnline(source.sourceId)
        fake.remove("2")
        source = db.sourceDao().get(source.sourceId)!!
        assertNull(source.syncVersion, "markSourceOnline must clear the generation baseline")

        val result = engine.sync(fake, source, partialAccess = false, nowMs = 3_000L)

        assertTrue((result as SyncResult.Completed).fullyEnumerated, "no baseline left -> must be a full pass")
        assertEquals(SourceState.ONLINE, db.sourceDao().get(source.sourceId)?.state)
        assertEquals(Availability.ONLINE, db.assetDao().findByLocator(source.sourceId, "1")?.availability)
        assertEquals(
            Availability.MISSING_CONFIRMED,
            db.assetDao().findByLocator(source.sourceId, "2")?.availability,
            "found again -> ONLINE; still missing -> MISSING_CONFIRMED, not stuck OFFLINE forever",
        )
    }

    @Test
    fun `a failed enumeration never sweeps and never advances the sync token`() = runBlocking {
        var source = seedSource()
        val fake = FakeSource()
        fake.put(testItem("1"))
        fake.put(testItem("2"))
        engine.sync(fake, source, partialAccess = false, nowMs = 1_000L)
        source = db.sourceDao().get(source.sourceId)!!
        val tokenBefore = source.syncGeneration

        fake.remove("2")
        fake.reset("v2")
        fake.failNext(IllegalStateException("cursor died mid-read"))
        val result = engine.sync(fake, source, partialAccess = false, nowMs = 2_000L)

        assertTrue(result is SyncResult.Failed)
        assertEquals(
            Availability.ONLINE,
            db.assetDao().findByLocator(source.sourceId, "2")?.availability,
            "a pass that never reaches Completed must never sweep",
        )
        assertEquals(tokenBefore, db.sourceDao().get(source.sourceId)?.syncGeneration, "and never advance the token either")
    }

    @Test
    fun `an existing asset keeps its asset_id across an update -- foreign keys stay valid`() = runBlocking {
        var source = seedSource()
        val fake = FakeSource()
        fake.put(testItem("1"))
        engine.sync(fake, source, partialAccess = false, nowMs = 1_000L)
        source = db.sourceDao().get(source.sourceId)!!
        val originalAssetId = db.assetDao().findByLocator(source.sourceId, "1")!!.assetId

        fake.put(testItem("1", displayName = "IMG_1_v2.jpg"))
        engine.sync(fake, source, partialAccess = false, nowMs = 2_000L)

        assertEquals(1, db.assetDao().count(), "an update must never re-insert")
        assertEquals(originalAssetId, db.assetDao().findByLocator(source.sourceId, "1")?.assetId)
    }

    @Test
    fun `a sync pass never clobbers fields it doesn't own, like a later fingerprint`() = runBlocking {
        var source = seedSource()
        val fake = FakeSource()
        fake.put(testItem("1"))
        engine.sync(fake, source, partialAccess = false, nowMs = 1_000L)
        source = db.sourceDao().get(source.sourceId)!!
        val asset = db.assetDao().findByLocator(source.sourceId, "1")!!
        db.assetDao().update(asset.copy(fingerprint = "abc123"))

        fake.put(testItem("1", displayName = "IMG_1_v2.jpg"))
        engine.sync(fake, source, partialAccess = false, nowMs = 2_000L)

        assertEquals("abc123", db.assetDao().findByLocator(source.sourceId, "1")?.fingerprint)
    }

    @Test
    fun `a MediaStore version reset forces a full pass even with a generation token already recorded`() = runBlocking {
        var source = seedSource()
        val fake = FakeSource()
        fake.put(testItem("1"))
        fake.put(testItem("2"))
        engine.sync(fake, source, partialAccess = false, nowMs = 1_000L)
        source = db.sourceDao().get(source.sourceId)!!
        assertNotNull(source.syncGeneration)

        fake.reset("v2") // e.g. the MediaProvider database itself was rebuilt
        fake.put(testItem("1"))
        val result = engine.sync(fake, source, partialAccess = false, nowMs = 2_000L)

        assertTrue((result as SyncResult.Completed).fullyEnumerated, "a version change must never be trusted as a plain delta bound")
        assertEquals("v2", db.sourceDao().get(source.sourceId)?.syncVersion)
    }

    private fun testSource() = SourceEntity(
        sourceId = SourceId(0),
        kind = SourceKind.MEDIASTORE_VOLUME,
        rootLocator = "external_primary",
        volumeUuid = null,
        displayName = "Internal storage",
        state = SourceState.ONLINE,
        syncVersion = null,
        syncGeneration = null,
        lastFullScanMs = null,
    )
}
