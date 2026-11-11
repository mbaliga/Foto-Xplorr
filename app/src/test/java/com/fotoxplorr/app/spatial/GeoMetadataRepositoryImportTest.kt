package com.fotoxplorr.app.spatial

import com.fotoxplorr.core.model.MediaId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** [GeoMetadataRepository.importManualPins]: hand-placed pins coming back from an encrypted backup. Additive. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeoMetadataRepositoryImportTest {
    private val context = RuntimeEnvironment.getApplication()

    private fun pin(id: Long, lat: Double, lon: Double) = GeoMetadata(MediaId(id), lat, lon, null, null)

    @Test
    fun `pins are added and shown, as hand-placed ones`() {
        val repo = GeoMetadataRepository(context)
        val added = runBlocking { repo.importManualPins(listOf(pin(1, 51.5, -0.12), pin(2, -33.9, 151.2))) }

        assertEquals(2, added)
        assertEquals(setOf(MediaId(1), MediaId(2)), repo.observe().value.metadataById.keys)
        assertEquals(setOf(1L, 2L), runBlocking { repo.manualPins() }.map { it.mediaId.value }.toSet())
    }

    @Test
    fun `a photo that already has a hand-placed pin keeps it`() {
        val repo = GeoMetadataRepository(context)
        runBlocking { repo.setManualLocation(MediaId(1), 10.0, 20.0) }

        val added = runBlocking { repo.importManualPins(listOf(pin(1, 51.5, -0.12), pin(2, 1.0, 2.0))) }

        assertEquals(1, added)
        val mine = repo.observe().value.metadataById.getValue(MediaId(1))
        assertEquals(10.0, mine.latitude, 0.0001)
        assertEquals(20.0, mine.longitude, 0.0001)
    }

    @Test
    fun `a pin off the globe is refused`() {
        val repo = GeoMetadataRepository(context)
        val added = runBlocking {
            repo.importManualPins(listOf(pin(1, 91.0, 0.0), pin(2, 0.0, 181.0), pin(3, Double.NaN, 0.0), pin(4, 45.0, 45.0)))
        }
        assertEquals(1, added)
        assertEquals(setOf(MediaId(4)), repo.observe().value.metadataById.keys)
    }

    @Test
    fun `importing the same pins again adds none`() {
        val repo = GeoMetadataRepository(context)
        runBlocking { repo.importManualPins(listOf(pin(1, 1.0, 2.0))) }
        assertEquals(0, runBlocking { repo.importManualPins(listOf(pin(1, 1.0, 2.0))) })
    }

    @Test
    fun `the instance the map shows learns of the pins too`() {
        val shown = GeoMetadataRepository(context)
        runBlocking { GeoMetadataRepository(context).importManualPins(listOf(pin(7, 12.0, 34.0))) }

        val deadline = System.currentTimeMillis() + 5_000
        while (MediaId(7) !in shown.observe().value.metadataById && System.currentTimeMillis() < deadline) Thread.sleep(20)

        assertTrue("the other instance reloaded", MediaId(7) in shown.observe().value.metadataById)
    }
}
