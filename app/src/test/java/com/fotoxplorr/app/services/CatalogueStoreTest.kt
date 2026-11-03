package com.fotoxplorr.app.services

import com.fotoxplorr.app.ai.AiProviderConfig
import com.fotoxplorr.app.ai.RemoteAiBridge
import com.fotoxplorr.app.ai.RemoteAiUnavailableException
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CatalogueStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private class FakeBridge(
        override val available: Boolean,
        val files: Map<String, String> = emptyMap(),
        val fail: Boolean = false,
    ) : RemoteAiBridge {
        val requested = mutableListOf<String>()
        override suspend fun testConnection(config: AiProviderConfig, secret: CharArray) = Result.failure<String>(IllegalStateException())
        override suspend fun generateText(config: AiProviderConfig, secret: CharArray, prompt: String) = Result.failure<String>(IllegalStateException())
        override suspend fun downloadFile(url: String, destination: File, onProgress: (Long, Long?) -> Unit): Result<Unit> {
            requested += url
            if (fail) return Result.failure(java.io.IOException("down"))
            destination.writeText(files.getValue(url.substringAfterLast('/')))
            return Result.success(Unit)
        }
    }

    private fun store(dir: File = tmp.newFolder()) = CatalogueStore(dir) { name ->
        when (name) {
            CatalogueStore.MODELS -> CatalogueFixtures.models
            CatalogueStore.TIERS -> CatalogueFixtures.tiers
            else -> null
        }
    }

    private val fresh = mapOf(
        CatalogueStore.MODELS to """{"schemaVersion":1,"lastUpdated":"2026-12-01","models":[{"id":"n","kind":"LLM_GGUF"}]}""",
        CatalogueStore.TIERS to """{"schemaVersion":1,"lastUpdated":"2026-12-01","providers":[{"id":"groq"}]}""",
    )

    @Test fun firstRunUsesTheBundledSnapshot() {
        val s = store().load()
        assertFalse(s.fromCache)
        assertEquals("2026-07-22", s.models.lastUpdated)
        assertEquals("2026-07-12", s.tiers?.lastUpdated)
    }

    @Test fun nothingIsFetchedByLoadingOrConstructing() {
        val bridge = FakeBridge(true, fresh)
        store().load()
        assertTrue(bridge.requested.isEmpty())
    }

    @Test fun offlineBridgeRefusesAndKeepsTheBundledCopy() = runBlocking {
        val st = store()
        val r = st.update(FakeBridge(available = false))
        assertTrue(r.exceptionOrNull() is RemoteAiUnavailableException)
        assertEquals("2026-07-22", st.load().models.lastUpdated)
    }

    @Test fun explicitUpdateFetchesBothFilesAndCaches() = runBlocking {
        val dir = tmp.newFolder()
        val bridge = FakeBridge(true, fresh)
        val r = store(dir).update(bridge).getOrThrow()
        assertEquals(2, bridge.requested.size)
        assertTrue(bridge.requested.all { it.startsWith("https://raw.githubusercontent.com/mbaliga/nooz/") })
        assertTrue(r.fromCache)
        // A new store over the same directory sees the cached copy.
        val again = store(dir).load()
        assertTrue(again.fromCache)
        assertEquals("2026-12-01", again.models.lastUpdated)
    }

    @Test fun failedOrUnreadableUpdateKeepsTheOldCopy() = runBlocking {
        val dir = tmp.newFolder()
        val st = store(dir)
        assertTrue(st.update(FakeBridge(true, fail = true)).isFailure)
        assertTrue(st.update(FakeBridge(true, mapOf(
            CatalogueStore.MODELS to """{"schemaVersion":9,"models":[]}""",
            CatalogueStore.TIERS to fresh.getValue(CatalogueStore.TIERS),
        ))).isFailure)
        val s = st.load()
        assertFalse(s.fromCache)
        assertEquals("2026-07-22", s.models.lastUpdated)
    }
}
