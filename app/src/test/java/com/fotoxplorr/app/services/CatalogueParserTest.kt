package com.fotoxplorr.app.services

import com.fotoxplorr.app.services.catalogue.NoozCatalogueParser
import com.fotoxplorr.app.services.catalogue.NoozKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogueParserTest {
    private val models = NoozCatalogueParser.parseModels(CatalogueFixtures.models).getOrThrow()

    @Test fun realModelsFileParses() {
        assertEquals("2026-07-22", models.lastUpdated)
        assertTrue(models.models.size >= 20)
        assertEquals(setOf(NoozKind.LLM_GGUF, NoozKind.SD_IMAGE, NoozKind.TTS_ONNX), models.models.map { it.kind }.toSet())
    }

    @Test fun nullDownloadUrlAndShaStayNull() {
        val m = models.models.first { it.id == "qwen3-4b-instruct-q4" }
        assertNull(m.downloadUrl)
        assertNull(m.sha256)
        assertEquals(2_600_000_000L, m.sizeBytes)
    }

    @Test fun communityRemixesAreNotPolicySafe() {
        assertEquals(false, models.models.first { it.id == "qwen2.5-1.5b-abliterated-q4" }.policySafe)
        assertEquals(true, models.models.first { it.id == "qwen2.5-1.5b-instruct-q4" }.policySafe)
    }

    @Test fun realFreeTiersParse() {
        val tiers = NoozCatalogueParser.parseFreeTiers(CatalogueFixtures.tiers).getOrThrow()
        assertEquals("2026-07-12", tiers.lastUpdated)
        val groq = tiers.tiers.first { it.id == "groq" }
        assertEquals(1000L, groq.requestsPerDay)
        assertEquals("https://console.groq.com/docs/rate-limits", groq.sourceUrl)
        assertNull(tiers.tiers.first { it.id == "openai" }.requestsPerDay)
    }

    @Test fun unknownFieldsUnderscoreFieldsAndKindsAreTolerated() {
        val doc = NoozCatalogueParser.parseModels(
            """{"schemaVersion":1,"future":{"a":[1,2]},"models":[
              {"id":"x","kind":"VISION_TFLITE","_lastProbeStatus":200,"newField":true,"sizeBytes":null,"downloadUrl":""},
              {"name":"no id"}, 7]}""",
        ).getOrThrow()
        assertEquals(1, doc.models.size)
        val x = doc.models.single()
        assertEquals(NoozKind.UNKNOWN, x.kind)
        assertEquals("VISION_TFLITE", x.rawKind)
        assertNull(x.sizeBytes)
        assertNull(x.downloadUrl)
        assertEquals("x", x.name)
    }

    @Test fun badInputIsRejectedNotGuessed() {
        assertTrue(NoozCatalogueParser.parseModels("""{"schemaVersion":2,"models":[]}""").isFailure)
        assertTrue(NoozCatalogueParser.parseModels("""{"models":[]}""").isFailure)
        assertTrue(NoozCatalogueParser.parseModels("not json").isFailure)
        assertTrue(NoozCatalogueParser.parseModels("""{"schemaVersion":1}""").isFailure)
        assertTrue(NoozCatalogueParser.parseFreeTiers("[]").isFailure)
        assertFalse(NoozCatalogueParser.parseModels("""{"schemaVersion":1,"models":[]}""").isFailure)
    }
}
