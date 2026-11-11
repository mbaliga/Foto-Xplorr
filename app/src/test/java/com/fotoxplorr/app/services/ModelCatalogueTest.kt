package com.fotoxplorr.app.services

import com.fotoxplorr.app.ai.LocalModelState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogueTest {
    private val spec = ModelCatalogue.IMAGE_EMBEDDER
    private fun ready(source: String, size: Long = 5_000_000L) =
        LocalModelState.Ready(File("m.tflite"), size, "abc", source)

    @Test fun notInstalledMapsStraight() {
        assertEquals(ModelEntryState.NotInstalled, ModelCatalogue.entryState(spec, LocalModelState.NotInstalled))
    }

    @Test fun downloadingCarriesProgressOrNullWhenSizeUnknown() {
        val half = ModelCatalogue.entryState(spec, LocalModelState.Downloading(50, 100))
        assertEquals(ModelEntryState.Downloading(0.5f), half)
        assertEquals(ModelEntryState.Downloading(null), ModelCatalogue.entryState(spec, LocalModelState.Downloading(50, null)))
        assertNull(ModelCatalogue.progressOf(5, 0))
        assertEquals(1f, ModelCatalogue.progressOf(200, 100))
    }

    @Test fun readyFromRecommendedAddressOrImportIsInstalled() {
        assertTrue(ModelCatalogue.entryState(spec, ready(spec.sourceUrl)) is ModelEntryState.Installed)
        assertTrue(ModelCatalogue.entryState(spec, ready("user-import")) is ModelEntryState.Installed)
        assertTrue(ModelCatalogue.entryState(spec, ready("unknown")) is ModelEntryState.Installed)
    }

    @Test fun readyFromAnOlderAddressIsUpdate() {
        val state = ModelCatalogue.entryState(spec, ready("https://example.test/old.tflite"))
        assertTrue(state is ModelEntryState.Update)
    }

    @Test fun failedKeepsMessage() {
        assertEquals(ModelEntryState.Failed("x"), ModelCatalogue.entryState(spec, LocalModelState.Failed("x")))
    }

    @Test fun meteredDecision() {
        assertEquals(DownloadDecision.UNAVAILABLE, ModelCatalogue.downloadDecision(canDownload = false, metered = false))
        assertEquals(DownloadDecision.UNAVAILABLE, ModelCatalogue.downloadDecision(canDownload = false, metered = true))
        assertEquals(DownloadDecision.CONFIRM_METERED, ModelCatalogue.downloadDecision(canDownload = true, metered = true))
        assertEquals(DownloadDecision.START, ModelCatalogue.downloadDecision(canDownload = true, metered = false))
    }

    @Test fun storageTotalsOnlyCountInstalled() {
        val none = ModelCatalogue.entries(LocalModelState.NotInstalled, canDownload = true)
        assertEquals(0L, ModelCatalogue.storageUsedBytes(none))
        assertEquals(ServicesCopy.STORAGE_NONE, ModelCatalogue.storageLine(none))
        val some = ModelCatalogue.entries(ready(spec.sourceUrl, 10L * 1024 * 1024), canDownload = false)
        assertEquals(10L * 1024 * 1024, ModelCatalogue.storageUsedBytes(some))
        assertEquals("Models use 10.0 MB.", ModelCatalogue.storageLine(some))
        assertFalse(some.single().canDownload)
        assertTrue(some.single().canImport)
    }

    @Test fun formatSize() {
        assertEquals("0 B", ModelCatalogue.formatSize(-5))
        assertEquals("512 B", ModelCatalogue.formatSize(512))
        assertEquals("2 KB", ModelCatalogue.formatSize(2048))
        assertEquals("9.8 MB", ModelCatalogue.formatSize((9.8 * 1024 * 1024).toLong()))
        assertEquals("1.5 GB", ModelCatalogue.formatSize((1.5 * 1024 * 1024 * 1024).toLong()))
    }

    @Test fun registryHasOneRealModelWithHttpsHost() {
        assertEquals(1, ModelCatalogue.all.size)
        assertTrue(spec.sourceUrl.startsWith("https://${spec.host}/"))
    }
}
